# Final2 hardware visual QA

Scope: all 25 PNGs in `screenshots/hardware/final2/` opened and visually inspected.
Cross-check: `logcat.txt` (no ANR/FATAL for `com.lvgl.sample`; `LicensesActivity` START_SUCCESS and resumed).

**Overall verdict: FAIL** — 4 failing frames (2 black/blank, 2 with overlapping device-map chip text).

| # | File | Verdict | Notes |
|---|------|---------|-------|
| 01 | 01_dashboard.png | PASS | Clean device map: Display 1080x2392, Camera 2, Modem Cellular, WiFi/BT on/on, Battery Li-ion, Sensors 75, Audio Speaker+Phone, USB-C USB, SoC/Memory/Storage all readable, no overlap. Power controls readable. |
| 02 | 02_widget_showcase.png | PASS | Button, switch, checkbox, slider, progress, arc, dropdown all render; text readable. Bottom "MATERIAL 3" section header is at the viewport edge. |
| 03 | 03_layout_showcase.png | PASS | Flex row-wrap tiles A–F correct, no overlap. |
| 04 | 04_interactive_demo.png | PASS | Marker centred, instruction text readable. |
| 05 | 05_interactive_tapped.png | PASS | Marker moved to upper-right tap point; touch works. |
| 06 | 06_game_sample.png | PASS | "Tap the Target", Score: 0, target visible. |
| 07 | 07_game_tapped.png | PASS | Score incremented to 1, target repositioned. |
| 08 | 08_launcher.png | PASS | Launcher list (Accessibility … Android Auto) readable, no overlap. |
| 09 | 09_launcher_tapped.png | PASS | Tapping an app launches native Accessibility settings; no ANR. |
| 10 | 10_showcase_tiles.png | PASS | Audio / System / Sensors / Config tiles render correctly. |
| 11 | 11_showcase_window.png | PASS | Audio overlay with visualiser bars and close (X) control. |
| 12 | 12_showcase_closed.png | PASS | Overlay dismissed, tiles restored. |
| 13 | 13_app_demo.png | PASS | Kotlin/LvglUi screen: Taps 0, Slider 40, toggles, Option A, Selected 1. |
| 14 | 14_app_demo_button.png | PASS | Button tap increments Taps 0 → 1. |
| 15 | 15_app_demo_dropdown.png | PASS* | Checkbox toggled on (state change confirmed); dropdown itself is not opened in this frame. |
| 16 | 16_app_demo_licenses.png | PASS | Scrolled App Demo shows gauges and "Open source licenses" button. |
| 17 | 17_licenses.png | **FAIL** | Fully black/blank frame — license screen content not visible. |
| 18 | 18_back_chevron.png | **FAIL** | Fully black/blank frame — back chevron not visible. |
| 19 | 19_hardware_back.png | PASS* | Native Android Accessibility settings shown after back; LVGL back-stack not verifiable from this frame alone. |
| 20 | 20_dashboard_light.png | **FAIL** | Device-map chip labels overlap their values throughout (e.g. "10Display92", "Cellular" over Modem, "WiFi/BT" over on/on, "SpeaAudiohone", SoC/Memory/Storage double-drawn). |
| 21 | 21_dashboard_dark.png | **FAIL** | Same device-map label/value overlap in dark theme. |
| 22 | 22_camera_first.png | PASS* | Camera 1 of 2: live dark/noisy preview imagery present (not blank); status text readable. |
| 23 | 23_camera_second.png | PASS | Camera 2 of 2: clear live imagery visible; switch works. |
| 24 | 24_camera_third.png | PASS* | Cycled back to camera 1 of 2; live dark/noisy imagery present. |
| 25 | 25_camera_cycled_back.png | PASS | Camera 2 of 2 imagery visible again; cycling stable. |

\* PASS with an observation listed in Notes.

## FAIL reasons

1. **17_licenses.png** — black/blank frame; licenses navigation/content could not be visually verified. Logcat shows `LicensesActivity` started and resumed successfully, so this is likely a capture/display-timing black frame, but it fails visual QA as delivered.
2. **18_back_chevron.png** — black/blank frame; the back chevron could not be visually verified.
3. **20_dashboard_light.png** — severe overlapping text in the device-map chips: every chip's title and value are drawn on top of each other, making both hard to read.
4. **21_dashboard_dark.png** — same device-map chip overlap in dark mode.

## Additional observations (not FAIL)

- No ANR dialog or crash dialog appears in any frame; logcat contains no ANR/FATAL lines for the sample app.
- Frame 01 (captured later, 18:16) shows the device map with correct, non-overlapping label/value stacking — frames 20/21 (18:10) show the broken overlap state.
- Camera-details text says "not rendered (frames processed locally)" while live imagery is visibly displayed; a wording inconsistency, not a visual defect.
