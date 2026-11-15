# Metric Sensei Analytics for Android

Screens, events, sessions and revenue from an Android app into Metric Sensei Analytics. Kotlin, no dependencies
beyond the Android SDK. Android 6.0 (API 23)+.

## Install
Until it's published to Maven Central, add it as a Gradle module or with JitPack:
`implementation("com.github.Mycrom-Systems:ms-analytics-android:<tag>")` (JitPack builds it with `./gradlew publishToMavenLocal`).

## Use
1. In Metric Sensei, add the app under the client's Data sources > Websites & Apps (Android app, with its package
   name) and turn Analytics on for it. Note its site ID (`MS-XXXXXXXX`).
2. In the app (e.g. `Application.onCreate`):

```kotlin
import com.metricsensei.analytics.Analytics

Analytics.configure(this, siteId = "MS-XXXXXXXX")
Analytics.screen("Home")
Analytics.track("Purchase", mapOf("plan" to "pro"), revenue = 49.9, currency = "USD")
```

Property values can be text, numbers or true/false; anything else (and numbers JSON can't hold, like NaN) is left
out. `configure()` can be called again (another site ID or host); one send timer runs.

## Delivery
Events are sent every 10 seconds, at once when 20 are waiting, and when the app goes to the background (the SDK
watches the app's activities itself; `Analytics.flush()` sends right away if you want to). When the phone is offline
or the service is busy (HTTP 408, 429 or 5xx), the events stay queued and the SDK tries again after 10 seconds,
doubling up to 5 minutes. At most 1,000 events wait (the oldest go first), and an event more than 86,000 seconds
(about 24 hours) old is dropped when sent, because the service dates app events at most a day back. Waiting events
are also kept in `metricsensei_analytics_queue.json` in the app's private no-backup folder (`noBackupFilesDir`: never
in backups, removed with the app), written at most every 10 seconds and when the app goes to the background, so they
survive the app being closed. The file holds only what the SDK sends anyway, and is deleted once everything is sent.

Each event carries a random ID (`id`) and each batch one (`b`). A batch keeps its ID and its events on every retry,
also after a restart (a new batch's ID is written to the queue file before it is first sent, so even an app killed
mid-send resends it under the same ID), so when the service took a batch but its answer was lost, the retry is
recognised and counted once (the service answers "duplicate"). A 409 ("that batch is still being processed") and any
`Retry-After` the service sends make the SDK wait at least that long (at most an hour) before trying again.

Privacy: no advertising ID, Android ID or personal data is read. The visitor ID is random and kept in the app's
private preferences; `reset()` forgets it. The app needs the INTERNET permission (included in the library's manifest).

If the SDK itself fails (the service refuses a batch), it sends Metric Sensei a short report to `/v1/errors` so we
can fix it: at most 3 an app run, with the SDK version, the step, the error and the OS version. Never the visitor
ID, events or anything about the person; being offline is never reported.

MIT license.

## Build
JDK 17 and the Android SDK (platform 35): `./gradlew assembleRelease lint test`. The logic is in `Tracker.kt`
(plain Kotlin, no Android classes), so the unit tests in `src/test` run on the JVM.

## Releasing
The version is written in three places that must agree: `version` in `build.gradle.kts` and both
`put("sv", ...)` calls in `Tracker.kt` (ms-core `scripts/check-packages.py` fails otherwise). Raise it with any
change. After the next prod release, ms-core `scripts/publish-packages.py` tags `v<version>` and JitPack builds
that tag. Every field the SDK sends must be one ms-analytics' `/v1/app` or `/v1/errors` reads
(ms-analytics `tests/test_app_sdk_contract.py` reads this repo's source).
