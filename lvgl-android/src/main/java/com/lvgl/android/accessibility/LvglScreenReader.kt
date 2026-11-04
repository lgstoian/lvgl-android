// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.accessibility

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager

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
    /**
     * Initialises the bridge against the given [context].
     *
     * Logs whether the system currently has an accessibility service enabled;
     * it does not register any listener and is safe to call before a window
     * exists.
     */
    fun initialize(context: Context) {
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
}

