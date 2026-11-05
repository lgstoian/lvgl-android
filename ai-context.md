# AI Context — Notable Findings & Quirks

## Emulator

### Emulator Won't Start

**Problem**: After killing the emulator process, it may not restart. `adb devices` shows no devices/emulators even after `adb start-server`.

**Root Cause**: The `emulator.exe` binary is at `D:\ASDK\emulator\emulator.exe`. There is no `emulator.exe` under `D:\ASDK\platform-tools` (that directory only ships `adb.exe`), so the path that was being used did not exist.

**Solution**: Start the emulator binary directly:
```powershell
$env:ANDROID_HOME = "D:\ASDK"
$env:ANDROID_AVD_HOME = "C:\Users\lgstoian\.android\avd"
Start-Process -FilePath "D:\ASDK\emulator\emulator.exe" -ArgumentList "-avd", "test_avd", "-no-window", "-no-audio", "-gpu", "swiftshader_indirect", "-wipe-data" -WindowStyle Hidden
```

### ADB Unauthorized

**Problem**: After deleting ADB keys, the emulator shows as `unauthorized`.

**Root Cause**: The emulator's userdata partition stores the host's public key. Deleting host keys breaks authentication.

**Solution**: Wipe emulator data with `-wipe-data` flag to clear stored keys, then restart ADB server so new keys are accepted.

### ADB Output Parsing

**Problem**: `adb shell getprop sys.boot_completed` sometimes outputs nothing to stdout.

**Solution**: Use `adb shell uname -a` and `adb devices` to verify the device is responsive instead.

## Build Environment

### CMake `share/cmake-X.Y` Missing from GitHub Releases

**Problem**: GitHub release zips for CMake don't include `share/cmake-X.Y` directory.

**Symptom**: `CMake Error: Could not find CMAKE_ROOT !!!`

**Solution**: Copy `share` from a working CMake install (e.g., auto-downloaded `3.22.1-2`):
```powershell
Copy-Item "D:\ASDK\cmake\3.22.1-2\share" "D:\ASDK\cmake\3.22.1\" -Recurse -Force
```

### NDK r26b + x86_64 Build

**Problem**: NDK r26b with Clang 17 cannot link x86_64 binaries with CMake 3.22.1 because it does not automatically resolve `libatomic` for x86_64.

**Symptom**: `ld.lld: error: unable to find library -latomic`

**Solution**: Added `target_link_options(lvgl_android PRIVATE -latomic)` in `CMakeLists.txt`, guarded by `ANDROID_ABI STREQUAL "x86_64"`. Both `arm64-v8a` and `x86_64` now build and are packaged in the debug APK.

**Note**: armeabi-v7a still blocked (missing `libclang_rt.builtins-arm-android.a`).

### Demo Activity setContentView Conflict

**Problem**: Demo activities that extend LvglActivity called `setContentView()` with native Android views, replacing the LVGL SurfaceView and causing greyed out "system UI isn't responding" screen.

**Solution**: Activities extending LvglActivity must NOT call `setContentView()`. Use `loadDemo(id)` to switch LVGL content instead. Use `AppCompatActivity` for native Android views.

## Rendering / UI Bugs Fixed in This Session

### pendingDemoId Never Consumed

**Problem**: `nativeLoadDemo` stored `engine->pendingDemoId` when the display was not yet created, but nothing ever read it. The default UI always showed regardless of which demo was requested.

**Fix**: `nativeInitDisplay` now consumes `pendingDemoId` right after building the default UI. See `lvgl_port.cpp:nativeInitDisplay`.

### Demo Activities Called setContentView (ANR)

**Problem**: CameraSample / GameSample / LauncherSample extended `LvglActivity` and called `setContentView()`, replacing the LVGL SurfaceView and producing the greyed-out "System UI isn't responding" screen.

**Fix**: Removed all `setContentView()` calls; status is rendered in LVGL via `LvglEngine.setStatusText()`.

### Render Thread Blocked UI Thread

**Problem**: `nativeDeinitDisplay`/`nativeDestroy` joined the LVGL thread from the UI thread; if the render thread was blocked in `ANativeWindow_lock`, the UI thread wedged → ANR.

**Fix**: Set a `stopping` flag the flush callback checks; plain join returns promptly.

### "System UI Isn't Responding" on First Frame

**Problem**: The emulator is x86_64 but the APK shipped only `arm64-v8a`, forcing slow Berberis ARM translation. Startup took ~14s and was mistaken for an ANR.

**Fix**: Added `x86_64` to `abiFilters`; native x86_64 builds now.

### JNI Nav Crash (jobject invalid)

**Problem**: `nativeSetNavCallback` JNI signature was `(env, thiz, navigator)` but Kotlin passed `(handle, navigator)` — the handle value was read as the navigator jobject → "invalid JNI transition frame reference".

**Fix**: Corrected signature to `(env, thiz, handle, navigator)`.

### g_vm Never Set

**Problem**: `nav_valid()` always returned false because `JNI_OnLoad` was removed during the port rewrite, so `g_vm` stayed null and navigation silently failed.

**Fix**: Restored `JNI_OnLoad` in `lvgl_port.cpp`.

### MotionEvent Requires Robolectric

**Problem**: Unit tests using `MotionEvent.obtain()` fail with "not mocked" error.

**Solution**: Use `androidTest` (instrumented tests) for MotionEvent-dependent code. Pure logic tests can use `test` source set without Android framework.

## Threading, Layout & Interaction (Latest Session)

These four classes of bug are the ones that actually made the UI look broken.
They are all consequences of LVGL semantics that differ from Android's.

### Decorative `lv_obj` Children Swallow Taps

**Problem**: Tapping a menu card did nothing. The `LV_EVENT_CLICKED` handler on the
card never fired.

**Root Cause**: A plain `lv_obj_create()` object is **clickable by default**
(`lv_obj.c` sets `obj->clickable = 1`). LVGL's hit test returns the topmost
*clickable* object, so the card's inner column/labels (decorative containers)
captured the press and the click never reached the card.

**Fix**: `demo_ui.cpp` routes every decorative object through `decorative()`,
which clears `LV_OBJ_FLAG_CLICKABLE` and `LV_OBJ_FLAG_SCROLLABLE`. Any object
that should not itself handle input (labels, accent bars, inner columns) must go
through it, or it will steal taps from a clickable ancestor.

### Containers Have No Layout Unless You Set One

**Problem**: Card titles and subtitles were drawn on top of each other.

**Root Cause**: A plain `lv_obj` has **no layout**, so all children are placed at
(0, 0) and overlap. The old code created card/column containers without calling
`lv_obj_set_layout()`.

**Fix**: Every container that holds more than one child sets an explicit flex
layout (`make_row`/`make_column`/`make_group`).

### Fonts Are Fixed Pixel Sizes (dp → px → nearest enabled size)

**Problem**: All text rendered at roughly 5–7sp — unreadably small.

**Root Cause**: LVGL fonts are fixed *pixel* sizes. The code used
`lv_font_montserrat_14` / `_20` directly, which on a 440 dpi screen is ~5–7sp.

**Fix**: `ui_font(dp, dpi)` converts a dp request to pixels and picks the nearest
enabled Montserrat size; `dpx(dp, dpi)` does the same for spacing. The enabled
ladder lives in `lv_conf.h`.

**Limit**: LVGL ships Montserrat **only up to size 48**. Requesting
`lv_font_montserrat_56/64/72` fails to compile — the ladder caps at 48 even on a
440 dpi display.

### `scheduleTask()` Under `engine->mutex` Deadlocks (ANR)

**Problem**: Switching screens froze the UI thread → "System UI isn't responding".

**Root Cause**: `nativeSetScreen` / `nativeApplyTheme` held `engine->mutex` and
then called `scheduleTask()`, which locks the *same non-recursive* mutex again.

**Fix**: Never call `scheduleTask()` while holding `engine->mutex`. Take the
lock, copy what is needed, release, then schedule.

### Activity Switches: Rebind the Surface, Don't Rebuild the Display

**Problem**: Switching activities showed the wrong screen, or wedged during
startup.

**Root Cause**: Each activity tore down and recreated the LVGL display; Android
also delivers `surfaceDestroyed` for the *old* activity after the new one is
resumed, so the stale callback could clobber the live display.

**Fix**: The engine (and its display) is process-wide and outlives every
activity. `nativeInitDisplay` takes the new `ANativeWindow`, and the render
thread installs it; when the size is unchanged it is a pure *rebind* (fast path,
no display rebuild). Display binding is owner-scoped:
`initDisplay(owner, …)` / `deinitDisplay(owner)` — `deinitDisplay` from a stale
owner is ignored because it no longer matches `activeOwner`.

### Returning to an Activity Showed the Previous Activity's Screen

**Problem**: Pressing back from a sample left that sample's screen visible on the
menu activity.

**Root Cause**: The display is shared process-wide and keeps the last screen id,
so returning to an activity that never called `setScreen` again showed the wrong
screen.

**Fix**: `LvglActivity.setLvglScreen(id)` records the id, and `onResume` re-asserts
it when the display is showing a different screen (skipping the first resume,
which `onCreate` already handled).

### Live Status Text Was Wiped by the Resume Rebuild

**Problem**: The launcher screen sat on "Loading installed apps..." even though
the activity had pushed the real list.

**Root Cause**: `onResume` re-asserting the screen triggers a rebuild, which
resets the status label to the screen's initial text.

**Fix**: `nativeSetStatusText` caches the text together with the screen id it
belongs to, and `rebuild_screen()` re-applies it after a rebuild (only for the
matching screen, so text cannot leak between screens).

### Guard Test Silently Checked Nothing

**Problem**: `HardRuleGuardTest` passed, but two of its three rules never ran.

**Root Cause**: `File("../../../app/src/main/java")` resolved *outside* the
repository, so `exists()` was false and every check short-circuited.

**Fix**: Locate the repo root by walking up from the test working directory, and
assert the path exists so a broken path fails loudly instead of silently
passing.

## LVGL Integration

### LVGL 9.x Header Structure

**Problem**: LVGL 9.x moved headers from `src/` to `include/lvgl/`. Old `#include "lvgl.h"` no longer works.

**Solution**: Use `#include "lvgl/lvgl.h"` with include path pointing to `lvgl/include/`.

### LVGL Color Format

**Fact**: LVGL stores `lv_color32_t` as `{blue, green, red, alpha}` (BGRA). Android `RGBA_8888` expects `{red, green, blue, alpha}`. R/B swap required during framebuffer copy.

### LVGL User Data APIs

**Fact**: `lv_display_set_user_data()`, `lv_display_get_user_data()`, `lv_indev_set_user_data()`, `lv_indev_get_user_data()` available in LVGL 9.6.0.

### LV_THEME_DEFAULT_LIGHT Not Defined

**Problem**: LVGL 9.6.0 doesn't define `LV_THEME_DEFAULT_LIGHT`.

**Solution**: Use `0` for light mode, `LV_THEME_DEFAULT_DARK` for dark mode.

## Kotlin/JNI

### JNI Function Naming

**Rule**: JNI functions must follow `Java_<package>_<Class>_<method>` with underscores replacing dots. Package `com.lvgl.android.engine` with class `LvglEngine` becomes `Java_com_lvgl_android_engine_LvglEngine_*`.

### Kotlin Property vs JNI Method Conflict

**Problem**: Kotlin `var isImmersive` generates getter `isImmersive()` conflicting with `InputMethodService.isImmersive()`.

**Solution**: Use a different property name like `immersiveMode`.

## Git Submodule

### LVGL Version Update

```powershell
cd lvgl
git fetch --tags
git checkout vX.Y.Z
cd .. && git add lvgl && git commit -m "Update LVGL to vX.Y.Z"
```

## Emulator Commands Quick Reference

### Start Emulator
```powershell
$env:ANDROID_HOME = "D:\ASDK"
$env:ANDROID_AVD_HOME = "C:\Users\lgstoian\.android\avd"
Start-Process -FilePath "D:\ASDK\emulator\emulator.exe" -ArgumentList "-avd", "test_avd", "-no-window", "-no-audio", "-gpu", "swiftshader_indirect", "-wipe-data" -WindowStyle Hidden
```

### Wait for Boot
```powershell
$env:ANDROID_HOME = "D:\ASDK"
& "D:\ASDK\platform-tools\adb.exe" devices
# Wait until "1" is returned for sys.boot_completed
& "D:\ASDK\platform-tools\adb.exe" shell "getprop sys.boot_completed"
```

### Install & Run App
```powershell
$env:ANDROID_HOME = "D:\ASDK"
& "D:\ASDK\platform-tools\adb.exe" install -r "D:\Code\android lvgl\app\build\outputs\apk\debug\app-debug.apk"
& "D:\ASDK\platform-tools\adb.exe" shell am start -n com.lvgl.sample/.MainActivity
```

### Capture Screenshot
```powershell
$env:ANDROID_HOME = "D:\ASDK"
& "D:\ASDK\platform-tools\adb.exe" shell screencap -p /sdcard/screenshot.png
& "D:\ASDK\platform-tools\adb.exe" pull /sdcard/screenshot.png "D:\Code\android lvgl\screenshots\screenshot.png"
```

## Guard Tests & Machine-Readable Comments (2026-10-31)

### A Green Guard Test Can Still Check Nothing

**Problem**: `HardRuleGuardTest` passed while two of its three rules never ran,
because a hardcoded relative path resolved outside the repository and
`File.exists()` was false.

**Root Cause**: The suite short-circuited on a missing path instead of failing.

**Fix**: Locate the repo root by walking up from the working directory and
assert the path exists. Detection logic moved to `GuardRules` so
`GuardCanaryTest` can feed each detector a broken fixture that must be detected
(false-green protection) and a clean fixture that must not be (false-positive
protection). Annotation validation moved to `AnnotationValidator`, which has its
own canaries for dangling/orphaned references and unknown tags/rules. Verified by
injecting a dangling `@breadcrumb BC-999` and a real `setContentView()` call:
both were caught with file:line.

### Annotation Parser Edge Cases

**Facts**:
- `MiniJson` must distinguish a valid JSON `null` from a parse failure; use a
  sentinel, not `value() ?: return null`.
- `MiniJson.Parser.str()` must check `atEnd()` before `s[i]`, or `"{"` throws
  `StringIndexOutOfBoundsException` instead of returning null.
- The comment scanner skips string/char literals so `@breadcrumb` inside a
  string is not parsed as an annotation.

### Machine-Readable Comment Schema

- Schema: `docs/agent-comment-schema.md`; trail: `docs/agent-breadcrumbs.json`.
- `MachineCommentValidationTest` fails the build on an unknown tag, a dangling
  `BC-`/`ADR-` reference, invalid `@machine-json`, or an orphaned breadcrumb.
- Every `BC-NNN` entry must be referenced by at least one in-code annotation.

### demo_ui.h / demo_ui.cpp / demo_showcase.cpp Link Drift

**Problem**: `assembleDebug` failed with `ld.lld: error: undefined symbol:
lvgl_android::ui_dpi()` (and `ui_dpx`, `ui_make_panel`, `ui_decorative`,
`ui_make_column`, `ui_make_label`).

**Root Cause**: `demo_ui.h` declared the public `ui_*` UI-kit API and the new
`demo_showcase.cpp` consumed it, but `demo_ui.cpp` still defined the old static
names (`dpx`, `make_panel`, `make_column`, `make_label`, `decorative`). The
declared functions had no definition, so the linker failed.

**Fix**: Renamed the helpers in `demo_ui.cpp` to the `ui_*` names and dropped
`static` so they have external linkage; added `int ui_dpi()`. Removed the legacy
`dpx` declaration and the duplicate `ui_font` declaration from `demo_ui.h`.
Both arm64-v8a and x86_64 now link. See `BC-017`.

## Full Debug Pass (2026-10-31)

### Showcase Frame Callback Was Cleared at Thread Start

**Problem**: Tapping a showcase tile created a `Window` and called `open()`, but
the window never appeared (state stayed `Opening`, opacity stayed 0).

**Root Cause**: `lvgl_thread_func` calls `lvgl_anim::Engine::instance().reset()`
at thread start, and `reset()` cleared `frame_callback_`. The showcase sets its
callback while the screen is built — which happens *before* the render thread
starts — so the callback was wiped immediately.

**Fix**: `Engine::reset()` no longer clears `frame_callback_`; the showcase owns
its callback and clears it via `ui_reset_showcase()` when the screen is left.
See `BC-017`.

### Window Backdrop Participated in the Screen's Flex Layout

**Problem**: Opening a window made the launcher tiles vanish.

**Root Cause**: The window's 2000x2000 backdrop was a child of the screen, which
has a flex column layout. It consumed the column and pushed the tiles off-screen.

**Fix**: `LV_OBJ_FLAG_FLOATING` on both the backdrop and the container so they
ignore the parent's layout. The container also needs it so `lv_obj_set_pos()`
honours the animated x/y.

### Menu Showed Only Six Cards

**Problem**: The seventh menu card (`Showcase`) was unreachable from the UI.

**Root Cause**: `build_menu_screen` looped `for (int i = 0; i < 6; i++)` while
`items[]`/`ids[]` had 7 entries.

**Fix**: `i < 7`.

### `lv_indev_t` Leaked on Every Display Rebuild

**Problem**: `shutdown_display()` deleted the display but never the input device;
`init_display()` created a new one each time.

**Fix**: `lv_indev_delete(engine->indev)` + null in `shutdown_display()`.

### `LvglActivity` Used an API-30-Only Insets API

**Problem**: `android.view.WindowInsets.Type.systemBars()` crashes on API 24-29
(minSdk is 24).

**Fix**: `androidx.core.view.WindowInsetsCompat.Type.systemBars()`.

### Launcher Showed Almost No Apps

**Problem**: `getInstalledApplications()` returned a near-empty list on API 30+.

**Root Cause**: No `<queries>` declaration, so package visibility filtering hid
most packages.

**Fix**: Added `<queries><intent><action android:name="android.intent.action.MAIN"/>`
to `app/src/main/AndroidManifest.xml`. The launcher now lists 131 apps.

## M5Tab5 Dashboard Session (2026-11-03)

These were discovered while replacing the list menu with an M5Tab5-style device
dashboard (a full-screen Android schematic plus floating control panels).

### `lv_line_set_points()` Keeps the Point Pointer

**Problem**: Bus lines in the schematic were invisible.

**Root Cause**: `lv_line_set_points()` stores the caller's `lv_point_precise_t`
array instead of copying it. Passing a stack array left a dangling pointer, so
the widget drew garbage and self-sized to nothing.

**Fix**: Static per-bus point storage (`s_bus_points`) that outlives the lines.

**Note**: The line's self-size is `max(points.x), max(points.y)`, not a bounding
box; position the line at (0,0) and use parent coordinates.

### Short Taps Are Dropped Between LVGL Polls

**Problem**: `adb shell input tap` produced no click on some screens; a 200 ms
press always worked.

**Root Cause**: LVGL polls the input device once per rendered frame. With
software GL the emulator renders at ~3 fps, so a 2 ms DOWN/UP pair can land
entirely between two polls and was seen only as RELEASED.

**Fix**: `touch_read_cb` holds PRESSED until LVGL has polled the press once
(`touchPressSeen`, cleared on ACTION_DOWN). See BC-024.

**Note**: `adb shell input tap` emits DOWN and UP ~2 ms apart — always use a
slow `input swipe X Y X Y 200` when testing taps by hand.

### Navigator Lost After Returning From a Child Activity

**Problem**: After BACK from a sample, dashboard taps did nothing
(`drainNav id=N valid=0`).

**Root Cause**: A destroyed child activity sets the engine's navigator to null.
`onCreate` only re-registered it, and the parent's `onResume` runs *before* the
child's `onDestroy`, so an unconditional clear wiped the restored bridge.

**Fix**: `LvglActivity` stores its navigator in a field, re-registers it in
`onResume` when it differs, and clears it in `onDestroy` only if it is still
this activity's. See BC-025.

### Window Brightness Must Be Posted to the UI Thread

**Problem**: The LCD panel updated but the screen never dimmed.

**Root Cause**: Native calls `setBrightness` on the LVGL thread;
`WindowManager.LayoutParams.screenBrightness` triggers `View.requestLayout()`,
which throws `CalledFromWrongThreadException` (silently cleared by JNI).

**Fix**: `window.decorView.post { ... }` in `LvglActivity.setBrightness`.

**Note**: The emulator ignores brightness, so verify via logcat
(`LvglSystemControl: applyBrightness=...`), not screenshots.

### Clock Timer / Status Labels Must Be Torn Down on Every Screen Switch

**Problem**: After visiting the showcase, LVGL logged
`lv_obj_get_display: No screen found` repeatedly.

**Root Cause**: The showcase bypasses `make_screen()`, so the dashboard's clock
timer and RTC labels survived `lv_obj_clean()` and the timer kept touching freed
labels.

**Fix**: `launch_screen()` deletes `g_clock_timer` and clears every status-bar
label pointer before invoking the builder. It is the single entry point for all
screens. See BC-022.

### Emulator Renders at ~3 fps With SwiftShader

**Fact**: A 1080x2340 DIRECT-mode framebuffer with a per-pixel R/B swap is
expensive under `swiftshader_indirect`; frame times of 200-500 ms are normal.

**Consequences**:
- Animations and spring physics run slowly but remain frame-rate independent.
- Input polling is coarse; see the short-tap fix above.
- Do not mistake slow frames for an ANR.

## Beautiful-by-Default Session (2026-11-04)

### Empty `LV_SIZE_CONTENT` Containers in a Flex Row Become 480x480

**Problem**: The status-bar zones were reported as 480x480 squares by the layout
lint, and spacers stretched to 480 px tall.

**Root Cause**: A plain `lv_obj` with no content and `LV_SIZE_CONTENT` in a flex
row resolves its cross size to a 480 px default (`LV_DPI_DEF`) instead of 0.

**Fix**: Give such objects an explicit height (spacers use 1 px).

**Note**: Widgets (`lv_label`, `lv_button`, ...) compute their own self size and
are unaffected. Only empty plain containers are.

### Layout Lint Must Run After the First Refresh

**Problem**: `ui_layout_check()` reported garbage rectangles when called
immediately after building a screen.

**Root Cause**: LVGL computes object coordinates during the refresh; before the
first refresh `coords` is uninitialised.

**Fix**: `launch_screen` schedules a one-shot 500 ms timer that runs
`ui_layout_report()` after the first frame. Rectangles that fail a sanity check
are ignored.

### Layout Lint

- Tag: `LVGLLayout` (WARN) in debug builds.
- Report: `LvglEngine.dumpLayoutReport()` returns JSON
  (`{"issues":[{"type":"overlap|bounds|text",...}]}`).
- Detection: `ui_layout_check()` walks the tree and reports overlapping
  siblings (only for flex-laid parents), children outside their parent, and
  labels whose text overflows in `LV_LABEL_LONG_MODE_CLIP`.
- Overlays (`LV_OBJ_FLAG_FLOATING` / `IGNORE_LAYOUT`) are ignored by design.
- CI: `tools/dynamic-test.ps1` fails when any `LVGLLayout` warning appears.
- Validation logic lives in `LayoutLintRules` with canaries in
  `LayoutLintGuardTest` (BC-027).

## Device-Aware Dashboard Session (2026-11-04)

### Window Backdrop Opacity Was Overridden Per Frame

**Problem**: The showcase window's scrim stayed translucent; the launcher tiles
showed through around the window.

**Root Cause**: `Window::apply_anim_values()` set the backdrop opacity to
`window_opa * 0.4f` every frame, overriding the style.

**Fix**: The scrim now uses the window's own opacity (`LV_OPA_COVER`), so it is
fully opaque once the window is open.

### Flex Column Spread Two Labels to the Chip Edges

**Problem**: Chip name/sub-labels were pushed to the top and bottom edges of
their chip and clipped by 1-2 px.

**Root Cause**: A flex column distributes its children across the track; with
two content-sized labels the gap between them grew to fill the chip.

**Fix**: Map chips no longer use a flex column. The two labels are positioned
explicitly and centred as a block (`draw_map_chip()`).

### Device Info Reaches Native Through the Engine

**Facts**:
- `LvglDeviceInfo.collect(context)` reads `Build`, `ActivityManager.MemoryInfo`,
  `StatFs`, `DisplayMetrics`, `CameraManager` and `SensorManager`.
- `LvglActivity.pushDeviceInfo()` runs in `onCreate` before the dashboard is
  requested.
- `nativeSetDeviceInfo` copies the JNI strings into engine-owned storage (plain
  string copy, no LVGL objects, so it is safe from the UI thread).
- `rebuild_screen()` copies the engine strings onto the LVGL thread and calls
  `ui_set_device_info()` before building, so the first build already has them.
- `socMarketingName()` maps platform ids to marketing names; unknown ids fall
  back to a title-cased raw value, and `ranchu`/`goldfish` map to "Virtual SoC".

## Material Kit, Size and Toolkit Session (2026-11-04)

### Fixed-width Status-Bar Labels Can Overflow the Screen

**Problem**: The layout lint reported `child outside parent` for the battery
labels; the right edge of the status bar was clipped.

**Root Cause**: The status bar is a flex row of fixed-width labels. When the sum
of the fixed widths, `pad_column` gaps and `pad_left/right` exceeds the screen
width, the growing spacers collapse to zero and the last children overflow.

**Fix**: Keep the total fixed width under the screen width — the title is 80 dp
with an explicit height, `pad_column` is `g_tx.sm` (8 dp), and the date/clock
labels are sized to a sample string.

**Note**: The status bar is 52 dp tall; the title label needs an explicit height
(see below).

### `LV_LABEL_LONG_MODE_DOTS` Wraps Unless the Label Has a Fixed Height

**Problem**: The device-model title wrapped to two lines instead of truncating
with dots.

**Root Cause**: DOTS mode wraps the text into the label's height and only adds
dots to the last line. With `LV_SIZE_CONTENT` height the label grows to fit two
lines, so nothing is truncated.

**Fix**: Set an explicit single-line height (e.g. `ui_dpx(24, dpi)`) on labels
that must truncate with dots. `ui_fit_label()` is the alternative for shrinking
text instead.

### Library `isMinifyEnabled = true` Breaks `lintVitalRelease`

**Problem**: `assembleRelease` failed with `MainActivity must extend
android.app.Activity [Instantiatable]` for every activity, although the classes
were kept by the consumer ProGuard rules.

**Root Cause**: With the **library** module's `isMinifyEnabled = true`, lint's
release-vital class model cannot resolve the activity superclass that lives in
the library. `lintDebug` passes; only `lintVitalRelease` fails.

**Fix**: Keep `isMinifyEnabled = false` on the library module. Libraries are not
minified in place — the consumer app's R8 pass tree-shakes the whole graph. See
BC-031.

### Static C++ STL Removes `libc++_shared.so`

**Fact**: `ANDROID_STL=c++_static` links the STL into `liblvgl_android.so`, so the
per-ABI APK no longer ships a separate `libc++_shared.so` (~1.3 MB). The main
`.so` grows slightly, but the APK shrinks overall.

**Note**: The app has no other native code, so there is no STL symbol conflict.

### `useLegacyPackaging = true` Trades Install Size for APK Size

**Fact**: Compressing the native libraries inside the APK (`useLegacyPackaging =
true`) reduces the download size (the APK is zipped) at the cost of a larger
installed footprint, because Android extracts the libraries on install.

### Build Artifacts Were Tracked in Git

**Problem**: `app/build/`, `lvgl-android/build/`, `.gradle/` and `.cxx/` were
committed, inflating the repository and producing noisy diffs.

**Fix**: `.gitignore` now covers them and `git rm -r --cached` removed them from
the index. The files remain on disk.

### The Template Uses a Composite Build

**Fact**: `template/` is a standalone Gradle project that `includeBuild`s the
framework repository root and substitutes
`com.lvgl.android:lvgl-android` with `:lvgl-android`. The library module declares
`group = "com.lvgl.android"` and `version` from `gradle.properties`, so the
substitution resolves. Copy the Gradle wrapper from the framework repo into a
copied template.

## Kotlin UI Builder Session (2026-11-04)

### App Screen Ids Must Be >= 1000

**Problem**: `LvglUi.registerScreen(1000, ...)` followed by `setLvglScreen(1000)` showed the dashboard instead of the app screen.

**Root Cause**: `nativeSetScreen` rejected any id outside `0..7` (`if (screenId < 0 || screenId >= SCREEN_COUNT) return;`).

**Fix**: accept ids `>= APP_SCREEN_BASE` (1000); `launch_screen` dispatches those to `ui_build_app_screen`. See BC-033.

### Node Callbacks Do Not Cross JNI

**Problem**: `LvglUi.listener` was set, but the `onClick`/`onChange` lambdas in the DSL never fired.

**Root Cause**: only the serialised tree crosses JNI; the Kotlin lambdas stay in Kotlin. The native side reports a node id and an event type, and `LvglUi` must dispatch that to the callback captured at registration time.

**Fix**: `registerScreen` builds a `nodeId -> handler` map from the assigned tree; `receiver` invokes it before the screen-level listener.

### The Receiver Must Be Installed Independently of the Listener

**Problem**: no events arrived when an app used only node callbacks and never set `LvglUi.listener`.

**Root Cause**: the native listener was only installed when `listener` was set.

**Fix**: `registerScreen` always sets `LvglEngine.instance.uiEventListener = receiver`.

### LVGL Returns the Topmost Object, Clickable or Not

**Fact**: `lv_indev_search_obj` returns the deepest object under the point whose hit test passes, regardless of `LV_OBJ_FLAG_CLICKABLE`. For a "tap anywhere" surface, attach the handler to an object that actually receives the press (a button, or an explicitly clickable container) and hit-test manually.

### The Game Target Is a Button

**Decision**: the tap-the-target demo uses a `lv_button` as the target so LVGL's normal click path applies. A plain `lv_obj` child of a non-clickable container did not receive `LV_EVENT_PRESSED` reliably.

### Screenshot Coordinates Are Not Input Coordinates

**Trap**: screenshots rendered in the review pane are scaled, so coordinates read from them miss the target. Use `adb shell input tap` with the physical resolution (`adb shell wm size`) and verify with a log of the indev point.

## Showcase Window & Launcher Session (2026-11-04)

### Window Content Was Vertically Centred

**Problem**: the showcase windows showed a large empty gap between the header title and the content, and tall content could overflow the window.

**Root Cause**: `Window::create` set the content's main-axis alignment to `LV_FLEX_ALIGN_CENTER`.

**Fix**: top-align the content (`LV_FLEX_ALIGN_START`) and add a consistent 12 dp `pad_row`.

### Gauge Columns Overlapped

**Problem**: the layout lint reported overlapping siblings in the System window; the two gauge columns were wider than half the window.

**Root Cause**: the columns were content-sized and inherited theme padding, so two 174 dp columns exceeded the window width.

**Fix**: `lv_obj_set_flex_grow(col, 1)` + `pad_all(0)` and a 132 dp arc.

### Sensor Names Sat on the Bars

**Problem**: the Sensors window drew the sensor names on top of the progress bars.

**Root Cause**: the bar had a fixed 260 dp width; together with the name it exceeded the row width, so the flex layout overlapped them.

**Fix**: the bar grows to fill the row (`flex_grow(1)`) and the name takes its natural width.

### The Launcher Grid Cell Size Was Unreliable

**Problem**: `ui_make_grid` produced cells larger than the requested size and the layout lint reported children outside the parent.

**Fix**: the launcher uses a plain column of full-width rows, which is robust at any density and width.

### Arc Gauges Must Not Be Clipped

**Problem**: the showcase gauges were cropped at the top and sides.

**Root Cause**: an arc's stroke is centred on its radius (`min(w,h)/2`), so it extends past the widget bounds; in the old layout the column also had theme padding that made the arc larger than its container.

**Fix**: the gauge columns use `flex_grow(1)` with `pad_all(0)`, the arcs are 132 dp, and the columns set `LV_OBJ_FLAG_OVERFLOW_VISIBLE` so the stroke is never clipped.

### Google Play Compliance (2026-11-04)

**Requirements enforced by `PlayComplianceGuardTest`** (see `docs/play-store.md`):

- `targetSdk >= 36` (the Play requirement from 31 August 2026).
- `arm64-v8a` is present (64-bit requirement).
- The manifest declares `dataExtractionRules`, `fullBackupContent`,
  `enableOnBackInvokedCallback`, `resizeableActivity` and `localeConfig`.
- Only the optional `CAMERA` permission is requested.
- `PRIVACY.md`, `docs/play-store.md`, `backup_rules.xml`,
  `data_extraction_rules.xml`, `locales_config.xml` and the licence screen exist.

**Trap**: adding a permission makes `PlayComplianceGuardTest` fail — Play
requires a policy justification for every permission.

## CI Emulator Session (2026-11-05)

### The CI Emulator Has No Hardware Acceleration

**Fact**: GitHub's `ubuntu-latest` runners do not expose `/dev/kvm`. The
`android-emulator-runner` logs:

```
You're running a Linux VM where hardware acceleration is not available.
...
disable Linux hardware acceleration: true
```

The guest is pure software emulation: `connectedDebugAndroidTest` takes about
9 minutes for 5 tests and the whole `dynamic` job about 25 minutes.

**Consequence**: system processes are starved and crash. The Bluetooth stack
(`droid.bluetooth`) logs `AdapterState: Start Timeout Delay: 4000` and then
`Fatal signal 6 (SIGABRT)`. This is environmental noise, not an app defect.

### "Crash/ANR detected in logcat" Was a False Positive

**Problem**: `tools/dynamic-test.ps1` failed the CI walk with
`Crash/ANR detected in logcat`, although the same walk passed locally and the
app had not crashed.

**Root Cause**: the pattern `FATAL|SIGSEGV|JNI DETECTED ERROR|start timeout|ANR in com\.lvgl`
is case-insensitive and matched the Bluetooth stack's
`Start Timeout Delay: 4000` and its `Fatal signal 6 (SIGABRT)` in
`pid 3585 (droid.bluetooth)`.

**Fix**: match only lines that name the sample app — `ANR in com.lvgl.sample`,
a native tombstone (`>>> com.lvgl.sample <<<`), a Java crash
(`Process: com.lvgl.sample, PID:`) or `pid NNN (com.lvgl.sample)`.
`DynamicHarnessGuardTest` and `GuardRules.detectUnscopedCrashPattern` keep the
pattern honest. See BC-036.

**Trap**: never widen the pattern to bare `FATAL`, `SIGSEGV` or
`start timeout` — they match system-process noise on an unaccelerated emulator.

### android-emulator-runner Executes the Script Line by Line

**Fact**: the `reactivecircus/android-emulator-runner` `script` input is run one
line at a time, each as a separate `sh -c` — the log shows
`[command]/usr/bin/sh -c <line>` for every line. Multi-line constructs (shell
functions, `for`/`while` loops, `if` blocks) are split apart and fail with
`Syntax error: end of file unexpected (expecting "}")`.

**Fix**: keep the workflow `script` to a single line and put any multi-line
logic in a script file — see `tools/ci-dynamic.sh`, called as
`script: sh tools/ci-dynamic.sh`.

### The Emulator Sometimes Comes Up Offline

**Problem**: occasionally the `dynamic` job fails straight away with
`ERROR | Unable to connect to adb daemon on port: 5037` followed by
`adb: device offline` for the rest of the run; `connectedDebugAndroidTest` then
fails with "There were failing tests".

**Root Cause**: the runner has no KVM (`ProbeKVM: This user doesn't have
permissions to use KVM (/dev/kvm)`), so x86_64 emulation is software-only and
the emulator sometimes never reaches the host adb daemon.

**Fix**: the CI `dynamic` script restarts the emulator (`adb emu kill`, then a
`pkill` fallback, then a fresh boot) and retries the whole sequence up to three
times. A failure on a *healthy* device is never retried — that is a real test
failure.

**Trap**: do not "fix" this by ignoring `connectedDebugAndroidTest` failures; a
device that is offline is not a test result.

### Screenshot Coordinates Must Come From the Device, Not the Preview

**Fact**: the review pane scales screenshots, so a coordinate read off a
screenshot misses the target. Probe by pixel analysis instead — find the rows
containing the widget's fill colour, then tap the device coordinate
(`adb shell wm size`).

**Note**: `input swipe X Y X Y 250` is enough for LVGL (the touch callback holds
PRESSED until polled), but use 400 ms to be safe.


