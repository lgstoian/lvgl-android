# Motion configuration (v1.3.0)

`LvglMotionConfig` (`com.lvgl.android.motion`) makes LVGL's scrolling and
spring/transition timing follow the **user's device** instead of a fixed set of
numbers chosen by the framework. It reads the same platform values Android
apps use, logs what it found, and pushes the result through
`LvglEngine.applyMotionConfig()` to native code.

This is default SDK behaviour: an app that does nothing still gets motion
derived from ROM settings. Apps that need a specific feel can override or
disable it (see below).

## 1. What is polled

The poll is a **snapshot of platform values** — it does not start an animation
or wait for user input:

| Source | Values read | Used for |
| --- | --- | --- |
| `ViewConfiguration` | scaled minimum/maximum fling velocity, scroll friction, scaled touch slop, tap timeout, long-press timeout | LVGL scroll throw / fling behaviour and the mapping of input velocity to a throw fraction |
| `Settings.Global` | `ANIMATOR_DURATION_SCALE`, `TRANSITION_ANIMATION_SCALE`, `WINDOW_ANIMATION_SCALE` | scaling spring and window transition durations (including "animations off" for accessibility) |
| Framework resources | `android.R.integer.config_shortAnimTime`, `config_mediumAnimTime`, `config_longAnimTime` | the Short / Medium / Long spring roles used by the component kit |
| Framework interpolator | `android.R.interpolator.fast_out_slow_in` sampled at `t = 0.5` | shaping the non-linear spring/transition curve |

Every read is a **single-shot, bounded call with a `try/catch` and a documented
fallback**. A missing setting, a restricted ROM or an unusual resource value
cannot block startup. There is no polling loop, no busy wait and no unbounded
`wait()`; a `ContentObserver` on the three `Settings.Global` keys re-polls
asynchronously when the user changes an animation scale. No USB/device access
is involved.

**Trap:** do not replace these reads with hard-coded "nice" values. A hard-coded
duration ignores the user's accessibility setting and defeats the port; the
whole point of v1.3.0 is that the values come from the device.

## 2. What is applied by default

- `LvglActivity` calls `LvglMotionConfig.install(context)` on create and again
  on configuration change. It is enabled by default with no override set.
- `LvglMotionConfig.install()` polls (or uses an override), logs an INFO line
  with the polled values, and calls `LvglEngine.applyMotionConfig(values)`.
- `LvglEngine` forwards the values over JNI through
  `nativeSetMotionConfig(handle, values)`; the native side stores them and
  schedules an LVGL-thread task so a runtime scale change applies live.
- Native code configures the input device with
  `lv_indev_set_scroll_throw(...)` (plus scroll limit and gesture minimum
  velocity), so flings decelerate like the platform's own scroll views.
- Spring and window transition durations come from the polled framework roles
  (`Short`, `Medium`, `Long`, `Bouncy`, `Wobbly`) instead of fixed constants.
  The Material 3 component kit, showcase windows and dashboard animations all
  read those roles.

Poll results and applied values are logged at INFO. Check them with:

```powershell
& "D:\ASDK\platform-tools\adb.exe" logcat -d -s "LvglMotionConfig"
```

Fail-closed guards verify these call paths: `V130GuardTest` checks that the
activity installs the config, that the engine declares **and calls** the native
setter, and that native code calls `lv_indev_set_scroll_throw()`. See
[BC-014/BC-037 in the breadcrumb trail](agent-breadcrumbs.json).

## 3. Overriding or disabling the defaults

`poll(context)` produces an immutable snapshot; `setOverride(config)` makes
every later `install()` push that snapshot instead of polling, and
`setEnabled(false)` stops default polling and the settings observer entirely.
Both are process-wide and volatile, so set them before the first activity's
`onCreateLvgl()` if you want them to affect the first screen.

```kotlin
import com.lvgl.android.motion.LvglMotionConfig

class MyActivity : LvglActivity() {
    override fun onCreateLvgl() {
        // Use the device values, but pin the animation scales to a fixed feel.
        val snapshot = LvglMotionConfig.poll(this).copy(/* your values */)
        LvglMotionConfig.setOverride(snapshot)
        // setLvglScreen(...)
    }
}
```

```kotlin
// Opt out completely: no polling, no observer, no native push from LvglActivity.
LvglMotionConfig.setEnabled(false)
LvglMotionConfig.setOverride(null)
```

An app that opts out keeps LVGL's own defaults; nothing is removed from the
framework and other apps in the same process are unaffected only if they share
the setting intentionally — the configuration is process-wide, like the engine.

**Trap:** do not push a snapshot from the UI thread into LVGL widgets directly.
`install()` is the single path that marshals values to the LVGL thread.

## 4. Scope and caveats

- Values are polled at activity create/configuration change and when a watched
  `Settings.Global` key changes. A ROM that does not expose a key uses the
  documented fallback; the log line tells you which values were actually found.
- The polled values describe the platform's intent, not GPU completion time.
  They do not make the custom render thread phase-locked to vsync; see
  [performance.md](performance.md) for measurement caveats.
- These APIs are additive. `LvglActivity` subclasses still must not call
  `setContentView()`, and LVGL-thread callbacks still must hop to the UI thread
  before touching Android views.

## 5. Public API summary

| Member | Meaning |
| --- | --- |
| `LvglMotionConfig.poll(context)` | Read a snapshot of platform motion values (bounded, with fallbacks). |
| `LvglMotionConfig.install(context)` | Poll (or use the override), log and push to the native engine. Called by `LvglActivity` by default. |
| `config.apply()` / `config.logPolled()` / `config.logApplied()` | Apply an existing snapshot, or log the polled/applied values. |
| `LvglMotionConfig.setOverride(config?)` | Pin a snapshot for all later installs; `null` resumes polling. |
| `LvglMotionConfig.setEnabled(enabled)` | Enable/disable default polling and the settings observer. |
| `LvglEngine.applyMotionConfig(values)` | Push a `FloatArray` of motion values over JNI. |

See [public-api.md](public-api.md) for the rest of the API.
