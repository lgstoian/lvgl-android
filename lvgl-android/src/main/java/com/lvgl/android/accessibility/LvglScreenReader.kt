// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.accessibility

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import java.lang.ref.WeakReference

private const val TAG = "LvglScreenReader"

/**
 * Screen-reader bridge that announces LVGL focus changes to Android's
 * accessibility services (TalkBack).
 *
 * The bridge is stateless and process-wide: call [initialize] once per
 * application before the first [announce]. [announce] posts the event through
 * [Activity.runOnUiThread], so it is safe to call from any thread, including
 * the LVGL render thread.
 */
object LvglScreenReader {

    /** Last activity that initialised the bridge, held weakly to avoid a leak. */
    private var currentActivity: WeakReference<Activity>? = null

    /**
     * Initialises the bridge against the given [context].
     *
     * When the context is (or wraps) an Activity it is remembered as the window
     * used by later [announce] calls. Logs whether the system currently has an
     * accessibility service enabled; it does not register any listener and is
     * safe to call before a window exists.
     */
    fun initialize(context: Context) {
        val activity = context.asActivity()
        if (activity != null) currentActivity = WeakReference(activity)
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        if (manager.isEnabled) {
            Log.d(TAG, "Accessibility enabled - setting up bridges")
        }
    }

    /**
     * Announces [text] to the user through the given [activity]'s window.
     *
     * The event is dispatched on the UI thread, so callers do not need to
     * switch threads themselves.
     *
     * @param activity the foreground activity whose window receives the event.
     * @param text the message to announce.
     */
    fun announce(activity: Activity, text: String) {
        activity.runOnUiThread {
            val view = activity.window.decorView
            val event = AccessibilityEvent.obtain(
                AccessibilityEvent.TYPE_ANNOUNCEMENT
            )
            event.text.add(text)
            view.sendAccessibilityEventUnchecked(event)
        }
    }

    /**
     * Announces [text] using the activity associated with [context], or the
     * activity last passed to [initialize] when [context] is not an activity.
     *
     * This is the entry point used by `LvglAccessibilityHelper.announce`. When
     * no activity is available (for example an IME service before its view is
     * created) the announcement is dropped rather than failing.
     */
    fun announce(context: Context, text: String) {
        val activity = context.asActivity() ?: currentActivity?.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Log.d(TAG, "Announcement dropped: no live activity")
            return
        }
        announce(activity, text)
    }

    /** Forgets the remembered activity; useful when the bridge is re-initialized. */
    fun clear() {
        currentActivity = null
    }

    /** Unwraps a [ContextWrapper] chain to find an Activity, if there is one. */
    private fun Context.asActivity(): Activity? =
        generateSequence(this) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>()
            .firstOrNull()
}
