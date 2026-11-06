plugins {
    id("com.android.application")
}

// Release key, kept outside the repo, e.g. in ~/.gradle/gradle.properties:
//   nspanelHaScreenStoreFile=C:/Users/you/.android/nspanel-ha-screen-release.p12
//   nspanelHaScreenStorePassword=...   (PKCS12: the key has the same password)
//   nspanelHaScreenKeyAlias=nspanel-ha-screen
// Without them, release builds are signed with the debug key so they still install.
val releaseStoreFile = providers.gradleProperty("nspanelHaScreenStoreFile").orNull
val releaseStorePassword = providers.gradleProperty("nspanelHaScreenStorePassword").orNull
val releaseKeyAlias = providers.gradleProperty("nspanelHaScreenKeyAlias").orNull ?: "nspanel-ha-screen"

android {
    namespace = "io.github.nspanelhascreen"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.nspanelhascreen"
        minSdk = 26 // NSPanel Pro runs Android 8.1 (27)
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0"
    }

    signingConfigs {
        if (releaseStoreFile != null && releaseStorePassword != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseStorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
