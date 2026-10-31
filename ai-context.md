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
