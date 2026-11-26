# Final Visual QA — v1.3.0 hardware screenshots (recaptured frames)

Scope: 25 PNGs in `screenshots/hardware/v130/`. The previous QA accepted 23
frames and failed only `19_hybrid_android_to_lvgl.png` and
`23_camera_captured.png`. Both of those were recaptured and re-opened with the
`read` tool for this pass; the other 23 are carried over as previously accepted.
No files were changed except this report.

## Re-check of the two recaptured frames

| File | Verdict | Exact reason / evidence |
|------|---------|--------------------------|
| `19_hybrid_android_to_lvgl.png` | PASS | The LVGL card now reads **`Hello from Android (1)`** where the previous capture showed `Waiting for Android...`. The native header (`Hybrid interop - native Android header`), native status bar, LVGL intro card and the native `SEND TO LVGL` / `SWITCH LVGL SCREEN` controls are all present and unclipped. Combined with `18_hybrid_lvgl_to_android.png` (`LVGL button tapped`), both bridge directions are now evidenced. |
| `23_camera_captured.png` | PASS | The status line now reads **`Camera status: saved photo 1`** where the previous capture showed `camera 1 of 2 - tap preview to switch`. The grey **`CAPTURE`** button is at the bottom of the screen (below the DETAILS card), completely clear of the status text. The hint `Tap preview to switch` is clear of the `Camera2 Preview` heading, and the preview remains 407x540 (aspect 0.7537). |

## Overall verdict for all 25 PNGs: **PASS**

| # | Check | Verdict | Evidence |
|---|-------|---------|----------|
| 1 | No black / loading / dialer frames | PASS | All 25 frames show the intended app UI (previous full-set luminance check: mean 28.9–211.6, no spinner/lock/dialer). |
| 2 | Marker moved after tap | PASS | `06` centre → `07` upper right (5,730 changed pixels). |
| 3 | Score 0 → 1 | PASS | `08` `Score: 0` → `09` `Score: 1` (12,738 changed pixels). |
| 4 | App demo taps 0 → 1 | PASS | `14` `Taps: 0` → `15` `Taps: 1` (134 changed pixels). |
| 5 | Showcase open → close | PASS | `11` tiles → `12` Audio window → `13` tiles (218,767 changed pixels each transition). |
| 6 | Scrolled frames differ | PASS | `01`→`02` 45,080 changed pixels; `03`→`04` 87,326 changed pixels. |
| 7 | Hybrid both bridge directions | PASS | `18` native label `LVGL button tapped`; `19` LVGL card `Hello from Android (1)`. |
| 8 | Light / dark theme differs | PASS | `20` light (mean luminance 211.6), `21` dark (40.4); 596,552 changed pixels. |
| 9 | MATERIAL 3 caption readable | PASS | `03` reads `MATERIAL 3` with complete glyphs. |
| 10 | Camera hint / button / status clear | PASS | Hint above heading with clear gap; CAPTURE at bottom clear of status text (verified at 2x). |
| 11 | Captured status `saved photo 1` | PASS | `23` reads `Camera status: saved photo 1`. |
| 12 | Camera aspect ~0.753 | PASS | Preview 407x540 = 0.7537 in `22`, `23`, `25`. |
| 13 | Dashboard real device values | PASS | `01`, `20`, `21`: Android 16/API 36, Z2465N, 480 dpi, `Ums9620_2h10`, 11 GB, 224 GB, 1080x2392, Camera 2, Cellular, WiFi/BT on/on, Li-ion, Sensors 75, Speaker+Phone, USB-C USB. |
| 14 | Licences / launcher | PASS | `16` licence text legible; `10` app list present. |

## FAIL summary

**None.** All 25 PNGs pass. **Overall verdict: PASS.**
