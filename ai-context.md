# AI Context — Notable Findings & Quirks

## Emulator

### Emulator Won't Start

**Problem**: After killing the emulator process, it may not restart. `adb devices` shows no devices/emulators even after `adb start-server`.

**Root Cause**: The `emulator.exe` binary is at `D:\ASDK\emulator\emulator.exe`, NOT `D:\ASDK\platform-tools\arrom`. The path `D:\ASDK\arrom\arrom.exe` does not exist.

**Solution**: Start the emulator binary directly:
```powershell
$env:ANDROID_HOME = "D:\ASDK"
$env:ANDROID_AVD_HOME = "C:\Users\lgstoian\.android\avd"
Start-Process -FilePath "D:\ASDK\emulator\emulator.exe" -ArgumentList "-avd", "test_avd", "-no-window", "-no-audio", "-gpu", "swiftshader_indirect", "-wipe-data" -WindowStyle Hidden
```

### ADB Unauthorized

**Problem**: After deleting ADB keys, the emulator shows as `unauthorized`.

**Root Cause**: The emulator's userdata partition stores the host's public key. Deleting host keys breaks authentication.

**Solution**: Wipe emulator data with `-wipe-data` flag to clear stored keys, then restart ADB server so new keys are accepted.

### ADB Output Parsing

**Problem**: `adb shell getprop sys.boot_completed` sometimes outputs nothing to stdout.

**Solution**: Use `adb shell uname -a` and `adb devices` to verify the device is responsive instead.

## Build Environment

### CMake `share/cmake-X.Y` Missing from GitHub Releases

**Problem**: GitHub release zips for CMake don't include `share/cmake-X.Y` directory.

**Symptom**: `CMake Error: Could not find CMAKE_ROOT !!!`

**Solution**: Copy `share` from a working CMake install (e.g., auto-downloaded `3.22.1-2`):
```powershell
Copy-Item "D:\ASDK\cmake\3.22.1-2\share" "D:\ASDK\cmake\3.22.1\" -Recurse -Force
```

### NDK r26b + x86_64 Build Failure

**Problem**: NDK r26b with Clang 17 cannot link x86_64 binaries with CMake 3.22.1.

**Symptom**: `ld.lld: error: unable to find library -latomic`

**Solution**: Build only for arm64-v8a ABI. x86_64 requires CMake 3.25+.

## LVGL Integration

### LVGL 9.x Header Structure

**Problem**: LVGL 9.x moved headers from `src/` to `include/lvgl/`. Old `#include "lvgl.h"` no longer works.

**Solution**: Use `#include "lvgl/lvgl.h"` with include path pointing to `lvgl/include/`.

### LVGL Color Format

**Fact**: LVGL stores `lv_color32_t` as `{blue, green, red, alpha}` (BGRA). Android `RGBA_8888` expects `{red, green, blue, alpha}`. R/B swap required during framebuffer copy.

### LVGL User Data APIs

**Fact**: `lv_display_set_user_data()`, `lv_display_get_user_data()`, `lv_indev_set_user_data()`, `lv_indev_get_user_data()` available in LVGL 9.6.0.

### LV_THEME_DEFAULT_LIGHT Not Defined

**Problem**: LVGL 9.6.0 doesn't define `LV_THEME_DEFAULT_LIGHT`.

**Solution**: Use `0` for light mode, `LV_THEME_DEFAULT_DARK` for dark mode.

## Kotlin/JNI

### JNI Function Naming

**Rule**: JNI functions must follow `Java_<package>_<Class>_<method>` with underscores replacing dots. Package `com.lvgl.android.engine` with class `LvglEngine` becomes `Java_com_lvgl_android_engine_LvglEngine_*`.

### Kotlin Property vs JNI Method Conflict

**Problem**: Kotlin `var isImmersive` generates getter `isImmersive()` conflicting with `InputMethodService.isImmersive()`.

**Solution**: Use a different property name like `immersiveMode`.

## Git Submodule

### LVGL Version Update

```powershell
cd lvgl
git fetch --tags
git checkout vX.Y.Z
cd .. && git add lvgl && git commit -m "Update LVGL to vX.Y.Z"
```

## Emulator Commands Quick Reference

### Start Emulator
```powershell
$env:ANDROID_HOME = "D:\ASDK"
$env:ANDROID_AVD_HOME = "C:\Users\lgstoian\.android\avd"
Start-Process -FilePath "D:\ASDK\emulator\emulator.exe" -ArgumentList "-avd", "test_avd", "-no-window", "-no-audio", "-gpu", "swiftshader_indirect", "-wipe-data" -WindowStyle Hidden
```

### Wait for Boot
```powershell
$env:ANDROID_HOME = "D:\ASDK"
& "D:\ASDK\platform-tools\adb.exe" devices
# Wait until "1" is returned for sys.boot_completed
& "D:\ASDK\platform-tools\adb.exe" shell "getprop sys.boot_completed"
```

### Install & Run App
```powershell
$env:ANDROID_HOME = "D:\ASDK"
& "D:\ASDK\platform-tools\adb.exe" install -r "D:\Code\android lvgl\app\build\outputs\apk\debug\app-debug.apk"
& "D:\ASDK\platform-tools\adb.exe" shell am start -n com.lvgl.sample/.MainActivity
```

### Capture Screenshot
```powershell
$env:ANDROID_HOME = "D:\ASDK"
& "D:\ASDK\platform-tools\adb.exe" shell screencap -p /sdcard/screenshot.png
& "D:\ASDK\platform-tools\adb.exe" pull /sdcard/screenshot.png "D:\Code\android lvgl\screenshots\screenshot.png"
```
