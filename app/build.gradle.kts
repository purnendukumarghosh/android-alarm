plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optional private signing key, supplied by CI from repository secrets.
// Without it the APK is signed with the standard Android debug key.
val keystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")
val hasReleaseKey = !keystorePath.isNullOrBlank() && file(keystorePath).exists()

android {
    namespace = "com.voicealarm.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.voicealarm.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
