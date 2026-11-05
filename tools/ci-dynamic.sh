#!/usr/bin/env sh
# Dynamic CI checks for the LVGL Android Framework: the instrumented tests plus
# the screen walk in tools/dynamic-test.ps1.
#
# The CI emulator has no KVM (software emulation only) and sometimes comes up
# offline: the emulator cannot reach the host adb daemon and the device stays
# "offline" for the rest of the job. Retry the whole sequence when the device is
# unhealthy; a failure on a healthy device is a real failure and is never
# retried.
#
# android-emulator-runner executes every line of its `script` input as a
# separate `sh -c`, so this logic must live in a file rather than inline.
#
# Environment:
#   ANDROID_SDK_ROOT / ANDROID_HOME  SDK location (required)
#   ANDROID_AVD_NAME                AVD to reboot on retry (default: test)
#   DYNAMIC_ATTEMPTS                retries on an unhealthy device (default: 3)

set -u

chmod +x gradlew

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK_ROOT" ]; then
  echo "=== ANDROID_SDK_ROOT or ANDROID_HOME must be set"
  exit 1
fi
EMULATOR="$SDK_ROOT/emulator/emulator"
AVD="${ANDROID_AVD_NAME:-test}"
ATTEMPTS="${DYNAMIC_ATTEMPTS:-3}"

wait_for_boot() {
  i=0
  while [ "$i" -lt 120 ]; do
    if adb shell getprop sys.boot_completed 2>/dev/null | grep -q 1; then
      return 0
    fi
    i=$((i + 1))
    sleep 5
  done
  return 1
}

restart_emulator() {
  echo "=== restarting the emulator"
  adb emu kill >/dev/null 2>&1 || true
  sleep 5
  # adb emu kill needs a working adb channel; fall back to signalling the
  # emulator process so port 5554 is released for the next boot.
  pkill -f "emulator.*-avd $AVD" >/dev/null 2>&1 || true
  sleep 10
  nohup "$EMULATOR" -avd "$AVD" -no-window -gpu swiftshader_indirect \
    -no-snapshot -noaudio -no-boot-anim >/dev/null 2>&1 &
  adb wait-for-device || true
  wait_for_boot || true
}

attempt=1
while [ "$attempt" -le "$ATTEMPTS" ]; do
  echo "=== dynamic attempt $attempt/$ATTEMPTS ==="
  adb logcat -c || true
  if ./gradlew :lvgl-android:connectedDebugAndroidTest --no-daemon &&
     pwsh ./tools/dynamic-test.ps1; then
    echo "=== dynamic checks passed on attempt $attempt"
    exit 0
  fi

  echo "=== attempt $attempt failed"
  state="$(adb devices | awk '/emulator-/ {print $2; exit}')"
  if [ -n "$state" ] && [ "$state" = "device" ]; then
    echo "=== device is healthy; this is a real failure, not an emulator flake"
    exit 1
  fi

  echo "=== device state: '${state:-missing}'; recovering"
  restart_emulator
  attempt=$((attempt + 1))
done

echo "=== dynamic checks failed after $ATTEMPTS attempts"
exit 1
