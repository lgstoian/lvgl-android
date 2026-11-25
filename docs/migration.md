# Adopting and exiting the framework

> Applies to v1.2.0. This is a general Android framework: adopt it for the
> screens where a custom, shared, C++-backed UI is worth the cost, and keep
> Compose/XML for everything else. Exiting is a supported, incremental path,
> not a rewrite. See [interop.md](interop.md) for the APIs and
> [README.md](../README.md#why--when-to-use-this) for the trade-offs.

## Adopt: from Compose or XML

### 1. Keep your existing app

Add `lvgl-android` (via [template/](../template/README.md) or the
`com.lvgl.android.app` plugin) without removing a single screen. The library
ships an AAR with native libraries for `arm64-v8a` and `x86_64`; your existing
Compose/XML screens keep working. The only manifest permission the framework
needs is the optional `CAMERA`, requested at runtime by the camera sample.

### 2. Pick one leaf surface

Choose a self-contained feature — a game canvas, a device-status dashboard, a
custom visualization — and host an `LvglSurfaceView` there:

- In an Activity/Fragment: place it in the layout and forward
  `onResume()` / `onPause()` / `destroy()` (see section 1 of
  [interop.md](interop.md)).
  A `LvglActivity` subclass is also available if the whole screen is LVGL.
- In Compose: use `AndroidView` with a `DisposableEffect` for the lifecycle.

Keep navigation, AppCompat/Compose theming, dependency injection and platform
services in your own code. LVGL becomes a leaf renderer, not the app shell.

### 3. Declare the screen in Kotlin

```kotlin
LvglUi.registerScreen(1000, title = "My feature") {
    column {
        label("Hello from Kotlin!", id = "greeting")
        button("Next", id = "next") { LvglUi.updateNode(1000, "greeting", text = "Tapped") }
    }
}
LvglEngine.instance.setScreen(1000)
```

App ids start at `LvglUi.APP_SCREEN_BASE` (1000); `0..7` are built-in framework
screens. Those app screens are also the ones the TalkBack node provider can
expose — see [accessibility.md](accessibility.md).

### 4. Bridge both directions

- Android → LVGL: `setScreen`, `setStatusText`, `LvglUi.updateNode`.
- LVGL → Android: `LvglUi.listener` / node callbacks (LVGL thread — hop to the
  UI thread before touching views), or `LvglEngine.ScreenNavigator` for
  built-in screens.

`HybridSampleActivity` is the working proof of this pattern: native buttons and
a native status `TextView` around a live LVGL screen.

### 5. Move more screens only when they benefit

Do not port a settings list or a form just because it is possible. Port screens
that need a single cross-platform C++ implementation, a custom animation
system, or a shared UI across Android and other LVGL targets. Keep text-heavy,
accessibility-sensitive and platform-integrated screens native.

### 6. Check the release gates

Run the unit, guard and release checks in [CONTRIBUTING.md](../CONTRIBUTING.md).
In particular, integration guards verify that features are *wired*, not merely
present in source. Play Console forms and the store listing are still manual —
see [play-store.md](play-store.md).

## Exit: move a screen back to native views

### 1. Pick one screen and reimplement it

Build the Compose/XML equivalent behind the same entry point. For a
`LvglActivity` subclass, replace it with a normal `Activity`/Fragment or a
`@Composable` destination; for an `LvglSurfaceView` leaf, replace just that
view. Keep the other LVGL screens untouched.

### 2. Preserve behaviour while switching

Move any state that lived in `LvglUi.updateNode()` or status text into your
view model. Recreate the same interactions (clicks, value changes) with native
listeners. Use the accessibility node provider's mapping from
[accessibility.md](accessibility.md) as a checklist of semantics the native
version should expose — and improve on it with real platform widgets.

### 3. Remove the display cleanly

When the last `LvglSurfaceView` for a screen goes away:

```kotlin
surface.destroy()                 // unbinds the owner's display only
LvglUi.unregisterScreen(1000)     // drop the tree and its callbacks
```

`destroy()` never shuts the process-wide engine down; another activity can
rebind later, so removing one screen cannot break the rest.

### 4. Remove the dependency when no screens remain

Once no `LvglSurfaceView`, `LvglActivity` subclass or `LvglUi` screen is left,
remove the `lvgl-android` dependency and the composite-build inclusion. The
native library and its size cost disappear from the next release. Also remove:

- the optional `CAMERA` permission if nothing else needs it;
- any `ScreenNavigator` / `LvglUi.listener` references;
- LVGL-specific logcat and layout-lint steps from your own runbooks.

### 5. Verify the exit

- No `LvglSurfaceView` remains attached; `destroy()` was called exactly once
  per view.
- No stale listener or `updateNode` callback holds an Activity reference.
- The hybrid/camera overlay pattern is removed if it existed only for the
  retired screen.
- TalkBack explores the replacement natively with real bounds and semantics.

## Decision table

| Need | Prefer |
| --- | --- |
| Shared C++/LVGL UI across platforms, custom animations, compact native code | LVGL screen |
| Forms, long text, platform look-and-feel, deep TalkBack/switch access | Compose/XML |
| Camera preview, maps, ads, platform IME | Native view above LVGL (section 2 of [interop.md](interop.md)) |
| A screen in transition | Hybrid: native views around one `LvglSurfaceView`, then remove one side |
| Precise frame scheduling / GPU profiling | Compose/XML ([performance.md](performance.md)) |
