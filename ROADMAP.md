# LVGL Android Framework — Development Roadmap

> Last updated: 2026-11-04
> Current Phase: Phase 23 — Google Play compatibility

## Overview

This roadmap tracks planned improvements for the LVGL Android Framework. All phases are executed in order from top to bottom. No phases are skipped.

**Hard Rules:**
- No duplication: Single source of truth for each component
- No feature removals: All existing features preserved and enhanced
- All app types: Activities, IME, launchers, games, camera
- Material theme: Full Material 3 implementation
- DPI adaptation: Dynamic scaling and orientation handling
- Multiwindow: Full multiwindow + foldable + PiP support
- LVGL version updates via Git submodule

## Completed Phases

### Phase 1: Project Foundation ✅
- LVGL v9.6.0 as Git submodule
- Library module (`lvgl-android`) + app module
- Engine architecture (Kotlin singleton + native handle)
- CMake build with core-only compilation

### Phase 2: Core Framework ✅
- `LvglEngine` singleton with native create/destroy/initdisplay
- `LvglSurfaceView` with lifecycle awareness
- `LvglActivity` base class
- Touch input handled through JNI
- Theme bridge for sync

### Phase 3: Material Design Theme ✅
- `MaterialTheme` with Material 3 color tokens
- Dark/light mode detection from system
- `LvglThemeBridge` applying colors to LVGL

### Phase 4: Display & Configuration ✅
- `DpiManager` for dp/px/sp conversion
- `DisplayMetricsManager` for density info
- `ConfigurationManager` for configuration change events
- `MultiWindowManager` for multiwindow/PiP/foldable support

### Phase 5: Specialized App Types ✅
- `LvglInputMethodService` for keyboard apps
- `LvglLauncherActivity` for launcher apps
- `LvglGameActivity` for games (vsync, immersive mode)
- `LvglCameraPreview` for camera integration

### Phase 6: Developer Experience ✅
- JNI function naming convention documented
- Engine bridge pattern fully connected
- Build instructions with working commands

### Phase 7: Sample Application & Verification ✅
- App builds, installs, runs on emulator
- Screenshot captured and analyzed (rendering correct)
- Emulator startup documented

### Phase 8: Documentation Overhaul ✅
- README.md rewritten with current architecture
- AGENTS.md updated with current conventions and full troubleshooting table
- ROADMAP.md created with all planned phases
- ai-context.md maintained with all quirks
- BUILDING.md quick start guide updated

### Phase 9: Demo Application ✅
- Comprehensive demo with widget showcase, layout showcase, interactive demo
- `LvglNavigation` helper for screen transitions
- `nativeLoadDemo` JNI function for switching LVGL content
- Fixed rendering: demo activities no longer call `setContentView()` (which replaced LVGL SurfaceView)

### Phase 10: Enhanced Input ✅
- `GestureRecognizer` with tap, long-press, swipe, pinch detection
- `HardwareKeyboardHandler` for D-pad, Enter, Tab, etc.
- `LvglInputProcessor` combining multi-touch and gesture recognition
- `LvglSurfaceViewWithGestures` drop-in replacement

### Phase 11: Accessibility ✅
- `LvglAccessibilityManager` with TalkBack support
- `LvglFocusNavigation` for D-pad/keyboard focus
- `LvglScreenReader` for announcing LVGL focus changes
- Accessibility service config in `res/xml`

### Phase 12: Additional Sample Apps ✅
- `GameSampleActivity` demonstrating `LvglGameActivity`
- `LauncherSampleActivity` demonstrating `LvglLauncherActivity`
- `CameraSampleActivity` demonstrating `LvglCameraPreview`
- `MainActivity` menu linking all demos

### Phase 13: Testing & CI ✅
- Unit tests: `DpiManagerTest`, `LvglNavigationTest`, `GestureLogicTest`
- Guard tests: `HardRuleGuardTest`, `GuardRules`, `GuardCanaryTest`
- Annotation tests: `MachineCommentParser`, `SourceComments`, `AnnotationValidator`,
  `MachineCommentValidationTest`
- Instrumented tests: `LvglActivityTest`, `LvglEngineLifecycleInstrumentedTest`
- GitHub Actions CI workflow in `.github/workflows/ci.yml`
- Build verified passing for debug and release

### Phase 14: Multi-ABI & Performance ✅
- `LvglPerformanceMonitor` for FPS, frame time tracking
- CI workflow expanded with release builds
- Multi-ABI: arm64-v8a + x86_64 both build and package; armeabi-v7a blocked by NDK r26b + CMake 3.22.1

### Phase 15: M5Tab5-Style Device Dashboard ✅
- Home screen is now an Android system dashboard (screen id 0): full-screen
  schematic drawn with LVGL primitives, floating control panels, status bar
- Schematic chips describe a generic Android phone (Snapdragon SoC, AMOLED,
  LPDDR5X, UFS 4.0, Wi-Fi 7, 5G modem, PMIC, USB-C, IMU, NFC, camera, audio)
- Functional backlight + speaker volume panels drive real Android APIs via the
  ScreenNavigator bridge (`LvglSystemControl`)
- All seven samples remain reachable from the Demos panel
- Every screen has the shared status bar (Wi-Fi, live clock, battery) and the
  app runs immersive so it does not duplicate the Android system bar
- Showcase restyled to the same palette; windows open responsive to the screen
- Fixed: short taps dropped between LVGL polls on slow frames; navigator lost
  after returning from a child activity

### Phase 16: Beautiful-by-Default UI System ✅
- `UiTokens` design tokens (spacing, radius, type) are the single source of
  truth for every default style
- `ui_make_scaffold` (status bar + header + scrollable body) is used by every
  sample screen, so new screens are correctly inset and themed by construction
- Overlap-proof layout primitives: `ui_make_row`, `ui_make_column`,
  `ui_make_grid`, `ui_make_card`, `ui_make_section`, `ui_make_list_row`
- `ui_fit_label` shrinks text to fit its box instead of clipping
- Native layout lint reports overlapping siblings, out-of-bounds children and
  clipped text to logcat (`LVGLLayout`) and to a JSON report
  (`LvglEngine.dumpLayoutReport()`)
- `LayoutLintRules` + `LayoutLintGuardTest` validate the report (with canaries);
  the dynamic harness fails when any lint warning appears

### Phase 17: Device-Aware Dashboard + Window Polish ✅
- The home map is now a phone outline filled with the components actually
  detected on the device (SoC, RAM, storage, display, cameras, sensors)
- `LvglDeviceInfo` collects Build / MemoryInfo / StatFs / DisplayMetrics /
  CameraManager / SensorManager facts and maps platform ids to marketing names
- Showcase windows use an opaque scrim and fuller content (gauges, four stat
  rows, two progress bars)
- Fixed: the window backdrop opacity was overridden by `apply_anim_values`, so
  launcher tiles showed through
- Fixed: chip labels spread to the chip edges; map chips now position their two
  lines explicitly

### Phase 18: Material 3 Palette & Component Kit ✅
- `MaterialPalette` defines the 20 Material 3 tonal roles in a fixed index order
  shared with the native `MaterialRole` enum; `MaterialTheme` resolves the
  baseline scheme or Material You dynamic color (API 31+) and follows the system
  dark/light mode (BC-029)
- Native component kit: `ui_make_button` (filled/tonal/outlined/text + danger),
  `ui_make_chip`, `ui_make_fab`, `ui_make_m3_card`, `ui_make_text_field`,
  `ui_make_divider`, `ui_make_bottom_nav`, `ui_make_dialog`, `ui_make_snackbar`
  and `ui_make_app_bar` (BC-030)
- The dashboard uses M3 buttons, chips and a theme FAB; the widget showcase has a
  `MATERIAL 3` gallery demonstrating every component
- The showcase follows the palette and uses the M3 app bar

### Phase 19: APK Size Budget ✅
- Release: R8 full mode, resource shrinking, per-ABI splits, static C++ STL,
  legacy native packaging and `resConfigs("en")`
- Per-ABI APK: 8.4 MB → ~1.3 MB; universal APK: ~2.1 MB
- `tools/size-report.ps1` enforces 4 MB per ABI / 6 MB universal in CI (BC-031)
- Build artifacts removed from git; `.gitignore` covers `build/`, `.gradle/`,
  `.cxx/` and `screenshots/dynamic/`

### Phase 20: Developer Toolkit & Release Prep ✅
- `docs/quickstart.md`, `docs/public-api.md`, `template/` (copyable standalone
  app) and the `com.lvgl.android.app` Gradle plugin (`build-logic/`) that
  scaffolds a new module (BC-032)
- `lvgl-android/consumer-rules.pro` keeps the JNI entry points and public API
- `lvgl-android/api/lvgl-android.api` + `:lvgl-android:apiCheck` gate API changes
- `maven-publish` coordinates `com.lvgl.android:lvgl-android:1.0.0`
- `CHANGELOG.md`, `docs/release-checklist.md`, `tools/bump-version.ps1`
- Release workflow: signed per-ABI APKs + AAB + `SHA256SUMS.txt`

### Phase 21: Kotlin UI Builder & SDK Docs ✅
- `LvglUi` DSL: apps declare their own screens in Kotlin (`column`, `row`,
  `label`, `button`, `slider`, `toggle`, `checkbox`, `progress`, `arc`,
  `divider`, `spacer`), serialised to JSON and rendered by the native UI kit
- Node callbacks and `LvglUi.listener` deliver taps/value changes as typed
  `LvglUiEvent`s; `LvglUi.updateNode()` patches a node from any thread
- App screen ids start at `APP_SCREEN_BASE` (1000); `nativeSetScreen` accepts them
- `AppDemoActivity` + `AppDemoScreen` demonstrate the builder end to end
- KDoc on the whole public API and Dokka HTML output (`:lvgl-android:dokkaHtml`)
- Standard KDoc tags are accepted by the machine-comment schema (BC-034)
- `LvglEngine.initialize()` reports a missing native library through
  `nativeLoadError` instead of crashing

### Phase 22: Showcase Window Layout & Launcher Grid ✅
- Fixed the showcase window content: it is now top-aligned with a consistent
  12 dp gap instead of vertically centred (which left a large empty area and let
  tall content overflow the header)
- Fixed the System window gauges: the two columns now share the row equally and
  the arcs are sized to fit, so they no longer overlap or spill outside the window
- Fixed the Sensors window: the bars grow to fill the row, so the sensor names
  no longer sit on top of the bars
- Launcher demo: the installed apps are collected on a background thread and
  rendered as a native scrollable grid; tapping an app launches it through a new
  `LauncherAppListener` bridge
- `LvglUi` dropdown node (options + selection callback) and the matching
  `LvglUiEvent.Selected` event

### Phase 23: Google Play Compatibility ✅
- Target API 36 (the Play requirement from 31 August 2026), 64-bit ABIs, AAB
  output and release signing.
- Manifest: `dataExtractionRules`, `fullBackupContent`, `localeConfig`,
  `enableOnBackInvokedCallback`, `resizeableActivity`, and a `LAUNCHER` package
  visibility query.
- Only the optional `CAMERA` permission is requested, at runtime, and the app is
  fully usable without it.
- `LicensesActivity` discloses the LVGL / AndroidX / Kotlin licences in-app.
- `PRIVACY.md` and `docs/play-store.md` document the privacy policy and the
  Play release checklist.
- `PlayComplianceGuardTest` machine-checks the target API, ABIs, manifest
  attributes, permission set, backup resources, privacy policy and licence
  disclosure.

---

## Progress Tracking

| Phase | Status | Started | Completed |
|-------|--------|---------|-----------|
| Phase 1: Project Foundation | ✅ Complete | 2026-10-16 | 2026-10-16 |
| Phase 2: Core Framework | ✅ Complete | 2026-10-16 | 2026-10-16 |
| Phase 3: Material Design Theme | ✅ Complete | 2026-10-16 | 2026-10-16 |
| Phase 4: Display & Configuration | ✅ Complete | 2026-10-16 | 2026-10-16 |
| Phase 5: Specialized App Types | ✅ Complete | 2026-10-16 | 2026-10-16 |
| Phase 6: Developer Experience | ✅ Complete | 2026-10-16 | 2026-10-16 |
| Phase 7: Sample App & Verification | ✅ Complete | 2026-10-16 | 2026-10-16 |
| Phase 8: Documentation Overhaul | ✅ Complete | 2026-10-16 | 2026-10-16 |
| Phase 9: Demo Application | ✅ Complete | 2026-10-16 | 2026-10-16 |
| Phase 10: Enhanced Input | ✅ Complete | 2026-10-16 | 2026-10-16 |
| Phase 11: Accessibility | ✅ Complete | 2026-10-16 | 2026-10-16 |
| Phase 12: Additional Sample Apps | ✅ Complete | 2026-10-16 | 2026-10-16 |
| Phase 13: Testing & CI | ✅ Complete | 2026-10-16 | 2026-10-16 |
| Phase 14: Multi-ABI & Performance | ✅ Complete | 2026-10-16 | 2026-10-16 |
| Phase 15: M5Tab5-Style Device Dashboard | ✅ Complete | 2026-11-03 | 2026-11-03 |
| Phase 16: Beautiful-by-Default UI System | ✅ Complete | 2026-11-04 | 2026-11-04 |
| Phase 17: Device-Aware Dashboard + Window Polish | ✅ Complete | 2026-11-04 | 2026-11-04 |
| Phase 18: Material 3 Palette & Component Kit | ✅ Complete | 2026-11-04 | 2026-11-04 |
| Phase 19: APK Size Budget | ✅ Complete | 2026-11-04 | 2026-11-04 |
| Phase 20: Developer Toolkit & Release Prep | ✅ Complete | 2026-11-04 | 2026-11-04 |
| Phase 21: Kotlin UI Builder & SDK Docs | ✅ Complete | 2026-11-04 | 2026-11-04 |
| Phase 22: Showcase Window Layout & Launcher Grid | ✅ Complete | 2026-11-04 | 2026-11-04 |
| Phase 23: Google Play Compatibility | ✅ Complete | 2026-11-04 | 2026-11-04 |

## Architecture Decision Records

### ADR-001: Use Git Submodule for LVGL Source

**Status**: Accepted

**Context**: LVGL needs to be updated independently from the Android framework.

**Decision**: Use Git submodule pinned to a specific version tag.

### ADR-002: Library Module Architecture

**Status**: Accepted

**Context**: Framework code should be reusable.

**Decision**: `lvgl-android` library module + `app` sample module.

### ADR-003: DIRECT Render Mode

**Status**: Accepted

**Context**: ANativeWindow swapchain issues with partial rendering.

**Decision**: Full-screen framebuffer with DIRECT render mode.

### ADR-004: Dedicated LVGL Thread

**Status**: Accepted

**Context**: LVGL timer handler needs regular calls without blocking UI thread.

**Decision**: Dedicated thread at 5ms intervals with condition variable.

### ADR-005: R/B Pixel Swap

**Status**: Accepted

**Context**: LVGL BGRA vs Android RGBA format mismatch.

**Decision**: Per-pixel channel swap in flush callback.

### ADR-006: Core-Only Compilation

**Status**: Accepted

**Context**: Drivers/demos/examples cause symbol conflicts and bloat.

**Decision**: CMakeLists.txt compiles only core modules.

### ADR-007: Material 3 Theme Integration

**Status**: Accepted

**Context**: Users expect Material Design.

**Decision**: `MaterialTheme` with Material 3 color tokens synced to LVGL.

### ADR-008: JNI Engine Pattern

**Status**: Accepted

**Context**: Global C++ variables unsafe for multiple instances.

**Decision**: Engine struct with handle-based JNI interface.

### ADR-009: Kotlin-First API

**Status**: Accepted

**Context**: Developers write Kotlin, not C++.

**Decision**: Kotlin APIs in `com.lvgl.android` package.

### ADR-010: ADR Location

**Status**: Accepted

**Context**: Decisions need documentation.

**Decision**: Stored in ROADMAP.md.

### ADR-011: Machine-Readable Comment Schema

**Status**: Accepted

**Context**: AI agents and tools need structured comments, not prose, to read the
intent behind non-obvious code.

**Decision**: Source files carry `@tag key=value` annotations (guard-rule,
invariant, decision, breadcrumb, machine-json, contract, ai-hint, why, do-not,
trap). `MachineCommentValidationTest` parses and validates every annotation in
the repo. See `docs/agent-comment-schema.md`.

### ADR-012: Breadcrumb Trail for AI Agents

**Status**: Accepted

**Context**: Non-obvious decisions were scattered across markdown, so an agent
could not follow the trail without reading every file.

**Decision**: `docs/agent-breadcrumbs.json` is the machine-readable decision
trail. Each entry has an id, decision, rationale, consequences, files, symbols,
see-also ADRs, do-not list and an AI hint. Source files link to entries via
`@breadcrumb BC-NNN`; every entry must be referenced at least once.

### ADR-013: Mutation-Style Canary Tests

**Status**: Accepted

**Context**: A guard test once passed while checking nothing (a broken repo-root
path made `exists()` false), so green did not mean safe.

**Decision**: Guard detection lives in `GuardRules` and `GuardCanaryTest` feeds
each detector a broken fixture that must be detected (no false green) and a
clean fixture that must not be (no false positive). See `BC-014`.

### ADR-014: Material 3 Component Kit

**Status**: Accepted

**Context**: Apps should look and behave natively while staying pure LVGL, but
hand-rolled widgets drifted from Material and did not follow the theme.

**Decision**: A native component kit renders Material 3 from the tonal palette;
every component reads `ui_palette()` so a whole app re-themes by construction.
See `BC-030`.

### ADR-015: Balanced APK Size Budget

**Status**: Accepted

**Context**: The unminified universal release was 8.4 MB, which is too large for
a "small, efficient" framework.

**Decision**: Enable R8 full mode, resource shrinking, per-ABI splits, the static
C++ STL, legacy native packaging and English-only resources. Budget: 4 MB per
ABI, 6 MB universal, enforced by `tools/size-report.ps1` in CI. See `BC-031`.

### ADR-016: Developer Toolkit

**Status**: Accepted

**Context**: Starting a new app should take minutes, not a copy of the sample.

**Decision**: Ship a quickstart, a public API reference, a copyable `template/`, a
`com.lvgl.android.app` Gradle plugin, consumer ProGuard rules and a
binary-compatibility API dump. See `BC-032`.

### ADR-017: Kotlin UI Builder

**Status**: Accepted

**Context**: The public API was screen-level only — an app could pick one of eight
built-in screens but could not build its own UI, which made the framework
unsuitable for real apps.

**Decision**: Apps declare screens with the `LvglUi` Kotlin DSL. The tree is
serialised to JSON, rendered by the existing native UI kit (so there is still one
source of truth for widget styling), and interactions return as typed events.
App screen ids start at 1000. See `BC-033`.

## Change Log

- **2026-10-16**: Roadmap created. Phases 1-8 marked complete.
- **2026-10-16**: Phases 9-14 complete. Rendering fixed. Performance monitor, CI, unit tests added.
- **2026-10-31**: Added ADR-011..013: machine-readable comment schema, breadcrumb trail, and canary tests (BC-014/015).
- **2026-11-03**: Phase 15 — M5Tab5-style device dashboard replaces the list menu as
  the home screen; full app restyle; functional backlight/volume panels
  (BC-020..025).
- **2026-11-04**: Phase 16 — design tokens, screen scaffold, overlap-proof layout
  primitives, text auto-fit and the layout lint with CI reporting (BC-027).
- **2026-11-04**: Phase 17 — the dashboard map reflects the real device and the
  showcase windows use an opaque scrim with fuller content (BC-028).
- **2026-11-04**: Phase 18 — Material 3 tonal palette with dynamic color and a
  native Material 3 component kit (BC-029/030).
- **2026-11-04**: Phase 19 — balanced size optimisation: ~1.3 MB per ABI with a CI
  size budget (BC-031).
- **2026-11-04**: Phase 20 — developer toolkit (quickstart, public API, template,
  Gradle plugin, API dump, publishing, release workflow) (BC-032).
- **2026-11-04**: Phase 21 — Kotlin UI builder (`LvglUi`), app-defined screens,
  KDoc + Dokka, and the `AppDemoActivity` demo (BC-033/034).
- **2026-11-04**: Phase 22 — showcase window content top-aligned, gauge/sensor
  overlaps fixed, and a real launcher app grid with tap-to-launch.
- **2026-11-04**: Phase 23 — Google Play compatibility: target API 36, backup
  rules, predictive back, locale config, privacy policy and licence disclosure
  (BC-035).
