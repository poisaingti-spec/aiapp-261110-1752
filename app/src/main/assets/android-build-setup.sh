set -e
export DEBIAN_FRONTEND=noninteractive
# 1. JDK 21 and Gradle
if ! command -v javac >/dev/null 2>&1 || ! command -v unzip >/dev/null 2>&1; then
  apt-get update
  apt-get install -y --no-install-recommends openjdk-21-jdk-headless unzip zip
fi
G=@@GRADLE@@
if [ ! -x /opt/gradle-$G/bin/gradle ]; then
  echo "Downloading Gradle $G…"
  curl -fsSL -o /tmp/gradle.zip https://services.gradle.org/distributions/gradle-$G-bin.zip
  mkdir -p /opt && unzip -q -o /tmp/gradle.zip -d /opt && rm /tmp/gradle.zip
fi
ln -sf /opt/gradle-$G/bin/gradle /usr/local/bin/gradle
# 2. Android SDK command-line tools, platform and licences
export ANDROID_HOME=/opt/android-sdk
mkdir -p $ANDROID_HOME
if [ ! -x $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager ]; then
  echo "Downloading the Android SDK command-line tools…"
  Z=$(curl -fsSL https://dl.google.com/android/repository/repository2-3.xml | grep -o 'commandlinetools-linux-[0-9]*_latest.zip' | sort -V | tail -1)
  [ -n "$Z" ] || Z=commandlinetools-linux-13114758_latest.zip
  curl -fsSL -o /tmp/clt.zip "https://dl.google.com/android/repository/$Z"
  rm -rf /tmp/clt && unzip -q /tmp/clt.zip -d /tmp/clt && rm /tmp/clt.zip
  mkdir -p $ANDROID_HOME/cmdline-tools && rm -rf $ANDROID_HOME/cmdline-tools/latest
  mv /tmp/clt/cmdline-tools $ANDROID_HOME/cmdline-tools/latest
fi
SDKM=$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager
yes | $SDKM --sdk_root=$ANDROID_HOME --licenses >/dev/null 2>&1 || true
echo "Installing Android platform @@SDK@@ (Gradle downloads anything else it needs)…"
$SDKM --sdk_root=$ANDROID_HOME "platforms;android-@@SDK@@" "platform-tools" 2>&1 | grep -v '^\[' | tail -3 || true
# 3. aapt2 that runs on this phone's CPU (Google only ships it for x86-64 Linux)
mkdir -p $ANDROID_HOME/arin
A2=$ANDROID_HOME/arin/aapt2
ARCH=$(uname -m)
if [ "$ARCH" != "x86_64" ] && ! "$A2" version >/dev/null 2>&1; then
  echo "Getting aapt2 for $ARCH…"
  for REPO in lzhiyong/android-sdk-tools hamza72x/android-sdk-linux-arm64; do
    URL=$(curl -fsSL "https://api.github.com/repos/$REPO/releases" | grep -o 'https://[^"]*\.\(zip\|tar\.gz\|tar\.xz\)' | grep -i "$ARCH" | grep -iv 'windows\|darwin\|macos' | head -1 || true)
    [ -n "$URL" ] || continue
    rm -rf /tmp/a2 && mkdir -p /tmp/a2
    curl -fsSL -o /tmp/a2/pkg "$URL" || continue
    case "$URL" in *.zip) unzip -q -o /tmp/a2/pkg -d /tmp/a2 ;; *) tar -xf /tmp/a2/pkg -C /tmp/a2 ;; esac
    F=$(find /tmp/a2 -type f -name aapt2 | head -1)
    if [ -n "$F" ]; then cp "$F" "$A2"; chmod +x "$A2"; "$A2" version >/dev/null 2>&1 && { echo "aapt2 from $REPO"; break; }; fi
  done
  if ! "$A2" version >/dev/null 2>&1; then
    echo "Trying Ubuntu's aapt2…"
    apt-get install -y --no-install-recommends aapt >/dev/null 2>&1 || true
    if command -v aapt2 >/dev/null 2>&1; then ln -sf "$(command -v aapt2)" "$A2"; fi
  fi
fi
# 4. Settings for Gradle on a phone: less memory, no background daemons
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
# 5. arin-build: compiles a project folder into a debug APK
cat > /usr/local/bin/arin-build <<'B'
#!/bin/bash
# Usage: arin-build /path/to/project  — builds a debug APK and prints ARIN_APK=<path> when it works.
set -o pipefail
. /etc/profile.d/arin-android.sh
export UV_USE_IO_URING=0 GLIBC_TUNABLES=glibc.pthread.rseq=0
cd "${1:-.}" || { echo "No such folder: $1"; exit 2; }
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
java -version 2>&1 | head -1
gradle --version 2>/dev/null | grep -m1 Gradle
ls $ANDROID_HOME/platforms 2>/dev/null | tail -1
if [ "$ARCH" = "x86_64" ] || "$A2" version >/dev/null 2>&1; then echo "ANDROID_BUILD_READY"; else echo "aapt2 for $ARCH is missing: apps will still build on GitHub."; fi
