// Unit tests for Tracker (the SDK's logic): batches as /v1/app reads them, sessions, the queue's limits, retries
// after failed sends and the SDK's own failure reports. A fake clock and a fake sender stand in for the device.
package com.metricsensei.analytics

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TrackerTest {
    /** One request the fake sender received. */
    private data class Sent(val url: String, val body: JSONObject)

    private var now = 1_700_000_000_000L
    private val sent = mutableListOf<Sent>()
    /** The status the fake service answers with; null means offline. */
    private var answer: Int? = 202
    private val store = object : VisitorStore {
        var saved: String? = null
        override fun get() = saved
        override fun set(id: String) { saved = id }
        override fun clear() { saved = null }
    }
    /** The fake saved-queue file: [file] is its text, null when there is none. */
    private val disk = object : QueueStore {
        var file: String? = null
        var writes = 0
        override fun load() = file
        override fun save(text: String?) { file = text; writes += 1 }
    }
    private lateinit var tracker: Tracker

    /** A tracker sharing this test's clock, sender and stores, not configured yet (a new "app run"). */
    private fun newTracker() =
        Tracker(
            clock = { now },
            send = { url, body -> sent.add(Sent(url, JSONObject(body.toString()))); answer },
            visitors = store,
            saved = disk,
            osVersion = "15",
            language = { "en-US" },
        )

    /** Batches sent to /v1/app (not failure reports). */
    private val batches get() = sent.filter { it.url.endsWith("/v1/app") }.map { it.body }

    /** Failure reports sent to /v1/errors. */
    private val reports get() = sent.filter { it.url.endsWith("/v1/errors") }.map { it.body }

    /** A tracker whose sender records each request and answers [answer]. */
    @Before
    fun setUp() {
        tracker = newTracker()
        tracker.configure("MS-APP00001", "com.acme.shop", "https://analytics.api.dev.metricsensei.com")
    }

    @Test
    fun `a batch has the fields v1 app reads and each event its age`() {
        tracker.screen("Home Page")
        now += 1500
        val props = mapOf("plan" to "pro", "seats" to 3, "trial" to true)
        tracker.track("Purchase", props, revenue = 49.9, currency = "USD")
        tracker.flush()
        val body = batches.single()
        assertEquals("MS-APP00001", body.getString("site"))
        assertEquals("com.acme.shop", body.getString("app"))
        assertEquals("Android", body.getString("os"))
        assertEquals("en-US", body.getString("lang"))
        assertEquals("1.2.0", body.getString("sv"))
        assertTrue(body.getString("sid").matches(Regex("^[0-9a-f]{32}$")))
        assertTrue(body.getString("vid").matches(Regex("^[0-9a-f]{32}$")))
        val ev = body.getJSONArray("ev")
        val pv = ev.getJSONObject(0)
        assertEquals("pv", pv.getString("t"))
        assertEquals("/Home-Page", pv.getString("p"))
        assertEquals(1, pv.getInt("n"))
        assertEquals(1500L, pv.getLong("o"))
        assertFalse(pv.has("at"))
        val e = ev.getJSONObject(1)
        assertEquals("Purchase", e.getString("nm"))
        assertEquals("/Home-Page", e.getString("p"))
        assertEquals(0L, e.getLong("o"))
        val pr = e.getJSONObject("pr")
        assertEquals("pro", pr.getString("plan"))
        assertEquals(3, pr.getInt("seats"))
        assertTrue(pr.getBoolean("trial"))
        assertEquals(49.9, pr.getDouble("revenue"), 0.0)
        assertEquals("USD", pr.getString("currency"))
        assertEquals(0, tracker.queued)
    }

    @Test
    fun `numbers JSON can't hold are left out instead of failing the event`() {
        tracker.track("Odd", mapOf("bad" to Double.NaN, "inf" to Double.POSITIVE_INFINITY, "ok" to 1), Double.NaN, null)
        tracker.flush()
        val pr = batches.single().getJSONArray("ev").getJSONObject(0).getJSONObject("pr")
        assertEquals(setOf("ok"), pr.keys().asSequence().toSet())
    }

    @Test
    fun `the second screen of a session marks it not a bounce, once`() {
        tracker.screen("A")
        tracker.screen("B")
        tracker.screen("C")
        tracker.flush()
        val ev = batches.single().getJSONArray("ev")
        assertFalse(ev.getJSONObject(0).has("nb"))
        assertEquals(1, ev.getJSONObject(1).getInt("nb"))
        assertFalse(ev.getJSONObject(2).has("nb"))
    }

    @Test
    fun `30 minutes without activity starts a new session, sent in its own batch`() {
        tracker.screen("A")
        now += Tracker.SESSION_MS + 1
        tracker.screen("B")
        tracker.flush()
        assertEquals(2, batches.size)
        assertNotEquals(batches[0].getString("sid"), batches[1].getString("sid"))
        assertEquals(1, batches[1].getJSONArray("ev").getJSONObject(0).getInt("n"))
    }

    @Test
    fun `reset makes a new visitor but queued events keep the one they were tracked as`() {
        tracker.screen("A")
        tracker.reset()
        tracker.screen("B")
        tracker.flush()
        assertEquals(2, batches.size)
        assertNotEquals(batches[0].getString("vid"), batches[1].getString("vid"))
        assertEquals(store.saved, batches[1].getString("vid"))
    }

    @Test
    fun `the visitor ID is kept between runs`() {
        store.saved = "b".repeat(32)
        tracker.screen("A")
        tracker.flush()
        assertEquals("b".repeat(32), batches.single().getString("vid"))
    }

    @Test
    fun `events from before configure wait and then go with the saved visitor`() {
        val early = newTracker()
        store.saved = "c".repeat(32)
        early.screen("A")
        early.flush()
        assertTrue(sent.isEmpty())
        early.configure("MS-APP00001", "com.acme.shop", "https://h")
        early.flush()
        assertEquals("c".repeat(32), batches.single().getString("vid"))
    }

    @Test
    fun `20 waiting events send at once and batches hold at most 50`() {
        repeat(19) { tracker.track("e$it", emptyMap(), null, null) }
        assertTrue(sent.isEmpty())
        tracker.track("e19", emptyMap(), null, null)
        assertEquals(20, batches.single().getJSONArray("ev").length())
        sent.clear()
        answer = null // offline, so events pile up past one batch
        repeat(120) { tracker.track("x$it", emptyMap(), null, null) }
        answer = 202
        now += Tracker.MAX_RETRY_MS
        tracker.flush()
        assertTrue(batches.all { it.getJSONArray("ev").length() <= Tracker.MAX_BATCH })
        assertEquals(120, batches.drop(1).sumOf { it.getJSONArray("ev").length() })
        assertEquals(0, tracker.queued)
    }

    @Test
    fun `offline keeps the events and retries after a growing wait`() {
        answer = null
        tracker.screen("A")
        tracker.flush()
        assertEquals(1, sent.size)
        assertEquals(1, tracker.queued)
        tracker.flush() // inside the wait: no request
        assertEquals(1, sent.size)
        now += Tracker.FIRST_RETRY_MS
        tracker.flush()
        assertEquals(2, sent.size)
        now += Tracker.FIRST_RETRY_MS // the second wait is twice as long
        tracker.flush()
        assertEquals(2, sent.size)
        now += Tracker.FIRST_RETRY_MS
        answer = 202
        tracker.flush()
        assertEquals(3, sent.size)
        assertEquals(0, tracker.queued)
        assertTrue(reports.isEmpty()) // being offline is never reported
    }

    @Test
    fun `the wait never goes past five minutes`() {
        answer = null
        tracker.screen("A")
        repeat(12) {
            tracker.flush()
            now += Tracker.MAX_RETRY_MS
        }
        assertEquals(12, sent.size)
    }

    @Test
    fun `429 and 5xx keep the batch, a 5xx is reported once per run of failures`() {
        tracker.screen("A")
        answer = 429
        tracker.flush()
        assertEquals(1, tracker.queued)
        assertTrue(reports.isEmpty())
        answer = 503
        now += Tracker.MAX_RETRY_MS
        tracker.flush()
        now += Tracker.MAX_RETRY_MS
        tracker.flush()
        assertEquals(1, tracker.queued)
        assertTrue(reports.isEmpty()) // the 503s came after a 429 in the same run of failures
        answer = 202
        now += Tracker.MAX_RETRY_MS
        tracker.flush()
        answer = 500
        tracker.screen("B")
        tracker.flush()
        now += Tracker.MAX_RETRY_MS
        tracker.flush()
        assertEquals(1, reports.size)
        assertEquals("http_500", reports.single().getString("name"))
    }

    @Test
    fun `a refusal drops the batch and is reported without anything about the person`() {
        answer = 404
        tracker.screen("A")
        tracker.flush()
        assertEquals(0, tracker.queued)
        val r = reports.single()
        assertEquals("android", r.getString("src"))
        assertEquals("1.2.0", r.getString("sv"))
        assertEquals("flush", r.getString("where"))
        assertEquals("http_404", r.getString("name"))
        assertEquals("MS-APP00001", r.getString("site"))
        assertEquals("Android 15", r.getString("os"))
        assertFalse(r.has("vid"))
        assertFalse(r.has("ev"))
    }

    @Test
    fun `at most three reports an app run`() {
        answer = 400
        repeat(5) {
            tracker.screen("S$it")
            tracker.flush()
        }
        assertEquals(3, reports.size)
    }

    @Test
    fun `the queue keeps the newest 1000 events`() {
        answer = null
        tracker.flush() // nothing queued: no request, no wait
        tracker.track("first", emptyMap(), null, null)
        tracker.flush() // starts the wait, so pushes don't send
        repeat(Tracker.MAX_QUEUE + 10) { tracker.track("e$it", emptyMap(), null, null) }
        assertEquals(Tracker.MAX_QUEUE, tracker.queued)
        answer = 202
        now += Tracker.MAX_RETRY_MS
        tracker.flush()
        // batches[0] was the offline try; the oldest 11 ("first", e0..e9) were dropped.
        assertEquals("e10", batches[1].getJSONArray("ev").getJSONObject(0).getString("nm"))
    }

    @Test
    fun `events older than 86,000 seconds are dropped, not sent with a wrong time`() {
        answer = null
        tracker.screen("Old")
        tracker.flush()
        now += Tracker.MAX_AGE_MS + 1
        tracker.screen("New")
        answer = 202
        tracker.flush()
        val ev = batches.last().getJSONArray("ev")
        assertEquals(1, ev.length())
        assertEquals("/New", ev.getJSONObject(0).getString("p"))
    }

    @Test
    fun `configure again clears the wait so a new host is tried at once`() {
        answer = null
        tracker.screen("A")
        tracker.flush()
        answer = 202
        tracker.configure("MS-APP00001", "com.acme.shop", "https://analytics.api.metricsensei.com")
        tracker.flush()
        assertEquals("https://analytics.api.metricsensei.com/v1/app", sent.last().url)
        assertEquals(0, tracker.queued)
    }

    @Test
    fun `a forced flush tries at once despite the wait, the timer's doesn't`() {
        answer = null
        tracker.screen("A")
        tracker.flush()
        tracker.flush()
        assertEquals(1, sent.size)
        answer = 202
        tracker.flush(force = true)
        assertEquals(2, sent.size)
        assertEquals(0, tracker.queued)
    }

    @Test
    fun `waiting events survive a restart and go out after configure`() {
        answer = null
        tracker.screen("A")
        tracker.flush()
        assertTrue(disk.file!!.contains("/A"))
        assertFalse(disk.file!!.contains("\"p\":\"/A\",\"o\"")) // saved without an offset
        val next = newTracker()
        next.screen("B") // tracked before configure in the new run: goes after the saved one
        answer = 202
        now += 1000
        next.configure("MS-APP00001", "com.acme.shop", "https://h")
        next.flush()
        val pages = batches.flatMap { b ->
            val ev = b.getJSONArray("ev")
            (0 until ev.length()).map { ev.getJSONObject(it).getString("p") }
        }
        assertEquals(listOf("/A", "/A", "/B"), pages) // the offline try, then the restored one, then the new one
        assertEquals(2, batches.drop(1).map { it.getString("sid") }.distinct().size) // two sessions, two batches
        assertNull(disk.file) // everything sent: the file is gone
    }

    @Test
    fun `the file is written once per flush, not per event`() {
        repeat(5) { tracker.track("e$it", emptyMap(), null, null) }
        assertEquals(0, disk.writes)
        answer = null
        tracker.flush()
        assertEquals(1, disk.writes)
        tracker.flush() // nothing changed
        assertEquals(1, disk.writes)
    }

    @Test
    fun `a saved file that can't be read is dropped`() {
        disk.file = "{not json"
        val next = newTracker()
        next.configure("MS-APP00001", "com.acme.shop", "https://h")
        assertNull(disk.file)
        assertEquals(0, next.queued)
    }

    @Test
    fun `saved events too old to send are dropped after a restart`() {
        answer = null
        tracker.screen("A")
        tracker.flush()
        now += Tracker.MAX_AGE_MS + 1
        answer = 202
        val next = newTracker()
        next.configure("MS-APP00001", "com.acme.shop", "https://h")
        next.flush()
        assertEquals(1, batches.size) // only the first offline try
        assertNull(disk.file)
    }

    @Test
    fun `nothing is sent before configure`() {
        val t = newTracker()
        repeat(25) { t.track("e", emptyMap(), null, null) }
        t.flush()
        assertTrue(sent.isEmpty())
        assertNull(store.saved)
    }
}
