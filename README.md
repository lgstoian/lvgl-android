# LVGL Android Framework

[![CI](https://github.com/lgstoian/lvgl-android/actions/workflows/ci.yml/badge.svg)](https://github.com/lgstoian/lvgl-android/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![LVGL v9.6.0](https://img.shields.io/badge/LVGL-v9.6.0-blue.svg)](https://github.com/lvgl/lvgl)

A framework for building small, efficient Android applications using [LVGL (Light and Versatile Graphics Library)](https://lvgl.io/) v9.6.0 — as one surface inside a normal Android app, or as the UI for a whole app.

## Overview

This project turns LVGL into a **general Android UI framework** with Kotlin-first APIs: activities, input methods (keyboards), launchers, games and camera integration. LVGL renders into an Android `SurfaceView` through native C++/JNI, with a Material 3 component kit and a Kotlin DSL for declaring screens. It is an additional rendering path on Android — not an embedded-only toolkit, and not a wholesale replacement for Compose or XML.

A typical app keeps its normal Android architecture and uses this framework for the screens where a custom, shared C++ UI pays off: dashboards, games, launchers, custom visualizations. The sample app shows full-LVGL activities; `HybridSampleActivity` shows an `LvglSurfaceView` living beside native Android views in one layout.

## Why / when to use this

**Reach for it when:**

- You want one widget/animation codebase shared with other LVGL targets, or a compact native renderer for a game, launcher or dashboard.
- You need a custom-drawn UI that would be awkward to express with platform widgets, while still using Android APIs for permissions, camera, system services and lifecycle.
- You are migrating an existing C++/LVGL UI to Android and want to keep it while adopting native screens incrementally.
- You want a small release artifact: the sample is ~1.3 MB per ABI after R8, resource shrinking, per-ABI splits and the static C++ STL.

**Do not use it when:**

- Your app is mostly forms, long text, lists and settings — Compose/XML will be faster to build and better integrated.
- You need full platform accessibility semantics, native text fields/IME, platform widgets, maps, ads or in-app billing UI out of the box.
- You require platform-consistent behaviour and frame scheduling from the Android UI toolkit (see [performance caveats](docs/performance.md)).

### Honest trade-offs

| Area | What you gain | What you give up |
| --- | --- | --- |
| Ecosystem | One LVGL codebase across targets; small native artifact | Most AndroidX/Compose libraries, platform widgets and Material components are not available inside LVGL |
| Accessibility | TalkBack virtual nodes for `LvglUi` app screens (ids ≥ 1000) via an accessibility node provider; announcement path for code | Built-in screens and exact widget geometry are not exposed; native text/scroll semantics are absent ([details](docs/accessibility.md)) |
| Rendering | Full control over pixels, animations and theme | A custom render thread that is not phase-locked to platform vsync; measurements must be taken on real hardware ([details](docs/performance.md)) |
| Integration | Activities, IME, launchers, games, camera overlays, multiwindow/PiP/foldables | Native UI work must be bridged explicitly; callbacks land on the LVGL thread and must hop to the UI thread |
| Maintenance | Small, self-contained C++ core; pinned LVGL submodule | A small, community-maintained project: no commercial support or response SLA ([SUPPORT.md](SUPPORT.md), [SECURITY.md](SECURITY.md)) |

**Escape hatches:** you can embed `LvglSurfaceView` in a plain Activity, Fragment or Compose `AndroidView`; host native Android views **above** LVGL (the camera preview is the shipped proof); and move any screen back to Compose/XML one at a time. Start with [docs/interop.md](docs/interop.md) and [docs/migration.md](docs/migration.md) — adopting this framework does not require committing the whole app to it.

**Key Features:**
- LVGL v9.6.0 as a Git submodule for easy version updates
- Modular library (`lvgl-android`) + sample app architecture
- Kotlin-first API, including the `LvglUi` DSL for declaring app screens
- Material Design 3 theme integration with dark/light mode and Material You dynamic color
- Material 3 component kit (buttons, chips, FAB, cards, text field, bottom nav, dialog, snackbar, app bar) rendered natively on LVGL
- Kotlin UI builder: declare app screens with the `LvglUi` DSL and let the native UI kit render them
- Dynamic DPI adaptation and screen rotation handling
- Multiwindow and foldable device support
- Input Method Service (keyboard) support
- Launcher/home screen support
- Game-optimized activity with vsync
- Camera2 helper with sensor-accurate aspect (active array + `CaptureResult` crop region), a public `displayAspect` / `setDisplayAspectListener` API, logical/physical camera enumeration and tap-to-cycle; the sample composites a native preview surface above LVGL
- ROM-derived motion: `LvglMotionConfig` polls platform animation scales, framework durations and `ViewConfiguration` fling/touch values, then drives LVGL scroll throw and spring/window transitions — enabled by default, overridable or disabled per app ([details](docs/motion.md))
- R/B pixel swap for correct RGBA rendering
- Dedicated LVGL render thread (vsync-aware, honours LVGL's requested period)
- Touch input with pointer gestures
- Spring-physics animation engine and animated window overlays
- M5Tab5-style device dashboard home: Android system schematic, floating control
  panels and a shared status bar (immersive)
- App Demo: a complete screen declared in Kotlin with the `LvglUi` DSL, plus a
  playable tap-the-target game demo
- Hybrid interop sample: `HybridSampleActivity` mixes `LvglSurfaceView` with native Android views and bridges both directions
- Accessibility node-provider bridge: TalkBack virtual nodes for `LvglUi` app screens (ids ≥ 1000), with a documented native-view fallback
- Fail-closed integration guards that verify features are actually wired (call paths), not just present in source
- Beautiful-by-default UI system: design tokens, a screen scaffold, overlap-proof
  layout primitives, text auto-fit and a layout lint with CI reporting
- Developer toolkit: quickstart, public API reference, copyable `template/`, a
  `com.lvgl.android.app` Gradle scaffolding plugin and a binary-compatibility API dump
- Google Play ready: target API 36, 64-bit, AAB, runtime permissions, privacy
  policy and in-app licence disclosure
- Space-efficient releases: R8 full mode, resource shrinking, per-ABI splits and the
  static C++ STL — 1.31 MB arm64-v8a, 1.33 MB x86_64, 2.06 MB universal APK,
  4.13 MB AAB

Both v1.3.0 features are **default SDK behaviour**, not opt-in samples:
`LvglActivity` installs `LvglMotionConfig` on create/configuration change, and
`LvglCameraPreview` publishes the effective sensor aspect through a listener
that the sample uses to letterbox its native overlay. Apps can override or
disable motion with `LvglMotionConfig.setOverride(...)` / `setEnabled(false)`,
or subscribe to camera aspect with `setDisplayAspectListener { ... }`; no
framework source change is needed. Fail-closed guards verify each call path.

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
│   │   │   ├── view/           # LvglSurfaceView, LvglSurfaceViewWithGestures
│   │   │   ├── activity/       # LvglActivity base class, accessibility ext
│   │   │   ├── config/         # DpiManager, DisplayMetricsManager, ConfigurationManager
│   │   │   ├── theme/          # MaterialTheme, MaterialPalette, LvglThemeBridge
│   │   │   ├── window/         # MultiWindowManager
│   │   │   ├── input/          # LvglInputMethodService, LvglInputProcessor, gestures
│   │   │   ├── launcher/       # LvglLauncherActivity, LauncherAppListener
│   │   │   ├── game/           # LvglGameActivity
│   │   │   ├── camera/         # LvglCameraPreview (sensor aspect listener)
│   │   │   ├── motion/         # LvglMotionConfig (ROM motion polling)
│   │   │   ├── navigation/     # LvglNavigation (screen back stack)
│   │   │   ├── accessibility/  # LvglAccessibilityManager, focus navigation, screen reader
│   │   │   ├── perf/           # LvglPerformanceMonitor
│   │   │   ├── system/         # LvglSystemControl, LvglDeviceInfo
│   │   │   ├── ui/             # LvglUi DSL, nodes, events, JSON wire format
│   │   │   └── util/           # LvglLog
│   │   └── cpp/                # Native code (CMake): port, demo UI, animation, windows, app screens
│   │       └── api/            # Binary-compatibility API dump (apiCheck)
├── app/                        # Sample application
│   ├── src/main/
│   │   ├── java/com/lvgl/sample/
│   │   │   ├── *.kt            # Dashboard + widget/layout/interactive/camera/game/launcher samples
│   │   │   └── showcase/       # DemoShowcaseActivity (spring-physics demo)
│   │   └── res/
│   └── build.gradle.kts
├── build-logic/                # Included Gradle build: com.lvgl.android.app plugin
├── template/                   # Copyable standalone app project (composite build)
├── docs/                       # Quickstart, API, interop, accessibility, performance, migration, breadcrumb trail
├── lvgl-android/api/           # Binary-compatibility API dump (apiCheck)
├── tools/                      # Dynamic (emulator) harness + size report
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
- Gradle 8.11.1+ (Android Gradle Plugin 8.10.1)

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

APK: `app/build/outputs/apk/debug/app-universal-debug.apk`
(per-ABI APKs are also produced: `app-arm64-v8a-debug.apk`, `app-x86_64-debug.apk`)

## Demos

Screenshots from the v1.1.0 build (2026-11-24); the v1.2.0 UI adds the hybrid
and camera-preview interop demonstrations described in [docs/interop.md](docs/interop.md).

| Device dashboard | Widget showcase |
| --- | --- |
| ![Device dashboard](screenshots/dashboard.png) | ![Widget showcase](screenshots/widget_showcase.png) |

| Showcase tiles | Showcase window |
| --- | --- |
| ![Showcase tiles](screenshots/showcase.png) | ![Showcase window](screenshots/showcase_window.png) |

| Launcher (real app grid) | App demo (Kotlin `LvglUi` screen) |
| --- | --- |
| ![Launcher](screenshots/launcher.png) | ![App demo](screenshots/app_demo.png) |

| Game sample | Open source licenses |
| --- | --- |
| ![Game sample](screenshots/game_sample.png) | ![Open source licenses](screenshots/licenses.png) |

## Build a new app

The fastest path is the copyable template:

```powershell
# Copy template/ next to the framework checkout, then build and run.
Copy-Item -Recurse template C:\src\MyLvglApp
cd C:\src\MyLvglApp
& "..\android lvgl\gradlew.bat" :app:assembleDebug
```

Or scaffold a module in place with the Gradle plugin:

```kotlin
// settings.gradle.kts
pluginManagement { includeBuild("build-logic") }

// build.gradle.kts
plugins { id("com.lvgl.android.app") }
lvglApp { moduleName = "myapp"; packageName = "com.example.myapp" }
```

```powershell
.\gradlew.bat createLvglApp
```

See [docs/quickstart.md](docs/quickstart.md) for the full walkthrough and
[docs/public-api.md](docs/public-api.md) for the API reference.

## Size

Release APKs are split per ABI and minified:

```powershell
.\gradlew.bat assembleRelease
pwsh tools/size-report.ps1

# AAB for Play: a separate step, because ABI splits and R8 shrinking together
# make bundleRelease fail. Do not combine the two commands.
.\gradlew.bat bundleRelease -Plvgl.bundleNoSplits=true
```

| artefact | size |
| --- | --- |
| `app-arm64-v8a-release.apk` | 1.31 MB |
| `app-x86_64-release.apk` | 1.33 MB |
| `app-universal-release.apk` | 2.06 MB |
| `app-release.aab` (Play artifact) | 4.13 MB |

The budget (4 MB per ABI, 6 MB universal) is enforced in CI. CI also runs a
release-APK smoke test and `lintVitalRelease`.

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
- Dedicated LVGL thread runs `lv_timer_handler()`, honouring the period LVGL
  requests (capped at ~16 ms) so animations stay vsync-aware
- Touch events propagate from Kotlin UI thread to native via JNI

### Material Theme Integration

- `MaterialPalette` defines the 20 Material 3 tonal roles in a fixed index order
  shared with the native `MaterialRole` enum.
- `MaterialTheme` resolves the palette from the baseline scheme or the Android
  system accents (Material You, API 31+) and follows the system dark/light mode.
  The dashboard theme FAB can pin an app-local dark-mode override.
- `LvglActivity` registers theme change listeners; `LvglThemeBridge` pushes the
  palette to native, which rebuilds the current screen.
- Every native component reads `ui_palette()`, so a whole app re-themes by
  construction.

### Material 3 component kit

`demo_ui.h` exposes a native component kit that renders Material 3 from the
palette, so apps look and behave natively while staying pure LVGL:

- `ui_make_button` — filled / tonal / outlined / text, plus a danger variant.
- `ui_make_chip`, `ui_make_fab`, `ui_make_m3_card` (filled/elevated/outlined).
- `ui_make_text_field`, `ui_make_divider`, `ui_make_bottom_nav`.
- `ui_make_dialog`, `ui_make_snackbar` — floating overlays with a scrim and
  auto-dismiss.
- `ui_make_app_bar` — the M3 top app bar used by the screen scaffold.

The widget showcase's `MATERIAL 3` section demonstrates every component.

### Kotlin UI builder

`LvglUi` lets an app declare its own screens in Kotlin instead of picking one of
the built-in ones. The tree is rendered by the native UI kit, so it shares the
Material 3 palette, the layout lint and the event bridge:

```kotlin
LvglUi.registerScreen(1000, title = "Hello") {
    column {
        label("Hello from Kotlin!", sizeDp = 20)
        button("Tap me", fillWidth = true, id = "tap") {
            LvglUi.updateNode(1000, "status", text = "Tapped!")
        }
        label("Tapped!", id = "status", sizeDp = 16)
    }
}
```

App screen ids start at `LvglUi.APP_SCREEN_BASE` (1000); `0..7` are built in.
`LvglEngine.initialize()` reports a missing native library through
`nativeLoadError` instead of crashing.

### Device Dashboard (home screen)

The entry screen (`SCREEN_HOME`) is a phone outline filled with the components
actually detected on the device, inspired by the M5Tab5 user demo:

- The map shows the real SoC, RAM, storage, display, camera and sensor counts
  (`LvglDeviceInfo`), drawn with LVGL primitives and scaled to portrait and
  landscape.
- LCD backlight and speaker volume panels drive real Android APIs through the
  `ScreenNavigator` bridge; power/RTC/peripheral panels are visual.
- Every screen shares the status bar (Wi-Fi, live clock, battery), and the app
  runs immersive so it does not duplicate the Android system bar.

### Beautiful-by-default UI system

New screens are consistent and correctly aligned without hand-tuning:

- `ui_tokens()` — spacing/radius/type scales; never hard-code a dp value.
- `ui_make_scaffold(parent, title, show_back)` — status bar + header + body.
- `ui_make_row/column/grid/card/section/list_row` — every container sets a flex
  layout, gap and alignment, so children cannot pile up at (0,0).
- `ui_fit_label()` — shrinks text to fit its box instead of clipping.
- `ui_layout_check()` / `LvglEngine.dumpLayoutReport()` — a lint that reports
  overlapping siblings, out-of-bounds children and clipped text; the dynamic
  harness fails CI when any warning appears.

### Interop and accessibility

- `LvglSurfaceView` is a normal `SurfaceView`: it can be placed in a plain
  Activity/Fragment layout or a Compose `AndroidView`. One display is live at
  a time; the engine rebinds the surface across activity transitions.
- Native Android views are composited **above** the LVGL surface by giving
  them their own overlay flags; `CameraSampleActivity` is the working proof and
  `HybridSampleActivity` shows native controls around an LVGL surface.
- `LvglSurfaceView` exposes an `AccessibilityNodeProvider` backed by the
  `LvglUi` tree for app screens (ids ≥ 1000). See
  [docs/accessibility.md](docs/accessibility.md) for the exact coverage and the
  native-view fallback.

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
- [CONTRIBUTING.md](CONTRIBUTING.md) — PR process, CI and guard rules
- [ai-context.md](ai-context.md) — Known issues, quirks, and fixes
- [docs/quickstart.md](docs/quickstart.md) — Your first LVGL Android app in 5 minutes
- [docs/public-api.md](docs/public-api.md) — Public API reference
- [docs/interop.md](docs/interop.md) — LVGL inside a normal Android app; native views above LVGL; escape hatches
- [docs/accessibility.md](docs/accessibility.md) — What TalkBack can and cannot see; native fallback
- [docs/motion.md](docs/motion.md) — ROM motion polling, ported physics, defaults and overrides
- [docs/performance.md](docs/performance.md) — Measuring with `LvglPerformanceMonitor`; render-thread caveats
- [docs/migration.md](docs/migration.md) — Adopting from Compose/XML and exiting back
- [template/README.md](template/README.md) — Copyable app template
- [build-logic/README.md](build-logic/README.md) — `com.lvgl.android.app` scaffolding plugin
- [docs/agent-comment-schema.md](docs/agent-comment-schema.md) — Machine-readable comment schema
- [docs/agent-breadcrumbs.json](docs/agent-breadcrumbs.json) — Decision trail for AI agents
- [BUILDING.md](BUILDING.md) — Quick start guide
- [ROADMAP.md](ROADMAP.md) — Development roadmap and progress
- [CHANGELOG.md](CHANGELOG.md) — Release notes
- [docs/release-checklist.md](docs/release-checklist.md) — Release verification checklist
- [PRIVACY.md](PRIVACY.md) — Privacy policy (no personal data collected)
- [SECURITY.md](SECURITY.md) — Vulnerability reporting and supported versions
- [SUPPORT.md](SUPPORT.md) — Support policy and version matrix
- [docs/play-store.md](docs/play-store.md) — Google Play readiness checklist

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for the full text.

### Third-Party Software

This project uses [LVGL](https://lvgl.io) v9.6.0, which is also MIT licensed. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for a complete list of attributions.
