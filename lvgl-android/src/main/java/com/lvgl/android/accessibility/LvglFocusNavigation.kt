// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.accessibility

import android.content.Context
import android.view.KeyEvent
import android.util.Log

/**
 * D-pad/keyboard navigation helper for the LVGL focus system.
 *
 * Keeps an ordered list of focusable element IDs and tracks which one is
 * focused, translating arrow keys, Enter and Tab into focus moves. Callers
 * supply the IDs from their own widget model; the class only remembers order,
 * it does not hit-test against native LVGL objects.
 *
 * Intended to be driven from the UI thread with key events.
 */
class LvglFocusNavigation(
    private val context: Context
) {

    // Track currently focused element ID
    private var focusedId: Int = -1
    private val focusableIds = mutableListOf<Int>()

    /**
     * Direction of a focus move.
     *
     * @property dx horizontal offset of the move.
     * @property dy vertical offset of the move.
     */
    enum class FocusDirection(val dx: Int, val dy: Int) {
        /** Move focus up. */
        UP(0, -1),

        /** Move focus down. */
        DOWN(0, 1),

        /** Move focus left. */
        LEFT(-1, 0),

        /** Move focus right. */
        RIGHT(1, 0)
    }

    /** Registers [id] as focusable. Re-adding an existing id is ignored. */
    fun addFocusable(id: Int) {
        if (!focusableIds.contains(id)) focusableIds.add(id)
    }

    /**
     * Removes [id] from the focus list.
     *
     * If that element was focused, focus is cleared (see [getFocused]).
     */
    fun removeFocusable(id: Int) {
        focusableIds.remove(id)
        if (focusedId == id) focusedId = -1
    }

    /**
     * Moves focus to [id].
     *
     * The call has no effect unless [id] was previously registered with
     * [addFocusable].
     */
    fun setFocused(id: Int) {
        if (focusableIds.contains(id)) focusedId = id
    }

    /** The currently focused element ID, or `-1` when nothing is focused. */
    fun getFocused(): Int = focusedId

    /**
     * Handles a key event, moving focus or activating the focused element.
     *
     * @param keyCode an Android [KeyEvent] key code.
     * @return `true` when the key was consumed, `false` when it was not handled
     *   by this navigator.
     */
    fun handleKeyEvent(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> moveFocus(FocusDirection.UP, 0f, -1f)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveFocus(FocusDirection.DOWN, 0f, 1f)
            KeyEvent.KEYCODE_DPAD_LEFT -> moveFocus(FocusDirection.LEFT, -1f, 0f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveFocus(FocusDirection.RIGHT, 1f, 0f)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                onEnter()
                true
            }
            KeyEvent.KEYCODE_TAB -> {
                // Move to next focusable
                if (focusableIds.isNotEmpty()) {
                    if (focusedId == -1 || !focusableIds.contains(focusedId)) {
                        setFocused(focusableIds.first())
                    } else {
                        val idx = focusableIds.indexOf(focusedId)
                        val next = (idx + 1) % focusableIds.size
                        setFocused(focusableIds[next])
                    }
                    true
                } else false
            }
            else -> false
        }
    }

    private fun moveFocus(direction: FocusDirection, dx: Float, dy: Float): Boolean {
        // In full implementation, this would call native LVGL to find objects in the direction.
        // For now, just simulate focus movement.
        if (focusableIds.isEmpty()) return false
        val current = if (focusedId in focusableIds) focusedId else focusableIds.first()
        val idx = focusableIds.indexOf(current)
        val next = (if (direction == FocusDirection.DOWN || direction == FocusDirection.RIGHT) (idx + 1) else (idx - 1 + focusableIds.size)) % focusableIds.size
        setFocused(focusableIds[next])
        return true
    }

    private fun onEnter() {
        Log.d(TAG, "Focus enter on $focusedId")
    }

    /** Holds the private log tag. */
    companion object {
        private const val TAG = "LvglFocusNavigation"
    }
}

