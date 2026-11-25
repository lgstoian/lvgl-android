# Accessibility and TalkBack

> Applies to v1.2.0. This page states exactly what the framework exposes to
> Android accessibility services and what it does not. It is deliberately
> explicit: an LVGL canvas is not automatically a tree of Android views, so a
> screen that needs full platform semantics should be built as a native view.
> See [interop.md](interop.md) for how to host one above LVGL.

## 1. What exists after the node-provider work

`LvglSurfaceView` overrides `getAccessibilityNodeProvider()` and returns
`LvglNodeAccessibilityProvider` (internal, not part of the public API dump).
The host view is made focusable and marked
`IMPORTANT_FOR_ACCESSIBILITY_YES`, so TalkBack sees a virtual view hierarchy
inside the surface. The provider attaches to `LvglUi` state updates with the
surface and is refreshed on `onResume()` / `surfaceChanged()` and detached on
`destroy()`; this override is the only public API addition from the bridge.

**Scope: only app-defined screens built with the `LvglUi` Kotlin DSL, with ids
`>= LvglUi.APP_SCREEN_BASE` (1000).** Virtual nodes are derived from the tree
stored by `LvglUi.registerScreen(id, …)`; ids are stable (`1..N`) in tree order,
which lets TalkBack keep focus across `LvglUi.updateNode()` calls.

| `LvglNode` type | Android class exposed | Notable properties / actions |
| --- | --- | --- |
| `Label` | `TextView` | text/content description, `ACTION_FOCUS`, `ACTION_CLEAR_FOCUS` |
| `Button` | `Button` | clickable, `ACTION_CLICK`, `ACTION_FOCUS` |
| `Checkbox` | `CheckBox` | checkable/checked, `ACTION_CLICK` |
| `Switch` | `Switch` | checkable/checked, `ACTION_CLICK` |
| `Slider` | `SeekBar` | `RangeInfo`, `ACTION_SET_PROGRESS` |
| `Progress` | `SeekBar` | `RangeInfo` (read-only progress) |
| `Arc` | `SeekBar` | `RangeInfo` (read-only gauge) |
| `Dropdown` | `Spinner` | clickable, `ACTION_CLICK` |
| `Column`, `Row`, `Divider`, `Spacer` | — | **not exposed**; the hierarchy is flat and the surface view is the parent |

For each exposed node the provider reports text/content description and class
name, clickable/checkable/checked state and `RangeInfo` where applicable. It
supports `findAccessibilityNodeInfosByText()`, `ACTION_FOCUS`,
`ACTION_CLEAR_FOCUS`, `ACTION_CLICK` and `ACTION_SET_PROGRESS`, and posts
`TYPE_VIEW_FOCUSED` events so exploration mode can track focus.

Accessibility actions do not bypass the app: they synthesize the same typed
events the native UI kit delivers (`Click`, `ValueChanged`, `Toggled`,
`Selected`) through the internal UI-event receiver, and push changed state back
with `LvglUi.updateNode()`, so the native LVGL widget follows the spoken state.
All mirroring and event delivery are marshalled to the main thread with a
`Handler`; node queries never call native LVGL directly.

### The announcement path (unchanged)

The pre-existing pieces remain and are still the right way to make an
announcement from code:

- `LvglAccessibilityManager` — wraps `AccessibilityManager`; `isEnabled`,
  `isTouchExplorationEnabled`, state-change listeners, `announce(text)`,
  `setFocusable(id, …)`, `setContentDescription(id, …)`.
- `LvglScreenReader` — posts a real
  `AccessibilityEvent.TYPE_ANNOUNCEMENT` on the UI thread; safe to call from
  the LVGL thread via `LvglAccessibilityHelper.announce(context, text)`.
- `LvglAccessibilityDelegate` — per-id `dispatchFocusGained(description)` /
  `dispatchFocusLost()`.
- `LvglFocusNavigation` — D-pad/keyboard focus over numeric ids.

`IntegrationGuardTest` (rule `wired-accessibility-announcements`) fails closed
if the helper stops delegating to `LvglScreenReader` or the reader stops
posting a real event.

## 2. What is deliberately *not* exposed

Be honest with users and reviewers about these limits:

- **Built-in screens `0..7`** and every native `demo_ui` widget. They have no
  Kotlin node tree to mirror, so TalkBack sees the surface host, not their
  internals.
- **Real LVGL object geometry.** There is no JNI bounds API; TalkBack bounds
  are approximate row bands inside the `SurfaceView`, not the exact widget
  rectangles.
- **LVGL's internal focus and hit-testing.** The provider's focus state is
  Android-side only; it does not move LVGL's own focus ring.
- **Native scroll, IME and text-field semantics.** The `LvglUi` DSL has no
  text-field node, so there are no editable-text, live-region or heading
  semantics to expose.
- **No new permissions** are required, and the virtual tree exists whether or
  not TalkBack is enabled.
- **JVM unit tests see no nodes**: `LvglEngine.currentScreen()` is `-1` before
  native initialisation. Verification is on a device or emulator, not in a
  unit test.

## 3. Verifying on a device or emulator

1. Enable TalkBack in system accessibility settings.
2. Open a screen whose id is `>= 1000` (the sample app's **App Demo** screen is
   built with `LvglUi`).
3. Explore by swiping right/left and confirm one stop per exposed node, with the
   expected class and label; double-tap a `Button`/`Checkbox` and confirm the
   LVGL widget changes state.
4. Move a `Slider` with TalkBack volume keys and confirm `RangeInfo` and the
   on-screen value agree.
5. Check logcat for announcement/focus noise with
   `adb logcat -d -s LvglAccessibilityManager LvglScreenReader`.

If a screen is important enough that approximate bounds or missing semantics
would be a real barrier, use the native-view fallback below rather than adding
ad-hoc announcements.

## 4. Native-view fallback path

The supported escape hatch is to implement the inaccessible part as a standard
Android view and place it over, beside, or instead of the LVGL screen:

1. Build the control as a normal `View`/`Compose` element with real content
   descriptions, focus order and semantics. Platform widgets get TalkBack,
   switch access, IME and locale handling for free.
2. Host it above the LVGL surface with `addContentView()` and the overlay flags
   shown in section 2 of [interop.md](interop.md); the camera preview is the
   shipped proof that native pixels and LVGL pixels can coexist.
3. Bridge state in both directions with `LvglUi.listener`, `LvglUi.updateNode()`
   and `LvglEngine.ScreenNavigator` (marshal LVGL-thread callbacks to the UI
   thread).
4. If the whole screen needs platform semantics, replace it outright — see the
   exit checklist in [migration.md](migration.md). You can keep other LVGL
   screens; the engine is process-wide and display owners are independent.

Nothing in this bridge relaxes the app's Play obligations: the store listing,
data-safety form and privacy-policy URL remain manual steps in the Play
Console (see [play-store.md](play-store.md)).
