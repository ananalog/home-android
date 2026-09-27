
buildscript {
    val withAndroid = System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null || file("local.properties").exists()
    repositories {
        mavenCentral()
        gradlePluginPortal()
        if (withAndroid) google()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.21")
        if (withAndroid) {
            classpath("com.android.tools.build:gradle:8.9.3")
            classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.1.21")
        }
    }
}
