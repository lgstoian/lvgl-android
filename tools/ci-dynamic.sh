#!/usr/bin/env sh
# Dynamic CI checks for the LVGL Android Framework: the instrumented tests, the
# screen walk in tools/dynamic-test.ps1, and a release-APK smoke over the same
# harness (R8/JNI survival in the published artifact, not just in debug).
#
# The CI emulator has no KVM (software emulation only) and sometimes comes up
# unhealthy: adb reports "device offline", or it reports "device" before the
# system server/package service is ready ("Can't find service: package"). Retry
# the whole sequence for either condition; a failure on a genuinely healthy
# device is a real failure and is never retried.
#
# android-emulator-runner executes every line of its `script` input as a
# separate `sh -c`, so this logic must live in a file rather than inline.
#
# Environment:
#   ANDROID_SDK_ROOT / ANDROID_HOME  SDK location (required)
#   ANDROID_AVD_NAME                AVD to reboot on retry (default: test)
#   DYNAMIC_ATTEMPTS                retries on an unhealthy device (default: 3)
#   RELEASE_APK                     universal release APK (default:
#                                   release-apk/app-universal-release.apk)
#   SMOKE_ONLY                      1 = skip the debug walk, release smoke only
#                                   (used by the release workflow)

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
SMOKE_ONLY="${SMOKE_ONLY:-0}"
RELEASE_APK="${RELEASE_APK:-release-apk/app-universal-release.apk}"
case "$RELEASE_APK" in
  /*) ;;
  *) RELEASE_APK="$PWD/$RELEASE_APK" ;;
esac

# Fail closed: a missing release artifact is not a reason to skip the gate.
if [ ! -f "$RELEASE_APK" ]; then
  echo "=== Release APK not found: $RELEASE_APK (release smoke is a required gate)"
  exit 1
fi
echo "=== Release APK under test (variant release): $RELEASE_APK"

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

# adb can report "device" while Android's package service is still unavailable
# (a software-emulated API 36 image can take a long time after boot_completed).
# Such a device is NOT healthy for instrumented tests.
device_ready() {
  state="$(adb devices | awk '/emulator-/ {print $2; exit}')"
  [ "$state" = "device" ] || return 1
  adb shell getprop sys.boot_completed 2>/dev/null | grep -q 1 || return 1
  probe="$(adb shell cmd package list packages -3 2>&1)"
  echo "$probe" | grep -q "Can't find service" && return 1
  return 0
}

# Markers that only appear when the emulator/adb channel itself is broken, not
# when the app under test failed.
unhealthy_log() {
  grep -Eq "device offline|Can't find service: package|no devices/emulators found|Failed to start Emulator console|Unable to connect to adb daemon" "$1"
}

run_checks() {
  if [ "$SMOKE_ONLY" = "1" ]; then
    pwsh ./tools/dynamic-test.ps1 \
      -ApkPath "$RELEASE_APK" -Variant release -SmokeOnly \
      -ScreenshotDir screenshots/dynamic-release
    return $?
  fi

  ./gradlew :lvgl-android:connectedDebugAndroidTest --no-daemon &&
    pwsh ./tools/dynamic-test.ps1 &&
    pwsh ./tools/dynamic-test.ps1 \
      -ApkPath "$RELEASE_APK" -Variant release -SmokeOnly \
      -ScreenshotDir screenshots/dynamic-release
}

attempt=1
while [ "$attempt" -le "$ATTEMPTS" ]; do
  echo "=== dynamic attempt $attempt/$ATTEMPTS (SMOKE_ONLY=$SMOKE_ONLY) ==="
  adb logcat -c || true
  attempt_log="screenshots/dynamic/attempt-$attempt.log"
  mkdir -p screenshots/dynamic
  (run_checks) > "$attempt_log" 2>&1
  status=$?
  cat "$attempt_log"
  if [ "$status" -eq 0 ]; then
    echo "=== dynamic checks passed on attempt $attempt"
    exit 0
  fi

  echo "=== attempt $attempt failed"
  if unhealthy_log "$attempt_log"; then
    echo "=== emulator/adb channel is unhealthy; recovering"
  elif device_ready; then
    echo "=== device is healthy; this is a real failure, not an emulator flake"
    exit 1
  else
    echo "=== device is not ready for instrumented tests; recovering"
  fi

  restart_emulator
  attempt=$((attempt + 1))
done

echo "=== dynamic checks failed after $ATTEMPTS attempts"
exit 1
