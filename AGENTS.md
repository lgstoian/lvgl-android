# Development Guide

## Project Overview

This project ports LVGL v9.6.0 to Android. The native code renders LVGL graphics to an Android `SurfaceView` via JNI.

## Environment Setup

Required tools (all already installed):

- **JDK 17**: `C:\Users\lgstoian\jdk-17\jdk-17.0.12`
- **Android SDK**: `D:\ASDK`
- **Android NDK**: `D:\ASDK\ndk\android-ndk-r26b` (r26b — must match for CMake 3.22.1 compatibility)
- **CMake**: `D:\ASDK\cmake\3.22.1`

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

# Start emulator
D:\ASDK\emulator\emulator.exe -avd test_avd -no-window -no-audio -gpu swiftshader_indirect

# Wait for boot, then install & launch
D:\ASDK\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
D:\ASDK\platform-tools\adb.exe shell am start -n com.lvgl.sample/.MainActivity

# Capture screenshot
D:\ASDK\platform-tools\adb.exe shell screencap -p /sdcard/lvgl.png
D:\ASDK\platform-tools\adb.exe pull /sdcard/lvgl.png screenshots\test.png

# View LVGL logs
D:\ASDK\platform-tools\adb.exe logcat -d -s "LVGLPort"
```

## Code Conventions

- **C/C++**: Follow LVGL naming conventions. Prefix static file-underscore globals with `g_`.
- **Kotlin**: Standard Android Kotlin style. No comments unless non-obvious.
- **JNI**: Functions use `Java_com_lvgl_sample_LvglView_native*` naming.
- **Commit messages**: Concise, imperative mood.

## Key Architecture Decisions

1. **Single native library**: All LVGL sources compiled into one shared library (`liblvgl_app.so`)
2. **DIRECT render mode**: Full-screen framebuffer (not partial) to avoid ANativeWindow swapchain issues
3. **Dedicated LVGL thread**: Runs `lv_timer_handler()` every 5ms with condition variable wake
4. **R/B pixel swap**: LVGL stores `lv_color32_t` as BGRA in memory; Android RGBA_8888 expects RGBA. Swap happens in flush callback.
5. **No drivers, no demos**: CMakeLists.txt compiles only core LVGL modules (core, draw, font, indev, layouts, misc, osal, stdlib, themes, tick, widgets, image)

## Known Limitations

- Only arm64-v8a ABI is built (x86_64 requires CMake 3.25+ with NDK r26b)
- LVGL runs on a separate thread; ANativeWindow access from non-UI thread may cause issues on some devices
- No vsync synchronization (rendering is timer-based at ~5ms intervals)
- Touch input supports pointer (single touch) only

## Adding New LVGL Features

1. Enable the feature in `app/src/main/cpp/lv_conf.h`
2. Add any new source files to `app/src/main/cpp/CMakeLists.txt` glob patterns
3. Rebuild with `.\gradlew.bat assembleDebug --no-daemon`

## Troubleshooting

| Issue | Solution |
|-------|----------|
| CMake C compiler test fails | Check NDK version matches CMake compatibility |
| `Could not find CMAKE_ROOT` | CMake `share/cmake-X.Y` directory missing from install |
| Black screen | Check `lv_display_set_buffers()` called before `lv_display_set_flush_cb()` |
| Touch not working | Verify `nativeTouchEvent` receives correct action codes (0=down, 1=up) |
| Build downloads fail | Use `curl.exe -L -o` instead of `Invoke-WebRequest` for large files |
