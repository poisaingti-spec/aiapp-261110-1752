#!/bin/bash
# Arin's APK toolkit: every tool to decompile, compile, change, rebuild, sign and package Android apps, always the
# NEWEST release (looked up on each run; a tool is only downloaded again when a newer version is out).
# Researched November 2026. The Android SDK / Gradle / native aapt2 part is android-build-setup.sh (run before this).
#   Gradle (latest, services.gradle.org)       Kotlin compiler (latest JetBrains release)
#   apktool 3.x (decode/build, aapt2)          APKEditor (pure-Java decode/rebuild/merge splits)
#   jadx CLI (dex → Java source)               jadx-headless-mcp (REA)
#   dex2jar (d2j-*: dex2jar, smali, baksmali)  bundletool (.aab → .apks / universal APK)
#   uber-apk-signer (zipalign + sign)          apksigner / d8 / r8 from Android build-tools
#   androguard (Python APK analysis)           file, xxd, jq, sqlite3, binutils
set -o pipefail
export DEBIAN_FRONTEND=noninteractive
fetch(){ if command -v arin-fetch >/dev/null 2>&1; then arin-fetch "$@"; else curl -fL --retry 5 -sS -o "$2" "$1"; fi; }
[ -f /root/.arin/github.env ] && . /root/.arin/github.env
T=/opt/arin-tools; mkdir -p $T /opt/rea-android /usr/local/bin
V=$T/VERSIONS; touch $V
ok=(); bad=()
api(){ curl -fsSL --retry 3 -H "Accept: application/vnd.github+json" ${GH_TOKEN:+-H "Authorization: Bearer $GH_TOKEN"} "https://api.github.com/repos/$1/releases/latest"; }
have(){ grep -qx "$1=$2" $V; }
mark(){ sed -i "/^$1=/d" $V; echo "$1=$2" >> $V; }
# latest <name> <owner/repo> <asset regex> <fallback url>  → sets URL and TAG (fallback when GitHub can't be asked)
latest(){
  local j; j=$(api "$2" 2>/dev/null || true)
  TAG=$(echo "$j" | grep -o '"tag_name": *"[^"]*"' | head -1 | cut -d'"' -f4)
  URL=$(echo "$j" | grep -o '"browser_download_url": *"[^"]*"' | cut -d'"' -f4 | grep -E "$3" | head -1)
  if [ -z "$URL" ]; then URL="$4"; TAG="fallback:${4##*/}"; fi
}
step(){ echo; echo "== $1"; }

step "Small helper tools"
apt-get install -y --no-install-recommends file xxd jq sqlite3 binutils python3-pip >/dev/null 2>&1 \
  || { apt-get update >/dev/null 2>&1; apt-get install -y --no-install-recommends file xxd jq sqlite3 binutils python3-pip >/dev/null 2>&1; } \
  && ok+=("helpers") || bad+=("helpers")

step "Gradle (latest)"
GJ=$(curl -fsSL https://services.gradle.org/versions/current 2>/dev/null || true)
GV=$(echo "$GJ" | jq -r .version 2>/dev/null); GU=$(echo "$GJ" | jq -r .downloadUrl 2>/dev/null)
[ -n "$GV" ] && [ "$GV" != null ] || { GV=9.9.2; GU=https://services.gradle.org/distributions/gradle-9.9.2-bin.zip; }
if [ ! -x /opt/gradle-$GV/bin/gradle ]; then
  fetch "$GU" /tmp/gradle.zip "Gradle $GV" && unzip -q -o /tmp/gradle.zip -d /opt && rm -f /tmp/gradle.zip
fi
[ -x /opt/gradle-$GV/bin/gradle ] && ln -sf /opt/gradle-$GV/bin/gradle /usr/local/bin/gradle && ok+=("Gradle $GV") || bad+=("Gradle")

step "Kotlin compiler (latest)"
latest kotlinc JetBrains/kotlin 'kotlin-compiler-[0-9.]+\.zip$' https://github.com/JetBrains/kotlin/releases/download/v2.4.20/kotlin-compiler-2.4.20.zip
if ! have kotlinc "$TAG" || [ ! -x /opt/kotlinc/bin/kotlinc ]; then
  fetch "$URL" /tmp/kc.zip "Kotlin $TAG" && rm -rf /opt/kotlinc && unzip -q /tmp/kc.zip -d /opt && rm -f /tmp/kc.zip && mark kotlinc "$TAG"
fi
for b in kotlinc kotlin kotlinr; do [ -x /opt/kotlinc/bin/$b ] && ln -sf /opt/kotlinc/bin/$b /usr/local/bin/$b; done
[ -x /opt/kotlinc/bin/kotlinc ] && ok+=("Kotlin $TAG") || bad+=("Kotlin")

jarTool(){ # jarTool <name> <owner/repo> <regex> <fallback> <dest.jar> <command>
  latest "$1" "$2" "$3" "$4"
  if ! have "$1" "$TAG" || [ ! -s "$5" ]; then fetch "$URL" "$5.new" "$1 $TAG" && mv "$5.new" "$5" && mark "$1" "$TAG"; fi
  printf '#!/bin/sh\nexec java -jar %s "$@"\n' "$5" > /usr/local/bin/$6; chmod +x /usr/local/bin/$6
  [ -s "$5" ] && ok+=("$1 $TAG") || bad+=("$1")
}
step "apktool (latest)"
jarTool apktool iBotPeaches/Apktool 'apktool_[0-9.]+\.jar$' https://github.com/iBotPeaches/Apktool/releases/download/v3.0.3/apktool_3.0.3.jar /opt/rea-android/apktool.jar apktool
step "APKEditor (latest)"
jarTool APKEditor REAndroid/APKEditor 'APKEditor-[0-9.]+\.jar$' https://github.com/REAndroid/APKEditor/releases/latest/download/APKEditor.jar /opt/rea-android/APKEditor.jar apkeditor
step "uber-apk-signer (latest)"
jarTool uber-apk-signer patrickfav/uber-apk-signer 'uber-apk-signer-[0-9.]+\.jar$' https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar /opt/rea-android/uber-apk-signer.jar uber-apk-signer
step "bundletool (latest)"
jarTool bundletool google/bundletool 'bundletool-all-[0-9.]+\.jar$' https://github.com/google/bundletool/releases/download/1.18.1/bundletool-all-1.18.1.jar $T/bundletool.jar bundletool

zipTool(){ # zipTool <name> <owner/repo> <regex> <fallback> <dir>
  latest "$1" "$2" "$3" "$4"
  if ! have "$1" "$TAG" || [ ! -d "$5" ]; then
    fetch "$URL" /tmp/$1.zip "$1 $TAG" && rm -rf "$5" /tmp/$1.x && mkdir -p /tmp/$1.x && unzip -q /tmp/$1.zip -d /tmp/$1.x && rm -f /tmp/$1.zip \
      && { d=$(find /tmp/$1.x -mindepth 1 -maxdepth 1); [ "$(echo "$d" | wc -l)" = 1 ] && [ -d "$d" ] && mv "$d" "$5" || mv /tmp/$1.x "$5"; } && mark "$1" "$TAG"
  fi
}
step "jadx CLI (latest)"
zipTool jadx skylot/jadx 'jadx-[0-9.]+\.zip$' https://github.com/skylot/jadx/releases/download/v1.5.6/jadx-1.5.6.zip /opt/jadx
[ -f /opt/jadx/bin/jadx ] && chmod +x /opt/jadx/bin/* && ln -sf /opt/jadx/bin/jadx /usr/local/bin/jadx && ok+=("jadx $TAG") || bad+=("jadx")
step "dex2jar (latest)"
zipTool dex2jar ThexXTURBOXx/dex2jar 'dex-tools.*\.zip$' https://github.com/ThexXTURBOXx/dex2jar/releases/latest/download/dex-tools.zip /opt/dex2jar
if ls /opt/dex2jar/d2j-*.sh >/dev/null 2>&1; then
  chmod +x /opt/dex2jar/*.sh
  for s in /opt/dex2jar/d2j-*.sh; do n=$(basename "$s" .sh); ln -sf "$s" /usr/local/bin/$n; done
  ok+=("dex2jar $TAG")
else bad+=("dex2jar"); fi

step "androguard (Python APK analysis)"
pip3 install -q --break-system-packages --upgrade androguard >/dev/null 2>&1 && ok+=("androguard") || bad+=("androguard")

step "Android build-tools commands on PATH (apksigner, d8, r8)"
BT=$(ls -d /opt/android-sdk/build-tools/*/ 2>/dev/null | sort -V | tail -1)
if [ -n "$BT" ]; then
  for c in apksigner d8 r8 dexdump; do [ -e "$BT$c" ] && ln -sf "$BT$c" /usr/local/bin/$c; done
  [ -x /opt/android-sdk/arin/aapt2 ] && ln -sf /opt/android-sdk/arin/aapt2 /usr/local/bin/aapt2
  [ -x /opt/android-sdk/arin/zipalign ] && ln -sf /opt/android-sdk/arin/zipalign /usr/local/bin/zipalign
  ok+=("build-tools $(basename "$BT")")
else bad+=("Android build-tools (run Install Android build tools first)"); fi

# arin-tools: one command that lists every tool with its version, or updates them all.
cat > /usr/local/bin/arin-tools <<'A'
#!/bin/bash
if [ "$1" = update ]; then exec bash /root/.arin/setup-apk-toolkit.sh; fi
. /etc/profile.d/arin-android.sh 2>/dev/null
v(){ printf '%-16s %s\n' "$1" "$( { eval "$2"; } 2>&1 | grep -m1 -oE '[0-9]+(\.[0-9]+)+[^ ]*' || echo MISSING)"; }
v java 'java -version'; v gradle 'gradle --version'; v kotlinc 'kotlinc -version'
v apktool 'apktool --version'; v apkeditor 'grep ^APKEditor= /opt/arin-tools/VERSIONS'; v jadx 'jadx --version'
v dex2jar 'grep ^dex2jar= /opt/arin-tools/VERSIONS'; v bundletool 'bundletool version'
v uber-apk-signer 'grep ^uber-apk-signer= /opt/arin-tools/VERSIONS'; v apksigner 'apksigner --version'
v aapt2 'aapt2 version'; v androguard 'pip3 show androguard'
printf '%-16s %s\n' zipalign "$(command -v zipalign >/dev/null && echo present || echo MISSING)"
echo "SDK platforms: $(ls /opt/android-sdk/platforms 2>/dev/null | tr '\n' ' ')"
echo "Build-tools:   $(ls /opt/android-sdk/build-tools 2>/dev/null | tr '\n' ' ')"
echo "Build an app: arin-build <project folder>   ·   Rebuild+sign an APK: bash /root/.arin/rebuild-sign.sh <dir|apk>"
A
chmod +x /usr/local/bin/arin-tools

echo
echo "Installed / up to date: ${ok[*]}"
[ ${#bad[@]} -gt 0 ] && echo "Not installed: ${bad[*]}"
echo APK_TOOLKIT_READY
