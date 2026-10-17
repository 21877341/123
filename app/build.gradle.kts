plugins {
    id("com.android.application")
}

android {
    namespace = "dev.hyperosfix.desktop"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.hyperosfix.desktop"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }
}

dependencies {
    // Provided by LSPosed at runtime. Do not package it.
    compileOnly("de.robv.android.xposed:api:82")
}
