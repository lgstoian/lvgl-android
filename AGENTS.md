# Development Guide

## Important: Known Issues & Quirks

**Before troubleshooting any build issues, read [ai-context.md](ai-context.md)** which documents all known quirks, oddities, and their resolutions discovered during development. Key issues include:

- Emulator binary: `D:\ASDK\emulator\emulator.exe` (NOT `D:\ASDK\platform-tools\arrom`)
- ADB unauthorized after key deletion: use `-wipe-data` flag
- CMake `share/cmake-X.Y` directory missing from GitHub releases
- NDK r26b + CMake 3.22.1 x86_64 incompatibility (only arm64-v8a)
- Emulator slow boot: wait 3+ minutes for `sys.boot_completed=1`
- LVGL 9.x header structure (`include/lvgl/lvgl.h`), color format (BGRA→RGBA swap), user data APIs
- Kotlin/JNI naming conflicts (`immersiveMode` not `isImmersive`)
- `LV_THEME_DEFAULT_LIGHT` not defined in LVGL 9.6.0 (use `0`)

If you encounter an issue, check `ai-context.md` first. If you discover a new issue not documented there, add it with the problem, symptom, root cause, and resolution.

## Project Overview

This project is an Android framework for building apps using LVGL (Light and Versatile Graphics Library) v9.6.0. The framework provides Kotlin-first APIs for creating activities, keyboards (IME), launchers, games, and camera apps with custom LVGL-based UI.

## Environment Setup

Required tools:

- **JDK 17**: `C:\Users\lgstoian\jdk-17\jdk-17.0.12`
- **Android SDK**: `D:\ASDK`
- **Android NDK**: `D:\ASDK\ndk\android-ndk-r26b` (r26b — must match for CMake 3.22.1 compatibility)
- **CMake**: `D:\ASDK\cmake\3.22.1` (must have `share/cmake-3.22` directory)

Set environment before building:

```powershell
$env:JAVA_HOME = "C:\Users\lgstoian\jdk-17\jdk-17.0.12"
$env:ANDROID_HOME = "D:\ASDK"
```

## Build & Test Cycle

```powershell
# Build
cd "D:\Code\android lvgl"
.\gradlew.bat assembleDebug --no-daemon

# Start emulator (wait 3+ minutes for boot)
$env:ANDROID_HOME = "D:\ASDK"
$env:ANDROID_AVD_HOME = "C:\Users\lgstoian\.android\avd"
Start-Process -FilePath "D:\ASDK\emulator\emulator.exe" -ArgumentList "-avd", "test_avd", "-no-window", "-no-audio", "-gpu", "swiftshader_indirect", "-wipe-data" -WindowStyle Hidden

# Wait for boot, then install & launch
& "D:\ASDK\platform-tools\adb.exe" devices
& "D:\ASDK\platform-tools\adb.exe" shell "getprop sys.boot_completed"
& "D:\ASDK\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
& "D:\ASDK\platform-tools\adb.exe" shell am start -n com.lvgl.sample/.MainActivity

# Capture screenshot
& "D:\ASDK\platform-tools\adb.exe" shell screencap -p /sdcard/lvgl.png
& "D:\ASDK\platform-tools\adb.exe" pull /sdcard/lvgl.png screenshots\test.png

# View LVGL logs
& "D:\ASDK\platform-tools\adb.exe" logcat -d -s "LVGLPort"
```

## Code Conventions

- **C/C++**: Follow LVGL naming conventions. Prefix static globals with `g_`.
- **Kotlin**: Standard Android Kotlin style. No comments unless non-obvious.
- **JNI**: Functions use `Java_com_lvgl_android_*` naming (matches `com.lvgl.android` package).
- **Commit messages**: Concise, imperative mood.
- **No duplication**: Single source of truth for each component.
- **No feature removals**: All existing features preserved and enhanced.

## Key Architecture Decisions

1. **Library + App modules**: `lvgl-android` is reusable library, `app` is sample
2. **Singleton Engine**: `LvglEngine.instance` manages display lifecycle
3. **DIRECT render mode**: Full-screen framebuffer (not partial) for swapchain compatibility
4. **Dedicated LVGL thread**: Runs `lv_timer_handler()` every 5ms with condition variable
5. **R/B pixel swap**: BGRA to RGBA during framebuffer copy in flush callback
6. **Core-only compilation**: CMakeLists.txt compiles only core LVGL modules, no drivers/demos
7. **Git submodule**: LVGL source is submodule, enabling easy version updates
8. **Material 3 theme**: Dynamic colors, dark/light mode from system configuration
9. **Multi-ABI**: arm64-v8a (x86_64 blocked by NDK r26b/x86_64 incompatibility)

## Hard Rules (enforced from user requirements)

- No duplication: Single source of truth for each component
- No feature removals: All existing features preserved and enhanced
- All app types: Activities, IME, launchers, games, camera
- Material theme: Full Material 3 implementation
- DPI adaptation: Dynamic scaling and orientation handling
- Multiwindow: Full multiwindow + foldable + PiP support
- LVGL version updates via Git submodule

## Adding New LVGL Features

1. Enable in `lvgl-android/src/main/cpp/lv_conf.h`
2. No CMakeList changes needed (glob patterns auto-include)
3. Rebuild: `.\gradlew.bat assembleDebug --no-daemon`

## Troubleshooting

| Issue | Solution |
|-------|----------|
| CMake C compiler test fails | Check NDK version matches CMake compatibility |
| `Could not find CMAKE_ROOT` | CMake `share/cmake-X.Y` directory missing - copy from 3.22.1-2 |
| Black screen | Check `lv_display_set_buffers()` called before `lv_display_set_flush_cb()` |
| Touch not working | Verify `nativeTouchEvent` receives correct action codes (0=down, 1=up) |
| Build downloads fail | Use `curl.exe -L -o` instead of `Invoke-WebRequest` for large files |
| Emulator won't start | Use binary at `D:\ASDK\emulator\emulator.exe` not `platform-tools\arrom` |
| ADB unauthorized | Wipe emulator with `-wipe-data`, then restart ADB server |
| Slow emulator boot | Wait 3+ minutes, check `sys.boot_completed=1` |

## Roadmap

See [ROADMAP.md](ROADMAP.md) for planned improvements.

## Project Structure

```
android_lvgl/
├── lvgl/                      # Git submodule (LVGL v9.6.0)
├── lvgl-android/               # Framework library module
│   ├── src/main/java/com/lvgl/android/
│   │   ├── engine/            # LvglEngine (singleton)
│   │   ├── view/              # LvglSurfaceView
│   │   ├── activity/          # LvglActivity base
│   │   ├── config/            # DPI, display metrics, configuration
│   │   ├── theme/             # MaterialTheme, LvglThemeBridge
│   │   ├── window/            # MultiWindowManager
│   │   ├── input/             # LvglInputMethodService
│   │   ├── launcher/          # LvglLauncherActivity
│   │   ├── game/              # LvglGameActivity
│   │   ├── camera/            # LvglCameraPreview
│   │   └── util/              # LvglLog
│   └── src/main/cpp/          # Native code + CMake
├── app/                        # Sample application
├── screenshots/                # Screenshots for documentation
├── ai-context.md               # Known issues and quirks
├── AGENTS.md                   # This file
├── BUILDING.md                 # Quick start guide
├── ROADMAP.md                  # Development roadmap
└── README.md                   # Architecture overview
```