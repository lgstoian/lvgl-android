// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.accessibility

import android.content.Context
import android.util.Log

/**
 * Helper methods to interface LVGL's focus system with Android Accessibility.
 *
 * The methods accept an opaque view id and are currently the Kotlin scaffolding
 * for a native JNI bridge: they log the intent now and are the single place to
 * route through once the native exports exist.
 *
 * NOTE: Full integration requires native JNI exports; this is the Kotlin scaffolding.
 */
object LvglAccessibilityHelper {
    /** Log tag used by this helper. */
    const val TAG = "LvglAccessibilityHelper"

    /**
     * Announces [text] to the accessibility service attached to [context].
     *
     * @param context used to resolve the accessibility service.
     * @param text the message to announce.
     */
    @JvmStatic
    fun announce(context: Context, text: String) {
        Log.d(TAG, "Announcing: $text")
    }

    /**
     * Marks the element [viewId] as focusable when [focusable] is `true`.
     *
     * @param viewId opaque element identifier supplied by the app.
     * @param focusable whether the element should take part in focus navigation.
     */
    @JvmStatic
    fun setFocusable(viewId: String, focusable: Boolean) {
        Log.d(TAG, "Set focusable $viewId = $focusable")
    }

    /**
     * Sets the spoken [description] for the element [viewId].
     *
     * @param viewId opaque element identifier supplied by the app.
     * @param description the content description announced by the screen reader.
     */
    @JvmStatic
    fun setContentDescription(viewId: String, description: String) {
        Log.d(TAG, "Set content description $viewId = $description")
    }
}

/**
 * Per-element bridge that forwards LVGL focus events to [LvglAccessibilityHelper].
 *
 * One delegate is created per logical view id; callers keep it alongside the
 * element it describes.
 */
class LvglAccessibilityDelegate(
    private val context: Context,
    private val viewId: String
) {
    /**
     * Reports that this element gained focus and should announce [description].
     *
     * The description is stored as the element's content description.
     */
    fun dispatchFocusGained(description: String) {
        LvglAccessibilityHelper.setContentDescription(viewId, description)
    }

    /** Reports that this element lost focus. */
    fun dispatchFocusLost() {
        // Handle focus lost
    }
}

