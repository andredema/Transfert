plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zenviko.transfert"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zenviko.transfert"
        minSdk = 24          // Android 7.0 et plus
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
}

// Aucune bibliothèque externe : uniquement le SDK Android et Kotlin.
