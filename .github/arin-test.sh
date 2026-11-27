#!/usr/bin/env bash
# Arin's app check: installs the APK on the emulator, opens it, taps around, and writes what happened.
APK="$1"
R=arin-test-report.txt
SHOT=arin-test-screen.png
AAPT=$(ls "$ANDROID_HOME"/build-tools/*/aapt 2>/dev/null | sort -V | tail -1)
PKG=$("$AAPT" dump badging "$APK" 2>/dev/null | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
report() {
  { echo "RESULT: $1"; [ -n "$2" ] && echo "ERROR: $2"; echo "PACKAGE: $PKG"
    for f in tests.txt device.txt tests/unit-test-report.txt tests/lint-report.txt; do [ -f "$f" ] && cat "$f"; done
    [ -f crash.txt ] && cat crash.txt; [ -f instr.txt ] && { echo "--- on-device test output ---"; tail -80 instr.txt; }; } > "$R"
  cat "$R"
  exit 0
}
[ -z "$PKG" ] && report NO_TEST "couldn't read the app's package name"
adb wait-for-device
adb logcat -c
if ! adb install -r -g "$APK" > install.log 2>&1; then
  tail -20 install.log > crash.txt
  report INSTALL_FAILED "$(grep -m1 -E 'Failure|INSTALL_' install.log)"
fi
check() {
  adb logcat -d > logcat.txt 2>/dev/null
  if grep -A2 "FATAL EXCEPTION" logcat.txt | grep -q "Process: $PKG"; then
    grep -A60 "FATAL EXCEPTION" logcat.txt | grep "AndroidRuntime" | sed 's/^.*AndroidRuntime: //' | head -60 > crash.txt
    report CRASH "$(grep -v -E 'FATAL EXCEPTION|Process:' crash.txt | head -1)"
  fi
  if grep -q "ANR in $PKG" logcat.txt; then
    grep -A12 "ANR in $PKG" logcat.txt | head -20 > crash.txt
    report CRASH "The app stopped responding (ANR)"
  fi
}
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
sleep 15
adb exec-out screencap -p > "$SHOT" 2>/dev/null
check
if [ -z "$(adb shell pidof "$PKG" | tr -d '\r')" ]; then
  adb logcat -d | grep -i "$PKG" | tail -40 > crash.txt
  report NOT_RUNNING "The app closed by itself right after it opened"
fi
adb shell monkey -p "$PKG" --pct-syskeys 0 --pct-appswitch 0 --throttle 250 -v 150 > monkey.log 2>&1
sleep 3
check
# ARIN-PROTECTED: tests-gate — the on-device tests (conversation isolation, delegation firewall, Security screen) must pass too.
TESTAPK="$2"
if [ -n "$TESTAPK" ] && [ -f "$TESTAPK" ]; then
  if ! adb install -r -t "$TESTAPK" > instr-install.log 2>&1; then
    tail -5 instr-install.log > crash.txt
    report TESTS_NOT_RUN "the test APK couldn't be installed"
  fi
  adb shell am instrument -w "$PKG.test/androidx.test.runner.AndroidJUnitRunner" > instr.txt 2>&1
  # What the on-device checks found (real Linux terminal, the running app's model instructions).
  { adb shell run-as "$PKG" cat files/terminal-selftest.txt 2>/dev/null; echo; adb shell run-as "$PKG" cat files/rule-integrity.txt 2>/dev/null; echo; } > device.txt
  if grep -q "^OK (" instr.txt; then
    echo "ON-DEVICE TESTS: $(grep '^OK (' instr.txt)" > tests.txt
  else
    echo "ON-DEVICE TESTS: $(grep -E '^Tests run:' instr.txt || echo 'did not finish')" > tests.txt
    report TESTS_FAILED "$(grep -E '^Tests run:' instr.txt || echo 'on-device tests failed')"
  fi
elif [ -n "$TESTAPK" ]; then
  echo "ON-DEVICE TESTS: test APK not available" > tests.txt
  report TESTS_NOT_RUN "the test APK wasn't built"
else
  echo "ON-DEVICE TESTS: not part of this run (install, open and tap test only)" > tests.txt
fi
check
report PASS ""
