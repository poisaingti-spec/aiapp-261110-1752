package com.arin.builder

/**
 * A known-good Gradle project that every generated app starts from. The model only writes code and
 * resources under app/src/main, so small models (including offline Gemma) don't have to get the
 * build setup right.
 */
object ProjectTemplate {

    /**
     * Toolchain for new apps (checked November 2026 against the Android Gradle plugin and AndroidX
     * release pages; tested with a real GitHub build). AGP 9.5 needs Gradle 9.6 or newer and supports API 37.
     */
    const val AGP = "9.5.0"
    const val KOTLIN = "2.4.20"
    const val COMPOSE_BOM = "2026.08.00"
    const val GRADLE = "9.9.2"
    const val JDK = "21"
    const val SDK = 37
    const val CORE = "1.19.0"
    const val ACTIVITY = "1.13.0"
    const val LIFECYCLE = "2.11.0"
    const val NAVIGATION = "2.10.2"
    const val COROUTINES = "1.10.2"
    const val SERIALIZATION = "1.10.0"
    /** KSP (for Room and other code generators); the newest release is looked up when a build needs it. */
    const val KSP = "2.3.13"

    /** Libraries that generate code and so must be added with ksp(...) instead of implementation(...). */
    val kspArtifacts = listOf("room-compiler")

    private fun kspDeps(extraDeps: List<String>) = extraDeps.filter { it.startsWith("ksp:") }.map { it.removePrefix("ksp:") }
    // ARIN-PROTECTED: toolchain-guard — a KSP version that doesn't work with Kotlin $KOTLIN (an old "1.5.30-1.0.0"-style
    // version tied to another Kotlin, or anything older than KSP 2.3) is never used: the compatible KSP is used instead.
    private fun kspVersion(extraDeps: List<String>) =
        extraDeps.firstOrNull { it.startsWith("plugin:com.google.devtools.ksp:") }?.substringAfterLast(':')?.takeIf { kspFits(it) } ?: KSP

    /** True when this KSP version works with the template's Kotlin: KSP 2.3+ (no Kotlin prefix), or "<KOTLIN>-x". */
    fun kspFits(v: String): Boolean {
        if (v.startsWith("$KOTLIN-")) return true
        val m = Regex("""^(\d+)\.(\d+)\.(\d+)$""").matchEntire(v.trim()) ?: return false
        val (a, b) = m.destructured
        return a.toInt() > 2 || a.toInt() == 2 && b.toInt() >= 3
    }
    private fun plainDeps(extraDeps: List<String>) = extraDeps.filter { !it.startsWith("ksp:") && !it.startsWith("plugin:") }

    /**
     * One signing key shared by EVERY app Arin builds, committed into each project, so rebuilding an app always
     * installs over the previous version instead of "App not installed". (A fixed debug key; fine for sideloading.)
     */
    const val SHARED_KEYSTORE_B64 =
        "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"

    /** Files the app writes; the model is not allowed to replace these. */
    fun files(appName: String, packageName: String, extraDeps: List<String>): Map<String, String> = mapOf(
        "settings.gradle.kts" to """
            pluginManagement {
                repositories {
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                repositories {
                    google()
                    mavenCentral()
                    maven("https://jitpack.io")
                }
            }
            rootProject.name = "${appName.replace("\"", "")}"
            include(":app")
        """.trimIndent(),
        "build.gradle.kts" to """
            plugins {
                // AGP 9 compiles Kotlin itself (built-in Kotlin); this line only picks the Kotlin version.
                id("com.android.application") version "$AGP" apply false
                id("org.jetbrains.kotlin.android") version "$KOTLIN" apply false
                id("org.jetbrains.kotlin.plugin.compose") version "$KOTLIN" apply false
                id("org.jetbrains.kotlin.plugin.serialization") version "$KOTLIN" apply false
                ${if (kspDeps(extraDeps).isNotEmpty()) "id(\"com.google.devtools.ksp\") version \"${kspVersion(extraDeps)}\" apply false" else ""}
            }
        """.trimIndent(),
        "gradle.properties" to """
            org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
            android.useAndroidX=true
            kotlin.code.style=official
            android.nonTransitiveRClass=true
        """.trimIndent(),
        "app/build.gradle.kts" to """
            plugins {
                id("com.android.application")
                id("org.jetbrains.kotlin.plugin.compose")
                id("org.jetbrains.kotlin.plugin.serialization")
                ${if (kspDeps(extraDeps).isNotEmpty()) "id(\"com.google.devtools.ksp\")" else ""}
            }

            android {
                namespace = "$packageName"
                compileSdk = $SDK
                defaultConfig {
                    applicationId = "$packageName"
                    minSdk = 26
                    targetSdk = $SDK
                    versionCode = 1
                    versionName = "1.0"
                }
                signingConfigs {
                    // Created once by the workflow and kept in the repo, so every build installs over the last one.
                    getByName("debug") {
                        if (file("signing.keystore").exists()) {
                            storeFile = file("signing.keystore")
                            storePassword = "android"
                            keyAlias = "app"
                            keyPassword = "android"
                        }
                    }
                }
                buildTypes { release { isMinifyEnabled = false } }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
                buildFeatures { compose = true }
            }

            kotlin {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                }
            }

            dependencies {
                val composeBom = platform("androidx.compose:compose-bom:$COMPOSE_BOM")
                implementation(composeBom)
                implementation("androidx.core:core-ktx:$CORE")
                implementation("androidx.activity:activity-compose:$ACTIVITY")
                implementation("androidx.compose.ui:ui")
                implementation("androidx.compose.ui:ui-tooling-preview")
                implementation("androidx.compose.foundation:foundation")
                implementation("androidx.compose.material3:material3")
                implementation("androidx.compose.material:material-icons-extended")
                implementation("androidx.lifecycle:lifecycle-viewmodel-compose:$LIFECYCLE")
                implementation("androidx.lifecycle:lifecycle-runtime-ktx:$LIFECYCLE")
                implementation("androidx.lifecycle:lifecycle-runtime-compose:$LIFECYCLE")
                implementation("androidx.navigation:navigation-compose:$NAVIGATION")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$COROUTINES")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$SERIALIZATION")
                implementation("com.squareup.okhttp3:okhttp:4.12.0")
            ${(plainDeps(extraDeps).map { "    implementation(\"$it\")" } + kspDeps(extraDeps).map { "    ksp(\"$it\")" }).joinToString("\n")}
            }
        """.trimIndent(),
        ".gitignore" to "build/\n.gradle/\n.kotlin/\nlocal.properties\n*.iml\n.idea/\n.DS_Store\ncaptures/\n.cxx/\n",
        "app/proguard-rules.pro" to "# Add app-specific ProGuard rules here.\n",
        // So Android Studio opens the project with the right Gradle (the workflow adds gradlew and the jar).
        "gradle/wrapper/gradle-wrapper.properties" to """
            distributionBase=GRADLE_USER_HOME
            distributionPath=wrapper/dists
            distributionUrl=https\://services.gradle.org/distributions/gradle-$GRADLE-bin.zip
            networkTimeout=10000
            validateDistributionUrl=true
            zipStoreBase=GRADLE_USER_HOME
            zipStorePath=wrapper/dists
        """.trimIndent(),
        ".github/workflows/build.yml" to """
            name: Build APK
            on:
              push:
              workflow_dispatch:
            permissions:
              contents: write
            jobs:
              build:
                runs-on: ubuntu-latest
                timeout-minutes: 30
                steps:
                  - uses: actions/checkout@v5
                  - uses: actions/setup-java@v5
                    with:
                      distribution: temurin
                      java-version: "$JDK"
                  - uses: gradle/actions/setup-gradle@v5
                    with:
                      gradle-version: "$GRADLE"
                  - name: Signing key and Gradle wrapper (created once, then reused)
                    run: |
                      CHANGED=""
                      if [ ! -f app/signing.keystore ]; then
                        keytool -genkeypair -keystore app/signing.keystore -storepass android -keypass android -alias app -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Arin"
                        git add app/signing.keystore
                        CHANGED=1
                      fi
                      # gradlew + its jar, so the project also opens and builds in Android Studio with no setup.
                      if [ ! -f gradlew ] || [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
                        gradle wrapper --gradle-version "$GRADLE" --distribution-type bin && chmod +x gradlew
                        git add gradlew gradlew.bat gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties
                        CHANGED=1
                      fi
                      if [ -n "${'$'}CHANGED" ]; then
                        git config user.name "Arin"
                        git config user.email "arin@users.noreply.github.com"
                        git commit -m "Add signing key and Gradle wrapper"
                        git push origin HEAD:${'$'}{{ github.ref_name }} || echo "Couldn't save them; this build still works."
                      fi
                  - name: Build debug APK
                    run: |
                      set -o pipefail
                      gradle assembleDebug --stacktrace 2>&1 | tee build.log
                  - name: Report errors
                    if: failure()
                    run: |
                      grep -E "^e: |error:|What went wrong|Could not resolve|FAILURE" -A2 build.log | head -60 | while IFS= read -r l; do
                        echo "::error::${'$'}{l//%/%25}"
                      done
                  - name: Rename APK
                    run: cp app/build/outputs/apk/debug/app-debug.apk app-${'$'}{{ github.run_number }}.apk
                  - uses: softprops/action-gh-release@v2
                    with:
                      tag_name: build-${'$'}{{ github.run_number }}
                      name: Build ${'$'}{{ github.run_number }}
                      files: app-*.apk
                      # Not verified yet: the test job marks it verified (and newest) only if the app passes.
                      prerelease: true
                      make_latest: false
              # ARIN-PROTECTED: test-before-handover — the APK is installed on an Android emulator, opened and tapped
              # around; Arin hands the download link over only when this test passes. (Its own job: a test problem
              # never breaks the build itself.)
              test:
                needs: build
                runs-on: ubuntu-latest
                timeout-minutes: 25
                continue-on-error: true
                steps:
                  - uses: actions/checkout@v5
                  - name: Get the APK that was just built
                    env:
                      GH_TOKEN: ${'$'}{{ github.token }}
                    run: gh release download "build-${'$'}{{ github.run_number }}" --pattern 'app-*.apk' --output app.apk --clobber
                  - name: Enable KVM
                    run: |
                      echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules
                      sudo udevadm control --reload-rules
                      sudo udevadm trigger --name-match=kvm
                  - name: Open the app on an Android emulator and tap around
                    uses: reactivecircus/android-emulator-runner@v2
                    with:
                      api-level: 34
                      target: google_apis
                      arch: x86_64
                      disable-animations: true
                      emulator-options: -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim -no-snapshot
                      script: bash $TEST_SCRIPT_PATH app.apk
                  - name: Make sure there is a report
                    if: always()
                    run: '[ -f arin-test-report.txt ] || echo "RESULT: NO_TEST" > arin-test-report.txt'
                  - uses: softprops/action-gh-release@v2
                    if: always()
                    with:
                      tag_name: build-${'$'}{{ github.run_number }}
                      files: |
                        arin-test-report.txt
                        arin-test-screen.png
                      fail_on_unmatched_files: false
                  # ARIN-PROTECTED: release-gating — a release is marked verified (and newest) ONLY if the app passed the emulator test.
                  - name: Mark the release verified only if the app passed the test
                    if: always()
                    env:
                      GH_TOKEN: ${'$'}{{ github.token }}
                    run: |
                      if grep -q "RESULT: PASS" arin-test-report.txt; then gh release edit "build-${'$'}{{ github.run_number }}" -R "${'$'}{{ github.repository }}" --prerelease=false --latest; else gh release edit "build-${'$'}{{ github.run_number }}" -R "${'$'}{{ github.repository }}" --prerelease=true --latest=false; fi
        """.trimIndent(),
        TEST_SCRIPT_PATH to testScript,
    )

    fun defaultManifest(appName: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
            <uses-permission android:name="android.permission.INTERNET" />
            <application
                android:label="${appName.replace("\"", "")}"
                android:supportsRtl="true"
                android:theme="@android:style/Theme.Material.Light.NoActionBar">
                <activity
                    android:name=".MainActivity"
                    android:exported="true">
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN" />
                        <category android:name="android.intent.category.LAUNCHER" />
                    </intent-filter>
                </activity>
            </application>
        </manifest>
    """.trimIndent()

    /** Workflow added to uploaded projects; uses the project's own Gradle wrapper when it has one. */
    const val IMPORT_WORKFLOW_FILE = "arin-build.yml"

    /** Where the app-test script lives in a project. Its presence turns the emulator test on in the workflow. */
    const val TEST_SCRIPT_PATH = ".github/arin-test.sh"

    /** Installs the built APK on GitHub's Android emulator, opens it, taps around, and reports crashes. */
    val testScript = """#!/usr/bin/env bash
# Arin's app check: installs the APK on the emulator, opens it, taps around, and writes what happened.
APK="${'$'}1"
R=arin-test-report.txt
SHOT=arin-test-screen.png
AAPT=${'$'}(ls "${'$'}ANDROID_HOME"/build-tools/*/aapt 2>/dev/null | sort -V | tail -1)
PKG=${'$'}("${'$'}AAPT" dump badging "${'$'}APK" 2>/dev/null | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
report() {
  { echo "RESULT: ${'$'}1"; [ -n "${'$'}2" ] && echo "ERROR: ${'$'}2"; echo "PACKAGE: ${'$'}PKG"; [ -f crash.txt ] && cat crash.txt; } > "${'$'}R"
  cat "${'$'}R"
  exit 0
}
[ -z "${'$'}PKG" ] && report NO_TEST "couldn't read the app's package name"
adb wait-for-device
adb logcat -c
if ! adb install -r -g "${'$'}APK" > install.log 2>&1; then
  tail -20 install.log > crash.txt
  report INSTALL_FAILED "${'$'}(grep -m1 -E 'Failure|INSTALL_' install.log)"
fi
check() {
  adb logcat -d > logcat.txt 2>/dev/null
  if grep -A2 "FATAL EXCEPTION" logcat.txt | grep -q "Process: ${'$'}PKG"; then
    grep -A60 "FATAL EXCEPTION" logcat.txt | grep "AndroidRuntime" | sed 's/^.*AndroidRuntime: //' | head -60 > crash.txt
    report CRASH "${'$'}(grep -v -E 'FATAL EXCEPTION|Process:' crash.txt | head -1)"
  fi
  if grep -q "ANR in ${'$'}PKG" logcat.txt; then
    grep -A12 "ANR in ${'$'}PKG" logcat.txt | head -20 > crash.txt
    report CRASH "The app stopped responding (ANR)"
  fi
}
adb shell monkey -p "${'$'}PKG" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
sleep 15
adb exec-out screencap -p > "${'$'}SHOT" 2>/dev/null
check
if [ -z "${'$'}(adb shell pidof "${'$'}PKG" | tr -d '\r')" ]; then
  adb logcat -d | grep -i "${'$'}PKG" | tail -40 > crash.txt
  report NOT_RUNNING "The app closed by itself right after it opened"
fi
adb shell monkey -p "${'$'}PKG" --pct-syskeys 0 --pct-appswitch 0 --throttle 250 -v 150 > monkey.log 2>&1
sleep 3
check
report PASS ""
"""

    /**
     * Devcontainer for a GitHub Codespace: a Java image with the Android SDK set up on create, so opening the
     * codespace gives a ready Android build environment. Build with:  ./gradlew assembleDebug
     */
    val devcontainer = """
        {
          "name": "Android build",
          "image": "mcr.microsoft.com/devcontainers/java:21",
          "features": {
            "ghcr.io/devcontainers/features/android-sdk:1": {}
          },
          "onCreateCommand": "chmod +x gradlew || true",
          "customizations": { "vscode": { "extensions": ["redhat.java", "vscjava.vscode-gradle"] } }
        }
    """.trimIndent()

    /**
     * ARIN-PROTECTED: test-before-handover — the emulator test job (YAML, at the jobs: level) for any workflow: downloads the
     * APK that [tag]'s release got ([pattern]), opens it on an Android emulator, taps around, and adds the report and a
     * screenshot to that release. Used by Arin-made apps, uploaded projects and Arin's own updates.
     */
    fun testJob(tag: String, pattern: String): String = """
          # ARIN-PROTECTED: test-before-handover — the APK is installed on an Android emulator, opened and tapped
          # around; Arin hands the download link over only when this test passes. (Its own job: a test problem
          # never breaks the build itself.)
          test:
            needs: build
            runs-on: ubuntu-latest
            timeout-minutes: 25
            continue-on-error: true
            steps:
              - uses: actions/checkout@v5
              - name: Get the APK that was just built
                env:
                  GH_TOKEN: ${'$'}{{ github.token }}
                run: gh release download "$tag" --pattern '$pattern' --output app.apk --clobber
              - name: Enable KVM
                run: |
                  echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules
                  sudo udevadm control --reload-rules
                  sudo udevadm trigger --name-match=kvm
              - name: Open the app on an Android emulator and tap around
                uses: reactivecircus/android-emulator-runner@v2
                with:
                  api-level: 34
                  target: google_apis
                  arch: x86_64
                  disable-animations: true
                  emulator-options: -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim -no-snapshot
                  script: bash .github/arin-test.sh app.apk
              - name: Make sure there is a report
                if: always()
                run: '[ -f arin-test-report.txt ] || echo "RESULT: NO_TEST" > arin-test-report.txt'
              - uses: softprops/action-gh-release@v2
                if: always()
                with:
                  tag_name: $tag
                  files: |
                    arin-test-report.txt
                    arin-test-screen.png
                  fail_on_unmatched_files: false
              # ARIN-PROTECTED: release-gating — a release is marked verified (and newest) ONLY if the app passed the emulator test.
              - name: Mark the release verified only if the app passed the test
                if: always()
                env:
                  GH_TOKEN: ${'$'}{{ github.token }}
                run: |
                  if grep -q "RESULT: PASS" arin-test-report.txt; then gh release edit "$tag" -R "${'$'}{{ github.repository }}" --prerelease=false --latest; else gh release edit "$tag" -R "${'$'}{{ github.repository }}" --prerelease=true --latest=false; fi
    """.trimIndent()

    val importWorkflow = """
        name: Arin build
        on:
          push:
          workflow_dispatch:
        permissions:
          contents: write
        jobs:
          build:
            runs-on: ubuntu-latest
            timeout-minutes: 45
            steps:
              - uses: actions/checkout@v5
              - uses: actions/setup-java@v5
                with:
                  distribution: temurin
                  java-version: |
                    11
                    17
                    21
              - uses: gradle/actions/setup-gradle@v5
                with:
                  gradle-version: "$GRADLE"
              - name: Build debug APK
                run: |
                  set -o pipefail
                  # Old projects need an older JDK and Gradle; new ones (AGP 9) need JDK 21 and Gradle 9.
                  ver() { printf '%s' "${'$'}1" | awk -F. '{ printf "%d%03d", ${'$'}1, ${'$'}2 }'; }
                  GV=${'$'}(grep -o 'gradle-[0-9.]*-' gradle/wrapper/gradle-wrapper.properties 2>/dev/null | head -1 | sed 's/gradle-//; s/-${'$'}//')
                  AGPV=${'$'}(grep -rhoE '(com\.android\.tools\.build:gradle:|com\.android\.application"?\)? version "?|agp ?= ?")[0-9]+\.[0-9]+' --include=*.gradle* --include=*.toml . 2>/dev/null | grep -oE '[0-9]+\.[0-9]+${'$'}' | head -1)
                  if [ -z "${'$'}GV" ]; then
                    if [ -n "${'$'}AGPV" ] && [ "${'$'}(ver ${'$'}AGPV)" -lt "${'$'}(ver 9.0)" ]; then
                      if [ "${'$'}(ver ${'$'}AGPV)" -lt "${'$'}(ver 7.0)" ]; then GV=6.9.4
                      elif [ "${'$'}(ver ${'$'}AGPV)" -lt "${'$'}(ver 8.0)" ]; then GV=7.6.4
                      else GV=8.14.3; fi
                    else GV="$GRADLE"; fi
                    echo "No Gradle wrapper: using Gradle ${'$'}GV for AGP ${'$'}{AGPV:-unknown}"
                    gradle wrapper --gradle-version "${'$'}GV" --distribution-type bin || true
                  fi
                  if [ "${'$'}(ver ${'$'}GV)" -lt "${'$'}(ver 7.3)" ]; then export JAVA_HOME=${'$'}JAVA_HOME_11_X64
                  elif [ "${'$'}(ver ${'$'}GV)" -lt "${'$'}(ver 8.5)" ]; then export JAVA_HOME=${'$'}JAVA_HOME_17_X64
                  else export JAVA_HOME=${'$'}JAVA_HOME_21_X64; fi
                  echo "Gradle ${'$'}GV, JDK ${'$'}JAVA_HOME"
                  chmod +x gradlew
                  ./gradlew assembleDebug --stacktrace 2>&1 | tee build.log
              - name: Report errors
                if: failure()
                run: |
                  grep -E "^e: |error:|What went wrong|Could not resolve|FAILURE" -A2 build.log | head -60 | while IFS= read -r l; do
                    echo "::error::${'$'}{l//%/%25}"
                  done
              - name: Collect APK
                run: |
                  APK=${'$'}(find . -path "*/build/outputs/apk/*" -name "*.apk" | head -n 1)
                  if [ -z "${'$'}APK" ]; then echo "No APK was produced"; exit 1; fi
                  cp "${'$'}APK" app-${'$'}{{ github.run_number }}.apk
              - uses: softprops/action-gh-release@v2
                with:
                  tag_name: build-${'$'}{{ github.run_number }}
                  name: Build ${'$'}{{ github.run_number }}
                  files: app-*.apk
                  prerelease: true
                  make_latest: false
    """.trimIndent() + "\n" + testJob("build-${'$'}{{ github.run_number }}", "app-*.apk").prependIndent("  ")

    val importedSystemPrompt = """
        You are an expert Android developer working on an existing Android Studio project (Gradle, Kotlin or Java).
        You fix build errors and make the changes the user asks for, keeping the project's existing style and setup.

        Output ONLY changed or new files, in this format:

        === FILE: path/from/project/root ===
        (full file content)
        === END FILE ===

        Rules:
        - Paths are relative to the project root (where settings.gradle is), e.g. app/src/main/java/... or app/build.gradle.kts.
        - Write every file you send in full. No placeholders, no "..." and no TODOs.
        - To add a library, edit the module's build.gradle(.kts) dependencies (and gradle/libs.versions.toml if the project uses it).
        - To delete a file, send it with the single line: DELETE
        - To get a file from the internet (image, font, sound, data, a library jar), add a line: === DOWNLOAD: https://url -> path/in/project === .
        - Don't touch .github/ or the Gradle wrapper jar.
        - Right before each file, write ONE line explaining it in plain English for someone who doesn't code (no code words), e.g.
          === ABOUT: The main screen: shows your notes in a list with a + button to add a new one ===
          When fixing errors, say what was wrong and what you changed, e.g. === ABOUT: Fixed a missing import so the list screen can show icons ===
    
        ${com.arin.selffix.TaskSummary.EXACT_REQUEST.trim()}
    """.trimIndent()

    /** Lets the model look things up before writing code. */
    val webInstructions = "\n\n" + """

        Before writing files you may look things up online. Reply with ONLY <search>query</search> to
        search the web, or ONLY <fetch>https://url</fetch> to read a page (docs, READMEs, release
        notes). Use this when you're unsure how a library or API works. Then write the files.
    """.trimIndent()

    private const val SIZE_RULE = "If the whole app doesn't fit in one reply, write as many COMPLETE files as fit and end with the line " +
        "=== MORE === ; you'll be asked to continue. When every file is written, end with the line === DONE === ."

    /** Rules given to the model. Kept short so small offline models can follow them. */
    fun systemPrompt(packageName: String): String = """
        You are an expert Android developer. You write complete, working Android apps in Kotlin with Jetpack Compose and Material 3.

        The Gradle setup already exists. It includes: Compose (BOM $COMPOSE_BOM, Compose 1.12), material3, material-icons-extended, activity-compose $ACTIVITY, core-ktx $CORE, lifecycle-viewmodel-compose and lifecycle-runtime-compose $LIFECYCLE, navigation-compose $NAVIGATION, kotlinx-coroutines $COROUTINES, kotlinx-serialization-json $SERIALIZATION (the serialization plugin is applied), okhttp. Kotlin $KOTLIN, Android Gradle plugin $AGP, Gradle $GRADLE, JDK $JDK, compileSdk $SDK, targetSdk $SDK, minSdk 26.
        Package name: $packageName. Source folder: app/src/main/java/${packageName.replace('.', '/')}/
        The entry point must be $packageName.MainActivity (a ComponentActivity calling setContent).

        Output ONLY in this format, nothing else:

        === FILE: app/src/main/java/${packageName.replace('.', '/')}/MainActivity.kt ===
        (full file content)
        === END FILE ===

        Rules:
        - Only write files under app/src/main/ (Kotlin sources, AndroidManifest.xml, res/ files).
        - Write every file in full. No placeholders, no "..." and no TODOs.
        - ${com.arin.selffix.TaskSummary.EXACT_REQUEST.trim()}
        - OWNER'S STRICT RULE (tested before handover): the app is installed and opened on an Android emulator after it
          builds; it must open and run without crashing (no missing permissions, null values, network on the main thread,
          missing resources or activities), so write it to work on first launch.
        - OWNER'S STRICT RULE (complete project): include EVERY file the app needs to compile into an APK in Android Studio:
          all Kotlin files, the AndroidManifest.xml entries, and every resource the code uses (each R.string / R.drawable /
          R.raw etc. must have its file or entry). Use only STABLE library versions (no alpha, beta or rc) that work with
          compileSdk $SDK; prefer the libraries listed above. Check every import and every name you use exists.
        - Only use the libraries listed above unless you add a line: === DEPENDENCY: group:artifact:version === (use "latest" as the version if unsure).
        - To get a file from the internet (image, font, sound, JSON), add a line: === DOWNLOAD: https://url -> app/src/main/res/raw/name.ext === (res/raw, res/drawable, res/font or assets only; file names lowercase with underscores).
        - Size the code to the app. A small tool: one or a few files. A bigger app: organise it properly, one screen per file, in
          packages such as ui/ (screens, components, theme), data/ (storage, network), model/ and viewmodel/. Every import must exist.
        - Build real apps, not demos: ViewModel + StateFlow for state, navigation-compose between screens, a Material 3 theme with
          light and dark colours, loading / empty / error states, input validation, and data that survives restarting the app when
          it should (DataStore for settings, Room or JSON files for records). Ask for runtime permissions before using them and
          declare them in AndroidManifest.xml. Never leave TODOs, empty click handlers or fake sample data in place of a feature.
        - Libraries you may add with a DEPENDENCY line (use "latest"): androidx.room:room-compiler (database; Arin adds room-runtime
          and the code generator setup for you, use @Entity/@Dao/@Database with suspend functions and Flow),
          androidx.datastore:datastore-preferences, io.coil-kt.coil3:coil-compose and io.coil-kt.coil3:coil-network-okhttp (images
          from the internet), com.squareup.retrofit2:retrofit with com.squareup.retrofit2:converter-kotlinx-serialization (web APIs),
          androidx.work:work-runtime-ktx (background work), androidx.media3:media3-exoplayer and androidx.media3:media3-ui (audio
          and video), androidx.camera:camera-camera2, camera-lifecycle and camera-view (camera), com.google.android.gms:play-services-location
          (location), com.google.accompanist:accompanist-permissions, androidx.biometric:biometric, androidx.glance:glance-appwidget
          (home-screen widgets), com.google.mlkit libraries (on-device text recognition, barcode scanning, translation),
          org.osmdroid:osmdroid-android (maps without an API key). Don't use Hilt, Dagger, kapt, Glide or Firebase: pass
          dependencies by hand (a small AppContainer object) instead.
        - $SIZE_RULE
        - If you write AndroidManifest.xml, keep MainActivity as the launcher activity.
        - Right before each file, write ONE line explaining it in plain English for someone who doesn't code (no code words), e.g.
          === ABOUT: The main screen: shows your notes in a list with a + button to add a new one ===
          When fixing errors, say what was wrong and what you changed, e.g. === ABOUT: Fixed a missing import so the list screen can show icons ===
    """.trimIndent()
}
