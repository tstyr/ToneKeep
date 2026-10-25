#!/usr/bin/env bash
set -uo pipefail
adb shell wm size 1080x1920
adb shell wm density 480
if bash ./gradlew :app:connectedDebugAndroidTest --no-daemon --max-workers=2; then
  device_result=0
else
  device_result=$?
fi
mkdir -p device-verification
adb logcat -d > device-verification/logcat.txt
# AGP uninstalls the app after instrumentation; shared Downloads survive that cleanup.
if ! adb pull /sdcard/Download/ToneKeep-verification device-verification/screenshots; then
  exit 1
fi
exit "$device_result"
