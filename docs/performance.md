# Performance measurement and honest caveats

> Applies to v1.3.0. This page explains how to measure LVGL rendering with
> `LvglPerformanceMonitor`, what to expect on real hardware, and where a custom
> render thread has costs. **No measured numbers are claimed here.** The table
> below is a placeholder: measured values will be recorded only after a hardware
> run, and no emulator or estimated figure is presented as a result.

## 1. Measuring with `LvglPerformanceMonitor`

`LvglPerformanceMonitor` is a `object` in `com.lvgl.android.perf`. It logs one
line per second on tag `LvglPerfMon` and exposes the same values programmatically.

```kotlin
import com.lvgl.android.perf.LvglPerformanceMonitor

LvglPerformanceMonitor.start()          // resets counters, starts the window
// ... one call per rendered frame, from a single thread ...
LvglPerformanceMonitor.onFrameRendered()
// ...
val stats = LvglPerformanceMonitor.getStats()
// fps, frameCount, avgFrameTimeMs, minFrameTimeMs, maxFrameTimeMs
LvglPerformanceMonitor.stop()
```

- `getFps()` — frames per second over the last completed one-second window.
- `getFrameCount()` — frames since `start()`.
- `getStats()` — snapshot map with the keys above; frame times are in ms.
- `resetStats()` — clears frame-time statistics but keeps the frame count and
  the running flag.

**Important:** there is no native frame-callback JNI export yet, so nothing
calls `onFrameRendered()` automatically. The caller drives the sink — for
example `GameSampleActivity` calls it from its game loop (the
`wired-performance-caller` integration guard checks that a real caller exists).
If your app renders LVGL without such a loop, add the call to the place where a
frame is actually presented, or treat the monitor as unavailable.

The monitor's state is intentionally **not synchronized**: call every method
from the same thread (normally the LVGL render thread). It is a debugging aid,
not a production profiling tool.

### Measurement command

Run on a physical device, with the release APK (not the debug build), at a
fixed refresh rate and brightness, for at least 60 seconds per screen:

```powershell
# 0. Install the artifact being measured (release, never debug).
& "D:\ASDK\platform-tools\adb.exe" install -r app\build\outputs\apk\release\app-universal-release.apk
& "D:\ASDK\platform-tools\adb.exe" shell am start -n com.lvgl.sample/.MainActivity

# 1. Record the device configuration and the motion values actually polled.
& "D:\ASDK\platform-tools\adb.exe" shell wm size
& "D:\ASDK\platform-tools\adb.exe" shell wm density
& "D:\ASDK\platform-tools\adb.exe" shell settings get global animator_duration_scale
& "D:\ASDK\platform-tools\adb.exe" shell settings get global transition_animation_scale
& "D:\ASDK\platform-tools\adb.exe" shell settings get global window_animation_scale
& "D:\ASDK\platform-tools\adb.exe" logcat -d -s LvglMotionConfig

# 2. Clear old logs, call start() from the app, exercise ONE screen for ~60 s.
& "D:\ASDK\platform-tools\adb.exe" logcat -c
# ... interact with the app for ~60 s ...

# 3. Dump the monitor. Capture one line per completed one-second window.
& "D:\ASDK\platform-tools\adb.exe" logcat -d -s LvglPerfMon
```

Keep the monitor's `start()`/`stop()` calls in the app around the measured
window; `onFrameRendered()` is still driven by the app (for example the game
sample's frame loop), not by a native callback. Capture the logcat line
(`FPS: … | Avg: …ms | Min: …ms | Max: …ms | Total: … frames`) and `getStats()`
if the app surfaces it. For comparison, also capture the optional layout-lint
tag `LVGLLayout` and the engine tag `LvglEngine`. Repeat per screen and note the
device, Android version, ABI, refresh rate and motion scales alongside each row.

## 2. Results — recorded after the hardware run

These cells are deliberately empty. **Measured values will be recorded here
after a hardware run**; do not copy emulator numbers, debug-build logs or
estimates into them, and do not treat a placeholder row as evidence of a
measured result.

| Device / ABI | Android | Screen | FPS (avg) | Frame time avg / min / max (ms) | Notes |
| --- | --- | --- | --- | --- | --- |
| _TBD — physical device, arm64-v8a_ | _TBD_ | Dashboard (0) | _TBD_ | _TBD_ | _TBD_ |
| _TBD — physical device, arm64-v8a_ | _TBD_ | App Demo (1000) | _TBD_ | _TBD_ | _TBD_ |
| _TBD — physical device, arm64-v8a_ | _TBD_ | Game sample (5) | _TBD_ | _TBD_ | `targetFps` = _TBD_ |
| _TBD — physical device, arm64-v8a_ | _TBD_ | Hybrid sample (native overlay) | _TBD_ | _TBD_ | With / without camera overlay |

Do not copy emulator numbers into this table. The CI emulator uses
`swiftshader_indirect` software rendering; its frame times say nothing about a
GPU-backed phone.

## 3. What to expect — qualitatively

- On a normal phone the UI is a single full-screen `SurfaceView` with a
  full-framebuffer copy and a BGRA→RGBA swap in the flush callback. Simple
  Material 3 screens (labels, buttons, cards) are inexpensive; the cost grows
  with overdraw, large animated windows and continuous animations.
- The engine caps `lv_timer_handler()`'s requested period at about 16 ms, so a
  static screen need not redraw every vsync. `LvglGameActivity` can select a
  refresh rate between 30 and 144 Hz on Android 11+; a higher `targetFps`
  raises both work and frame pacing pressure.
- Native views above the surface (camera preview, map, Compose content) are
  composed by Android's window compositor. They do not go through LVGL's frame
  loop and can add latency and GPU work of their own; measure the hybrid/camera
  screen separately.
- Release R8/JNI behaviour is verified by the release smoke test, but frame
  numbers from the debug APK are not transferable.

## 4. Caveats about the custom render thread

This framework does **not** use the platform `Choreographer` to drive LVGL.
That is a deliberate trade-off, not an oversight:

- `lv_timer_handler()` runs on a dedicated native thread with its own period.
  It is **not phase-locked to platform vsync**, so frames can be early or late
  relative to the display's compositor. A stable 60 FPS in the monitor does not
  guarantee perfectly even presentation.
- Frame times reported by the monitor measure the interval between
  `onFrameRendered()` calls, not GPU completion or display latency. There is no
  `Choreographer` frame-timeline integration and no GPU profiler hook.
- Two surfaces (LVGL plus a native overlay) are composited by the platform.
  Overlay updates are outside the monitor's view; measure them with system
  tools (`dumpsys gfxinfo`, Perfetto) if they matter.
- Touch is forwarded from the UI thread to JNI; callbacks return on the LVGL
  thread. Anything touching Android views must hop back to the UI thread
  (`runOnUiThread`), which adds a scheduling hop.
- v1.3.0 motion values are polled from ROM settings and framework durations
  ([motion.md](motion.md)). They affect spring and transition timing, not
  frame pacing, so record the polled scales next to each measurement row.
- The monitor is a debug aid. Do not gate app behaviour on its values or ship
  per-frame logging.

If your app needs precise frame scheduling, platform animation APIs, or deep
GPU profiling, consider using Compose/XML for that screen — see
[migration.md](migration.md). The framework supports a mixed app; it does not
claim to beat the platform toolkit at its own job.
