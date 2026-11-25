// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.sample

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import com.lvgl.android.engine.LvglEngine
import com.lvgl.android.input.LvglInputMethodService
import com.lvgl.android.ui.LvglUi
import com.lvgl.android.view.LvglSurfaceView

/**
 * Sample LVGL keyboard.
 *
 * The input view is an [LvglSurfaceView], so the keyboard is drawn by the same
 * engine and Material 3 UI kit as the app screens. Its buttons are declared
 * with the [LvglUi] DSL; node callbacks arrive on the LVGL thread and are
 * marshalled to the main thread before touching the [android.view.inputmethod.InputConnection].
 */
class ImeSampleService : LvglInputMethodService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var keyboardView: LvglSurfaceView? = null

    override fun onCreateKeyboardView(): View {
        registerKeyboardScreen()
        LvglEngine.instance.setScreen(KEYBOARD_SCREEN_ID)
        return LvglSurfaceView(this).also { keyboardView = it }
    }

    private fun registerKeyboardScreen() {
        LvglUi.registerScreen(
            KEYBOARD_SCREEN_ID,
            title = getString(R.string.ime_title),
            showBack = false
        ) {
            column(card = true) {
                label(getString(R.string.ime_hint), sizeDp = 13)
                row(gapDp = 6) {
                    button(getString(R.string.ime_letters), id = ID_LETTERS) {
                        mainHandler.post { commitText(getString(R.string.ime_letters_value)) }
                    }
                    button(getString(R.string.ime_digits), id = ID_DIGITS) {
                        mainHandler.post { commitText(getString(R.string.ime_digits_value)) }
                    }
                    button(getString(R.string.ime_space), id = ID_SPACE) {
                        mainHandler.post { commitText(" ") }
                    }
                }
                row(gapDp = 6) {
                    button(getString(R.string.ime_enter), id = ID_ENTER) {
                        mainHandler.post { sendKeyDownUp(KeyEvent.KEYCODE_ENTER) }
                    }
                    button(getString(R.string.ime_backspace), id = ID_BACKSPACE) {
                        mainHandler.post { sendKeyDownUp(KeyEvent.KEYCODE_DEL) }
                    }
                }
            }
        }
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        keyboardView?.onResume()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        keyboardView?.onPause()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        LvglUi.unregisterScreen(KEYBOARD_SCREEN_ID)
        keyboardView = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ImeSampleService"
        private const val KEYBOARD_SCREEN_ID = 1100
        private const val ID_LETTERS = "ime_letters"
        private const val ID_DIGITS = "ime_digits"
        private const val ID_SPACE = "ime_space"
        private const val ID_ENTER = "ime_enter"
        private const val ID_BACKSPACE = "ime_backspace"
    }
}
