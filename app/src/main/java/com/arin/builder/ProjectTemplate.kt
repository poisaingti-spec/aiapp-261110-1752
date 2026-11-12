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
    const val GRADLE = "9.9.0"
    const val JDK = "21"
    const val SDK = 37
    const val CORE = "1.19.0"
    const val ACTIVITY = "1.13.0"
    const val LIFECYCLE = "2.11.0"
    const val NAVIGATION = "2.10.2"
    const val COROUTINES = "1.10.2"
    const val SERIALIZATION = "1.10.0"

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
            ${extraDeps.joinToString("\n") { "    implementation(\"$it\")" }}
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
                      make_latest: true
        """.trimIndent(),
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
                  make_latest: true
    """.trimIndent()

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
    """.trimIndent()

    /** Lets the model look things up before writing code. */
    val webInstructions = "\n\n" + """

        Before writing files you may look things up online. Reply with ONLY <search>query</search> to
        search the web, or ONLY <fetch>https://url</fetch> to read a page (docs, READMEs, release
        notes). Use this when you're unsure how a library or API works. Then write the files.
    """.trimIndent()

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
        - Only use the libraries listed above unless you add a line: === DEPENDENCY: group:artifact:version === (use "latest" as the version if unsure).
        - To get a file from the internet (image, font, sound, JSON), add a line: === DOWNLOAD: https://url -> app/src/main/res/raw/name.ext === (res/raw, res/drawable, res/font or assets only; file names lowercase with underscores).
        - Keep it simple and correct: prefer one or a few files. Every import must exist.
        - If you write AndroidManifest.xml, keep MainActivity as the launcher activity.
    """.trimIndent()
}
