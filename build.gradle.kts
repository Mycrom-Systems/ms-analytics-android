// Metric Sensei Analytics for Android (Kotlin, no dependencies beyond the Android SDK).
plugins {
    id("com.android.library") version "8.7.3"
    kotlin("android") version "2.1.0"
    `maven-publish`
}

group = "com.metricsensei"
version = "1.3.0"

android {
    namespace = "com.metricsensei.analytics"
    compileSdk = 35
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    publishing { singleVariant("release") { withSourcesJar() } }
}

// Unit tests run on the JVM (./gradlew test). The Android SDK's org.json is only a stub there, so the tests get the
// real one; neither is part of what apps install.
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

// For JitPack (and a later Maven Central release): com.metricsensei:analytics:<version>.
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.metricsensei"
            artifactId = "analytics"
            afterEvaluate { from(components["release"]) }
        }
    }
}
