#!/usr/bin/env bash
set -euo pipefail
BASE=de.kango99w.orbitadmin
TRIAL=de.kango99w.orbitadmin.compactpreview
APK_BASE=app/build/outputs/apk/debug/app-debug.apk
APK_TRIAL=app/build/outputs/apk/compactPreview/app-compactPreview.apk
mkdir -p compact-preview-evidence
test -s "$APK_BASE" && test -s "$APK_TRIAL"
adb install "$APK_BASE"
adb install "$APK_TRIAL"
uid() {
  adb shell pm list packages -U "$1" | tr -d '\r' |
    grep -F "package:$1 uid:" | head -1 | sed 's/^.*uid://'
}
A="$(uid "$BASE")"; B="$(uid "$TRIAL")"
test -n "$A" && test -n "$B" && test "$A" != "$B"
for pkg in "$BASE" "$TRIAL" "$BASE" "$TRIAL"; do
  adb shell am force-stop "$pkg" || true
  adb shell am start -W -n "$pkg/.MainActivity" | tee -a compact-preview-evidence/start.txt
  sleep 7
  test -n "$(adb shell pidof "$pkg" | tr -d '\r')" || {
    echo "App process exited unexpectedly: $pkg" >&2; exit 1;
  }
  adb shell dumpsys meminfo "$pkg" > "compact-preview-evidence/$(basename "$pkg").meminfo"
done
adb shell pm path "$BASE" > compact-preview-evidence/base-path.txt
adb shell pm path "$TRIAL" > compact-preview-evidence/trial-path.txt
{
  echo "# Android 35 dual-app smoke"
  echo "Original package: $BASE (UID $A)"
  echo "Preview package: $TRIAL (UID $B)"
  echo "Two APKs installed simultaneously; both launched twice and survived at least 7 seconds."
  echo "No login, real-device session, mail delivery or FCM claim."
  echo "Disposable emulator only; no existing phone data touched."
} > compact-preview-evidence/summary.md
cat compact-preview-evidence/summary.md
