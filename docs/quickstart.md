# Your first LVGL Android app in 5 minutes

This guide gets a minimal LVGL app running against the framework in this
repository. For the full API surface see [public-api.md](public-api.md); for a
ready-made project see [template/](../template/README.md); for build
environment setup see [BUILDING.md](../BUILDING.md).

## 1. Prerequisites

- JDK 17
- Android SDK API 24+ (the framework is compiled against API 36)
- Android NDK r26b and CMake 3.22.1 (only needed to build the library; a
  published AAR would not need them)
- A device or emulator running API 24+

Set the environment before building (Windows example from
[BUILDING.md](../BUILDING.md)):

```powershell
$env:JAVA_HOME = "C:\Users\lgstoian\jdk-17\jdk-17.0.12"
$env:ANDROID_HOME = "D:\ASDK"
```

## 2. Start from the template

The fastest path is the standalone template in [template/](../template/README.md).
It is a sibling of `lvgl-android` and uses a Gradle composite build, so it
compiles the local library before anything is published:

```powershell
cd template
# Copy the wrapper from the framework repo (gradlew, gradlew.bat, gradle/wrapper/*)
.\gradlew.bat assembleDebug --no-daemon
adb install -r app\build\outputs\apk\debug\app-universal-debug.apk
adb shell am start -n com.example.lvglapp/.MainActivity
```

## 3. The minimal activity

`LvglActivity` owns the `SurfaceView`, the engine lifecycle, theme handling and
touch input. Subclasses only build content in `onCreateLvgl()`.

```kotlin
package com.example.lvglapp

import com.lvgl.android.activity.LvglActivity
import com.lvgl.android.engine.LvglEngine

class MainActivity : LvglActivity() {

    override fun onCreateLvgl() {
        // Show a built-in LVGL screen (see the table below).
        setLvglScreen(1)

        // Push dynamic text into that screen's status label.
        LvglEngine.instance.setStatusText("Hello from Kotlin")
    }
}
```

**Do not call `setContentView()`** from a subclass: it replaces the LVGL
`SurfaceView` and produces a greyed-out, non-responsive UI. The base class owns
the root layout (see `BC-010` in [agent-breadcrumbs.json](agent-breadcrumbs.json)).

## 4. Screens: labels and buttons

The public Kotlin API is **screen-level**, not widget-level. LVGL labels,
buttons, sliders and switches are built by the native UI kit in
`lvgl-android/src/main/cpp/demo_ui.cpp`; Kotlin selects which screen to show
with `setLvglScreen(id)`. The ids are a stable Kotlin/native contract declared
in `lvgl-android/src/main/cpp/demo_ui.h`:

| id | Screen | What it shows |
|----|--------|----------------|
| 0 | Device dashboard | Android system schematic, floating control panels, status bar |
| 1 | Widget Showcase | LVGL label + "Tap me" button, switch, checkbox, slider, arc, dropdown |
| 2 | Layout Showcase | Flex rows/columns/grids, cards and sections |
| 3 | Interactive Demo | Full-screen touch surface with a draggable marker |
| 4 | Camera Sample | Camera2 preview status page |
| 5 | Game Sample | Status page driven by `onGameLoop()` |
| 6 | Launcher Sample | Installed-app list status page |
| 7 | Showcase | Animated spring-physics launcher demo |

So the label + button screen is:

```kotlin
override fun onCreateLvgl() {
    setLvglScreen(1)   // Widget Showcase: an LVGL label and a "Tap me" button
}
```

If you need to update a label at runtime, use `LvglEngine.setStatusText(...)`;
the text is cached per screen and restored if the screen is rebuilt
(`BC-008`).

## 5. Build your own screen in Kotlin

The `LvglUi` DSL declares an app screen as a tree of nodes; the native UI kit
renders it with the same Material 3 styling as the built-in screens:

```kotlin
import com.lvgl.android.ui.LvglUi

LvglUi.registerScreen(1000, title = "Hello") {
    column {
        label("Hello from Kotlin!", sizeDp = 20)
        button("Tap me", fillWidth = true, id = "tap") {
            LvglUi.updateNode(1000, "greeting", text = "Tapped!")
        }
        label("Tapped!", id = "greeting", sizeDp = 16)
    }
}
```

Supported nodes: `column`, `row`, `label`, `button`, `slider`, `toggle`,
`checkbox`, `progress`, `arc`, `divider` and `spacer`. Every builder takes an
optional `id`; `LvglUi.updateNode(screenId, id, text = ..., value = ...)` patches
a node from any thread. Node callbacks run on the LVGL thread, and
`LvglUi.listener` receives every event for the screen.

App screen ids start at `LvglUi.APP_SCREEN_BASE` (1000). See
[public-api.md](public-api.md) for the full node reference.

## 6. Click callbacks

Native LVGL buttons that need Kotlin call back through
`LvglEngine.ScreenNavigator`:

- `navigateTo(screenId)` — a dashboard menu card was tapped
- `navigateBack()` — the header back button was tapped
- `setBrightness(brightness: Float)` — dashboard backlight panel
- `setVolumePercent(percent: Int)` — dashboard volume panel

`LvglActivity` registers its navigator automatically. To handle navigation,
map screen ids to activities with `LvglActivity.screenResolver` **before**
calling `super.onCreate()`:

```kotlin
package com.example.lvglapp

import android.os.Bundle
import com.lvgl.android.activity.LvglActivity

class MainActivity : LvglActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        LvglActivity.screenResolver = { screenId ->
            when (screenId) {
                1 -> SecondActivity::class.java
                else -> null
            }
        }
        super.onCreate(savedInstanceState)
    }

    override fun onCreateLvgl() {
        setLvglScreen(0)   // dashboard: its cards call navigateTo(...)
    }
}

class SecondActivity : LvglActivity() {
    override fun onCreateLvgl() {
        setLvglScreen(1)
    }
}
```

For raw tap/gesture callbacks on a view, use `LvglInputProcessor` (see the next
section) or `LvglSurfaceViewWithGestures`.

## 7. Input handling

**Touch.** `LvglSurfaceView` (used automatically by `LvglActivity`) forwards
every `MotionEvent` to `LvglEngine.onTouchEvent(action, x, y)`; actions are
`0` = down, `1` = up, `2` = move. You do not need to do anything.

**Gestures.** `LvglInputProcessor` forwards raw touch to LVGL *and* detects
gestures as a side channel:

```kotlin
import android.view.MotionEvent
import com.lvgl.android.input.Gestures
import com.lvgl.android.input.LvglInputProcessor

val input = LvglInputProcessor(context)

fun onTouch(event: MotionEvent) {
    when (val gesture = input.processMotionEvent(event)) {
        is Gestures.Tap       -> { /* gesture.x, gesture.y */ }
        is Gestures.LongPress -> { /* gesture.duration */ }
        is Gestures.Swipe     -> { /* gesture.direction */ }
        is Gestures.Pinch     -> { /* gesture.scaleFactor */ }
        is Gestures.Rotate    -> { /* gesture.rotation */ }
        null -> { /* no gesture detected */ }
    }
}
```

`LvglSurfaceViewWithGestures` is a drop-in `LvglSurfaceView` that runs the
processor for you and exposes `getLastGestureResult()`:

```kotlin
val view = LvglSurfaceViewWithGestures(context)
val last: Gestures? = view.getLastGestureResult()
```

Host it in a custom activity or in an IME's `onCreateKeyboardView()`;
`LvglActivity` uses the plain `LvglSurfaceView` internally and has no hook to
swap it.

**Hardware keyboard / D-pad.**

```kotlin
import android.view.KeyEvent
import com.lvgl.android.input.HardwareKeyboardHandler

private val keyHandler = HardwareKeyboardHandler(this) { code, pressed ->
    if (pressed && code == HardwareKeyboardHandler.KeyCode.ENTER) {
        // handle Enter
    }
}

override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
    keyHandler.processKeyEvent(event) || super.onKeyDown(keyCode, event)
```

## 8. Material theme

`LvglActivity` already initializes `MaterialTheme` and applies the palette to
LVGL, so in an activity you normally do nothing. To read the resolved palette:

```kotlin
import com.lvgl.android.theme.MaterialPalette
import com.lvgl.android.theme.MaterialTheme

MaterialTheme.instance.initialize(this)

val palette: IntArray = MaterialTheme.instance.materialPalette()
val primary: Int = palette[MaterialPalette.PRIMARY]
val dark: Boolean = MaterialTheme.instance.isDarkMode
```

**Dark / light.** `MaterialTheme` follows the system UI mode
(`Configuration.UI_MODE_NIGHT_MASK`). `LvglActivity` re-applies the theme on
`onConfigurationChanged()` and notifies listeners; the dashboard and every
sample screen follow automatically.

**Dynamic color.** On Android 12+ (API 31) `MaterialPalette.dynamic(...)`
derives the palette from the system accent colors
(`android.R.color.system_accent*_500`); below API 31 it falls back to the
baseline Material 3 scheme. `MaterialTheme.useDynamicColors` is `true` by
default. Opt out with:

```kotlin
MaterialTheme.instance.setDynamicColors(this, false)   // force the baseline scheme
```

**Non-activity contexts** (for example an input method that is not an
activity): call `LvglThemeBridge().applyCurrentTheme()` to push the current
palette into LVGL.

**Listening for theme changes:**

```kotlin
MaterialTheme.instance.addListener(object : MaterialTheme.OnThemeChangedListener {
    override fun onThemeChanged(theme: MaterialTheme) {
        // theme.materialPalette() is already updated
    }
})
```

## 9. Build and run

From the template:

```powershell
.\gradlew.bat assembleDebug --no-daemon
adb install -r app\build\outputs\apk\debug\app-universal-debug.apk
adb shell am start -n com.example.lvglapp/.MainActivity
```

Or add your activity to the sample app and build the repository:

```powershell
.\gradlew.bat :app:installDebug
adb shell am start -n com.lvgl.sample/.MainActivity
```

Useful logcat tags while debugging: `LvglActivity`, `LvglEngine`,
`LvglSurfaceView`, `LVGLPort`, `LVGLLayout` (layout lint).
