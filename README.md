# LVGL on Android

A port of [LVGL (Light and Versatile Graphics Library)](https://lvgl.io/) v9.6.0 to Android, enabling the creation of small, efficient Android applications with rich graphical user interfaces.

## Overview

This project demonstrates how to integrate LVGL (a lightweight embedded GUI library) into an Android application. LVGL renders directly to an Android `SurfaceView` via native C code using JNI, providing a fully customizable UI without relying on Android's native widget system.

**Key Features:**
- LVGL v9.6.0 core library compiled as a shared library
- Direct framebuffer rendering via `ANativeWindow`
- Touch input handling through Android's `SurfaceView` touch events
- Support for LVGL themes, styles, layouts, and widgets
- Minimal memory footprint compared to traditional Android UI frameworks

## Requirements

- Android SDK (API 24+)
- Android NDK r26b or compatible
- JDK 17+
- CMake 3.22.1+
- Gradle 8.5+

## Building

1. Set up environment variables:
   ```powershell
   $env:JAVA_HOME = "C:\Users\lgstoian\jdk-17\jdk-17.0.12"
   $env:ANDROID_HOME = "D:\ASDK"
   ```

2. Create `local.properties` in the project root:
   ```properties
   sdk.dir=D\:\\ASDK
   ndk.dir=D\:\\ASDK\\ndk\\android-ndk-r26b
   ```

3. Build the debug APK:
   ```powershell
   .\gradlew.bat assembleDebug
   ```

4. The APK will be at `app/build/outputs/apk/debug/app-debug.apk`

## Project Structure

```
android_lvgl/
├── app/                        # Android application module
│   ├── src/main/
│   │   ├── cpp/
│   │   │   ├── CMakeLists.txt  # Native build configuration
│   │   │   ├── lv_conf.h       # LVGL configuration
│   │   │   └── lvgl_port.cpp   # LVGL port (JNI, rendering, input)
│   │   ├── java/com/lvgl/sample/
│   │   │   ├── MainActivity.kt  # Main activity
│   │   │   └── LvglView.kt     # Custom SurfaceView for LVGL
│   │   └── res/                # Android resources
│   └── build.gradle.kts         # App module build config
├── lvgl/                       # LVGL v9.6.0 source code
│   ├── include/lvgl/           # Public headers
│   └── src/                    # Implementation files
├── screenshots/                # Screenshots for documentation
├── build.gradle.kts            # Root build config
├── settings.gradle.kts         # Gradle settings
└── README.md                   # This file
```

## Architecture

### Rendering Pipeline

1. LVGL renders widgets into a framebuffer in memory
2. The flush callback copies the framebuffer to an `ANativeWindow`
3. Pixel format conversion (BGRA → RGBA) is performed during copy
4. `ANativeWindow_unlockAndPost()` presents the frame

### Threading Model

- LVGL runs on a dedicated thread (`g_lvglThread`)
- `lv_timer_handler()` is called every 5ms
- Touch events are received on the UI thread and passed to LVGL via JNI
- `ANativeWindow` operations happen on the LVGL thread during the flush callback

### Key Files

| File | Purpose |
|------|---------|
| `lvgl_port.cpp` | Main JNI bridge, display/touch initialization, LVGL thread |
| `LvglView.kt` | Custom `SurfaceView` that manages the native surface lifecycle |
| `lv_conf.h` | LVGL configuration (color depth, widgets, features) |
| `CMakeLists.txt` | Native build configuration |

## Testing

1. Start an Android emulator:
   ```powershell
   emulator -avd test_avd -no-window -no-audio -gpu swiftshader_indirect
   ```

2. Install the APK:
   ```powershell
   adb install -r app\build\outputs\apk\debug\app-debug.apk
   ```

3. Launch the app:
   ```powershell
   adb shell am start -n com.lvgl.sample/.MainActivity
   ```

4. Capture a screenshot:
   ```powershell
   adb shell screencap -p /sdcard/screenshot.png
   adb pull /sdcard/screenshot.png screenshots/
   ```

## Customizing the UI

Edit the `create_default_ui()` function in `lvgl_port.cpp` to create your own LVGL-based interface. See [LVGL documentation](https://docs.lvgl.io/) for available widgets and APIs.

Example:
```c
static void create_default_ui() {
    lv_obj_t* screen = lv_screen_active();

    lv_obj_t* label = lv_label_create(screen);
    lv_label_set_text(label, "Hello Android!");
    lv_obj_center(label);
}
```

## License

LVGL is licensed under the MIT License. See `lvgl/LICENCE.txt` for details.
