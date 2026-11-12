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
        versionCode = 9
        versionName = "0.6.1"
    }

    signingConfigs {
        // Fixed key so each new build installs over the previous one.
        getByName("debug") {
            storeFile = file("arin-debug.keystore")
            storePassword = "arinandroid"
            keyAlias = "arin"
            keyPassword = "arinandroid"
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
}
