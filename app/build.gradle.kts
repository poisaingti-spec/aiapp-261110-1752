plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.arin"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.arin.app"
        minSdk = 26
        // 28, like Termux: Android blocks running programs from app storage for apps targeting 29+,
        // and the built-in Linux (PRoot) needs that. Arin is installed from GitHub, not the Play Store.
        targetSdk = 28
        versionCode = 269
        versionName = "1.26.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    signingConfigs {
        // Fixed key so each new build installs over the previous one.
        // Signing: if the repo has signing secrets (ARIN_KEYSTORE_FILE / ARIN_STORE_PASSWORD / ARIN_KEY_ALIAS /
        // ARIN_KEY_PASSWORD, set by the workflow from GitHub Secrets), they are used; otherwise the bundled key, so every
        // existing install keeps updating (Android only accepts updates signed with the same key).
        // NOTE: the bundled key and its password are public in this repo, so treat that key as known to anyone. Moving
        // to a private key means one reinstall (uninstall + install) — see SIGNING.md.
        getByName("debug") {
            val ks = System.getenv("ARIN_KEYSTORE_FILE")?.takeIf { it.isNotBlank() && file(it).exists() }
            storeFile = if (ks != null) file(ks) else file("arin-debug.keystore")
            storePassword = System.getenv("ARIN_STORE_PASSWORD")?.takeIf { ks != null && it.isNotBlank() } ?: "arinandroid"
            keyAlias = System.getenv("ARIN_KEY_ALIAS")?.takeIf { ks != null && it.isNotBlank() } ?: "arin"
            keyPassword = System.getenv("ARIN_KEY_PASSWORD")?.takeIf { ks != null && it.isNotBlank() } ?: "arinandroid"
        }
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
    packaging {
        // Keep PRoot (lib*.so) extracted to nativeLibraryDir so it can be run.
        jniLibs { useLegacyPackaging = true }
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
        disable += "OldTargetApi"
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // Tests: JVM unit tests (testDebugUnitTest) and on-emulator tests (connected, run by the workflow).
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Reads the text of PDFs, so big PDFs and models without PDF support still work.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // Unpacks the Ubuntu image for the built-in Linux.
    implementation("org.apache.commons:commons-compress:1.27.1")
    // Reads text in pictures and video frames on the phone (offline), so models that can't see still get it.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:image-labeling:17.0.9")
}
