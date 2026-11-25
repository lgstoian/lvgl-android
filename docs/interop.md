# Interoperating with the rest of your Android app

> Applies to v1.2.0. This framework is **an addition to the Android UI toolkit,
> not a replacement for it**. You can put an LVGL surface inside a normal
> Activity, Fragment or Compose screen, put native Android views above it, and
> move any screen back to Views/Compose without rewriting the rest of the app.
> See [migration.md](migration.md) for the step-by-step adoption and exit plan.

## 1. The interop contract

`LvglEngine` is a **process-wide singleton**; a display is *owner-scoped*. A
`LvglSurfaceView` binds its `Surface` to that display in `surfaceChanged()` and
unbinds it in `surfaceDestroyed()` / `destroy()`. Consequence: **host one
`LvglSurfaceView` at a time**. Two simultaneously attached surfaces fight over
the single display. Multiple activities are fine — as long as only one surface
is live during a transition, the engine rebinds a same-size surface instead of
rebuilding it.

The view forwards touch itself (`onTouchEvent` → `LvglEngine.onTouchEvent`,
actions `0` = down, `2` = move, `1` = up). You do not need to install a touch
listener. Call `onResume()` / `onPause()` from the host lifecycle and `destroy()`
when the view is removed for good.

### Plain Activity

A normal `AppCompatActivity` can use `setContentView()` as usual — the
"never call `setContentView()`" rule applies to `LvglActivity` subclasses,
whose base class owns the root layout. Put the view in your XML and host the
lifecycle:

```xml
<!-- res/layout/activity_hybrid.xml -->
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:orientation="vertical" android:layout_width="match_parent"
    android:layout_height="match_parent">

    <TextView android:id="@+id/header" android:layout_width="match_parent"
        android:layout_height="wrap_content" android:text="@string/hybrid_header" />

    <com.lvgl.android.view.LvglSurfaceView android:id="@+id/lvgl"
        android:layout_width="match_parent" android:layout_height="0dp"
        android:layout_weight="1" />

    <Button android:id="@+id/next" android:layout_width="wrap_content"
        android:layout_height="wrap_content" android:text="@string/hybrid_next" />
</LinearLayout>
```

```kotlin
class HybridHostActivity : AppCompatActivity() {
    private lateinit var lvgl: LvglSurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hybrid)
        lvgl = findViewById(R.id.lvgl)
        LvglUi.registerScreen(1000, title = "Native host") {
            column { label("Rendered by LVGL", id = "status") }
        }
        findViewById<Button>(R.id.next).setOnClickListener {
            LvglEngine.instance.setScreen(1000)
        }
    }

    override fun onResume() { super.onResume(); lvgl.onResume() }
    override fun onPause() { super.onPause(); lvgl.onPause() }
    override fun onDestroy() { lvgl.destroy(); super.onDestroy() }
}
```

The shipped `HybridSampleActivity` (`com.lvgl.sample`, not exported) is a
working version of this pattern. It deliberately uses `addContentView()` rather
than `setContentView()` because the sample app's guard rules forbid a
`setContentView(` call in app sources; that restriction is a sample-app guard,
not an Android requirement for consumer apps.

### Fragment

Inflate the same layout in `onCreateView` and forward the matching callbacks:

```kotlin
class LvglFragment : Fragment(R.layout.fragment_lvgl) {
    private var view: LvglSurfaceView? = null

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        view = v.findViewById(R.id.lvgl)
    }

    override fun onResume() { super.onResume(); view?.onResume() }
    override fun onPause() { super.onPause(); view?.onPause() }
    override fun onDestroyView() { view?.destroy(); view = null; super.onDestroyView() }
}
```

Keep the fragment's `View` in the hierarchy but attach only one LVGL surface at
a time if you use a navigation host with multiple LVGL fragments.

### Compose `AndroidView`

Compose does not need an LVGL-specific composable; `AndroidView` hosts the
`SurfaceView` and `DisposableEffect` drives the lifecycle:

```kotlin
@Composable
fun LvglPane(screenId: Int, modifier: Modifier = Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var surface: LvglSurfaceView? = null
    DisposableEffect(lifecycle) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) { surface?.onResume() }
            override fun onPause(owner: LifecycleOwner) { surface?.onPause() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); surface?.destroy() }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            LvglSurfaceView(ctx).also {
                it.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                surface = it
            }
        }
    )
    LaunchedEffect(screenId) { LvglEngine.instance.setScreen(screenId) }
}
```

The sample app does not depend on Compose, so this snippet is a recipe rather
than a shipped screen. The same engine/surface rules apply.

## 2. Native Android views above LVGL

`LvglSurfaceView` intentionally calls `setZOrderOnTop(false)` and
`setZOrderMediaOverlay(false)` so in-app dialogs and permission prompts render
above the LVGL surface. A native view that must be visible *over* LVGL has to
opt into the overlay flags itself:

```kotlin
val overlay = SurfaceView(this).apply {
    setZOrderOnTop(true)
    setZOrderMediaOverlay(true)
    holder.setFormat(PixelFormat.RGBA_8888)
}
addContentView(overlay, FrameLayout.LayoutParams(width, height).apply {
    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
})
```

### The camera proof

`CameraSampleActivity` is the concrete demonstration: an LVGL status screen
(screen id `4`) with a native `SurfaceView` camera preview composited above it
and a native `TextView` hint. It proves that an app can mix a Camera2 preview —
which Android cannot render into an LVGL widget — with an LVGL-rendered UI in
the same window.

The interop points:

- Call `preview.startPreview(surface)` **before** `preview.open(cameraId)`. The
  surface is remembered and targeted as soon as the device opens, so no
  internal `ImageReader` session is created first. `onOpenResult` reports the
  real open outcome; marshal it to the UI thread before touching views.
- `preview.allCameraIds()` enumerates **all** ids, including physical sensors
  behind logical cameras. The sample cycles through them on tap.
- v1.2.0 adds `preview.selectPreviewSize(cameraId, target)` and the
  `chosenPreviewSize` property. The preview size is chosen by closest aspect
  ratio to `target` (from the available `YUV_420_888` sizes), and the overlay is
  letterboxed/centred to that aspect instead of being stretched to a fixed box.
  Recompute it on camera switch and configuration change.
- `CAMERA` is requested at runtime; denial leaves the LVGL screen fully usable.
  `preview.stopPreview()` / `preview.close()` release the session in `onPause`
  / `onDestroy`, and the overlay views are removed with them.

Any native view works the same way: a `MapView`, an ad view, a platform text
field with full IME and TalkBack support, or a Compose `ComposeView` added to
the same hierarchy. The rule is that the native view owns its own pixels; LVGL
never draws inside it.

## 3. Both directions of the bridge

**Android → LVGL.** `LvglEngine.instance.setScreen(id)`,
`LvglEngine.instance.setStatusText(text)`, `LvglUi.updateNode(screenId, nodeId,
…)` and `MaterialTheme.instance.setDynamicColors(context, enabled)` are all
callable from the UI thread. `HybridSampleActivity` uses native buttons to
switch between two `LvglUi` screens and to push text into them.

**LVGL → Android.** `LvglUi.listener` receives `LvglUiEvent`s and node callback
lambdas run on the **LVGL render thread**. Switch before touching Android views:

```kotlin
LvglUi.listener = { screenId, event ->
    runOnUiThread { statusText.text = "LVGL: $event (screen $screenId)" }
}
```

For built-in screens, `LvglEngine.ScreenNavigator` carries `navigateTo`,
`navigateBack`, brightness and volume callbacks; `LvglActivity` registers one
automatically, and a plain host can set `LvglEngine.instance.screenNavigator`.

## 4. Escape hatches

There are three ways to reduce or remove LVGL from an app, all incremental:

1. **Overlay a native view** for the parts that need platform behaviour
   (accessibility semantics, text input, maps, ads) — see §2 and the camera
   sample.
2. **Replace one screen** with a Compose/XML implementation while the rest of
   the app keeps its LVGL screens — see [migration.md](migration.md). The hybrid
   sample shows that native and LVGL content can share one Activity.
3. **Exit entirely**: unregister the `LvglUi` screens, call `destroy()` on the
   last surface and remove the dependency. The engine is never shut down by a
   display owner, so removing one screen cannot break a remaining one; see the
   exit checklist in [migration.md](migration.md).

Accessibility is the most common reason to take hatch 1 or 2: read
[accessibility.md](accessibility.md) for exactly what TalkBack can and cannot
see, and [performance.md](performance.md) before deciding that a custom render
thread is the right fit.
