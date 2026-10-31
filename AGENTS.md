# Development Guide

## Important: Known Issues & Quirks

**Before troubleshooting any build issues, read [ai-context.md](ai-context.md)** which documents all known quirks, oddities, and their resolutions discovered during development. Key issues include:

- Emulator binary: `D:\ASDK\emulator\emulator.exe` (NOT `D:\ASDK\platform-tools\emulator.exe`)
- ADB unauthorized after key deletion: use `-wipe-data` flag
- CMake `share/cmake-X.Y` directory missing from GitHub releases
- Demo UI lives in `demo_ui.h/.cpp` (single source of truth); `lvgl_port.cpp` owns the engine + JNI only
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
2. **Process-wide engine, owner-scoped display**: `LvglEngine.instance` is a process-wide singleton. Activities bind/unbind a *display* (`initDisplay(owner, …)` / `deinitDisplay(owner)`) but never tear the engine down; `deinitDisplay` from a stale owner is ignored.
3. **DIRECT render mode**: Full-screen framebuffer (not partial) for swapchain compatibility
4. **Dedicated LVGL thread + task queue**: `lv_timer_handler()` runs on the render thread; the UI thread only enqueues tasks. Never call `scheduleTask()` while holding `engine->mutex` (non-recursive → deadlock/ANR).
5. **R/B pixel swap**: BGRA to RGBA during framebuffer copy in flush callback
6. **Core-only compilation**: CMakeLists.txt compiles only core LVGL modules, no drivers/demos
7. **Git submodule**: LVGL source is submodule, enabling easy version updates
8. **Material 3 theme**: Dynamic colors, dark/light mode from system configuration
9. **Multi-ABI**: arm64-v8a + x86_64 (x86_64 links `-latomic` explicitly in CMakeLists.txt)
10. **Surface rebind, not rebuild**: every activity transition delivers a new surface; a same-size surface is rebound to the shared display (fast path) instead of rebuilding it
11. **Screen ownership**: `LvglActivity.setLvglScreen(id)` records the activity's screen and re-asserts it in `onResume`; live status text is cached per screen and re-applied after a rebuild
12. **UI kit**: all screens are declared in `demo_ui.h` and implemented in `demo_ui.cpp` (single source of truth)

## UI Development Rules (LVGL)

These are **review rules** (not yet machine-guarded); the machine-checkable rules
are listed under "Guard Test Protection" below. Each rule is backed by a
`@breadcrumb` in `docs/agent-breadcrumbs.json`.

- Use `dpx(dp, dpi)` and `ui_font(dp, dpi)` for all spacing and fonts — never raw pixel
  values or `lv_font_montserrat_NN` directly (LVGL fonts are fixed pixel sizes). [BC-006]
- Montserrat only ships up to **48 px**; the enabled ladder in `lv_conf.h` caps there.
- Every container with more than one child must set an explicit layout (flex), or its
  children will all be placed at (0, 0) and overlap. [BC-005]
- Every decorative object (label, accent bar, inner column) must be made
  input-transparent: a plain `lv_obj` is **clickable by default** and will steal taps
  from a clickable ancestor. [BC-004]
- Large overlays (e.g. the animated window) must set `LV_OBJ_FLAG_FLOATING` so they
  ignore the parent's flex layout and honour `lv_obj_set_pos()`. [BC-019]
- Never call `scheduleTask()` while holding `engine->mutex` — it is non-recursive and
  will deadlock the UI thread (ANR). [BC-003]
- User-visible strings must live in `strings.xml`, not in activity sources.

## AI Agent Breadcrumbs & Machine-Readable Comments

- `docs/agent-breadcrumbs.json` is the machine-readable decision trail. Each entry
  (`BC-NNN`) records a non-obvious decision, its rationale, and a `do_not` list.
- Source files link to it with `// @breadcrumb BC-NNN` (or `/* @breadcrumb BC-NNN */`).
- Use the schema in `docs/agent-comment-schema.md`: `@tag key=value`, with tags
  `guard-rule`, `invariant`, `decision`, `breadcrumb`, `machine-json`, `contract`,
  `ai-hint`, `why`, `do-not`, `trap`.
- `MachineCommentValidationTest` fails the build on an unknown tag, a dangling
  `BC-`/`ADR-` reference, invalid `@machine-json`, or an orphaned breadcrumb.
- New non-obvious decisions should get a `BC` entry in the JSON **and** an in-code
  `@breadcrumb`. See `BC-015`.

## Guard Test Protection (no false green, no false positive)

- Detection logic lives in `GuardRules` (`lvgl-android/src/test/.../guards/`).
- `GuardCanaryTest` feeds each detector a broken fixture that **must** be detected
  (false-green protection) and a clean fixture that **must not** be (false-positive
  protection). If you change a detector, update its canary too.
- Guard tests locate the repo root by walking up and assert the path exists, so a
  broken path fails loudly instead of silently passing. See `BC-014`.

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
| Emulator won't start | Use binary at `D:\ASDK\emulator\emulator.exe` not `platform-tools\emulator.exe` |
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