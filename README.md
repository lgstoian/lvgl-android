# LVGL Android Framework

A framework for building small, efficient Android applications using [LVGL (Light and Versatile Graphics Library)](https://lvgl.io/) v9.6.0.

## Overview

This project transforms LVGL — a lightweight embedded GUI library — into a full Android framework. The framework provides Kotlin-first APIs for building Android apps with custom UI, including activities, input methods (keyboards), launchers, games, and apps with camera integration.

LVGL renders directly to an Android `SurfaceView` via native C code using JNI, providing a fully customizable UI without relying on Android's native widget system.

**Key Features:**
- LVGL v9.6.0 as a Git submodule for easy version updates
- Modular library (`lvgl-android`) + sample app architecture
- Kotlin-first API with DSL support planned
- Material Design 3 theme integration with dark/light mode
- Dynamic DPI adaptation and screen rotation handling
- Multiwindow and foldable device support
- Input Method Service (keyboard) support
- Launcher/home screen support
- Game-optimized activity with vsync
- Camera preview integration
- R/B pixel swap for correct RGBA rendering
- Dedicated LVGL render thread at ~60fps
- Touch input with pointer gestures

## Module Structure

```
android_lvgl/
├── lvgl/                       # Git submodule (LVGL v9.6.0 source)
│   ├── include/lvgl/           # Public headers
│   └── src/                    # Implementation files
├── lvgl-android/               # Framework library module
│   ├── src/main/
│   │   ├── java/com/lvgl/android/
│   │   │   ├── engine/         # LvglEngine (singleton rendering engine)
│   │   │   ├── view/           # LvglSurfaceView
│   │   │   ├── activity/       # LvglActivity base class
│   │   │   ├── config/         # DpiManager, DisplayMetricsManager, ConfigurationManager
│   │   │   ├── theme/          # MaterialTheme, LvglThemeBridge
│   │   │   ├── window/         # MultiWindowManager
│   │   │   ├── input/          # LvglInputMethodService
│   │   │   ├── launcher/       # LvglLauncherActivity
│   │   │   ├── game/           # LvglGameActivity
│   │   │   ├── camera/         # LvglCameraPreview
│   │   │   └── util/           # LvglLog
│   │   └── cpp/                # Native code (CMake)
│   └── build.gradle.kts
├── app/                        # Sample application
│   ├── src/main/
│   │   ├── java/com/lvgl/sample/
│   │   │   └── MainActivity.kt
│   │   └── res/
│   └── build.gradle.kts
├── screenshots/                # Screenshots for documentation
├── build.gradle.kts            # Root build config
├── settings.gradle.kts         # Gradle settings (includes :app, :lvgl-android)
├── ai-context.md               # Known issues and quirks
├── AGENTS.md                   # Development guide
├── BUILDING.md                 # Quick start guide
└── README.md                   # This file
```

## Requirements

- Android SDK (API 24+)
- Android NDK r26b or compatible
- JDK 17+
- CMake 3.22.1+ (with `share/cmake-X.Y` directory)
- Gradle 8.5+

## Environment Setup

```powershell
$env:JAVA_HOME = "C:\Users\lgstoian\jdk-17\jdk-17.0.12"
$env:ANDROID_HOME = "D:\ASDK"
```

## Building

```powershell
# Set environment
$env:JAVA_HOME = "C:\Users\lgstoian\jdk-17\jdk-17.0.12"
$env:ANDROID_HOME = "D:\ASDK"

# Build
cd "D:\Code\android lvgl"
.\gradlew.bat assembleDebug --no-daemon
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Architecture

### Framework Layers

```
┌─────────────────────────────────────────┐
│   Sample Apps (Activity, IME, Launcher)  │
├─────────────────────────────────────────┤
│   Kotlin Framework (LvglActivity, etc.)  │
├─────────────────────────────────────────┤
│   JNI Bridge (nativeCreate, etc.)       │
├─────────────────────────────────────────┤
│   C++ Native (Engine, rendering, input)  │
├─────────────────────────────────────────┤
│   LVGL 9.6.0 (core, widgets, themes)    │
└─────────────────────────────────────────┘
```

### Rendering Pipeline

1. LVGL renders widgets into a framebuffer in memory
2. The flush callback (C++) copies the framebuffer to an `ANativeWindow`
3. Pixel format conversion (BGRA → RGBA) is performed during copy
4. `ANativeWindow_unlockAndPost()` presents the frame

### Threading Model

- LVGL `LvglEngine` is a Kotlin singleton managing display lifecycle
- Native C++ `Engine` struct holds native state (window, display, framebuffers, threads)
- `nativeCreate`/`nativeDestroy` manage the native engine lifecycle via JNI
- Dedicated LVGL thread runs `lv_timer_handler()` every 5ms
- Touch events propagate from Kotlin UI thread to native via JNI

### Material Theme Integration

- `MaterialTheme` provides Material Design 3 color tokens
- Auto-detects dark/light mode from system configuration
- `LvglActivity` registers theme change listeners
- Theme bridge applies changes to LVGL's rendering

## Updating LVGL Version

LVGL is a Git submodule:

```powershell
cd lvgl
git fetch --tags
git checkout v9.7.0  # Change to desired version
cd ..
git add lvgl
git commit -m "Update LVGL to v9.7.0"
```

After updating, check for:
- New config options in `lv_conf.h`
- New/deprecated APIs in `lvgl/src/**/*.c`
- New function signatures in `include/lvgl/**/*.h`

## Documentation

- [AGENTS.md](AGENTS.md) — Development guide and troubleshooting (read before coding)
- [ai-context.md](ai-context.md) — Known issues, quirks, and fixes
- [BUILDING.md](BUILDING.md) — Quick start guide
- [ROADMAP.md](ROADMAP.md) — Development roadmap and progress

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for the full text.

### Third-Party Software

This project uses [LVGL](https://lvgl.io) v9.6.0, which is also MIT licensed. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for a complete list of attributions.
