plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.idlogistics.scannercheckout"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.idlogistics.scannercheckout"
        minSdk = 26
        // PM86 runs Android 11 (API 30). Targeting 30 keeps Android 11 behaviour exactly as tested.
        targetSdk = 30
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }
}

// No third-party libraries: Android framework + Kotlin standard library only.
dependencies {
}
