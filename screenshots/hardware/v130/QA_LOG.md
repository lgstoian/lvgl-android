# FINAL — v1.3.0 hardware log QA

Scope: read-only QA of `screenshots/hardware/v130/logcat.txt` (now 4,012,386 bytes,
30,717 lines, 11-26 10:45:54–11:37:35), cross-checked against `docs/motion.md`,
`docs/performance.md` and the current native source. No product file was changed;
this is the only write.

## Runs in the capture

| Run | App PID | Window | Notes |
| --- | --- | --- | --- |
| 1 | 28863 | 10:45:54–10:49:14 | Full screen walk; camera smoke started 10:48:50 via `am start --ei SMOKE_SCREEN 4`. **Pre-fix build** (no bind-generation log). |
| 2 | 32377 | 11:06:47–11:10:03 | Camera smoke again; `surface bind generation=20` present → **post-fix build**. |
| 3 | 6702 | 11:34:06–11:37:35 | Focused camera / still-capture run (hybrid launched 11:36:29). |

The capture contains **zero DEBUG lines**, so app lifecycle messages written with
`Log.d` (e.g. `HybridSampleActivity.onResume/onPause/onDestroy`) are not observable.

## Requested verification

| # | Check | Result |
| --- | --- | --- |
| 1 | No app-scoped crash / ANR | **PASS** |
| 2 | No `LVGLLayout` warnings | **PASS** (coverage caveat) |
| 3 | Camera aspect source logs | **PASS** (observability caveat) |
| 4 | Motion ROM poll / apply logs | **PASS** |
| 5 | Stale-deinit fix | **PASS on post-fix runs; FAIL in run 1 (pre-fix)** |
| 6 | Still capture logs | **PASS** |
| 7 | Hybrid bridge | **Partial — no app-level lifecycle evidence** |

### 1. Crash / ANR — PASS

- 0 hits for `ANR in com.lvgl.sample`, `>>> com.lvgl.sample <<<`,
  `Process: com.lvgl.sample, PID`, `pid N (com.lvgl.sample)`; 0 `FATAL EXCEPTION`;
  0 `Killing/died/Force finishing` for PIDs 28863/32377/6702.
- Every app `START` returned `START_SUCCESS`; all three PIDs stayed alive for
  their windows. App-scoped E lines below are handled errors, not crashes/ANRs.
- Vendor noise (`E ActivityThread: Failed to find provider info for
  com.zte.intellitext.IntelliTextProvider`, `E Macro-Helper: not in game list`,
  `E Macro-Helper: removeThisFromViewRootImpl viewRoot is null`) is attributed to
  the ROM / game-assist SDK, not app code.

### 2. LVGLLayout — PASS with caveat

- Zero `LVGLLayout` lines across all 30,717 lines. The harness's separate filtered
  dump (`adb logcat -d -s LVGLLayout`) is not in this file, so lint execution
  itself is not evidenced; no warnings are present in the full dump.

### 3. Camera aspect source — PASS with caveat

- Back camera (all runs): `activeArray=4080x3072 cropRegion=none
  outputSize=4080x3072 realAspect=0.7529`; `Display aspect
  source=characteristics display=3072x4080 relativeRotation=90`.
- Front camera (all runs): `activeArray=4608x3456 cropRegion=none
  outputSize=4608x3456 realAspect=0.7500`; `source=characteristics
  display=3456x4608 relativeRotation=270`.
- Consumer path evidenced by `CameraSampleActivity: Real camera display
  aspect=0.7529412 / 0.75` and the letterboxed `Preview view … px` lines;
  tap-to-cycle `Camera opened: 0 → 1 → 0` with `Preview started on N` in runs 1–3.
- **Caveat:** zero `source=capture` lines. `publishAspect()` dedupes identical
  aspect/size (LvglCameraPreview.kt:379-395), so when the streaming crop equals
  the active array no capture-callback line is emitted. The log proves the
  characteristics path and listener consumption, not that a CaptureResult
  crop was observed.

### 4. Motion ROM poll / apply — PASS

- 8 `LvglMotionConfig` INFO lines: two identical polls in run 1 (10:48:50.706,
  10:48:50.831) and two in run 2 (11:09:44.065, 11:09:44.137). Run 3 has no
  activity-create window in the log, hence no poll lines.
- Polled: `friction=0.015 minFling=150 maxFling=24000 touchSlop=24
  tapTimeout=100ms longPress=500ms animatorScale=1.0 transitionScale=1.0
  windowScale=1.0 short=200ms medium=400ms long=500ms
  fastOutSlowInMid=0.7751051` — all four sources named in `docs/motion.md`.
- Derived: `scrollThrow=2% scrollLimit=24 gestureMinVelocity=3 decay=0.98
  zeta=0.7248949`, springs `2741.5564/75.91081@200ms`,
  `685.3891/37.955406@400ms`, `438.64908/30.364325@500ms`.
- Native receipt (`ROM motion received`, 4 lines) and application
  (`LVGL motion applied: scrollThrow=2% scrollLimit=24 gestureMinVelocity=3
  decay=0.980`, run 1 at 10:48:50.956) confirmed. No application line in run 2
  because that run only rebound the surface (no display rebuild). Mapping
  friction 0.015 → 2% matches `scroll_throw_from_friction()`.

### 5. Stale-deinit fix — historical FAIL, current PASS

- **Run 1 (pre-fix) FAIL:** `nativeSetScreen: 4` at 10:48:50.834/874 →
  `nativeInitDisplay: 1080x2216` 10:48:50.938 → `LVGL initialised, screen=4`
  10:48:50.973 → `nativeDeinitDisplay` + `LVGL surface released` at
  **10:48:51.050**, i.e. a surface release 77 ms *after* the screen was
  initialised on the camera transition. This is exactly the invariant under
  review; it fails for that build.
- **Run 2 (post-fix) PASS:** `nativeInitDisplay: 1080x2392` +
  `surface bind generation=20` + `display surface rebound 1080x2392` at
  11:09:44.230, followed by `Camera opened: 0` (11:09:44.307) and
  `Preview started on 0` (11:09:44.323). **No** `nativeDeinitDisplay`, no
  `LVGL surface released` anywhere in run 2 or run 3 (whole-file counts: 1 each,
  both from run 1). Current source guards the stale callback at
  lvgl_port.cpp:883-898 (logs `nativeDeinitDisplay ignored: newer surface
  already bound (gen=…)`); that ignore line did not occur because the Kotlin
  owner check suppressed the call entirely.
- **New app-scoped error cluster in run 2 (not a crash):** between 11:09:44.401
  and 11:09:44.522 (8 frames), the render thread logged
  `E BufferQueueProducer: … SurfaceView[…MainActivity]#37 … dequeueBuffer:
  BufferQueue has been abandoned`, `E Surface : dequeueBuffer failed (No such
  device)` and `E LVGLPort: ANativeWindow_lock failed` — 8 of each, against the
  outgoing surface after the new bind. The flush handler sets an error and
  returns (`lvgl_port.cpp:256-259`); preview still started and no crash/ANR
  followed. Transient, handled, but not clean.

### 6. Still capture — PASS

All requested fields present in run 3:
- 11:37:19.905 `I LvglCameraPreview: Still capture requested camera=0
  jpegOrientation=90`
- 11:37:20.207 `I LvglCameraPreview: Still captured camera=0 jpeg=4080x3072
  bytes=1780810 jpegOrientation=90`
- 11:37:20.295 `I CameraSampleActivity: Saved still
  /storage/emulated/0/Android/data/com.lvgl.sample/files/Pictures/IMG_20261126_113720_247.jpg
  bytes=1780810 rotation=90 jpeg=4080x3072 count=1 camera=0`
Capture requested, saved path, byte count (consistent 1,780,810 in both lines),
rotation (90 in all three), and count (1) are all logged; no capture error line.

### 7. Hybrid bridge — partial

- `HybridSampleActivity` launched with `START_SUCCESS` and reached foreground in
  all three runs: 10:48:15.706, 11:09:08.601, 11:36:29.004.
- No app-tag lifecycle/bind/unbind or `Re-asserting app screen` lines: those use
  `Log.d` and the capture has zero DEBUG lines. Bridge internals cannot be
  verified from this log; only the system transition is visible.
- Only hybrid-related error: `E BufferQueueProducer: … HybridSampleActivity#37
  … disconnect: not connected (req=2)` at 10:48:33.750 on leaving hybrid in run
  1 (benign teardown double-disconnect). Runs 2 and 3 show no hybrid errors.

## Other observations

- `W Choreographer: Already have a pending vsync event` (3×) and
  `Skipped 35/41/25/26 frames! … too much work on its main thread` on camera
  switches — main-thread jank during transitions, no ANR; informational.
- 2 `disconnect: not connected (req=2)` and 3 `E DisplayEventDispatcher` lines
  in app PIDs — transient framework/surface noise; informational.
- No `LvglPerfMon` lines anywhere: no FPS/frame-time measurements; the results
  table in `docs/performance.md` correctly remains unmeasured.

## Severity summary

| Severity | Count | Items |
| --- | --- | --- |
| Critical / High | 0 | — |
| Medium | 0 | — |
| Low | 4 | (1) 8× `ANativeWindow_lock failed` / abandoned-buffer cluster on the 11:09 transition; (2) historical pre-fix stale-deinit surface release in run 1; (3) `source=capture` never evidenced; (4) hybrid bridge lifecycle not evidenced (DEBUG stripped). |
| Informational | 4 | No crash/ANR; no layout warnings (lint run not evidenced); still-capture PASS; no perf numbers + vendor/framework noise. |

## FAILs

- **Open FAIL on the current (post-fix) build: none.** All seven requested
  checks pass, except the two coverage items recorded as Low (capture-crop and
  hybrid lifecycle evidence).
- **Historical FAIL (run 1, pre-fix): stale deinit released the LVGL surface
  77 ms after `LVGL initialised, screen=4` on the camera transition.** Fixed in
  runs 2 and 3; no surface release after init in either.
- One new app-scoped ERROR cluster (8× abandoned-buffer / `ANativeWindow_lock
  failed` at 11:09:44.401–.522) is reported as Low, not a FAIL: it was handled,
  preview started, and no crash/ANR occurred.
