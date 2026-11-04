# Plan: Animated Demo Showcase + Spring Animation Toolkit

> **Historical plan.** This document is the original design for the animation
> engine and the showcase demo. All phases are complete; see [ROADMAP.md](../../ROADMAP.md)
> for the current status and [docs/quickstart.md](../../docs/quickstart.md) for
> how to use the result.

## Goal

Build a visually impressive, highly interactive demo for the Android LVGL toolkit,
inspired by the M5Tab5-UserDemo, that doubles as a research vehicle. The demo
exercises (and stress-tests) the toolkit; discoveries feed back into library
improvements. Result: both a cool demo **and** a meaningfully better android-lvgl.

The M5Tab5 "feel" comes from spring physics + easing layered on LVGL, not from
static widgets. We replicate that with a reusable C++ animation engine.

## Approach: Library + app

- **Library**: spring/easing animation engine lives in `lvgl-android` as reusable
  C++ (`lvgl_anim.h/.cpp`), ticked on the existing LVGL render thread.
- **App**: a separate `:demo` Gradle module (clean separation from the sample
  `:app`) that showcases the engine and grows as the research vehicle.

## Phases

### Phase 1 — Animation engine (reusable library) [DONE]
`lvgl_android::lvgl_anim`:
- `SpringValue`  — scalar spring (stiffness/damping/mass), semi-implicit Euler
- `SpringPoint`  — 2D spring for positions (launcher icon physics)
- `SpringColor`  — per-channel spring for animated theme/color transitions
- `Engine`       — registry ticked once per frame; tracks real wall-clock dt
- Easing helpers (cubic back-out, elastic, bounce) for entrance effects

Integration: `Engine::update(dt)` called from `lvgl_thread_func` right after
`lv_timer_handler()` so all object mutation stays on the LVGL thread.

### Phase 2 — Showcase content (the demo)
`DemoShowcaseActivity` + `demo_showcase.cpp`:
1. **Animated launcher** — grid of app tiles; staggered spring entrance on load;
   tap bounce + scale; smooth vertical scroll (fling + deceleration).
2. **NumberFlow** — animated digit counting (score, timer).
3. **Animated controls** — toggles/knobs/sliders with spring thumb, animated
   color/theme transitions.
4. **Sub-screen transitions** — spring-based push/pop between showcase panels.

### Phase 3 — Research discoveries → library fixes
As the demo stresses the toolkit, fold improvements back:
- Touch/gesture refinements (fling velocity, edge bounce)
- Animation-driven theming API
- New widgets proven in the demo become reusable library widgets
- Performance findings (batch invalidation, reduce per-frame allocs)

## Milestones

- [x] Engine compiles and ticks on the LVGL thread
- [x] Launcher grid renders with spring entrance + tap bounce
- [x] Smooth scroll with fling physics
- [x] NumberFlow + animated controls
- [x] Sub-screen transitions
- [x] Build green + demo installs and runs on AVD
- [x] Document findings; fold stable pieces back into library

## Out of scope (for now)

- Real app content / third-party integrations
- Fold-back of every experiment — only the stable, reusable ones
