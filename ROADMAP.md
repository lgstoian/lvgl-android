# Architecture Decision Records (ADRs)

## ADR-001: Use Git Submodule for LVGL Source

**Status**: Accepted

**Context**: LVGL needs to be updated independently from the Android framework. Previous approach (source copy) made version updates difficult and caused duplication.

**Decision**: Use Git submodule (`git submodule add https://github.com/lvgl/lvgl.git lvgl`) pinned to a specific version tag.

**Consequences**:
- Easy version updates: `cd lvgl && git checkout vX.Y.Z`
- No source code duplication
- Clean separation between LVGL and Android port code
- CMake references `../../../../../lvgl` from module cpp directory

## ADR-002: Library Module Architecture

**Status**: Accepted

**Context**: The project was a single app module. Framework code was tightly coupled with sample app code.

**Decision**: Split into `lvgl-android` (reusable library) and `app` (sample). Library compiles as Android AAR.

**Consequences**:
- Clean separation between framework and sample code
- Library can be published as AAR
- App depends on library via `implementation(project(":lvgl-android"))`
- Native code moved to library module

## ADR-003: DIRECT Render Mode

**Status**: Accepted

**Context**: LVGL supports PARTIAL and DIRECT render modes. PARTIAL requires area-based flush calls, which can cause synchronization issues with ANativeWindow swapchain.

**Decision**: Use `LV_DISPLAY_RENDER_MODE_DIRECT` with full-screen framebuffer buffers.

**Consequences**:
- Always renders full frame to ANativeWindow
- Avoids swapchain buffer stale data issues
- Higher memory usage (width × height × 4 bytes × 2 buffers)
- Simpler flush callback (no area-based copying needed)

## ADR-004: Dedicated LVGL Thread

**Status**: Accepted

**Context**: LVGL's `lv_timer_handler()` needs to be called regularly. Calling it on Android UI thread would block.

**Decision**: Dedicated thread running `lv_timer_handler()` every 5ms with condition variable wake.

**Consequences**:
- LVGL UI updates happen without blocking Android UI thread
- Touch events still delivered to UI thread
- ANativeWindow operations happen on LVGL thread
- Thread synchronization via std::mutex

## ADR-005: Pixel Format R/B Swap

**Status**: Accepted (documented)

**Context**: LVGL stores `lv_color32_t` as `{blue, green, red, alpha}` (BGRA). Android `WINDOW_FORMAT_RGBA_8888` expects `{red, green, blue, alpha}` (RGBA).

**Decision**: Swap R and B channels during framebuffer copy in `display_flush_cb()`.

**Consequences**:
- Simple per-pixel channel swap
- Burst copy via std::memcpy in future (SIMD optimization)
- No performance impact for typical UI sizes

## ADR-006: Core-Only Compilation

**Status**: Accepted

**Context**: LVGL include drivers, demos, examples that aren't needed for Android. Compiling everything causes:
- Duplicate main() symbols from demo files
- Unnecessary Bloat (all drivers compiled)
- Link errors on Linux/PC targets

**Decision**: CMakeLists.txt uses GLOB_RECURSE on only `src/core/`, `src/draw/`, `src/font/`, `src/indev/`, `src/layouts/`, `src/misc/`, `src/others/`, `src/osal/`, `src/stdlib/`, `src/themes/`, `src/themes/`, `src/tick/`, `src/widgets/`, `src/image/`, `src/*.c`

**Consequences**:
- Only 2.1 MB of native code compiled
- 16-second build time for full native
- No duplicate symbols
- Could miss new modules in future LVGL versions — need to update CMakeLists.txt on major updates

## ADR-007: Material 3 Theme Integration

**Status**: Accepted

**Context**: Android app users expect Material Design. LVGL's default theme is generic and doesn't match Android guidelines.

**Decision**: Implement `MaterialTheme` in Kotlin that:
- Provides Material 3 color tokens (primary, secondary, surface, onSurface)
- Auto-detects dark/light mode from system configuration
- Exposes `OnThemeChangedListener` callback
- Maps colors to LVGL via `LvglThemeBridge`

**Consequences**:
- Native LVGL theme uses Material 3 color palette
- Theme changes propagate from Android system to LVGL rendering
- `LvglActivity` handles theme changes automatically
- LVGL and Android system chrome share the same color scheme

## ADR-008: JNI Engine Pattern

**Status**: Accepted

**Context**: Previous port used global C++ variables. This is unsafe for multiple instances and hard to manage lifecycle.

**Decision**: Create `Engine` struct in C++ namespace holding all state. Kotlin `LvglEngine` is a singleton that creates/destroys the native engine via JNI.

**Interface**:
```
nativeCreate() → long (Engine*)
nativeDestroy(long)
nativeInitDisplay(handle, surface, w, h, dpi)
nativeDeinitDisplay(handle)
nativeResume(handle)
nativePause(handle)
nativeTouchEvent(handle, action, x, y)
nativeApplyTheme(handle, dark, primary, secondary)
```

**Consequences**:
- Native state is scoped to Engine instance
- Lifecycle management via handle pointer
- Kotlin singleton pattern
- Single display instance per app (sufficient for most use cases)

## ADR-009: Kotlin-First API

**Status**: Accepted

**Context**: LVGL is a C library. App developers write Kotlin/Java.

**Decision**: Provide Kotlin-first APIs in `com.lvgl.android` package:
- `LvglActivity` — base activity
- `LvglSurfaceView` — surface view component
- `LvglEngine` — engine singleton
- `MaterialTheme` — theme manager
- `DpiManager` — DPI adaptation
- `MultiWindowManager` — multiwindow support

**Consequences**:
- App developers write Kotlin code
- Native C++ hidden behind JNI
- DSL planned for declarative UI creation
- All public APIs are Kotlin methods with default parameters

## ADR-010: ADR Location

**Status**: Accepted

**Context**: Architecture decisions need to be documented for future reference.

**Decision**: Store ADRs in `ROADMAP.md` under Architecture Decisions section (and future ADR files in `docs/adr/` if needed).

**Consequences**:
- All decisions tracked in one place with ROADMAP
- Easy to find and update
- Linked from AGENTS.md for discoverability