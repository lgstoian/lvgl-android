# lvgl-android Constructor Guide

## Quick Start

### Basic Activity with LVGL

```kotlin
import com.lvgl.android.activity.LvglActivity

class MyActivity : LvglActivity() {
    override fun onCreateLvgl() {
        // Create UI here
    }
}
```

### Custom Input Method (Keyboard)

```kotlin
import com.lvgl.android.input.LvglInputMethodService

class MyKeyboard : LvglInputMethodService() {
    override fun onCreateKeyboardView(): View? {
        return LvglSurfaceView(this)
    }
}
```

### Custom Launcher

```kotlin
import com.lvgl.android.launcher.LvglLauncherActivity

class MyLauncher : LvglLauncherActivity() {
    override fun onCreateLvgl() {
        // Create launcher UI
    }

    override fun onAllAppsChanged() {}
    override fun onPackageAdded(packageName: String) {}
    override fun onPackageRemoved(packageName: String) {}
    override fun onPackageChanged(packageName: String) {}
}
```

### Game Activity

```kotlin
import com.lvgl.android.game.LvglGameActivity

class MyGame : LvglGameActivity() {
    override fun onCreateLvgl() {}

    override fun onGameLoop(deltaMs: Long) {
        // Update game state
    }
}
```

## Building from Source

### Prerequisites
- JDK 17+
- Android SDK (API 24+)
- Android NDK r26b
- CMake 3.22.1 (with share/ directory)

### Build Commands
```powershell
# Build library + app
cd "D:\Code\android lvgl"
.\gradlew.bat assembleDebug --no-daemon

# Install on emulator (per-ABI APKs are also produced)
adb install -r app\build\outputs\apk\debug\app-universal-debug.apk

# Run
adb shell am start -n com.lvgl.sample/.MainActivity
```

### Update LVGL Version
```powershell
cd "D:\Code\android lvgl\lvgl"
git fetch --tags
git checkout v9.7.0
cd ..
git add lvgl
git commit -m "Update LVGL to v9.7.0"
```
After updating LVGL, check for API changes in:
- `lv_conf.h` (new config options)
- `lvgl/src/**/*.c` (new/deprecated APIs)
- `include/lvgl/**/*.h` (new function signatures)
Update the native port code in `lvgl-android/src/main/cpp/lvgl_port.cpp` accordingly.

## Key Components

| Component | Purpose |
|-----------|---------|
| `LvglEngine` | Singleton rendering engine |
| `LvglSurfaceView` | SurfaceView with LVGL |
| `LvglActivity` | Base Activity with LVGL |
| `MaterialTheme` | Material 3 theme resolution |
| `MaterialPalette` | The 20 Material 3 tonal roles |
| `LvglUi` | Kotlin DSL for declaring app screens |
| `LvglInputMethodService` | Base for keyboard apps |
| `LvglLauncherActivity` | Base for launcher apps |
| `LvglGameActivity` | Base for games |
| `LvglCameraPreview` | Camera preview component |
| `DpiManager` | DPI adaptation |
| `MultiWindowManager` | Multiwindow support |
| `LvglThemeBridge` | Theme sync between Material and LVGL |

## Configuration

### lv_conf.h Location
`lvgl-android/src/main/cpp/lv_conf.h`

### lvgl Source Path (CMake)
`lvgl-android/src/main/cpp/CMakeLists.txt` → `../../../../lvgl`

### Enable/Disable Features
Toggle `LV_USE_*` in `lv_conf.h` and `lv_conf_template.h` defines.

## Theme Integration

The Material 3 tonal palette is the single contract between Android and LVGL:

- `MaterialPalette` defines 20 tonal roles in a fixed index order shared with
  the native `MaterialRole` enum.
- `MaterialTheme` resolves the palette from the baseline scheme or the Android
  system accents (Material You, API 31+) and follows the system dark/light mode.
  The dashboard theme FAB can pin an app-local override.
- `LvglThemeBridge` pushes the palette to native, which rebuilds the screen; the
  native UI kit reads `ui_palette()` so every component re-themes.

The native kit also provides Material 3 components (buttons, chips, FAB, cards,
text field, bottom nav, dialog, snackbar, app bar) and the `LvglUi` Kotlin DSL
for declaring app screens.

## DPI & Display

`DpiManager` provides:
- Dynamic DPI detection
- dp/px/sp conversion
- Screen size category (SMALL/MEDIUM/LARGE/EXTRA_LARGE)
- Orientation detection

`DisplayMetricsManager` integrates with `ConfigurationChangedListener` for automatic updates.

## Multiwindow

`MultiWindowManager` provides:
- Multiwindow mode detection
- Picture-in-picture support
- Foldable device compatibility
- State restoration

## Next steps

- [docs/quickstart.md](docs/quickstart.md) — your first screen, input and theme in 5 minutes.
- [template/](template/README.md) — copy a minimal standalone app.
- [docs/public-api.md](docs/public-api.md) — every public class.
- [tools/size-report.ps1](tools/size-report.ps1) — release size budget.
