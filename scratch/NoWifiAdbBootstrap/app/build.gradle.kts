plugins {
    id("com.android.application")
}

val ciRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
val noWifiAdbVersionCode = 300_000 + ciRunNumber
val noWifiAdbVersionName = "0.5.$ciRunNumber"

android {
    namespace = "com.mekromn.nowifiadb"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mekromn.nowifiadb"
        minSdk = 30
        targetSdk = 36
        versionCode = noWifiAdbVersionCode
        versionName = noWifiAdbVersionName
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        create("stableDevelopment") {
            // Reuse the repo's public development key solely for sideload/update continuity.
            storeFile = rootProject.file("../../signing/taskmanager-dev.jks")
            storePassword = "taskmanager"
            keyAlias = "taskmanager-dev"
            keyPassword = "taskmanager"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("stableDevelopment")
        }
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}
