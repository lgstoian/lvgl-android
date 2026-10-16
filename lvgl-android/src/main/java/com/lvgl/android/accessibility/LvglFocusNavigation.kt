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
 * D-pad/keyboard navigation helper for LVGL focus system.
 * Supports arrow keys, Enter, Tab, Back, Home, End.
 */
class LvglFocusNavigation(
    private val context: Context
) {

    // Track currently focused element ID
    private var focusedId: Int = -1
    private val focusableIds = mutableListOf<Int>()

    // Focus direction
    enum class FocusDirection(val dx: Int, val dy: Int) {
        UP(0, -1),
        DOWN(0, 1),
        LEFT(-1, 0),
        RIGHT(1, 0)
    }

    fun addFocusable(id: Int) {
        if (!focusableIds.contains(id)) focusableIds.add(id)
    }

    fun removeFocusable(id: Int) {
        focusableIds.remove(id)
        if (focusedId == id) focusedId = -1
    }

    fun setFocused(id: Int) {
        if (focusableIds.contains(id)) focusedId = id
    }

    fun getFocused(): Int = focusedId

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

    companion object {
        private const val TAG = "LvglFocusNavigation"
    }
}

