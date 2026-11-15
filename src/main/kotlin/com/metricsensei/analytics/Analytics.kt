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
// When the SDK itself fails (a batch the service refuses), it tells Metric Sensei so the problem can be fixed: at
// most 3 short reports an app run to /v1/errors, holding the SDK's version, the step, the error and the Android
// version. Never the visitor ID, events or anything about the person. Being offline isn't a failure and is never
// reported.
package com.metricsensei.analytics

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object Analytics {
    private const val SESSION_MS = 30 * 60 * 1000L
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val events = mutableListOf<JSONObject>()
    private var prefs: SharedPreferences? = null
    private var siteId = ""
    private var appId = ""
    private var endpoint = "https://analytics.api.metricsensei.com/v1/app"
    private var errorsEndpoint = "https://analytics.api.metricsensei.com/v1/errors"
    private var reportsLeft = 3
    private var session = ""
    private var lastActivity = 0L
    private var screenCount = 0
    private var screenPath = ""

    /** Start sending. [host] is for testing environments (e.g. https://analytics.api.dev.metricsensei.com). */
    fun configure(context: Context, siteId: String, host: String = "https://analytics.api.metricsensei.com") {
        worker.execute {
            this.prefs = context.applicationContext.getSharedPreferences("metricsensei_analytics", Context.MODE_PRIVATE)
            this.siteId = siteId
            this.appId = context.packageName
            this.endpoint = "$host/v1/app"
            this.errorsEndpoint = "$host/v1/errors"
        }
        worker.scheduleWithFixedDelay({ flushNow() }, 10, 10, TimeUnit.SECONDS)
    }

    /** A screen view (counted like a page view, path "/<name>"). */
    fun screen(name: String) = worker.execute {
        touchSession()
        screenCount += 1
        screenPath = "/" + name.replace(' ', '-')
        val e = JSONObject().put("t", "pv").put("p", screenPath).put("n", screenCount).put("at", System.currentTimeMillis())
        if (screenCount == 2) e.put("nb", 1)
        push(e)
    }

    /** A named event, with optional properties and revenue. */
    fun track(name: String, props: Map<String, Any> = emptyMap(), revenue: Double? = null, currency: String? = null) =
        worker.execute {
            touchSession()
            val pr = JSONObject()
            for ((k, v) in props) if (v is String || v is Number) pr.put(k, v)
            revenue?.let { pr.put("revenue", it) }
            currency?.let { pr.put("currency", it) }
            push(JSONObject().put("t", "ev").put("nm", name).put("pr", pr).put("p", screenPath).put("at", System.currentTimeMillis()))
        }

    /** Forget the visitor ID (a new one is made on the next event). */
    fun reset() = worker.execute { prefs?.edit()?.remove("visitor")?.apply(); session = "" }

    /** Send what is queued now (e.g. in onStop). */
    fun flush() = worker.execute { flushNow() }

    private fun visitorId(): String {
        val p = prefs ?: return "unknown"
        p.getString("visitor", null)?.let { return it }
        val v = UUID.randomUUID().toString().replace("-", "")
        p.edit().putString("visitor", v).apply()
        return v
    }

    private fun touchSession() {
        val now = System.currentTimeMillis()
        if (session.isEmpty() || now - lastActivity > SESSION_MS) {
            session = UUID.randomUUID().toString().replace("-", "")
            screenCount = 0
        }
        lastActivity = now
    }

    private fun push(e: JSONObject) {
        events.add(e)
        if (events.size >= 20) flushNow()
    }

    private fun flushNow() {
        if (events.isEmpty() || siteId.isEmpty()) return
        val batch = events.take(50)
        events.subList(0, batch.size).clear()
        val now = System.currentTimeMillis()
        val ev = JSONArray()
        for (e in batch) {
            val at = e.optLong("at", now)
            e.remove("at")
            ev.put(e.put("o", (now - at).coerceAtLeast(0)))
        }
        val body = JSONObject().put("v", 1).put("site", siteId).put("app", appId).put("sid", session)
            .put("vid", visitorId()).put("ev", ev).put("os", "Android").put("lang", Locale.getDefault().toLanguageTag())
            .put("sv", "1.1.0")
        val code = post(endpoint, body) ?: return // offline: these events are dropped (a later version may retry)
        // A refusal (other than "slow down") is a failure of ours or of the app's setup: report it.
        if (code >= 400 && code != 429) report("flush", "http_$code", "The service refused a batch with HTTP $code")
    }

    /** POSTs [body] as JSON to [url] and returns the HTTP status, or null when there was no answer (offline). */
    private fun post(url: String, body: JSONObject): Int? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val status = conn.responseCode
        conn.disconnect()
        status
    } catch (_: Exception) {
        null
    }

    /** Reports a failure of this SDK itself to the platform's failures dataset (POST /v1/errors, read by
     *  ms_core.failures.client_report): at most 3 an app run, nothing about the person or their events. */
    private fun report(step: String, name: String, message: String) {
        if (reportsLeft <= 0) return
        reportsLeft -= 1
        val body = JSONObject().put("v", 1).put("src", "android").put("sv", "1.1.0").put("where", step)
            .put("name", name).put("msg", message).put("site", siteId).put("os", "Android ${Build.VERSION.RELEASE}")
        post(errorsEndpoint, body)
    }
}
