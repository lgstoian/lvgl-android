# Final Visual QA — capture v3 (uiautomator ANR dismissal)

**Scope:** All 14 PNGs in `screenshots/final/` (recaptured 2026-11-24 17:22:14–17:28:58) and the new `logcat.txt`.
**Method:** Every PNG reopened and visually inspected from disk with the image reader. No files modified except this report. This overwrites the v2 report.
**Verdict: PASS — 14 PASS / 0 FAIL.**

The ANR dialog problem is resolved. No frame contains an ANR / "System UI isn't responding" / "Digital Wellbeing isn't responding" overlay. There are no black, blank, or greyed-out frames; all screens render their full content with readable, non-overlapping labels. All eight specific proof requirements are met.

## Per-file verdicts

| File | Verdict | What I actually see | Suggested owning file |
|---|---|---|---|
| `01_dashboard.png` | **PASS** | Clean, undimmed dashboard. "DEVICE MAP" with live values: Android 16 (API 36), `sdk_gphone64_x86_64`, 440 dpi, Display 1080 x 2340, Camera 2, Modem 5G, WiFi/BT, Battery Li-Po, Sensors 18, Audio I2S, USB-C USB 3.2, SoC "Virtual SoC", Memory 2 GB, Storage 6 GB. LCD 80, speaker volume 60, POWER OFF, "SLEEP & TOUCH TO WAKEUP". Refresh FAB at bottom-right, **no overlap with any button**. No ANR dialog, no clipped text. | — |
| `02_widget_showcase.png` | **PASS** | Clean. "Widget Showcase", BASIC CONTROLS (Button "Tap me", Switch on, Checkbox "Enabled"), VALUES (Slider, Progress, Arc), SELECTION (Dropdown "Option A"), MATERIAL 3 chips "Filled / Tonal / Outlined", footer "Drag the slider, switch or arc". All labels readable. (The bottom MATERIAL 3 card continues below the fold as expected for a scroll list.) | — |
| `03_layout_showcase.png` | **PASS** | Clean. "Layout Showcase", "Flex row-wrap container", six tiles A–F in a row-wrap layout with white lettering on blue/green/pink/orange/purple/teal, caption "Tiles wrap with LV_FLEX_FLOW_ROW_WRAP". No overlay, no clipping. | — |
| `04_interactive_demo.png` | **PASS** | Clean. "Interactive Demo", "Touch anywhere to move the marker", footer "The marker follows your finger". **A pink circular marker is clearly visible near the centre** of the content area. Interactivity is visually proven. | — |
| `05_camera_sample.png` | **PASS** | Clean. "Camera2 Preview", "Camera status: opened camera 0", DETAILS: Rendering "LVGL native", Thread "Dedicated", Refresh "Vsync-aware", Preview **"not rendered (frames processed locally)"**. Footer: **"Frames are processed locally; camera imagery is not rendered on this surface"**. Honest copy intact, no preview falsely shown. | — |
| `06_game_sample.png` | **PASS** | Clean. "Game Sample", "Tap the Target", **Score: 0**, blue circular target near the top-left, footer "Tap the circle to score". Correct pre-tap baseline for comparison with `12`. | — |
| `07_launcher_sample.png` | **PASS** | Clean. "Launcher Sample" with a long list of **installed apps**: Ad Privacy, Android Accessibility Suite, Android Auto, Android Easter Egg, Android Services Library, Android System, Blocked Numbers Storage, Bluetooth, Calendar, Calendar Storage, Call Log Backup/Restore, Camera, CameraExtensionsProxy, Carrier Communications, Cell Broadcast Service. No "Loading installed apps…". Footer "Tap an app to launch it". | — |
| `08_demo_showcase.png` | **PASS** | Clean. "Showcase" with 2×2 tiles Audio, System, Sensors, Config, icons and labels fully readable. | — |
| `09_app_demo.png` | **PASS** | Clean. "App Demo", "Built in Kotlin", "This screen is declared with LvglUi and rendered by the native UI kit.", "Button / Taps: 0 / Tap me", "Slider / Value: 40", "Toggles" switch, "Enable notifications", Dropdown "Option A", "Selected: 1", "Gauges" below the fold. | — |
| `10_showcase_window.png` | **PASS** | Clean. **Audio window is open** on a dark scrim. Header "Audio" with a **visible purple circular X close button in the top-right**. Body: "Audio visualiser", **12 vertical level bars**, and legend **"Live levels: low / mid / high"**. All three proof requirements met; no blank card. | — |
| `11_showcase_closed.png` | **PASS** | Clean. The window is gone and the 2×2 **Showcase tile grid is visible again** (Audio, System, Sensors, Config), matching `08` — the close action is proven by the contrast with `10`. | — |
| `12_game_tapped.png` | **PASS** | Clean. "Game Sample", **Score: 1**, and the blue target has **relocated** from the top-left position in `06` to roughly the centre-right of the play area. Both proof requirements met. | — |
| `13_licenses.png` | **PASS** | Clean. Header **"Open source licenses"** (not App Demo). Full text readable: LVGL 9.6.0 / MIT License / Copyright (c) 2026 LVGL LLC and contributors / https://lvgl.io; AndroidX (AppCompat, Core, Activity, Lifecycle) Apache License 2.0; Kotlin Standard Library Apache License 2.0; and the complete privacy paragraph "This application collects no personal data. Device information (SoC, memory, storage, display, camera and sensor counts) is read locally to render the device dashboard and never leaves the device." | — |
| `14_dashboard_bottom.png` | **PASS** | Clean and **genuinely scrolled relative to `01`**: while the device map remains at the top, the area below it now shows a navigation list — Layout Showcase, Interactive Demo, Camera Sample, Game Sample, Launcher Sample, Showcase, App Demo — instead of the LCD / speaker / POWER cards seen in `01`. Scrolled content is demonstrably reachable. FAB still bottom-right. (A tiny partial card edge is visible just above the nav list; it is not text and does not overlap any label.) | — |

## Exact camera copy observed (`05_camera_sample.png`)

- Preview row: **`not rendered (frames processed locally)`**
- Footer: **`Frames are processed locally; camera imagery is not rendered on this surface`**

## Failure ranking

None — no failures in this capture set.

Non-blocking observations (not failures):
- `02` and `07` show the bottommost list/card partially continuing below the footer bar; this is normal scroll-overflow, and all visible labels remain readable.
- `14` has a small partial card edge above the navigation list, with no clipped or overlapping text.

## Logcat findings (new `screenshots/final/logcat.txt`)

- **No app-scoped crash:** no `FATAL EXCEPTION`, no `Process: com.lvgl.sample` crash, no crash/tombstone attributed to `com.lvgl.sample`. All `Force stopping com.lvgl.sample` / `app died` entries belong to the earlier sessions (15:06–16:52); the v3 session (from 17:21) has none.
- **No app-scoped ANR:** no `ANR in com.lvgl.sample`. The logcat contains historical system ANRs (`com.google.android.apps.wellbeing` at 16:48, `com.google.android.gms` at 17:08) and one during v3, `ANR in com.google.android.as.oss` at **17:23:06** (reason: broadcast, completed in 10582 ms). None involve the app, and the uiautomator dismissal kept the dialog out of every captured frame.
- **`LVGLLayout` warnings: none** (zero matches).
- The v3 session shows `nativeSetScreen` 0,1,2,3,4,5,6,7,1000,1001,7,5,1001,0 in the expected order with clean `LVGL initialised` / thread cycles. No native-layer failure is logged.

## Bottom line

**PASS.** All 14 release-APK screenshots satisfy the visual checklist and all eight specific proof requirements. No application crash or layout warning is present. Safe to publish this screenshot set.
