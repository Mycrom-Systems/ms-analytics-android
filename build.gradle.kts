// Metric Sensei Analytics for Android (Kotlin, no dependencies beyond the Android SDK).
plugins {
    id("com.android.library") version "8.7.3"
    kotlin("android") version "2.1.0"
}

android {
    namespace = "com.metricsensei.analytics"
    compileSdk = 35
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
