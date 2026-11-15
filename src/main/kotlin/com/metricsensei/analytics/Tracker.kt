// The SDK's logic without Android in it: sessions, the queue of events (kept in a file between app runs),
// batching, sending with retries and the SDK's own failure reports. `Analytics` (the public object) runs every call
// of this class on its one worker thread, so nothing here is locked; unit tests drive it directly with a fake clock,
// a fake sender and fake stores.
package com.metricsensei.analytics

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Where the random visitor ID is kept between app runs (the app's private preferences on a device). */
internal interface VisitorStore {
    /** The saved visitor ID, or null when there is none (or the store isn't ready yet). */
    fun get(): String?

    /** Saves [id]; does nothing when the store isn't ready yet. */
    fun set(id: String)

    /** Forgets the saved visitor ID. */
    fun clear()
}

/** Where the waiting events are kept between app runs (a file in the app's private no-backup folder on a device). */
internal interface QueueStore {
    /** The saved text, or null when there is none (or the store isn't ready yet). */
    fun load(): String?

    /** Replaces the saved text with [text]; null removes it. Does nothing when the store isn't ready yet. */
    fun save(text: String?)
}

/**
 * One event waiting to be sent: its JSON (without the offset, which is worked out when it is sent), when it
 * happened, and the session and visitor it belongs to. [vid] is null for events tracked before `configure()`,
 * when the saved visitor ID can't be read yet; those take the visitor ID once it can.
 */
internal class Pending(val event: JSONObject, val at: Long, val sid: String, val vid: String?)

/**
 * Sessions, queue and sending. [send] POSTs a JSON body and returns the HTTP status, or null when there was no
 * answer (offline). [clock] is milliseconds since the epoch.
 */
internal class Tracker(
    private val clock: () -> Long,
    private val send: (url: String, body: JSONObject) -> Int?,
    private val visitors: VisitorStore,
    private val saved: QueueStore,
    private val osVersion: String,
    private val language: () -> String,
) {
    companion object {
        // The SDK's version is written as a literal in both put("sv", ...) calls below and in build.gradle.kts
        // `version`; ms-core's check-packages.py reads all three and fails when they differ.

        /** Inactivity that ends a session. */
        const val SESSION_MS = 30 * 60 * 1000L
        /** The most events kept while they can't be sent; the oldest go first beyond this. */
        const val MAX_QUEUE = 1000
        /** Events per batch: /v1/app reads at most 50 (ms-analytics events.MAX_EVENTS). */
        const val MAX_BATCH = 50
        /** Queued events that start a send at once instead of waiting for the next timer tick. */
        const val SEND_AT = 20
        /** Batches sent in one go, so one flush can't keep the worker busy for long. */
        const val MAX_BATCHES_PER_FLUSH = 5
        /**
         * Events older than this are dropped instead of sent: /v1/app dates an app event at most a day back
         * (ms-analytics events.APP_MAX_OFFSET_MS, 86,400,000 ms). 86,000 s leaves room for clock drift and the
         * request's own time; the same number as the iOS SDK.
         */
        const val MAX_AGE_MS = 86_000 * 1000L
        /** Waits between failed sends: 10 s, doubling, at most 5 minutes. */
        const val FIRST_RETRY_MS = 10_000L
        const val MAX_RETRY_MS = 5 * 60 * 1000L
        /** The SDK's own failure reports per app run. */
        const val MAX_REPORTS = 3
    }

    private val queue = ArrayDeque<Pending>()
    private var siteId = ""
    private var appId = ""
    private var endpoint = ""
    private var errorsEndpoint = ""
    private var reportsLeft = MAX_REPORTS
    private var session = ""
    private var lastActivity = 0L
    private var screenCount = 0
    private var screenPath = ""
    private var visitor: String? = null
    private var failures = 0
    private var retryAt = 0L
    /** True once the saved queue has been read back (the first configure()). */
    private var restored = false
    /** True when the queue changed since it was last saved. */
    private var dirty = false
    /** True when reset() was called before configure(), when the saved visitor ID couldn't be cleared yet. */
    private var clearOnConfigure = false

    /** Number of events waiting to be sent (for tests). */
    val queued: Int get() = queue.size

    /** True once [configure] has been called. */
    private val configured: Boolean get() = siteId.isNotEmpty()

    /** Sets (or changes) the site, app and host. A changed host or site starts sending again at once. */
    fun configure(siteId: String, appId: String, host: String) {
        this.siteId = siteId
        this.appId = appId
        this.endpoint = "$host/v1/app"
        this.errorsEndpoint = "$host/v1/errors"
        failures = 0
        retryAt = 0L
        if (clearOnConfigure) {
            clearOnConfigure = false
            visitors.clear()
        }
        if (!restored) {
            restored = true
            restore()
        }
    }

    /** A screen view (counted like a page view, path "/<name>"). */
    fun screen(name: String) {
        touchSession()
        screenCount += 1
        screenPath = "/" + name.replace(' ', '-')
        val e = JSONObject().put("t", "pv").put("p", screenPath).put("n", screenCount)
        // The session's second screen marks it as not a bounce (counted once, like the website tag).
        if (screenCount == 2) e.put("nb", 1)
        push(e)
    }

    /** A named event with text, number or true/false properties, and optional revenue. */
    fun track(name: String, props: Map<String, Any>, revenue: Double?, currency: String?) {
        touchSession()
        val pr = JSONObject()
        for ((k, v) in props) {
            when (v) {
                is String, is Boolean -> pr.put(k, v)
                // JSON has no NaN or infinity (org.json throws on them), so such numbers are left out.
                is Number -> if (v.toDouble().isFinite()) pr.put(k, v)
            }
        }
        revenue?.takeIf { it.isFinite() }?.let { pr.put("revenue", it) }
        currency?.let { pr.put("currency", it) }
        push(JSONObject().put("t", "ev").put("nm", name).put("pr", pr).put("p", screenPath))
    }

    /**
     * Forgets the visitor ID and ends the session. Events already queued keep the visitor they were tracked as.
     * Before [configure] the saved ID can't be reached yet, so it is cleared as soon as configure() runs.
     */
    fun reset() {
        if (!configured) clearOnConfigure = true
        visitors.clear()
        visitor = null
        session = ""
    }

    /**
     * Sends what is queued, in batches of one session and visitor each. After a failed send the timer's flushes
     * wait out the retry delay; a [force]d one (the app's own flush() or going to the background) tries at once.
     * A batch that couldn't be delivered (offline, 408, 429, 5xx) stays queued for the next try; one the service
     * refused for good (another 4xx) is dropped and reported, as is the first 5xx of a run of failures.
     */
    fun flush(force: Boolean = false) {
        if (!configured) return
        sendQueued(force)
        save()
    }

    /** The sending part of [flush]. */
    private fun sendQueued(force: Boolean) {
        var batches = 0
        while (queue.isNotEmpty() && batches < MAX_BATCHES_PER_FLUSH) {
            val now = clock()
            if (now < retryAt && !(force && batches == 0)) return
            while (queue.isNotEmpty() && now - queue.first().at > MAX_AGE_MS) {
                queue.removeFirst()
                dirty = true
            }
            if (queue.isEmpty()) return
            val batch = nextBatch()
            val code = send(endpoint, body(batch, now))
            if (code == null || code == 408 || code == 429 || code >= 500) {
                failures += 1
                retryAt = now + retryDelay()
                // Offline and "slow down" are normal; a server error is ours, so the first one is reported.
                if (code != null && code >= 500 && failures == 1) {
                    report("flush", "http_$code", "The service failed a batch with HTTP $code")
                }
                return
            }
            repeat(batch.size) { queue.removeFirst() }
            dirty = true
            failures = 0
            retryAt = 0L
            // A refusal is a failure of ours or of the app's setup: report it (the batch can't succeed later).
            if (code >= 400) report("flush", "http_$code", "The service refused a batch with HTTP $code")
            batches += 1
        }
    }

    /** The wait before the next send after [failures] failed sends in a row. */
    private fun retryDelay(): Long {
        val doublings = (failures - 1).coerceIn(0, 10)
        return (FIRST_RETRY_MS shl doublings).coerceAtMost(MAX_RETRY_MS)
    }

    /** The visitor ID: the saved one, else a new random one (saved). Null before [configure]. */
    private fun visitorId(): String? {
        if (!configured) return null
        visitor?.let { return it }
        val saved = visitors.get() ?: UUID.randomUUID().toString().replace("-", "").also { visitors.set(it) }
        visitor = saved
        return saved
    }

    /** Starts a new session after 30 minutes without activity (or after [reset]). */
    private fun touchSession() {
        val now = clock()
        if (session.isEmpty() || now - lastActivity > SESSION_MS) {
            session = UUID.randomUUID().toString().replace("-", "")
            screenCount = 0
        }
        lastActivity = now
    }

    /** Queues [e], dropping the oldest events beyond [MAX_QUEUE], and sends once enough are waiting. */
    private fun push(e: JSONObject) {
        queue.addLast(Pending(e, clock(), session, visitorId()))
        dirty = true
        while (queue.size > MAX_QUEUE) queue.removeFirst()
        if (queue.size >= SEND_AT) flush()
    }

    /**
     * Writes the queue to [saved] when it changed (after each flush, so at most every 10 seconds, and when the app
     * goes to the background). An empty queue removes the saved copy.
     */
    private fun save() {
        if (!dirty) return
        dirty = false
        if (queue.isEmpty()) {
            saved.save(null)
            return
        }
        val list = JSONArray()
        // Built from a map rather than put() calls: ms-analytics' contract test reads every put("name", ...) here as
        // a field sent to /v1/app, and this file is never sent.
        for (p in queue) {
            list.put(JSONObject(mapOf("e" to p.event, "at" to p.at, "sid" to p.sid, "vid" to (p.vid ?: ""))))
        }
        saved.save(list.toString())
    }

    /**
     * Puts the events saved by an earlier app run in front of anything tracked since (they are older), keeping the
     * newest [MAX_QUEUE]. A saved copy that can't be read is dropped rather than failing; events too old to send
     * are dropped by the next flush.
     */
    private fun restore() {
        val text = saved.load() ?: return
        val earlier = mutableListOf<Pending>()
        try {
            val list = JSONArray(text)
            for (i in 0 until list.length()) {
                val o = list.getJSONObject(i)
                val vid = o.optString("vid").takeIf { it.isNotEmpty() }
                earlier.add(Pending(o.getJSONObject("e"), o.getLong("at"), o.getString("sid"), vid))
            }
        } catch (_: Exception) {
            saved.save(null)
            return
        }
        for (p in earlier.asReversed()) queue.addFirst(p)
        while (queue.size > MAX_QUEUE) queue.removeFirst()
        dirty = true
    }

    /** The first events in the queue that share one session and visitor, at most [MAX_BATCH]. */
    private fun nextBatch(): List<Pending> {
        val first = queue.first()
        val vid = first.vid ?: visitorId()
        val batch = mutableListOf<Pending>()
        for (p in queue) {
            if (batch.size >= MAX_BATCH || p.sid != first.sid || (p.vid ?: visitorId()) != vid) break
            batch.add(p)
        }
        return batch
    }

    /** The /v1/app body for [batch]: each event gets its age in milliseconds as "o". */
    private fun body(batch: List<Pending>, now: Long): JSONObject {
        val ev = JSONArray()
        for (p in batch) ev.put(JSONObject(p.event.toString()).put("o", (now - p.at).coerceAtLeast(0)))
        val first = batch.first()
        return JSONObject().put("v", 1).put("site", siteId).put("app", appId).put("sid", first.sid)
            .put("vid", first.vid ?: visitorId()).put("ev", ev).put("os", "Android").put("lang", language())
            .put("sv", "1.2.0")
    }

    /** Reports a failure of this SDK itself to the platform's failures dataset (POST /v1/errors, read by
     *  ms_core.failures.client_report): at most 3 an app run, nothing about the person or their events. */
    private fun report(step: String, name: String, message: String) {
        if (reportsLeft <= 0) return
        reportsLeft -= 1
        val body = JSONObject().put("v", 1).put("src", "android").put("sv", "1.2.0").put("where", step)
            .put("name", name).put("msg", message).put("site", siteId).put("os", "Android $osVersion")
        send(errorsEndpoint, body)
    }
}
