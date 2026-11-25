# Public API reference

Concise reference for the public Kotlin API in `com.lvgl.android`, updated for
**v1.2.0**. It documents only what exists in
`lvgl-android/src/main/java/com/lvgl/android/` — internal helpers such as the
accessibility node provider are deliberately not listed. For a step-by-step
walkthrough see [quickstart.md](quickstart.md); for the architecture and trade
offs see [README.md](../README.md); for using these APIs inside an ordinary
Android app see [interop.md](interop.md).

Visibility notes: classes that apps are expected to subclass are marked
`abstract`/`open`; constructors and methods shown are public unless noted.

## activity

### `LvglActivity` (abstract)

Base class for every LVGL-rendered screen. It owns the root layout and
`LvglSurfaceView`, initializes the process-wide engine, applies the Material
theme, forwards touch events and re-asserts its screen id in `onResume()`.
Subclasses implement `onCreateLvgl()` and must **not** call `setContentView()`.
Protected hooks: `setLvglScreen(screenId)`, `immersiveMode`,
`applyImmersiveMode()`. Open callbacks: `onLvglThemeChanged(theme)`,
`onLvglMetricsChanged(metrics)`. The companion `screenResolver` maps native
screen ids to activity classes for navigation.

```kotlin
class MyActivity : LvglActivity() {
    override fun onCreateLvgl() { setLvglScreen(0) }
}
```

### `LvglActivity.screenResolver` (companion property)

`var screenResolver: ((Int) -> Class<*>?)?` — maps a native screen id to the
activity that hosts it. Set it before `super.onCreate()`.

```kotlin
LvglActivity.screenResolver = { screenId -> if (screenId == 1) DetailActivity::class.java else null }
```

### `setupAccessibility()` / `AccessibilitySetup`

Top-level extension `LvglActivity.setupAccessibility()` that wires
`LvglAccessibilityManager`, `LvglFocusNavigation` and `LvglScreenReader`, and
returns an `AccessibilitySetup(manager, focusNavigation)` holder.

```kotlin
val setup = setupAccessibility()
```

## engine

### `LvglEngine`

Process-wide singleton that owns the native LVGL engine. Activities bind a
display with `initDisplay(owner, surface, width, height, dpi)` and release it
with `deinitDisplay(owner)`; the engine itself outlives activities. Also exposes
`initialized`, `initialize()`, `resume()`, `pause()`, `shutdown()`, `onTouchEvent()`,
`applyTheme()`, `setScreen()`, `setStatusText()`, `setBatteryLevel()`,
`setDeviceInfo()`, `isDisplayReady()`, `currentScreen()`, `captureFrame()`,
`lastError()`, `dumpLayoutReport()`, `registerScreen()`, `unregisterScreen()`,
`updateNode()`, `nativeLoadError` and the `screenNavigator` / `uiEventListener`
properties. Access it via `LvglEngine.instance`.

If `liblvgl_android.so` cannot be loaded, `initialize()` records the reason in
`nativeLoadError` instead of crashing, so an app can show a friendly message.

```kotlin
LvglEngine.instance.setStatusText("Ready")
```

### `LvglEngine.ScreenNavigator` (interface)

Callback bridge used by native LVGL buttons: `navigateTo(screenId)`,
`navigateBack()`, `setBrightness(brightness: Float)`,
`setVolumePercent(percent: Int)` and `toggleDarkMode()` (the last three have
default no-op implementations). `LvglActivity` registers its own navigator
automatically.

```kotlin
LvglEngine.instance.screenNavigator = object : LvglEngine.ScreenNavigator {
    override fun navigateTo(screenId: Int) { /* ... */ }
    override fun navigateBack() { /* ... */ }
}
```

## ui

### `LvglUi`

Registry of **app-defined screens** built with the Kotlin DSL. A screen is a
tree of [LvglNode]s serialised to JSON and rendered by the native UI kit, so it
follows the Material 3 palette and is covered by the layout lint. App screen ids
start at `LvglUi.APP_SCREEN_BASE` (1000); `0..7` are the built-in screens.

```kotlin
LvglUi.registerScreen(1000, title = "Hello") {
    column {
        label("Hello from Kotlin!", sizeDp = 20)
        button("Tap me") {
            LvglUi.updateNode(1000, "status", text = "Tapped")
        }
    }
}

class HelloActivity : LvglActivity() {
    override fun onCreateLvgl() = setLvglScreen(1000)
}
```

Node callbacks (the trailing lambdas of `button`, `slider`, `toggle` and
`checkbox`) are invoked on the LVGL thread; [LvglUi.listener] is an additional
screen-level hook. [LvglUi.updateNode] patches text, value, checked state or
visibility from any thread.

### `LvglUiScope`

The DSL scope. Containers: `column(card, gapDp, padDp) { ... }` and
`row(...)`. Leaves: `label`, `button`, `slider`, `toggle`, `checkbox`,
`progress`, `arc`, `dropdown`, `divider`, `spacer`. Every builder accepts an
optional `id` used by [LvglUi.updateNode].

```kotlin
dropdown(listOf("A", "B", "C"), selectedIndex = 0, id = "pick") { index ->
    LvglUi.updateNode(1000, "value", text = "Selected: ${index + 1}")
}
```

### `LvglNode`

Sealed hierarchy of the supported node types: `Column`, `Row`, `Label`,
`Button`, `Slider`, `Switch`, `Checkbox`, `Progress`, `Arc`, `Dropdown`,
`Divider`, `Spacer`.

### `LvglUiEvent` / `LvglUiListener`

Events emitted by app-defined nodes: `Click(nodeId)`, `ValueChanged(nodeId,
value)`, `Toggled(nodeId, checked)` and `Selected(nodeId, index)`.
`LvglUi.listener` receives them with the screen id.

## view

### `LvglSurfaceView` (open)

`SurfaceView` that hosts the LVGL render surface. It binds its surface to the
shared engine on `surfaceChanged`, rebinds on recreation, and forwards
`MotionEvent`s to `LvglEngine.onTouchEvent()` (actions: 0 = down, 1 = up,
2 = move). Public lifecycle methods: `onResume()`, `onPause()`, `destroy()`.
Since v1.2.0 it also overrides `getAccessibilityNodeProvider()` to expose
virtual TalkBack nodes for `LvglUi` app screens (ids ≥ 1000); see
[accessibility.md](accessibility.md).

It is a normal view: host it in a plain Activity/Fragment layout or a Compose
`AndroidView`, forwarding the lifecycle methods above. Only one display is live
at a time. [interop.md](interop.md) has the recipes.

```kotlin
val view = LvglSurfaceView(context)
```

### `LvglSurfaceViewWithGestures` (open)

Drop-in replacement for `LvglSurfaceView` that runs an `LvglInputProcessor`
on every touch (raw events still reach LVGL) and exposes the last detected
gesture.

```kotlin
val view = LvglSurfaceViewWithGestures(context)
val last: Gestures? = view.getLastGestureResult()
```

## theme

### `MaterialTheme`

Singleton that resolves the Material 3 tonal palette for the app, following the
system dark/light mode and (on API 31+) Material You dynamic colors. Exposes
`isDarkMode`, `useDynamicColors`, `primaryColor`, `secondaryColor`,
`surfaceColor`, `onSurfaceColor`, `backgroundOpacity`, `materialPalette()`,
`initialize(context)`, `onConfigurationChanged(context, config)`,
`setDynamicColors(context, enabled)`, `addListener()`, `removeListener()` and
the nested `OnThemeChangedListener`.

```kotlin
MaterialTheme.instance.initialize(this)
```

### `MaterialPalette`

Object holding the fixed-order Material 3 role indexes (`BACKGROUND`, `SURFACE`,
`PRIMARY`, `ON_PRIMARY`, `ERROR`, … `SIZE`) and the palette factories `light()`,
`dark()` and `dynamic(context, dark)`. The index order is shared with the native
UI kit; do not reorder it.

```kotlin
val primary = MaterialTheme.instance.materialPalette()[MaterialPalette.PRIMARY]
```

### `LvglThemeBridge`

`MaterialTheme.OnThemeChangedListener` that pushes the resolved palette into
LVGL. Use `applyCurrentTheme()` once, or let `LvglActivity` do it.

```kotlin
LvglThemeBridge().applyCurrentTheme()
```

## config

### `DpiManager`

Density and screen-size helper: `getDpi()`, `getDensity()`, `dpToPx()`,
`pxToDp()`, `spToPx()`, `pxToSp()`, `getScreenWidthPx()`,
`getScreenHeightPx()`, `getScreenWidthDp()`, `getScreenHeightDp()`,
`getOrientation()`, `isLandscape()`, `isPortrait()`, `getSizeCategory()`.
`getSizeCategory()` returns the nested `ScreenSize` enum (`SMALL`, `MEDIUM`,
`LARGE`, `EXTRA_LARGE`).

```kotlin
val dpi = DpiManager(this).getDpi()
```

### `DisplayMetricsManager`

Caches density, screen size and orientation and notifies listeners on
configuration changes. Properties: `densityDpi`, `density`, `scaledDensity`,
`screenWidthDp`, `screenHeightDp`, `smallestScreenWidthDp`, `isLandscape`,
`screenWidthPx`, `screenHeightPx`. Methods: `update(context)`,
`onConfigurationChanged(newConfig)`, `addListener()`, `removeListener()`.

```kotlin
val metrics = DisplayMetricsManager(this).apply { update(this@MainActivity) }
```

### `ConfigurationManager`

Computes a `ConfigurationEvent` (orientation, uiMode, screen size, smallest
screen width, font scale and locale flags plus the new `Configuration`) and
notifies `OnConfigurationChangedListener`s.

```kotlin
ConfigurationManager(this).apply { addListener(listener); update(config) }
```

## window

### `MultiWindowManager`

Tracks multiwindow, picture-in-picture and resumed state for an activity:
`isInMultiWindowMode`, `isInPictureInPictureMode`, `isResumed`,
`isMultiWindowSupported()`, `onMultiWindowModeChanged()`,
`onPictureInPictureModeChanged()`, `onResumed()`, `onPaused()` and
`enterPictureInPictureMode(aspectRatio)`.

```kotlin
MultiWindowManager(this).enterPictureInPictureMode()
```

## input

### `LvglInputMethodService` (abstract)

Base class for LVGL keyboards. Implements `InputMethodService` and manages the
engine lifecycle. Subclasses implement `onCreateKeyboardView(): View?` and
optionally override the protected `ownsProcess` (set `true` when the IME runs
in its own process so the engine may be shut down). Helpers:
`getActiveInputConnection()`, `commitText()`, `deleteSurroundingText()`,
`sendKeyDownUp()`.

```kotlin
class MyKeyboard : LvglInputMethodService() {
    override fun onCreateKeyboardView(): View? = LvglSurfaceView(this)
}
```

### `LvglInputProcessor`

Forwards every `MotionEvent` to LVGL and detects gestures alongside it. Returns
the detected `Gestures?` for each event; raw touch is never dropped.

```kotlin
val gesture = LvglInputProcessor(context).processMotionEvent(event)
```

### `GestureRecognizer`

Standalone gesture detector (tap, long press, swipe, pinch, rotate) with a
tunable `Config`. Feed it `MotionEvent`s with `handle(event)`, then read
`consumeLastGesture()`; `reset()` clears the state.

```kotlin
val gesture = GestureRecognizer().handle(event)
```

### `TouchEvent`

Data class describing an LVGL-compatible touch event (action, coordinates,
pointer id/index/count, timestamps, pressure, tool type).

```kotlin
TouchEvent(action = 0, x = 10f, y = 20f, pointerCount = 1, pointerIndex = 0)
```

### `Gestures`

Sealed hierarchy of detected gestures: `Tap(x, y)`, `LongPress(x, y, duration)`,
`Swipe(direction, velocityX, velocityY)`, `Pinch(scaleFactor, centerX, centerY)`,
`Rotate(rotation, centerX, centerY)` and the `Direction` enum (`UP`, `DOWN`,
`LEFT`, `RIGHT`).

```kotlin
when (gesture) {
    is Gestures.Swipe -> handleSwipe(gesture.direction)
    else -> {}
}
```

### `HardwareKeyboardHandler`

Translates hardware/D-pad key events into `KeyCode` callbacks. `processKeyEvent(event)`
returns `true` when it handled the key; the nested `KeyCode` enum covers arrows,
Enter, Backspace, Tab, Escape, Home, End, Page Up/Down, Space and Unknown.

```kotlin
val handler = HardwareKeyboardHandler(this) { code, pressed -> }
```

## launcher

### `LvglLauncherActivity` (abstract)

`LvglActivity` subclass for home-screen apps. Adds app-list helpers
`getDefaultHomeIntent()`, `getAppList()`, `launchApp(packageName)` and declares
package-change callbacks `onAllAppsChanged()`, `onPackageAdded(packageName)`,
`onPackageRemoved(packageName)`, `onPackageChanged(packageName)`.

```kotlin
class MyLauncher : LvglLauncherActivity() {
    override fun onCreateLvgl() { setLvglScreen(0) }
    override fun onAllAppsChanged() {}
    override fun onPackageAdded(packageName: String) {}
    override fun onPackageRemoved(packageName: String) {}
    override fun onPackageChanged(packageName: String) {}
}
```

### `LvglEngine.setLauncherApps` / `launcherAppListener`

Push the installed-app list to the native launcher grid and receive taps:

```kotlin
LvglEngine.instance.setLauncherApps(labels, packages)
LvglEngine.instance.launcherAppListener = LauncherAppListener { pkg ->
    runOnUiThread { launchApp(pkg) }
}
```

`LauncherAppListener` is a fun interface with `onAppSelected(packageName)`; the
callback runs on the LVGL thread, so switch to the UI thread before starting an
activity.

## game

### `LvglGameActivity` (abstract)

`LvglActivity` subclass for games. Picks the highest refresh rate on Android 11+
(`targetFps`, clamped 30–144), keeps immersive mode, and declares the
`onGameLoop(deltaMs: Long)` callback. Also provides `lockOrientation()` /
`unlockOrientation()`.

```kotlin
class MyGame : LvglGameActivity() {
    override fun onCreateLvgl() { targetFps = 60 }
    override fun onGameLoop(deltaMs: Long) { /* update state */ }
}
```

## camera

### `LvglCameraPreview`

Camera2 preview helper. `open(cameraId = "0")` opens the camera and starts a
repeating preview; with no surface supplied it drains frames into an internal
`ImageReader` so it does not fight LVGL for the window. Call
`startPreview(surface)` **before** `open()` to target a caller-supplied
`Surface` — the sample passes the surface of a native `SurfaceView` composited
above the LVGL screen. `onOpenResult` reports the real open outcome (on the
Camera2 thread), `allCameraIds()` enumerates logical and physical sensors, and
`stopPreview()` / `close()` release the session and device.

Since v1.2.0: `selectPreviewSize(cameraId, target)` picks the available
`YUV_420_888` size whose aspect ratio is closest to `target` and stores it;
`chosenPreviewSize` exposes the selected size. Apps rendering into their own
surface should size/letterbox it to that aspect instead of stretching it.

```kotlin
val preview = LvglCameraPreview(this)
val size = preview.selectPreviewSize("0", Size(width, height))
preview.startPreview(surface)
preview.open("0")
```

The `CAMERA` permission is required and must be requested at runtime; denial
leaves the rest of the app usable. See [interop.md](interop.md) for the full
overlay pattern.

## system

### `LvglSystemControl`

Object that applies the dashboard's functional controls to real Android APIs:
`applyBrightness(window, percent)` (1–100), `applyVolumePercent(context, percent)`
(music stream), `batteryLevel(context)` and `isCharging(context)`.

```kotlin
LvglSystemControl.applyBrightness(window, 80)
```

### `LvglDeviceInfo`

Collects the real device facts shown on the dashboard map. `collect(context)`
returns a `Facts` data class (manufacturer, model, soc, memory, storage,
display, androidVersion, cameras, sensors); `socMarketingName(hardware)` maps
platform ids to marketing names and `formatSize(bytes)` formats sizes.

```kotlin
val facts = LvglDeviceInfo.collect(this)
```

## util

### `LvglLog`

Thin wrapper over `android.util.Log` with a configurable `level` and `d()`, `i()`,
`w()`, `e()` overloads.

```kotlin
LvglLog.d(TAG, "engine ready")
```

## navigation

### `LvglNavigation`

Screen back-stack helper: `pushScreen()`, `popScreen()`, `getBackStack()`,
`clearStack()`, `setMainActivity()`, `getMainActivity()`, `navigateTo(activity,
target)` and `navigateBack(activity)`.

```kotlin
LvglNavigation.pushScreen("Dashboard")
```

## accessibility

### `LvglAccessibilityManager`

Wraps the system `AccessibilityManager`: `isEnabled`, `isTouchExplorationEnabled`,
`addListener()`, `removeListener()`, `announce(text)`, `setFocusable()` and
`setContentDescription()`. Listeners are notified via the nested
`OnAccessibilityChangedListener`.

```kotlin
val enabled = LvglAccessibilityManager(this).isEnabled
```

### `LvglAccessibilityHelper`

Object with `@JvmStatic` stubs `announce(context, text)`, `setFocusable(viewId,
focusable)` and `setContentDescription(viewId, description)`.

```kotlin
LvglAccessibilityHelper.announce(this, "Focused")
```

### `LvglAccessibilityDelegate`

Per-view focus delegate: `dispatchFocusGained(description)` and
`dispatchFocusLost()`.

```kotlin
LvglAccessibilityDelegate(this, "play_button").dispatchFocusGained("Play")
```

### `LvglFocusNavigation`

D-pad/keyboard focus navigation over a list of numeric focus ids:
`addFocusable()`, `removeFocusable()`, `setFocused()`, `getFocused()` and
`handleKeyEvent(keyCode)`. The nested `FocusDirection` enum is `UP`, `DOWN`,
`LEFT`, `RIGHT`.

```kotlin
LvglFocusNavigation(this).handleKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT)
```

### `LvglScreenReader`

Object that announces LVGL focus changes: `initialize(context)` and
`announce(activity, text)`.

```kotlin
LvglScreenReader.announce(this, "Widget showcase")
```

### Accessibility coverage note

The public classes above (`LvglAccessibilityManager`, `LvglScreenReader`,
`LvglAccessibilityDelegate`, `LvglFocusNavigation`) provide announcements,
state and focus navigation. TalkBack virtual nodes for `LvglUi` app screens are
provided internally by `LvglSurfaceView.getAccessibilityNodeProvider()`; they
are documented, but not a public ABI, in [accessibility.md](accessibility.md).

## perf

### `LvglPerformanceMonitor`

Object that tracks FPS and frame times: `start()`, `stop()`,
`onFrameRendered()`, `getFps()`, `getFrameCount()`, `getStats()` and
`resetStats()`. Call `onFrameRendered()` once per rendered frame, from a single
thread. There is no native frame-callback JNI export yet, so the app must
drive this sink (the game sample does); call all methods from the same thread.
It is a debugging aid, not a production profiler. See
[performance.md](performance.md) for the measurement command and caveats.

```kotlin
LvglPerformanceMonitor.start()
LvglPerformanceMonitor.onFrameRendered()
val stats = LvglPerformanceMonitor.getStats()
```
