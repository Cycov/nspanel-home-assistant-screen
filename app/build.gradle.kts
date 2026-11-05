plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.nspanelhascreen"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.nspanelhascreen"
        minSdk = 26 // NSPanel Pro runs Android 8.1 (27)
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so `assembleRelease` gives an installable APK out of the box.
            // Use your own key if you distribute builds.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
