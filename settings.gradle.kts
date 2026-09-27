val hasAndroidSdk = System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null || file("local.properties").exists()

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        if (hasAndroidSdk) google()
    }
}

rootProject.name = "home-android"

// Protocol codecs (Kotlin/JVM) straight from the home-protocol submodule.
includeBuild("external/home-protocol/kotlin")

include(":core")
// The Android app needs the Android SDK (ANDROID_HOME or local.properties with sdk.dir).
if (hasAndroidSdk) include(":app")
