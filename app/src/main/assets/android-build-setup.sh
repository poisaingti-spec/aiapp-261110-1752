set -e
export DEBIAN_FRONTEND=noninteractive
# Downloads go through arin-fetch so Arin shows MB, %, speed and time left (falls back to plain curl).
fetch(){ if command -v arin-fetch >/dev/null 2>&1; then arin-fetch "$@"; else curl -fL --retry 5 -sS -o "$2" "$1"; fi; }
ARCH=$(uname -m)

# 1. JDK 21, Gradle, and the small tools the rest of this script needs
if ! command -v javac >/dev/null 2>&1 || ! command -v unzip >/dev/null 2>&1 || ! command -v curl >/dev/null 2>&1; then
  apt-get update
  apt-get install -y --no-install-recommends openjdk-21-jdk-headless unzip zip curl
fi
G=@@GRADLE@@
if [ ! -x /opt/gradle-$G/bin/gradle ]; then
  echo "Downloading Gradle $G…"
  fetch https://services.gradle.org/distributions/gradle-$G-bin.zip /tmp/gradle.zip "Gradle $G"
  mkdir -p /opt && unzip -q -o /tmp/gradle.zip -d /opt && rm /tmp/gradle.zip
fi
ln -sf /opt/gradle-$G/bin/gradle /usr/local/bin/gradle

# 2. Android SDK: command-line tools, platform, build-tools, platform-tools, licences
export ANDROID_HOME=/opt/android-sdk
mkdir -p $ANDROID_HOME
if [ ! -x $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager ]; then
  echo "Downloading the Android SDK command-line tools…"
  Z=$(curl -fsSL https://dl.google.com/android/repository/repository2-3.xml | grep -o 'commandlinetools-linux-[0-9]*_latest.zip' | sort -V | tail -1)
  [ -n "$Z" ] || Z=commandlinetools-linux-13114758_latest.zip
  fetch "https://dl.google.com/android/repository/$Z" /tmp/clt.zip "Android SDK command-line tools"
  rm -rf /tmp/clt && unzip -q /tmp/clt.zip -d /tmp/clt && rm /tmp/clt.zip
  mkdir -p $ANDROID_HOME/cmdline-tools && rm -rf $ANDROID_HOME/cmdline-tools/latest
  mv /tmp/clt/cmdline-tools $ANDROID_HOME/cmdline-tools/latest
fi
SDKM=$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager
yes | $SDKM --sdk_root=$ANDROID_HOME --licenses >/dev/null 2>&1 || true
echo "Installing Android platform @@SDK@@ + build-tools + platform-tools…"
$SDKM --sdk_root=$ANDROID_HOME "platforms;android-@@SDK@@" "build-tools;@@SDK@@.0.0" "platform-tools" 2>&1 | grep -v '^\[' | tail -3 || true
# If this phone's exact build-tools revision isn't X.0.0, grab whatever the platform pulls in; and keep a
# fallback platform + build-tools so projects with a lower compileSdk also build. Gradle fetches the rest.
$SDKM --sdk_root=$ANDROID_HOME "platforms;android-35" "build-tools;35.0.0" >/dev/null 2>&1 || true

# 3. Native host tools for this CPU. Google ships aapt2/aidl/zipalign as x86-64 Linux binaries only, so on
#    an ARM phone Gradle's downloaded aapt2 fails with "Exec format error". Fetch glibc arm64 builds.
mkdir -p $ANDROID_HOME/arin
A2=$ANDROID_HOME/arin/aapt2
AIDL=$ANDROID_HOME/arin/aidl
ZA=$ANDROID_HOME/arin/zipalign
# Fetch if ANY of the three is missing (so an existing install that only has aapt2 still gains aidl/zipalign).
NEED=0
if [ "$ARCH" != "x86_64" ]; then
  "$A2" version >/dev/null 2>&1 || NEED=1
  [ -x "$AIDL" ] || NEED=1
  [ -x "$ZA" ] || NEED=1
fi
if [ "$NEED" = 1 ]; then
  echo "Getting native aapt2/aidl/zipalign for $ARCH…"
  for REPO in lzhiyong/android-sdk-tools hamza72x/android-sdk-linux-arm64; do
    URL=$(curl -fsSL "https://api.github.com/repos/$REPO/releases" | grep -o 'https://[^"]*\.\(zip\|tar\.gz\|tar\.xz\)' | grep -i "$ARCH" | grep -iv 'windows\|darwin\|macos' | head -1 || true)
    [ -n "$URL" ] || continue
    rm -rf /tmp/a2 && mkdir -p /tmp/a2
    fetch "$URL" /tmp/a2/pkg "native aapt2 tools" || continue
    case "$URL" in *.zip) unzip -q -o /tmp/a2/pkg -d /tmp/a2 ;; *) tar -xf /tmp/a2/pkg -C /tmp/a2 ;; esac
    for T in aapt2 aidl zipalign; do
      [ -x "$ANDROID_HOME/arin/$T" ] && continue
      F=$(find /tmp/a2 -type f -name "$T" | head -1)
      [ -n "$F" ] && { cp "$F" "$ANDROID_HOME/arin/$T"; chmod +x "$ANDROID_HOME/arin/$T"; }
    done
    "$A2" version >/dev/null 2>&1 && [ -x "$AIDL" ] && [ -x "$ZA" ] && { echo "native tools from $REPO"; break; }
  done
  if ! "$A2" version >/dev/null 2>&1; then
    echo "Trying Ubuntu's aapt2…"
    apt-get install -y --no-install-recommends aapt >/dev/null 2>&1 || true
    command -v aapt2 >/dev/null 2>&1 && ln -sf "$(command -v aapt2)" "$A2"
  fi
fi
# Drop native aidl/zipalign over the x86-64 copies inside each build-tools dir (used for AIDL + packaging).
if [ "$ARCH" != "x86_64" ] && [ -d "$ANDROID_HOME/build-tools" ]; then
  for BT in "$ANDROID_HOME"/build-tools/*/; do
    [ -x "$AIDL" ] && cp "$AIDL" "${BT}aidl" 2>/dev/null || true
    [ -x "$ZA" ] && cp "$ZA" "${BT}zipalign" 2>/dev/null || true
  done
fi

# 4. A debug keystore so debug builds always sign (AGP usually creates this itself; make sure it's there).
mkdir -p /root/.android
KS=/root/.android/debug.keystore
if [ ! -f "$KS" ] && command -v keytool >/dev/null 2>&1; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Android Debug,O=Android,C=US' >/dev/null 2>&1 || true
fi

# 5. Gradle settings for a phone: less memory, no background daemon, and the native aapt2.
mkdir -p /root/.gradle
{
  echo "org.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8"
  echo "org.gradle.daemon=false"
  echo "org.gradle.workers.max=2"
  echo "kotlin.compiler.execution.strategy=in-process"
  echo "android.builder.sdkDownload=true"
  if [ "$ARCH" != "x86_64" ] && "$A2" version >/dev/null 2>&1; then echo "android.aapt2FromMavenOverride=$A2"; fi
} > /root/.gradle/gradle.properties
cat > /etc/profile.d/arin-android.sh <<'P'
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
export PATH=$PATH:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools
P

# 6. arin-build: compiles a project folder into a debug APK, after checking every tool is present.
cat > /usr/local/bin/arin-build <<'B'
#!/bin/bash
# Usage: arin-build /path/to/project  — builds a debug APK and prints ARIN_APK=<path> when it works.
set -o pipefail
. /etc/profile.d/arin-android.sh
export UV_USE_IO_URING=0 GLIBC_TUNABLES=glibc.pthread.rseq=0
ARCH=$(uname -m)
# Preflight: fail early with a precise message if any build tool is missing.
miss=""
command -v javac >/dev/null 2>&1 || miss="$miss JDK(javac)"
[ -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ] || miss="$miss SDK-cmdline-tools"
ls "$ANDROID_HOME"/platforms/android-* >/dev/null 2>&1 || miss="$miss Android-platform"
ls "$ANDROID_HOME"/build-tools/*/ >/dev/null 2>&1 || miss="$miss build-tools"
if [ "$ARCH" != "x86_64" ]; then "$ANDROID_HOME/arin/aapt2" version >/dev/null 2>&1 || miss="$miss aapt2($ARCH)"; fi
if [ -n "$miss" ]; then echo "Missing build tools:$miss — run Settings → Linux → Install Android build tools."; exit 3; fi
cd "${1:-.}" || { echo "No such folder: $1"; exit 2; }
if ! { [ -x ./gradlew ] && [ -f gradle/wrapper/gradle-wrapper.jar ]; } && ! command -v gradle >/dev/null 2>&1; then
  echo "No Gradle: this project has no ./gradlew wrapper and system gradle is missing."; exit 3
fi
echo "sdk.dir=$ANDROID_HOME" > local.properties
if [ -x ./gradlew ] && [ -f gradle/wrapper/gradle-wrapper.jar ]; then GR=./gradlew; else GR=gradle; fi
$GR assembleDebug --console=plain --stacktrace 2>&1 | tee build.log | grep -E --line-buffered '^> Task|^e: |error:|BUILD|FAILURE|What went wrong|^w: ' | grep -v ' UP-TO-DATE$\| NO-SOURCE$\| SKIPPED$'
code=${PIPESTATUS[0]}
if [ "$code" = 0 ]; then
  APK=$(find . -path '*/build/outputs/apk/*' -name '*.apk' -newer local.properties | head -1)
  [ -n "$APK" ] || APK=$(find . -path '*/build/outputs/apk/*' -name '*.apk' | head -1)
  echo "ARIN_APK=$(readlink -f "$APK")"
else
  echo "---- errors ----"
  grep -E '^e: |error:|What went wrong|Could not|Execution failed|AAPT|^> ' -A3 build.log | head -80
fi
exit $code
B
chmod +x /usr/local/bin/arin-build

# 7. Report what landed, so a missing piece is obvious.
echo "Checks:"
java -version 2>&1 | head -1
gradle --version 2>/dev/null | grep -m1 Gradle
echo "Platforms: $(ls $ANDROID_HOME/platforms 2>/dev/null | tr '\n' ' ')"
echo "Build-tools: $(ls $ANDROID_HOME/build-tools 2>/dev/null | tr '\n' ' ')"
if [ "$ARCH" != "x86_64" ]; then
  "$A2" version >/dev/null 2>&1 && echo "aapt2: $("$A2" version 2>/dev/null | head -1)" || echo "aapt2: MISSING for $ARCH"
  [ -x "$AIDL" ] && echo "aidl: native $ARCH" || echo "aidl: not replaced (only needed for AIDL projects)"
fi
[ -f "$KS" ] && echo "debug keystore: ready"
if [ "$ARCH" = "x86_64" ] || "$A2" version >/dev/null 2>&1; then echo "ANDROID_BUILD_READY"; else echo "aapt2 for $ARCH is missing: apps will still build on GitHub."; fi
