# Changelog

All notable changes to the LVGL Android Framework are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Notes

- No unreleased changes yet. The first public release is [1.0.0](#100---2026-11-04).

## [1.0.0] - 2026-11-04

The first public release: an Android framework for building native-looking apps
with LVGL, plus a demo app that showcases every app type.

### Framework

- LVGL v9.6.0 as a Git submodule, a reusable `lvgl-android` library module and a
  sample `app` module.
- Process-wide `LvglEngine` singleton with owner-scoped display rebinding and a
  dedicated vsync-aware render thread.
- `LvglActivity` base class with a `LvglSurfaceView` render surface, touch
  input, immersive mode, insets handling and configuration-change support.
- `LvglEngine.initialize()` reports a missing native library through
  `nativeLoadError` instead of crashing.
- App-defined screens declared in Kotlin with the `LvglUi` DSL and rendered by
  the native UI kit (`registerScreen`, `updateNode`, node callbacks, typed
  events).
- `LvglLauncherActivity` with a native app grid and tap-to-launch through
  `LauncherAppListener`.
- `LvglGameActivity` with a vsync-aware frame loop and refresh-rate selection.
- `LvglInputMethodService` for keyboard apps.
- `LvglCameraPreview` Camera2 helper with runtime-permission support.
- `LvglNavigation` screen back-stack helper.
- `LvglPerformanceMonitor` FPS and frame-time reporting.
- `LvglDeviceInfo` and `LvglSystemControl` for the device-aware dashboard and
  its functional backlight/volume panels.
- Accessibility: `LvglAccessibilityManager`, `LvglFocusNavigation`,
  `LvglScreenReader`, `LvglAccessibilityHelper` and `LvglAccessibilityDelegate`.
- Input: `LvglInputProcessor`, `GestureRecognizer`/`Gestures`,
  `HardwareKeyboardHandler` and `LvglSurfaceViewWithGestures`.
- Display and configuration helpers: `DpiManager`, `DisplayMetricsManager` and
  `ConfigurationManager`.
- Multiwindow, picture-in-picture and foldable support via `MultiWindowManager`.

### Theming

- Material 3 tonal palette (`MaterialPalette`) shared between Kotlin and native.
- `MaterialTheme` with dark/light mode, Material You dynamic colours (API 31+)
  and an app-local dark-mode override.
- `LvglThemeBridge` pushes the palette to native, which rebuilds the screen.
- Native Material 3 component kit: buttons (filled/tonal/outlined/text + danger),
  chips, FAB, cards, text field, divider, bottom nav, dialog, snackbar and a
  top app bar.

### Demos

- M5Tab5-style device dashboard as the home screen: a phone outline filled with
  the components actually detected on the device, floating control panels and a
  shared status bar.
- Widget showcase with the full Material 3 component gallery.
- Spring-physics showcase with animated windows (system gauges, sensors,
  quick controls, audio).
- Launcher sample listing the real installed apps and launching them.
- Game sample with a playable tap-the-target game.
- App Demo: a complete screen declared in Kotlin with the `LvglUi` DSL.
- Camera and layout/interactive samples.

### Developer experience

- `docs/quickstart.md` — a five-minute walkthrough.
- `docs/public-api.md` — a reference of every public class.
- `template/` — a standalone Gradle app project using a composite build.
- `com.lvgl.android.app` Gradle plugin (`build-logic/`) that scaffolds a new LVGL
  app module.
- KDoc on the public API and Dokka HTML output.
- `lvgl-android/consumer-rules.pro` — consumer ProGuard/R8 rules.
- `api/lvgl-android.api` — binary-compatibility dump checked by `apiCheck`.
- `tools/dynamic-test.ps1` — emulator harness that walks every screen and fails
  on crashes or layout-lint warnings.
- `tools/size-report.ps1` — release size budget report.
- `tools/bump-version.ps1` — single-source version bump.

### Google Play readiness

- Target API 36, 64-bit ABIs, AAB output and release signing.
- Runtime permissions; only the optional `CAMERA` permission is requested.
- Backup and device-transfer rules (`backup_rules.xml`,
  `data_extraction_rules.xml`).
- Predictive back, locale config and multi-window support.
- `PRIVACY.md` and `docs/play-store.md`.
- In-app open-source licence disclosure.
- `PlayComplianceGuardTest` machine-checks the Play requirements.

### Build and size

- R8 full mode, resource shrinking, per-ABI splits and the static C++ STL.
- ~1.3 MB per-ABI release APK, ~2.1 MB universal, ~3.9 MB AAB.
- Unit, guard, annotation and instrumented tests, plus GitHub Actions CI.

### Testing

- Unit, guard, annotation and instrumented tests.
- GitHub Actions CI: build, lint, tests, API check, plugin tests, size report and
  an emulator walk.
