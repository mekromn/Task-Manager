import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// GitHub Actions gives every workflow run a monotonically increasing run number.
// Canonical CI APKs therefore always receive a higher versionCode than older CI APKs.
val ciRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
val taskManagerVersionCode = 100_000 + ciRunNumber
val taskManagerVersionName = "1.0.$ciRunNumber"

android {
    namespace = "com.mekromn.taskmanager"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mekromn.taskmanager"
        minSdk = 29
        targetSdk = 36
        versionCode = taskManagerVersionCode
        versionName = taskManagerVersionName
    }

    // Intentionally stable DEVELOPMENT signing identity for sideload/update continuity.
    // This key is public with the source tree and MUST NOT be reused for a production/store release.
    signingConfigs {
        create("stableDevelopment") {
            storeFile = rootProject.file("signing/taskmanager-dev.jks")
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

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

dependencies {
    // API-36-compatible AndroidX generation. Compose 1.12 requires compileSdk 37.
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.foundation:foundation-layout")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
