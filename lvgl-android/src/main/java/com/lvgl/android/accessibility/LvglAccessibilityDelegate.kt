// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.accessibility

import android.content.Context

/**
 * Bridges LVGL's focus system to Android accessibility.
 *
 * Announcements go through the real [LvglScreenReader] path. Focusability and
 * content descriptions are kept in a process-wide registry keyed by the opaque
 * view id; the native UI kit does not currently expose a JNI call to set these
 * properties on an LVGL object, so no bridge is invented here. The registry is
 * still useful to callers: it keeps the state in one place and gives
 * [LvglAccessibilityDelegate] a description to announce on focus changes.
 */
object LvglAccessibilityHelper {
    /** Log tag used by this helper. */
    const val TAG = "LvglAccessibilityHelper"

    private val focusableIds = mutableMapOf<String, Boolean>()
    private val descriptions = mutableMapOf<String, String>()

    /**
     * Announces [text] to the accessibility service attached to [context].
     *
     * Delegates to [LvglScreenReader], which posts an
     * [android.view.accessibility.AccessibilityEvent.TYPE_ANNOUNCEMENT] on the
     * UI thread. Safe to call from the LVGL render thread.
     *
     * @param context used to resolve the foreground activity/window.
     * @param text the message to announce.
     */
    @JvmStatic
    fun announce(context: Context, text: String) {
        LvglScreenReader.announce(context, text)
    }

    /**
     * Marks the element [viewId] as focusable when [focusable] is `true`.
     *
     * @param viewId opaque element identifier supplied by the app.
     * @param focusable whether the element should take part in focus navigation.
     */
    @JvmStatic
    fun setFocusable(viewId: String, focusable: Boolean) {
        focusableIds[viewId] = focusable
    }

    /**
     * Sets the spoken [description] for the element [viewId].
     *
     * @param viewId opaque element identifier supplied by the app.
     * @param description the content description announced by the screen reader.
     */
    @JvmStatic
    fun setContentDescription(viewId: String, description: String) {
        descriptions[viewId] = description
    }

    /** Whether [viewId] was registered as focusable. Unknown ids are not focusable. */
    @JvmStatic
    fun isFocusable(viewId: String): Boolean = focusableIds[viewId] == true

    /** The content description registered for [viewId], or `null` when unset. */
    @JvmStatic
    fun contentDescription(viewId: String): String? = descriptions[viewId]
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
    private var focused = false
    private var focusLostAnnouncement: String? = null

    /**
     * Reports that this element gained focus and should announce [description].
     *
     * The description is stored as the element's content description and then
     * announced through the screen reader.
     */
    fun dispatchFocusGained(description: String) {
        focused = true
        LvglAccessibilityHelper.setFocusable(viewId, true)
        LvglAccessibilityHelper.setContentDescription(viewId, description)
        LvglAccessibilityHelper.announce(context, description)
    }

    /**
     * Reports that this element lost focus.
     *
     * Call [dispatchFocusLost] with an announcement to have the loss spoken;
     * otherwise focus state is simply cleared.
     */
    fun dispatchFocusLost() {
        focused = false
        focusLostAnnouncement?.let { LvglAccessibilityHelper.announce(context, it) }
    }

    /** Clears focus and announces [announcement] to the screen reader. */
    fun dispatchFocusLost(announcement: String) {
        focusLostAnnouncement = announcement
        dispatchFocusLost()
    }

    /** Whether this element is currently focused. */
    fun isFocused(): Boolean = focused
}
