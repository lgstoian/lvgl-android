# Final Visual QA — v1.3.0 hardware screenshots

Scope: all 27 PNGs in `screenshots/hardware/v130/` opened and inspected with
the `read` tool. Date: 2026-11-26. Device Z2465N, screenshots 1080x2392.

Method: visual inspection plus pixel-diff (samples every 2 px below the status
bar), luminance statistics, and 2x/3x crops of the previously suspect text.
`logcat.txt` was not needed for any verdict. No files were changed except this
report.

## Strict check table

| # | Check | Verdict | Evidence |
|---|-------|---------|----------|
| 1 | No black / ANR frames | PASS | Every PNG has visible UI. Mean luminance ranges 28.9–211.6; minimum non-black pixel share 91.4%. Black rectangles inside camera frames are the camera preview surface, not failed frames. |
| 2 | Readable, unclipped, non-overlapping text | PASS | `MATERIAL 3` in `03` now renders with all glyph strokes intact (verified at 3x). Camera hint/button/status are clear (verified at 2x). No clipped or overlapping text found in any current-run frame. |
| 3 | Dashboard real device values | PASS | `01`, `02`, `20`, `21`: Android 16 (API 36), Z2465N, 480 dpi, SoC `Ums9620_2h10`, 11 GB, 224 GB, Display 1080 x 2392, Camera 2, Modem Cellular, WiFi/BT on/on, Battery Li-ion, Sensors 75, Audio Speaker+Phone, USB-C USB. |
| 4 | Scroll proofs actually scrolled | PASS | `01` vs `02`: 45,080 changed pixels — `02` shows Camera/Game/Launcher/Showcase/App Demo list instead of the POWER controls. `03` vs `04`: 87,326 changed pixels — `04` shows Filled/Tonal/Outlined chips, WiFi/Bluetooth/Location/NFC, text field, bottom nav, dialog and snackbar instead of the basic controls. |
| 5 | Interactive demo marker moves on tap | PASS | `06` marker at screen centre; `07` marker at upper right; 5,730 changed pixels. Title, hint and footer unchanged. |
| 6 | Game sample tap increments score | PASS | `08` `Score: 0` with target top-left; `09` `Score: 1` with target at centre; 12,738 changed pixels. |
| 7 | Showcase open / close | PASS | `11` four tiles; `12` `Audio` window with visualiser bars and X close button (218,767 changed pixels); `13` back to the four tiles (218,767 changed pixels). |
| 8 | App demo button counter | PASS | `14` `Taps: 0` → `15` `Taps: 1`; all other fields unchanged (134 changed pixels). |
| 9 | Hybrid sample: native + LVGL content | PASS | `17`–`19`: native Android header with real status bar above, LVGL card in the middle, native controls (`SEND TO LVGL`, `SWITCH LVGL SCREEN`) below. |
| 10 | Hybrid LVGL→Android bridge | PASS | `18` native label changes from `Last LVGL event appears here` to `LVGL button tapped` (2,048 changed pixels). |
| 11 | Hybrid Android→LVGL bridge | PASS | `19` LVGL text changes from `Waiting for Android...` to `Hello from Android (1)` (2,461 changed pixels). |
| 12 | Light / dark theme toggle | PASS | `20_dashboard_light.png` has a light background (mean luminance 211.6) with dark text and red `OFF`; `21_dashboard_dark.png` is dark (mean 40.4) with light text and salmon `OFF`. 596,552 changed pixels; same layout/content. |
| 13 | Camera preview aspect ~0.753, portrait | PASS | Preview box x=336–742 (407 px), y=336–875 (540 px) in `22` and `23_camera_captured`; 407/540 = **0.7537**. `25_camera_third` is 407/539 = 0.7551. The square smoke-detector in `24_camera_second` stays square — not stretched/squashed. |
| 14 | Camera hint / button / status clear of overlap | PASS | Hint `Tap preview to switch` sits below the preview (preview ends y≈875, hint at y≈900) and above `Camera2 Preview` with a clear gap — no glyph collision at 2x. `CAPTURE` button moved to the bottom (y≈2140+), well clear of the orange status text at y≈1010–1095. |
| 15 | CAPTURE button present | PASS | Grey `CAPTURE` button visible at the bottom of `22`, `23_camera_captured`, `24_camera_second`, `25_camera_third`. |
| 16 | Captured status readable | PASS | `23_camera_captured.png`: `Camera status: saved photo 1` is fully legible at 2x, including the count. (The status shows the count but not the saved filename; no filename is claimed in the current check.) |
| 17 | Licences screen | PASS | `16_licenses.png`: LVGL 9.6.0 / MIT, AndroidX / Apache 2.0, Kotlin Standard Library / Apache 2.0 and the no-personal-data statement; all text legible. |
| 18 | Launcher sample | PASS | `10_launcher.png`: installed-app list with icons and `Tap an app to launch it` hint. |
| 19 | Layout showcase | PASS | `05`: six flex row-wrap tiles A–F in two rows of three, `Tiles wrap with LV_FLEX_FLOW_ROW_WRAP` caption; no overlap. |

## Prior FAIL re-verification

| Prior FAIL | Now | Verdict |
|------------|-----|---------|
| Interactions dead (taps not registering) | Marker, game score, app counter, showcase window, hybrid label and theme toggle all change between their before/after frames. | **FIXED** |
| Scroll proofs identical | `01`→`02` and `03`→`04` now show genuinely different content. | **FIXED** |
| `MATERIAL 3` caption clipped | Reads `MATERIAL 3` with complete `E` and `3` glyphs. | **FIXED** |
| Camera hint/button/status overlaps | Hint above heading with clear gap; CAPTURE button relocated below the details card. | **FIXED** |
| Captured status unreadable | `Camera status: saved photo 1` fully readable. | **FIXED** |

## Per-screenshot verdicts

| File | Verdict | Note |
|------|---------|------|
| 01_dashboard.png | PASS | Real values, dark theme |
| 02_dashboard_scrolled.png | PASS | Sample list visible |
| 03_widget_showcase.png | PASS | `MATERIAL 3` reads correctly |
| 04_widget_scrolled.png | PASS | Scrolled M3 widgets visible |
| 05_layout_showcase.png | PASS | Flex tiles A–F |
| 06_interactive_demo.png | PASS | Marker at centre |
| 07_interactive_tapped.png | PASS | Marker at upper right |
| 08_game_sample.png | PASS | Score 0 |
| 09_game_tapped.png | PASS | Score 1 |
| 10_launcher.png | PASS | App list |
| 11_showcase_tiles.png | PASS | Four tiles |
| 12_showcase_window.png | PASS | Window open |
| 13_showcase_closed.png | PASS | Window closed |
| 14_app_demo.png | PASS | Taps 0 |
| 15_app_demo_button.png | PASS | Taps 1 |
| 16_licenses.png | PASS | Licence text legible |
| 17_hybrid_sample.png | PASS | Native + LVGL, initial labels |
| 18_hybrid_lvgl_to_android.png | PASS | `LVGL button tapped` |
| 19_hybrid_android_to_lvgl.png | PASS | `Hello from Android (1)` |
| 20_dashboard_light.png | PASS | Light theme |
| 21_dashboard_dark.png | PASS | Dark theme |
| 22_camera_first.png | PASS | Correct aspect, no overlaps |
| 23_camera_captured.png | PASS | Status readable, count 1 |
| 23_camera_second.png | PASS (stale) | Old 11:10 run; prior camera UI, retained for reference |
| 24_camera_second.png | PASS | Correct aspect, no overlaps |
| 24_camera_third.png | PASS (stale) | Old 11:10 run; prior camera UI, retained for reference |
| 25_camera_third.png | PASS | Correct aspect, no overlaps |

Note: `23_camera_second.png` (11:10:01) and `24_camera_third.png` (11:10:06)
are stale files from an earlier run showing the old camera layout; they
duplicate the numbering of current files (`24_camera_second.png`,
`25_camera_third.png`). This is screenshot-set hygiene only — no current-run
verdict depends on them.

## FAIL summary

**No FAILs.** Every listed check passes in the current screenshot set.
