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
 * Handles hardware keyboard input and translates it to LVGL indev events.
 *
 * Maps Android key codes to [KeyCode] values and reports press/release state
 * through the supplied callback. Unmapped keys are ignored and reported as
 * `false` so the caller can pass them on.
 *
 * Supports arrow keys, Enter, Backspace, Tab, Escape, Home, End, page
 * navigation and space.
 */
class HardwareKeyboardHandler(
    private val context: Context,
    private val onKey: (KeyCode, Boolean) -> Unit // (code, isPressed)
) {

    /**
     * Key codes the handler understands, with their Android [KeyEvent] value.
     *
     * @property rawCode the Android key code this entry maps to.
     */
    enum class KeyCode(val rawCode: Int) {
        /** D-pad up. */
        UP(KeyEvent.KEYCODE_DPAD_UP),

        /** D-pad down. */
        DOWN(KeyEvent.KEYCODE_DPAD_DOWN),

        /** D-pad left. */
        LEFT(KeyEvent.KEYCODE_DPAD_LEFT),

        /** D-pad right. */
        RIGHT(KeyEvent.KEYCODE_DPAD_RIGHT),

        /** D-pad centre / select. */
        CENTER(KeyEvent.KEYCODE_DPAD_CENTER),

        /** Enter key. */
        ENTER(KeyEvent.KEYCODE_ENTER),

        /** Backspace / delete. */
        BACKSPACE(KeyEvent.KEYCODE_DEL),

        /** Tab key. */
        TAB(KeyEvent.KEYCODE_TAB),

        /** Escape key. */
        ESCAPE(KeyEvent.KEYCODE_ESCAPE),

        /** Home key. */
        HOME(KeyEvent.KEYCODE_HOME),

        /** End key. */
        END(123),

        /** Page up. */
        PAGE_UP(KeyEvent.KEYCODE_PAGE_UP),

        /** Page down. */
        PAGE_DOWN(KeyEvent.KEYCODE_PAGE_DOWN),

        /** Space bar. */
        SPACE(KeyEvent.KEYCODE_SPACE),

        /** Fallback for unmapped keys. */
        UNKNOWN(0)
    }

    /**
     * Translates [event] into an [onKey] callback.
     *
     * Both ACTION_DOWN and ACTION_UP are reported so the caller can drive
     * LVGL press and release states.
     *
     * @param event the key event to translate.
     * @return `true` when the key code was recognised, `false` otherwise.
     */
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

    /** Holds the private log tag. */
    companion object {
        private const val TAG = "HardwareKeyboardHandler"
    }
}


