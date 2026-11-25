import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "in.diganta.aurabutler"
    compileSdk = 36

    defaultConfig {
        applicationId = "in.diganta.aurabutler"
        minSdk = 26
        targetSdk = 36
        versionCode = 28
        versionName = "0.17.3-voice-continuity"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["usesCleartextTraffic"] = "false"

        ndk {
            // The connected Vivo 1901 reports arm64. Keeping one ABI makes the
            // bundled private voice pack substantially smaller.
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            // sherpa-onnx v1.13.4 is built against ONNX Runtime 1.27.0.
            // Resolve the identical runtime shared object supplied by both AARs once.
            pickFirsts += setOf(
                "lib/arm64-v8a/libonnxruntime.so",
                "lib/armeabi-v7a/libonnxruntime.so",
                "lib/x86/libonnxruntime.so",
                "lib/x86_64/libonnxruntime.so"
            )
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    androidResources {
        // sherpa-onnx reads models directly through AssetManager.
        noCompress += listOf("onnx", "model")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("androidx.security:security-crypto:1.1.0")
    // Keep aligned with the ONNX Runtime shared library bundled by sherpa-onnx v1.13.4.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // BSD-3-Clause, self-hosted WebRTC transport; no telephony or media cloud vendor.
    implementation("io.github.webrtc-sdk:android:150.7871.08")
    // Apache-2.0 WSS signaling transport.
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation(files("libs/sherpa-onnx-v1.13.4.aar"))

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

// okhttp 5.5.x compiles against Android API 37, which AGP 8.13.2 does not support yet.
// Keep every okhttp artifact, including the transitive okhttp-android, on 5.4.0.
configurations.all {
    resolutionStrategy {
        force("com.squareup.okhttp3:okhttp:5.4.0")
        force("com.squareup.okhttp3:okhttp-android:5.4.0")
    }
}
