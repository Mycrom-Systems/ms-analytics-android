# Metric Sensei Analytics for Android

Screens, events, sessions and revenue from an Android app into Metric Sensei Analytics. Kotlin, no dependencies
beyond the Android SDK. Android 6.0 (API 23)+.

## Install
Until it's published to Maven Central, add it as a Gradle module or with JitPack:
`implementation("com.github.Mycrom-Systems:ms-analytics-android:<tag>")`.

## Use
1. In Metric Sensei, add the app under the client's Data sources > Websites & Apps (Android app, with its package
   name) and turn Analytics on for it. Note its site ID (`MS-XXXXXXXX`).
2. In the app (e.g. `Application.onCreate`):

```kotlin
import com.metricsensei.analytics.Analytics

Analytics.configure(this, siteId = "MS-XXXXXXXX")
Analytics.screen("Home")
Analytics.track("Purchase", mapOf("plan" to "pro"), revenue = 49.9, currency = "USD")
Analytics.flush() // e.g. in onStop
```

Privacy: no advertising ID, Android ID or personal data is read. The visitor ID is random and kept in the app's
private preferences; `reset()` forgets it. The app needs the INTERNET permission (included in the library's manifest).

MIT license.
