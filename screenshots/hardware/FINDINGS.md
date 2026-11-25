# Hardware smoke findings — Nubia Z2465N (adb 324450843961)

Device: Nubia Z2465N, Android 16 / API 36, arm64-v8a, 1080x2392 @ 480 dpi, Mali GPU,
3 physical cameras. APK: published signed `v1.1.0` universal release
(`app-universal-release.apk`, 2,163,777 bytes) downloaded with
`gh release download v1.1.0 --repo lgstoian/lvgl-android --pattern '*universal*'`,
fresh-installed (`uninstall com.lvgl.sample` first). CAMERA was granted before the
first capture. All launches used the exported entry point
`am start --activity-clear-task -n com.lvgl.sample/.MainActivity --ei SMOKE_SCREEN <id>`
with a 6–10 s settle. Note: the device screen was initially **asleep/dozing**, which
produced all-black screencaps; the first `00_dashboard.png` was replaced after
`input keyevent 224` and `svc power stayon true`. All screenshots below were opened
and visually inspected.

## Screen-by-screen table

| id | Screen | Screenshot | Result | Actual observations |
|----|--------|------------|--------|---------------------|
| 0 | Dashboard | `00_dashboard.png` | **FAIL** | Dark theme, status bar visible (WiFi, "No SIM", 16:02, 100%). Device map renders real values: Android 16 (API 36), Z2465N, 480 dpi, SoC `Ums9620_2h10`, Memory 11 GB, Storage 224 GB, Display `1080 x 2392`, **Camera 2**, Modem 5G, WiFi/BT WiFi, Battery Li-Po, Sensors 75, Audio I2S, USB-C USB 3.2. LCD 80, speaker 60, POWER OFF. Bottom "SLEEP & TOUCH TO WAKEUP" card is cut off at the screen edge. FAB (refresh/theme) bottom-right at ~(950, 2260). Camera count is wrong (see P1). |
| 1 | Widget showcase | `01_widget_showcase.png` | PASS (visual caveats) | Dark theme, app bar with back chevron. Button "Tap me" (purple-on-orange, low contrast), Switch ON (light circle), Checkbox, Slider 40%, Progress 65%, Arc, Dropdown "Option A", "MATERIAL 3" section header partly overlapped by the footer snackbar "Drag the slider, switch or arc" at y≈2070. FAB "+" bottom-right. |
| 2 | Layout showcase | `02_layout_showcase.png` | PASS | Dark theme, "Flex row-wrap container", 6 coloured tiles A–F in 3x2 flex grid, no overlap/clipping; footer "Tiles wrap with LV_FLEX_FLOW_ROW_WRAP". Large empty lower area by design. |
| 3 | Interactive demo | `03_interactive_demo.png` | PASS | Dark theme; instruction top, pink/white marker centred (540, 1200), footer "The marker follows your finger". Tap at (850, 600) moved marker to tap point (`18_interactive_after_tap.png`). |
| 4 | Camera sample | `04_camera_sample.png` | PASS (by design) | Dark theme, "Camera status: opened camera 0" with CAMERA granted; details show LVGL native / dedicated / vsync-aware and "Preview: not rendered (frames processed locally)"; footer explains frames are local. Permission flow verified separately (see below). |
| 5 | Game sample | `05_game_sample.png` | PASS | Dark theme, "Tap the Target", Score: 0, target circle at (130, 710). Tap at (130, 710) → Score: 1 and target relocated to (540, 1370) (`17_game_after_tap.png`). |
| 6 | Launcher sample | `06_launcher.png` | **FAIL** (functional) | Dark theme, installed-app list (Accessibility, Ad Privacy, Advancer AD20, … Android Auto), home icons, footer "Tap an app to launch it". Taps at (300, 410), (540, 416), (540, 560/700/840) produced no launch and no bridge log (see P3). |
| 7 | Showcase tiles | `07_showcase_tiles.png` | PASS | Dark theme, 4 tiles (Audio, System, Sensors, Config) with coloured accent bars. Tile Audio tap at (320, 500) opened animated "Audio" window; visible X at (922, 397) closed it (`35`/`36`). |
| 1000 | App demo | `08_app_demo.png` (black capture), `23_appdemo_after_controls.png` (valid) | **FAIL** | renders on retry. Kotlin DSL screen: "Built in Kotlin", Taps: 0, Value: 40, toggles, Dropdown Option A, gauges, "Open source licenses" button. Native widgets respond but **all node callbacks are dead**: Taps stays 0, Value stays 40, Selected stays 1, licenses button does nothing (see P2). Dropdown popup uses white/blue LVGL default styling in dark theme (`25`). |
| 1001 | Open source licenses | `09_licenses.png`, `28_license_navigation.png` | PASS | Dark theme, readable LVGL 9.6.0 / AndroidX / Kotlin licences and privacy disclosure; no clipping. LVGL back chevron tap is inert (same as P4); hardware back works but leaves stale screen (P5). |

## Interaction / mode results

- **Widget controls PASS**: dropdown open (`20_widget_dropdown_open.png`) and Option B selection (`21`); slider dragged 634→900 px, switch toggled OFF (light track), checkbox checked (`22_widget_controls_changed.png`).
- **App demo FAIL**: button (540, 930), slider swipe 440→830 at y=1330, dropdown Option B at (250, 1915), licenses button (540, 2230) — widget visuals changed but no bound label or navigation ever updated.
- **Theme toggle PASS**: dashboard FAB at (950, 2260) toggles to light (`29_dashboard_light_theme.png`) and back to dark (`30_dashboard_dark_restored.png`). In light mode device-map chips remain near-black with coloured sub-labels (contrast is acceptable but the chip styling is inconsistent with the light surface), and the bottom sleep card is still clipped.
- **Rotation PASS**: `user_rotation=1` → 2392x1080 landscape, dashboard reflows to 3 columns, Display value correctly becomes `2392 x 1080` (`31_dashboard_landscape.png`). Power/RTC section is initially below the fold but reachable by an upward swipe (`32_dashboard_landscape_scrolled.png`). Portrait restored with `user_rotation=0`.
- **Back navigation FAIL (harness path)**: LVGL back chevron (70, 230) is inert on Showcase and Licenses. Hardware `KEYCODE_BACK` returns to `MainActivity` but the rendered LVGL screen stays on the previous screen (`33`, `34`, `38`).
- **Camera permission flow PASS**: with CAMERA revoked, opening screen 4 showed the Android 16 permission dialog "Allow LVGL Sample to take pictures and record video?" (`12_camera_denied_screen.png`); logcat shows `GrantPermissionsActivity` and `grantPermissionsViewModel` click. After granting, camera status "opened camera 0" and the green camera-privacy dot appeared in the status bar (`13`). No app crash or uncaught `SecurityException`.
- **Intermittent black surface**: `08_app_demo.png` (first 1000 launch, 8 s settle) and `19_widget_after_controls.png` were pure black (15,529-byte PNG) while the activity was resumed and the display reported Awake. Log evidence at 16:13:35–16:13:35.739: new screen init/rebind, then `W LVGL: lv_init: already initialized`, then `I LVGLPort: nativeDeinitDisplay` / `I LVGLPort: LVGL surface released` with no follow-up rebind. A later relaunch of the same screen rendered correctly (`_appdemo_retry_2.png`, `_widget_relaunch_8.png`), so this is a race, not a permanent failure. Six further rapid transitions produced no black captures.
- **Logs** (`logcat.txt`, 426 KB, captured after a final 6-screen transition walk): 0 `AndroidRuntime`, 0 `FATAL`, 0 `ANR`, 0 `LVGLLayout`, 0 JNI errors. Repeated app-scoped `E BufferQueueProducer: … disconnect: not connected (req=2)` on every transition (7 occurrences). `LvglEngine.dumpLayoutReport()` is not exposed over adb logs, so no native layout report could be read; no LVGL layout warnings were logged.

## Camera-count investigation (parent-requested, P1)

1. Real hardware: `adb shell dumpsys media.camera` → `Number of camera devices: 3`,
   `Number of normal camera devices: 3`, `Number of public camera devices visible to API1: 3`,
   `Device 0/1/2`; `pm list features | grep camera` includes
   `android.hardware.camera`, `android.hardware.camera.any`, `.autofocus`, `.flash`, `.front`.
2. Permission before first launch: `android.permission.CAMERA: granted=true` (see install step).
3. Dashboard text observed at the Camera chip subtitle, approx x=760–860, y=430–445: **"Camera 2"** in `00_dashboard.png`, `10_camera_revoked_dashboard.png`, `11_camera_granted_fresh_dashboard.png`, `16_camera_count_warm_start.png`.
4. `pm revoke com.lvgl.sample android.permission.CAMERA` → `granted=false`; relaunch dashboard → still "Camera 2" (`10_camera_revoked_dashboard.png`).
5. `pm grant … CAMERA` + `am force-stop` + relaunch → still "Camera 2" (`11_camera_granted_fresh_dashboard.png`). Warm start after a 10 s delay → still "Camera 2" (`16_camera_count_warm_start.png`).
6. Camera2 API works: the camera screen opened physical camera 0 and CameraService logged `CameraService::connect call (PID … "com.lvgl.sample", camera ID 0)`.

Conclusion: the dashboard count is independent of CAMERA permission/process lifetime and is
one less than the 3 cameras the platform reports (parent also observed "Camera 0" in their
run). No camera-related exception is logged. Likely owning file:
`lvgl-android/src/main/java/com/lvgl/android/system/LvglDeviceInfo.kt:79-82` (the
`cameraIdList.size` collection / error fallback) with the value piped through
`LvglActivity.pushDeviceInfo()` → `LvglEngine.setDeviceInfo()` →
`lvgl-android/src/main/cpp/demo_ui.cpp:1338` (`FIELD_CAMERA`).

## Ranked bugs

1. **P1 — Dashboard reports wrong camera count** (hardware fact: 3, UI: 2; parent saw 0).
   Repro: launch id 0, read Camera chip subtitle at (820, 435); compare with
   `dumpsys media.camera`. Permission grant/revoke and force-stop do not change it.
   Likely owner: `LvglDeviceInfo.kt:79-82`; verify `demo_ui.cpp:1338`.
2. **P1/P2 — Kotlin UI builder node callbacks do not execute / do not update nodes**.
   Repro: screen 1000, tap button (540, 930) → `Taps: 0` unchanged; drag slider
   (440,1330)→(830,1330) → `Value: 40` unchanged; dropdown → `Option B` selected but
   `Selected: 1` unchanged; scroll and tap "Open source licenses" (540, 2230) → no
   navigation (focus stays `AppDemoActivity`). Likely owner: native node event →
   Kotlin callback bridge (`lvgl-android/src/main/cpp/ui_*.cpp` / `lvgl_port.cpp`)
   and `lvgl-android/src/main/java/com/lvgl/android/ui/`, then `app/.../AppDemoScreen.kt`.
3. **P2 — Launcher tap-to-launch is inert**. Repro: screen 6, tap (300, 410) or
   (540, 416/560/700/840); focus remains `LauncherSampleActivity`, no `START u0`
   for the chosen package and no `LVGLMenu`/launcher bridge log. Likely owner:
   `lvgl-android/src/main/java/com/lvgl/android/launcher/LvglLauncherActivity.kt`
   (`launchApp`) and `lvgl_port.cpp:1346 ui_emit_launcher_event` listener wiring.
4. **P2 — LVGL app-bar back chevron does nothing**. Repro: screen 7 or 1001,
   tap (70, 230); activity unchanged. Likely owner: `demo_ui.cpp:1905 on_back_clicked`
   / `g_on_navigate_back` bridge, or touch-coordinate mapping of the small hit target.
5. **P2 — Back from smoke-launched child leaves stale LVGL screen on MainActivity**.
   Repro: launch id 7, press hardware back; focus becomes `MainActivity` but screen still
   shows "Showcase" (`34_hardware_back_result.png`); same for id 1001 (`38`). Likely owner:
   `app/src/main/java/com/lvgl/sample/MainActivity.kt:53-60` (smoke branch never calls
   `setLvglScreen(0)`) combined with `LvglActivity.kt:291` (`lvglScreenId == NO_SCREEN`).
   Note this is the harness path; normal in-app navigation is not proven broken by this.
6. **P2 — Intermittent black surface after activity transition**. Repro observed twice:
   id 1000 after id 7 (8 s), and widget after several taps; activity resumed, screen Awake,
   PNG exactly 15,529 bytes pure black. Log: `lv_init: already initialized` then
   `nativeDeinitDisplay` / `LVGL surface released` after the new screen was bound.
   Likely owner: `lvgl-android/src/main/java/com/lvgl/android/view/LvglSurfaceView.kt`
   / `activity/LvglActivity.kt` surface-owner lifecycle and `lvgl_port.cpp` re-init path.
7. **P3 — Material 3 theming gaps in dropdowns and switch-off state**. Repro: screen 1
   open dropdown (tap 663, 1800): popup is dark card with a bright blue selected row;
   screen 1000 dropdown (`25`) is white with tiny blue text; switch OFF state is light
   grey (`22`). Likely owner: `demo_ui.cpp` `make_dropdown` / `ui_make_switch` styling.
8. **P3 — Low-contrast button labels**: purple text on orange filled buttons
   ("Tap me" `01`, "Open source licenses" `27`) in dark and light themes. Likely owner:
   Material palette `onPrimary` mapping in `MaterialTheme`/`MaterialPalette` +
   `ui_make_filled_button`.
9. **P3 — Portrait bottom card clipped**: dashboard "SLEEP & TOUCH TO WAKEUP" is cut
   off at y≈2100–2160 and only becomes readable after scrolling. Likely owner:
   dashboard scaffold/footer spacing in `demo_ui.cpp build_dashboard_screen`.
10. **P3 — Surface teardown noise**: 7× `BufferQueueProducer disconnect: not connected
    (req=2)` during 6 screen transitions; no visible failure in the final walk, but it
    correlates with the black-frame race in #6. Likely owner: surface rebind logic in
    `LvglSurfaceView.kt` / `lvgl_port.cpp`.

## Key screenshot paths (all under `screenshots/hardware/`)

`00_dashboard.png`, `01_widget_showcase.png`, `02_layout_showcase.png`,
`03_interactive_demo.png`, `04_camera_sample.png`, `05_game_sample.png`,
`06_launcher.png`, `07_showcase_tiles.png`, `08_app_demo.png`,
`09_licenses.png`, `10_camera_revoked_dashboard.png`,
`11_camera_granted_fresh_dashboard.png`, `12_camera_denied_screen.png`,
`13_camera_permission_denied_result.png`, `14_permission_dialog_recheck.png`,
`16_camera_count_warm_start.png`, `17_game_after_tap.png`,
`18_interactive_after_tap.png`, `19_widget_after_controls.png`,
`20_widget_dropdown_open.png`, `21_widget_dropdown_selected.png`,
`22_widget_controls_changed.png`, `23_appdemo_after_controls.png`,
`24_appdemo_button_retry.png`, `25_appdemo_dropdown_open.png`,
`26_appdemo_dropdown_selected.png`, `27_appdemo_bottom_licenses_button.png`,
`28_license_navigation.png`, `29_dashboard_light_theme.png`,
`30_dashboard_dark_restored.png`, `31_dashboard_landscape.png`,
`32_dashboard_landscape_scrolled.png`, `33_back_arrow_result.png`,
`34_hardware_back_result.png`, `35_showcase_tile_window.png`,
`36_showcase_window_closed.png`, `37_license_back_arrow.png`,
`38_license_hardware_back.png`, `logcat.txt`.
