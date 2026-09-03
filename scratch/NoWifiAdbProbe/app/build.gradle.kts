plugins {
    id("com.android.application")
}

android {
    namespace = "com.mekromn.nowifiadbprobe"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mekromn.nowifiadbprobe"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-probe"
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
}
