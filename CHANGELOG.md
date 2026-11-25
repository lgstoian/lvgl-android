# Changelog

All notable changes to the LVGL Android Framework are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.2.0] - 2026-11-25

General Android adoption release: the framework is now positioned and
documented for use inside an ordinary Android app, with interop, accessibility
and escape hatches treated as first-class features. VersionCode 3.

### Added

- `HybridSampleActivity` (`com.lvgl.sample`, not exported): a plain
  `AppCompatActivity`-style host that mixes `LvglSurfaceView` with native
  Android views and proves both directions of the bridge — native buttons
  switch LVGL screens and push text via `LvglUi.updateNode()`, and LVGL node
  events reach a native `TextView` through `LvglUi.listener` on the UI thread.
- Accessibility node-provider bridge: `LvglSurfaceView.getAccessibilityNodeProvider()`
  returns an internal provider that mirrors `LvglUi` app screens (ids ≥ 1000)
  as virtual TalkBack nodes, with clickable/checkable/range state, focus and
  progress actions, and events pushed back through `LvglUi.updateNode()`.
  The exact scope and native-view fallback are documented in
  [docs/accessibility.md](docs/accessibility.md).
- `IntegrationGuardTest` with seven fail-closed wiring detectors and canaries:
  `wired-multiwindow-pip`, `wired-gesture-results`, `wired-performance-caller`,
  `wired-launcher-receiver`, `wired-accessibility-announcements`,
  `wired-camera-permission` and `wired-ui-event-bridge`. A feature existing in
  source is no longer treated as evidence that it is actually wired. [BC-037]
- Governance and adoption docs: `docs/interop.md`, `docs/accessibility.md`,
  `docs/performance.md`, `docs/migration.md`, `SECURITY.md` and `SUPPORT.md`;
  CONTRIBUTING, README, quickstart, public API, Play and release-checklist
  documentation updated.
- Camera interop: `LvglCameraPreview.allCameraIds()` enumerates logical and
  physical sensors, and the sample cycles them on tap.

### Changed

- The framework is documented as a **general Android UI framework** with an
  honest trade-off table and pointers to interop and migration. It is no longer
  positioned as embedded-only or as a wholesale replacement for Compose/XML.
- `LvglCameraPreview.selectPreviewSize(cameraId, target)` and the
  `chosenPreviewSize` property choose the available YUV_420_888 size with the
  closest aspect ratio; the native preview overlay is letterboxed/centred to
  that aspect instead of being stretched into a fixed box.
- Supported-version matrix, security reporting and PR/CI expectations are
  documented in `SECURITY.md`, `SUPPORT.md` and `CONTRIBUTING.md`.
- Play Console forms, the store listing and the privacy-policy URL are stated
  explicitly as manual user steps; CI gates cover the project-side artifacts
  only.

### Fixed

- The camera sample's preview no longer stretches or crops: its surface is
  fitted to the camera sensor's aspect ratio, recomputed on camera switch and
  configuration change.
- Features that were implemented but not called are now caught: integration
  guards verify call paths (gesture results, performance sink, launcher
  receiver, accessibility announcements, camera permission and UI event bridge)
  and fail closed on missing sources. [BC-037]

## [1.1.0] - 2026-11-24

Release-artifact integrity: fail-closed gates, a smoke test of the shipped APK,
and an AAB that bundles without ABI splits.

### Added

- Release-artifact verification: CI runs a release APK smoke test and
  `lintVitalRelease`, and the release workflow blocks on the smoke. The dynamic
  harness can run against the release APK, so R8/JNI survival is verified through
  runtime native init. [BC-037]
- Gate integrity canaries, including wiring canaries that prove each detector is
  actually invoked by its guard test rather than merely defined. [BC-014, BC-037]
- `MainActivity` accepts a `SMOKE_SCREEN` intent extra so the dynamic harness can
  reach the eight non-exported sample activities through the one exported entry
  point. [BC-035]
- Animated showcase windows have a visible close button.
- The audio showcase window gains a 12-band equalizer.
- The camera sample's copy is explicit that frames are processed locally and no
  camera imagery is shown on screen.

### Changed

- Toolchain aligned with the target API: AGP is upgraded from 8.2.0 to 8.10.1 and
  the Gradle wrapper from 8.5 to 8.11.1, so compileSdk/targetSdk 36 is officially
  supported and a Gradle check blocks an AGP/SDK mismatch instead of warning
  about it.
- The AAB is built separately from the APKs: `bundleRelease` fails when ABI
  splits and R8 resource shrinking are both enabled, so the bundle step uses
  `-Plvgl.bundleNoSplits=true`. Per-ABI APKs still come from `assembleRelease`;
  Play receives the AAB.

### Fixed

- CI gates no longer pass when their input is missing or malformed. A malformed
  layout-lint report fails `LayoutLintRules` instead of parsing as zero issues,
  and `size-report.ps1` fails when an expected per-ABI release APK is absent.
  [BC-037]
- `PlayComplianceGuardTest` is now a pure detector with canaries that parses and
  checks attribute values instead of only string presence; `HardRuleGuardTest`
  scans every `.kt` file under `app/src/main/java` instead of only
  `*Activity.kt`. [BC-035, BC-037]
- A `?: return` inside `MachineCommentValidationTest` could turn a parse failure
  into a passing test; the parse result is now asserted explicitly. [BC-037]
- Only `MainActivity` is exported in the shipped manifest. A Craft Anything
  inspection of the release APK found nine activities with `exported=true` and
  no intent filters; the eight sample activities are now `exported=false`, so
  they can no longer be launched externally. [BC-035]
- The CI dynamic job no longer fails on system-process noise. The harness's
  crash check matched the Bluetooth stack's `Start Timeout Delay` log and its
  own SIGABRT on the unaccelerated CI emulator; it now fails only on a crash or
  ANR in the sample app, guarded by `DynamicHarnessGuardTest`. [BC-036]
- The CI dynamic job installs the universal debug APK via the harness instead
  of the removed per-ABI `app-debug.apk` path.
- FAB insets are now accounted for, so the button no longer overlaps nearby
  content.

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
- ~1.3 MB per-ABI release APK, ~2.1 MB universal, ~4.13 MB AAB.
- Unit, guard, annotation and instrumented tests, plus GitHub Actions CI.

### Testing

- Unit, guard, annotation and instrumented tests.
- GitHub Actions CI: build, lint, tests, API check, plugin tests, size report and
  an emulator walk.
