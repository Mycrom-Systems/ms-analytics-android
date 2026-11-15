// Metric Sensei Analytics for Android: screens, events, sessions and revenue, sent in batches to the client's
// Analytics. Add the app under Data sources > Websites & Apps (Android app, its package name), turn Analytics on
// for it, then (e.g. in Application.onCreate):
//
//     Analytics.configure(this, siteId = "MS-XXXXXXXX")
//     Analytics.screen("Home")
//     Analytics.track("Purchase", mapOf("plan" to "pro"), revenue = 49.9, currency = "USD")
//
// No advertising ID, Android ID or personal data is read. The visitor ID is random, kept in this app's private
// preferences; call reset() when someone signs out if their next session shouldn't join the last.
//
// Events are sent every 10 seconds, when 20 are waiting, and when the app goes to the background. When a send
// fails (offline, the service busy) they wait, at most 1,000 and for up to 86,000 seconds (about a day), and are
// tried again with a growing wait (10 seconds up to 5 minutes). Waiting events are also kept in a file in the app's
// private no-backup folder (never in backups, removed with the app), so they survive the app being closed.
//
// When the SDK itself fails (a batch the service refuses), it tells Metric Sensei so the problem can be fixed: at
// most 3 short reports an app run to /v1/errors, holding the SDK's version, the step, the error and the Android
// version. Never the visitor ID, events or anything about the person. Being offline isn't a failure and is never
// reported.
//
// The logic lives in Tracker.kt; this object only gives it a thread, a timer, storage and the network.
package com.metricsensei.analytics

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object Analytics {
    /** Every call runs on this one thread, in order, so Tracker needs no locks and the app's thread never waits. */
    private val worker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "metricsensei-analytics").apply { isDaemon = true }
    }
    private var prefs: SharedPreferences? = null
    private var timer: ScheduledFuture<*>? = null
    private val watchingLifecycle = AtomicBoolean(false)

    /** The visitor ID in the app's private preferences; not ready (null) until configure() has run. */
    private val visitors = object : VisitorStore {
        override fun get(): String? = prefs?.getString("visitor", null)
        override fun set(id: String) { prefs?.edit()?.putString("visitor", id)?.apply() }
        override fun clear() { prefs?.edit()?.remove("visitor")?.apply() }
    }

    /** The file holding waiting events; null until configure() has run. */
    private var queueFile: File? = null

    /** The waiting events in [queueFile], written to a temporary file first so a crash never leaves half a file. */
    private val saved = object : QueueStore {
        override fun load(): String? = queueFile?.takeIf { it.exists() }?.readText()
        override fun save(text: String?) {
            val file = queueFile ?: return
            if (text == null) {
                file.delete()
                return
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) tmp.delete()
        }
    }

    private val tracker = Tracker(
        clock = System::currentTimeMillis,
        send = ::post,
        visitors = visitors,
        saved = saved,
        osVersion = Build.VERSION.RELEASE ?: "",
        language = { Locale.getDefault().toLanguageTag() },
    )

    /**
     * Start sending. [host] is for testing environments (e.g. https://analytics.api.dev.metricsensei.com).
     * Safe to call more than once: the latest site ID and host are used and one send timer runs.
     */
    fun configure(context: Context, siteId: String, host: String = "https://analytics.api.metricsensei.com") {
        val app = context.applicationContext
        run {
            prefs = app.getSharedPreferences("metricsensei_analytics", Context.MODE_PRIVATE)
            queueFile = File(app.noBackupFilesDir, "metricsensei_analytics_queue.json")
            tracker.configure(siteId, app.packageName, host)
            // Called again (a new site ID, a test host): stop the earlier timer, or each call would add one.
            timer?.cancel(false)
            timer = worker.scheduleWithFixedDelay({ safely { tracker.flush() } }, 10, 10, TimeUnit.SECONDS)
        }
        watchLifecycle(app)
    }

    /** A screen view (counted like a page view, path "/<name>"). */
    fun screen(name: String) = run { tracker.screen(name) }

    /** A named event, with optional properties (text, numbers, true/false) and revenue. */
    fun track(name: String, props: Map<String, Any> = emptyMap(), revenue: Double? = null, currency: String? = null) =
        run { tracker.track(name, props, revenue, currency) }

    /** Forget the visitor ID (a new one is made on the next event). */
    fun reset() = run { tracker.reset() }

    /** Send what is queued now. Also done by itself when the app goes to the background. */
    fun flush() = run { tracker.flush(force = true) }

    /** Runs [block] on the worker thread. */
    private fun run(block: () -> Unit) {
        worker.execute { safely(block) }
    }

    /**
     * Runs [block], swallowing anything it throws: analytics must never crash the app, and an exception would
     * also stop the repeating send timer for good.
     */
    private fun safely(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
        }
    }

    /**
     * Flushes when the app's last visible activity stops (the app went to the background), using the Android
     * SDK's own activity callbacks, so no extra library is needed. Registered once, whatever configure() does.
     */
    private fun watchLifecycle(context: Context) {
        val app = context as? Application ?: return
        if (!watchingLifecycle.compareAndSet(false, true)) return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            // Only touched on the main thread, where Android calls these.
            private var started = 0

            override fun onActivityStarted(activity: Activity) {
                started += 1
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                // A rotation stops and restarts the activity: that isn't the app leaving the screen.
                if (started == 0 && !activity.isChangingConfigurations) flush()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** POSTs [body] as JSON to [url] and returns the HTTP status, or null when there was no answer (offline). */
    private fun post(url: String, body: JSONObject): Int? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }
}
