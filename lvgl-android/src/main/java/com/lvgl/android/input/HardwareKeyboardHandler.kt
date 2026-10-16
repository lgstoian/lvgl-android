// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.input

import android.content.Context
import android.view.KeyEvent
import android.util.Log

/**
 * Handles hardware keyboard input and translates to LVGL indev events.
 * Supports arrow keys, Enter, Backspace, Tab, Escape, Home, End, etc.
 */
class HardwareKeyboardHandler(
    private val context: Context,
    private val onKey: (KeyCode, Boolean) -> Unit // (code, isPressed)
) {

    enum class KeyCode(val rawCode: Int) {
        UP(KeyEvent.KEYCODE_DPAD_UP),
        DOWN(KeyEvent.KEYCODE_DPAD_DOWN),
        LEFT(KeyEvent.KEYCODE_DPAD_LEFT),
        RIGHT(KeyEvent.KEYCODE_DPAD_RIGHT),
        CENTER(KeyEvent.KEYCODE_DPAD_CENTER),
        ENTER(KeyEvent.KEYCODE_ENTER),
        BACKSPACE(KeyEvent.KEYCODE_DEL),
        TAB(KeyEvent.KEYCODE_TAB),
        ESCAPE(KeyEvent.KEYCODE_ESCAPE),
        HOME(KeyEvent.KEYCODE_HOME),
        END(123),
        PAGE_UP(KeyEvent.KEYCODE_PAGE_UP),
        PAGE_DOWN(KeyEvent.KEYCODE_PAGE_DOWN),
        SPACE(KeyEvent.KEYCODE_SPACE),
        UNKNOWN(0)
    }

    fun processKeyEvent(event: KeyEvent): Boolean {
        val code = KeyCode.entries.firstOrNull { it.rawCode == event.keyCode }
        if (code != null) {
            val isPressed = event.action == KeyEvent.ACTION_DOWN
            onKey(code, isPressed)
            Log.d(TAG, "Key: ${code.name} pressed=$isPressed")
            return true
        }
        return false
    }

    companion object {
        private const val TAG = "HardwareKeyboardHandler"
    }
}


