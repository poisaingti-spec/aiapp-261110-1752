# Signing Arin

**Current state.** Arin is signed with `app/arin-debug.keystore`, whose password is written in `app/build.gradle.kts`.
This repository is public, so this key must be treated as **known to anyone**: someone could sign an APK that Android
accepts as an "update" of Arin. Only install Arin from this repository's own GitHub releases.

**Why it isn't simply replaced.** Android installs an update only if it's signed with the same key. Changing the key
would make every installed copy refuse the next update (it would need uninstalling first, losing its data).

**Safe migration (when you choose to do it):**
1. Make a new private key on any machine with Java: `keytool -genkeypair -keystore arin-release.jks -alias arin -keyalg RSA -keysize 4096 -validity 10000`.
2. In GitHub → repo Settings → Secrets → Actions add: `ARIN_KEYSTORE_B64` (the .jks file in base64), `ARIN_STORE_PASSWORD`,
   `ARIN_KEY_ALIAS`, `ARIN_KEY_PASSWORD`.
3. The workflow then decodes the key to a temporary file and Gradle signs with it (see `build.gradle.kts`); the bundled
   key is no longer used. Nothing secret is ever written into the repository or the logs.
4. Back up Arin's data, uninstall the old Arin once, install the newly signed one. All later updates install normally.
