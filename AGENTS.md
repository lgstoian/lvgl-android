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
- Never derive camera preview aspect from `getOutputSizes()` alone: use the
  active array plus a `CaptureResult` crop callback (v1.3.0)
- Never leave hard-coded motion values in the UI kit: `LvglMotionConfig` polls
  ROM scales/framework durations and pushes them to native
- No unbounded waits: every platform settings/resource read is single-shot and
  bounded, with a fallback

If you encounter an issue, check `ai-context.md` first. If you discover a new issue not documented there, add it with the problem, symptom, root cause, and resolution.

## Project Overview

This project is a **general Android framework** built on LVGL (Light and Versatile Graphics Library) v9.6.0, not an embedded-only toolkit. It provides Kotlin-first APIs for activities, keyboards (IME), launchers, games, and camera apps, and it can also supply a single `LvglSurfaceView` inside an otherwise normal Compose/XML app. Do not position it as requiring a wholesale replacement of the Android UI toolkit: see the trade-offs and escape hatches in [README.md](README.md#why--when-to-use-this), [docs/interop.md](docs/interop.md) and [docs/migration.md](docs/migration.md).

## Interop & adoption rules

- The engine is process-wide; a display is owner-scoped. Host **one live
  `LvglSurfaceView` at a time**; it binds/unbinds on surface callbacks and
  rebinds a same-size surface instead of rebuilding it.
- `LvglSurfaceView` is a normal `SurfaceView`: use it in a plain
  Activity/Fragment layout or a Compose `AndroidView`, and forward
  `onResume()` / `onPause()` / `destroy()` from the host lifecycle. Only
  `LvglActivity` subclasses must avoid `setContentView()`. The sample app's
  `HybridSampleActivity` uses `addContentView()` to keep its guard happy, but
  consumer apps may use `setContentView()` normally.
- Native Android views composited *above* LVGL set their own overlay flags
  (`setZOrderOnTop(true)`, `setZOrderMediaOverlay(true)`); `LvglSurfaceView`
  deliberately does not. `CameraSampleActivity` is the proof. For camera
  overlays use `LvglCameraPreview.startPreview(surface)` before `open()`, and
  `selectPreviewSize(cameraId, target)` / `chosenPreviewSize` to aspect-fit the
  preview; enumerate all cameras with `allCameraIds()` (logical + physical) and
  cycle on tap.
- LVGL-thread callbacks (`LvglUi.listener`, node callbacks, `ScreenNavigator`)
  must hop to the UI thread before touching Android views.
- TalkBack virtual nodes exist only for `LvglUi` app screens (ids ≥ 1000), via
  `LvglSurfaceView.getAccessibilityNodeProvider()` → internal
  `LvglNodeAccessibilityProvider`. Built-in screens, exact geometry, native
  scroll/IME semantics are out of scope; use a native view fallback. See
  [docs/accessibility.md](docs/accessibility.md).
- `LvglPerformanceMonitor` has no automatic native frame callback yet: the app
  must call `onFrameRendered()` itself. Never invent measured numbers in docs;
  the results table in [docs/performance.md](docs/performance.md) stays a
  placeholder until a hardware run.

## Environment Setup

Required tools:

- **JDK 17**: `C:\Users\lgstoian\jdk-17\jdk-17.0.12`
- **Android SDK**: `D:\ASDK`
- **Android NDK**: `D:\ASDK\ndk\android-ndk-r26b` (r26b — must match for CMake 3.22.1 compatibility)
- **CMake**: `D:\ASDK\cmake\3.22.1` (must have `share/cmake-3.22` directory)
- **Gradle**: wrapper 8.11.1, Android Gradle Plugin 8.10.1, Kotlin 1.9.22
  (these versions support compileSdk/targetSdk 36; see the guard in the root
  `build.gradle.kts`)

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

# Release artifact gates (build release first; fails on a missing ABI APK)
.\gradlew.bat assembleRelease --no-daemon

# AAB: bundle in a separate step with ABI splits disabled. Never combine this
# with assembleRelease: splits + R8 resource shrinking make bundleRelease fail.
.\gradlew.bat bundleRelease -Plvgl.bundleNoSplits=true --no-daemon
.\tools\size-report.ps1
.\gradlew.bat lintVitalRelease --no-daemon

# Start emulator (wait 3+ minutes for boot)
$env:ANDROID_HOME = "D:\ASDK"
$env:ANDROID_AVD_HOME = "C:\Users\lgstoian\.android\avd"
Start-Process -FilePath "D:\ASDK\emulator\emulator.exe" -ArgumentList "-avd", "test_avd", "-no-window", "-no-audio", "-gpu", "swiftshader_indirect", "-wipe-data" -WindowStyle Hidden

# Wait for boot, then install & launch
& "D:\ASDK\platform-tools\adb.exe" devices
& "D:\ASDK\platform-tools\adb.exe" shell "getprop sys.boot_completed"
& "D:\ASDK\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-universal-debug.apk
& "D:\ASDK\platform-tools\adb.exe" shell am start -n com.lvgl.sample/.MainActivity

# Capture screenshot
& "D:\ASDK\platform-tools\adb.exe" shell screencap -p /sdcard/lvgl.png
& "D:\ASDK\platform-tools\adb.exe" pull /sdcard/lvgl.png screenshots\test.png

# View LVGL logs
& "D:\ASDK\platform-tools\adb.exe" logcat -d -s "LVGLPort"

# Release smoke: walk the release APK (the release workflow blocks on this)
& powershell -File tools\dynamic-test.ps1 -ApkPath app\build\outputs\apk\release\app-universal-release.apk
```

## Release & Size Budget

- **Version**: single source of truth in `gradle.properties`
  (`lvgl.versionCode`, `lvgl.versionName`); both modules read it.
- **Size budget**: `tools/size-report.ps1` enforces 4 MB per-ABI / 6 MB universal
  in CI and fails when an expected ABI APK is missing. Release uses R8 full mode,
  resource shrinking, per-ABI splits, the static C++ STL, legacy native packaging
  and `resConfigs("en")`. [BC-031, BC-037]
- **Release smoke**: CI runs a release APK smoke test and `lintVitalRelease`; the
  release workflow blocks on the smoke, and the harness can run against the
  release APK to verify R8/JNI survival at runtime. [BC-037]
- **AAB bundling**: per-ABI APKs come from `assembleRelease`; the AAB comes from
  `bundleRelease -Plvgl.bundleNoSplits=true` as a separate step. The two must not
  be combined: with ABI splits and R8 resource shrinking both enabled,
  `bundleRelease` expects one shrunk `.ap_` but finds three and fails. Play
  receives the AAB; a green `assembleRelease` does not prove the bundle works.
- **Toolchain**: AGP and Gradle must officially support compileSdk/targetSdk 36;
  a Gradle check fails on an AGP/SDK mismatch instead of warning.
- **API compatibility**: `api/lvgl-android.api` is checked by
  `:lvgl-android:apiCheck` in CI. On a deliberate API change, run
  `:lvgl-android:apiDump` and commit the new dump.
- **Signing**: the release workflow supplies the keystore through the
  `LVGL_KEYSTORE*` secrets; a local `assembleRelease` without them falls back to
  the debug key so it still installs.
- **New apps**: copy `template/` or run the `com.lvgl.android.app` plugin's
  `createLvglApp` task; do not duplicate the sample app. [BC-032]
- **Google Play**: target API 36, 64-bit ABIs, an AAB, runtime permissions, a
  privacy policy and the in-app licence disclosure.
  `PlayComplianceGuardTest` machine-checks all of it; see `docs/play-store.md`.
  Request only the permissions the app needs (the only one is the optional
  `CAMERA`). [BC-035]

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
13. **Material 3 tonal palette**: `MaterialPalette` (20 roles) is the single contract between Android and LVGL; `MaterialTheme` resolves it (baseline or Material You dynamic color) and `nativeApplyTheme` pushes it; native derives every colour from it. [BC-029]
14. **Material 3 component kit**: native widgets (buttons, chips, FAB, cards, text field, bottom nav, dialog, snackbar, app bar) render Material from the palette, so apps look native while staying pure LVGL. [BC-030]
15. **Balanced size optimisation**: R8 full mode + resource shrinking + per-ABI splits + static C++ STL + legacy native packaging + `resConfigs("en")`; 1.31 MB arm64-v8a, 1.33 MB x86_64, 2.06 MB universal APK, 4.13 MB AAB. [BC-031]
16. **Developer toolkit**: quickstart, public API reference, `template/`, the `com.lvgl.android.app` Gradle plugin, consumer ProGuard rules and a binary-compatibility API dump. [BC-032]
17. **Kotlin UI builder**: apps declare their own screens with the `LvglUi` DSL; the tree is serialised to JSON and rendered by the native UI kit, with node callbacks and `updateNode()` for interactivity. App screen ids start at 1000. [BC-033]
18. **Interop is a first-class surface**: `LvglSurfaceView` works in plain Activity/Fragment/Compose hosts, native views can be composited above it, and `HybridSampleActivity` proves both directions of the bridge with no JNI changes.
19. **Accessibility node bridge**: `LvglSurfaceView.getAccessibilityNodeProvider()` returns an internal provider that mirrors `LvglUi` app screens (ids ≥ 1000) as virtual Android nodes; built-in screens and exact geometry are not exposed, so a native-view fallback is documented.
20. **Integration guards**: `IntegrationGuardTest` verifies that features are wired through their call paths (multiwindow, gestures, perf sink, launcher receiver, announcements, camera permission, UI events), with fail-closed canaries. [BC-037]
21. **Camera interop**: `LvglCameraPreview.selectPreviewSize(cameraId, target)` picks the closest-aspect YUV size and the sample letterboxes its native preview overlay; all camera ids (logical + physical) are enumerated for tap-to-cycle. Since v1.3.0 the effective aspect comes from `SENSOR_INFO_ACTIVE_ARRAY_SIZE` plus a `CaptureResult` callback and is published through `setDisplayAspectListener`.
22. **ROM-derived motion**: `LvglMotionConfig` (`com.lvgl.android.motion`) polls `ViewConfiguration`, `Settings.Global` animation scales and framework durations with bounded reads, and `LvglActivity` installs it by default; the engine pushes values over JNI to native scroll throw and spring/transition durations. Apps override with `setOverride(config)` or opt out with `setEnabled(false)`. [BC-037]

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
- The dashboard schematic must depict **Android device** internals (SoC, modem,
  PMIC, LPDDR, UFS, ...), not an embedded MCU board. [BC-021]
- The status-bar clock/labels are global state shared across screens: tear them
  down in `launch_screen()` before every build, including screens that bypass
  `make_screen()`. [BC-022]
- New screens use `ui_make_scaffold()` and the layout primitives
  (`ui_make_row/column/grid/card/section/list_row`) with `ui_tokens()` — never
  raw dp values or a plain `lv_obj_create()` container with children. [BC-027]
- Text must fit its box: use `ui_fit_label()` for chips, buttons and rows
  instead of letting it clip. [BC-027]
- The dashboard device map shows real values from `LvglDeviceInfo`; never
  hard-code component names or capacities in the UI. [BC-028]
- User-visible strings must live in `strings.xml`, not in activity sources.
- Use the Material 3 component kit (`ui_make_button`, `ui_make_chip`,
  `ui_make_fab`, `ui_make_m3_card`, `ui_make_text_field`, `ui_make_bottom_nav`,
  `ui_make_dialog`, `ui_make_snackbar`, `ui_make_app_bar`) instead of hand-rolling
  widgets; every component reads `ui_palette()` and re-themes with the active
  palette. [BC-030]
- Overlays (dialog, snackbar, FAB) must set `LV_OBJ_FLAG_FLOATING` so they ignore
  the parent's flex layout and are ignored by the layout lint. [BC-030]
- App screens are declared in Kotlin with the `LvglUi` DSL (`LvglUi.registerScreen`)
  and shown with `setLvglScreen(id)`; the tree is rendered by the native UI kit,
  so it re-themes automatically. App screen ids start at 1000. [BC-033]
- Node callbacks and `LvglUi.listener` run on the LVGL thread; switch to the UI
  thread before touching Android views. [BC-033]
- Host one live `LvglSurfaceView` at a time; forward its `onResume`/`onPause`/
  `destroy()` from a plain Activity, Fragment or Compose `AndroidView`. See
  [docs/interop.md](docs/interop.md).
- Native views above LVGL set their own overlay flags; do not enable
  `setZOrderOnTop(true)` on `LvglSurfaceView` itself (it hides in-app dialogs).
- Accessibility work must stay within the documented node-provider scope
  (`LvglUi` screens ≥ 1000); anything needing real bounds or native semantics
  belongs in a native view. See [docs/accessibility.md](docs/accessibility.md).
- Do not trust `getOutputSizes()` for camera aspect: derive it from
  `SENSOR_INFO_ACTIVE_ARRAY_SIZE` plus a `CaptureResult` crop callback and
  subscribe through `setDisplayAspectListener` (v1.3.0).
- Do not leave hard-coded motion values in the UI kit: let `LvglMotionConfig`
  supply ROM scales and framework durations, and keep every platform read
  single-shot/bounded with a fallback — no unbounded waits.

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
- **Gates fail closed**: a missing or malformed report, APK or attribute value
  must fail the gate, never read as "clean". Every detector needs a canary that
  must be detected, a clean fixture that must not be, and a wiring canary proving
  the detector is actually invoked. See `BC-037`.
- `V130GuardTest` (v1.3.0) adds five wiring detectors: `wired-motion-config`,
`wired-native-motion-setter`, `wired-scroll-throw`,
`camera-aspect-active-array` and `wired-camera-aspect-listener`, each with a
broken and a clean canary in `GuardCanaryTest`. The camera detector rejects an
aspect derived from `getOutputSizes()` alone; the motion detectors reject a
setter that is declared but never called and native scroll throw that exists
only in a comment. [BC-014, BC-037]

`IntegrationGuardTest` (v1.2.0) adds seven wiring detectors:
  `wired-multiwindow-pip`, `wired-gesture-results`, `wired-performance-caller`,
  `wired-launcher-receiver`, `wired-accessibility-announcements`,
  `wired-camera-permission` and `wired-ui-event-bridge`. Presence of a file or
  method is not evidence that it is called; change the detector **and** its
  canary together.

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
│   │   ├── view/              # LvglSurfaceView (+ accessibility provider)
│   │   ├── activity/          # LvglActivity base
│   │   ├── config/            # DPI, display metrics, configuration
│   │   ├── theme/             # MaterialTheme, MaterialPalette, LvglThemeBridge
│   │   ├── window/            # MultiWindowManager
│   │   ├── input/             # LvglInputMethodService, gestures
│   │   ├── launcher/          # LvglLauncherActivity
│   │   ├── game/              # LvglGameActivity
│   │   ├── camera/            # LvglCameraPreview (aspect-fit preview sizes)
│   │   ├── accessibility/     # Manager, focus navigation, screen reader, node provider
│   │   ├── perf/              # LvglPerformanceMonitor
│   │   ├── navigation/        # LvglNavigation
│   │   ├── system/            # LvglSystemControl, LvglDeviceInfo
│   │   ├── ui/                # LvglUi DSL, nodes, events, JSON wire format
│   │   └── util/              # LvglLog
│   ├── src/main/cpp/          # Native code + CMake
│   ├── api/                   # Binary-compatibility dump (apiCheck)
│   └── consumer-rules.pro     # Consumer ProGuard rules
├── app/                        # Sample application
├── build-logic/               # Included build: com.lvgl.android.app plugin
├── template/                   # Copyable standalone app project
├── docs/                       # Quickstart, API, interop, accessibility, performance, migration, release checklist
├── tools/                      # Dynamic harness, size report, version bump
├── screenshots/                # Screenshots for documentation
├── ai-context.md               # Known issues and quirks
├── AGENTS.md                   # This file
├── BUILDING.md                 # Quick start guide
├── CONTRIBUTING.md             # PR process, CI and guard rules
├── ROADMAP.md                  # Development roadmap
├── CHANGELOG.md                # Release notes
├── SECURITY.md                 # Vulnerability reporting, supported versions
├── SUPPORT.md                  # Support policy and version matrix
└── README.md                   # Architecture overview and trade-offs
```