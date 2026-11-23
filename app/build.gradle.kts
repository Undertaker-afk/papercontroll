plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.papercontrol.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.papercontrol.app"
        minSdk = 28
        targetSdk = 35
        // CI passes the run number and the tag, so every release installs over the previous one.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME")?.removePrefix("v") ?: "0.1.0"

        ndk {
            // LiteRT-LM ships native libraries for these two ABIs only.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // Release signing comes from the environment (GitHub Actions secrets), never from the repo.
    // Without it, release builds are produced unsigned.
    val keystore = System.getenv("RELEASE_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = keystore
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "papercontrol"
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: System.getenv("RELEASE_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            ndk { abiFilters.clear(); abiFilters += "arm64-v8a" }
            isMinifyEnabled = false
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/*.kotlin_module")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.maxHeapSize = "3g" }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Optional on-device chat model (Gemma) for titles, fields and sorting help.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")

    // On-device text recognition; the model is bundled, so OCR works offline from the start.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Other writing systems: small, Play services downloads the model when first used.
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-chinese:16.0.2")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-japanese:16.0.2")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-korean:16.0.2")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-devanagari:16.0.2")
    // ASN stickers, separator sheets and payment QR codes.
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    // Document detection, perspective correction and scan clean-up.
    implementation("org.opencv:opencv:4.13.0")
    // App lock.
    implementation("androidx.biometric:biometric:1.1.0")
    // Backup folder access.
    implementation("androidx.documentfile:documentfile:1.1.0")

    val camerax = "1.4.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")

    testImplementation("junit:junit:4.13.2")
    // OpenCV's desktop build, so the document detector can run on real photos in unit tests.
    testImplementation("org.openpnp:opencv:4.9.0-0")

    // Screenshot tests: render screens and frames of the scan animation on the JVM.
    testImplementation("org.robolectric:robolectric:4.18.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.79.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.79.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
