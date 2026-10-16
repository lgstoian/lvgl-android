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
 * Screen-reader bridge that announces LVGL focus changes.
 */
object LvglScreenReader {
    fun initialize(context: Context) {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        if (manager.isEnabled) {
            Log.d(TAG, "Accessibility enabled - setting up bridges")
        }
    }

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

