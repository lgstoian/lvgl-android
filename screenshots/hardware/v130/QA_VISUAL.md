# Visual QA — v1.3.0 hardware screenshot walk

Scope: strict visual inspection of all 24 PNGs in `screenshots/hardware/v130/`.
Date: 2026-11-26. Screenshots are 1080x2392 (device Z2465N).

Method: each image opened and inspected with the `read` tool. Pixel statistics
(mean luminance, % non-black pixels) and crop/zoom checks were used to confirm
suspected black frames and clipped glyphs; `logcat.txt` was used only to
cross-check camera aspect values, not as a substitute for visual evidence.

## Strict check table

| # | Check | Verdict | Evidence |
|---|-------|---------|----------|
| 1 | No black / ANR frames | **FAIL** | `06_interactive_demo.png`, `07_interactive_tapped.png`, `16_licenses.png` are 100% black: mean luminance 0, 0% of sampled pixels above luminance 12. No status bar, no UI, no content. |
| 2 | No clipped / overlapping text | **FAIL** | `03_widget_showcase.png`: the `MATERIAL 3` section label (native y≈1998–2015) is bottom-clipped — `E` renders as `F` (missing bottom bar) and `3` loses its lower curve. Its glyph height is 18 px versus 22 px for the sibling headers `BASIC CONTROLS`, `VALUES` and `SELECTION`. All other inspected text is unclipped and non-overlapping. |
| 3 | Dashboard shows real device values | PASS | `01`, `02`, `20`, `21`: Android 16 (API 36), Z2465N, 480 dpi, SoC `Ums9620_2h10`, Memory 11 GB, Storage 224 GB, Display 1080 x 2392, Camera 2, Modem Cellular, WiFi/BT on/on, Battery Li-ion, Sensors 75, Audio Speaker+Phone, USB-C USB. No placeholder/MCU-style values. |
| 4 | Scrolled dashboard proof shows scrolled content | PASS | `01` shows DEVICE MAP + POWER controls; `02` shows the same DEVICE MAP followed by the sample list (Camera Sample, Game Sample, Launcher Sample, Showcase, App Demo). Content genuinely moved. |
| 5 | Scrolled widget proof shows scrolled content | PASS | `03` shows BASIC CONTROLS / VALUES / SELECTION; `04` shows Filled-Tonal-Outlined chips, WiFi/Bluetooth/Location/NFC, the `Material text field`, bottom nav, `Open dialog`, `Show snackbar`. Genuinely different content. |
| 6 | All screens show their correct content | **FAIL** | Correct on 01–05, 08–15, 17–24. Fails on `06`, `07` (Interactive Demo — no counter/controls visible) and `16` (Licenses — no licence text visible). |
| 7 | Showcase window open / close | PASS | `12_showcase_window.png`: `Audio` overlay with visualiser bars and an X close button. `13_showcase_closed.png`: overlay gone, four tiles (Audio / System / Sensors / Config) visible again. |
| 8 | App demo button counter | PASS | `14_app_demo.png` `Taps: 0` → `15_app_demo_button.png` `Taps: 1`; all other fields unchanged. |
| 9 | Licenses screen | **FAIL** | `16_licenses.png` is entirely black; no licence list, title or navigation is visible. |
| 10 | Hybrid sample: native + LVGL content, both bridge directions | PASS | `17_hybrid_sample.png`: native Android header above (`Hybrid interop - native Android header`, real status bar) and native controls below (`SEND TO LVGL`, `SWITCH LVGL SCREEN`), LVGL card in between. `18_hybrid_lvgl_to_android.png`: native label changes to `LVGL button tapped` (LVGL→Android). `19_hybrid_android_to_lvgl.png`: LVGL text changes to `Hello from Android (1)` (Android→LVGL). |
| 11 | Light / dark theme | PASS | `20_dashboard_light.png` light background with dark text and red `OFF` button; `21_dashboard_dark.png` dark background with light text and salmon `OFF` button. Same content/layout in both. |
| 12 | Camera preview aspect correct, no overlap with LVGL text | PASS | `22`/`23`/`24`: preview box x=336–742 (407 px), y≈336–875 (540 px), centred horizontally; aspect 407/540 = 0.7537, matching the logged real camera aspect 0.7529 (cam 0) / 0.75 (cam 1). No stretching/squashing; the square smoke-detector in `23` stays square. Hint text sits at y≈900–934, clear of the preview bottom (≈875). |
| 13 | Game sample tap increments score | PASS | `08_game_sample.png` `Score: 0` → `09_game_tapped.png` `Score: 1`, target repositioned. |
| 14 | Launcher sample content | PASS | `10_launcher.png`: scrollable installed-app list with icons and `Tap an app to launch it` hint. |
| 15 | Layout showcase content | PASS | `05_layout_showcase.png`: six flex row-wrap tiles A–F in two rows of three with `Tiles wrap with LV_FLEX_FLOW_ROW_WRAP` caption; no overlap. |

## Per-screenshot verdicts

| File | Verdict | Note |
|------|---------|------|
| 01_dashboard.png | PASS | Real device values, dark theme, no clipping |
| 02_dashboard_scrolled.png | PASS | Scrolled list visible |
| 03_widget_showcase.png | **FAIL** | `MATERIAL 3` header bottom-clipped |
| 04_widget_scrolled.png | PASS | Scrolled M3 widgets visible |
| 05_layout_showcase.png | PASS | Flex wrap tiles A–F |
| 06_interactive_demo.png | **FAIL** | Fully black (0% non-black pixels) |
| 07_interactive_tapped.png | **FAIL** | Fully black (0% non-black pixels) |
| 08_game_sample.png | PASS | Score 0, target present |
| 09_game_tapped.png | PASS | Score 1 |
| 10_launcher.png | PASS | App list |
| 11_showcase_tiles.png | PASS | Four tiles |
| 12_showcase_window.png | PASS | Window open |
| 13_showcase_closed.png | PASS | Window closed |
| 14_app_demo.png | PASS | Taps 0 |
| 15_app_demo_button.png | PASS | Taps 1 |
| 16_licenses.png | **FAIL** | Fully black (0% non-black pixels) |
| 17_hybrid_sample.png | PASS | Native + LVGL, placeholder bridge label |
| 18_hybrid_lvgl_to_android.png | PASS | `LVGL button tapped` |
| 19_hybrid_android_to_lvgl.png | PASS | `Hello from Android (1)` |
| 20_dashboard_light.png | PASS | Light theme |
| 21_dashboard_dark.png | PASS | Dark theme |
| 22_camera_first.png | PASS | Correct aspect, text clear |
| 23_camera_second.png | PASS | Correct aspect, text clear |
| 24_camera_third.png | PASS | Correct aspect, text clear |

## FAIL summary (do not fix here)

1. `06_interactive_demo.png` — fully black; Interactive Demo proof invalid.
2. `07_interactive_tapped.png` — fully black; Interactive Demo tap proof invalid.
3. `16_licenses.png` — fully black; licences proof invalid.
4. `03_widget_showcase.png` — `MATERIAL 3` section header is bottom-clipped.
