// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.input

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.view.inputmethod.InputConnection
import com.lvgl.android.engine.LvglEngine

/**
 * Base input method service that renders an LVGL keyboard.
 *
 * Owns the process-wide [LvglEngine] lifecycle while the keyboard is visible:
 * the engine is created in [onCreate], resumed in [onStartInputView] and paused
 * in [onFinishInputView]. Subclasses provide the keyboard view through
 * [onCreateKeyboardView] and commit text through the protected helpers.
 *
 * All Android callbacks run on the main thread; the LVGL render thread is
 * driven by the engine itself.
 */
abstract class LvglInputMethodService : InputMethodService() {

    /** Set true when the IME runs in its own process and may tear down the
     *  process-wide engine; otherwise the engine is shared with the app and
     *  must survive the IME being destroyed. */
    protected open val ownsProcess: Boolean = false

    /**
     * Builds the keyboard view shown to the user.
     *
     * @return the view, or `null` when the IME should show no input area.
     */
    abstract fun onCreateKeyboardView(): View?

    /** Creates the service and initialises the process-wide LVGL engine. */
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        LvglEngine.instance.initialize()
    }

    /** Returns the view built by [onCreateKeyboardView]. */
    override fun onCreateInputView(): View? {
        Log.d(TAG, "onCreateInputView")
        return onCreateKeyboardView()
    }

    /** Resumes the LVGL engine while the keyboard is visible. */
    override fun onStartInputView(info: android.view.inputmethod.EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.d(TAG, "onStartInputView")
        LvglEngine.instance.resume()
    }

    /** Pauses the LVGL engine when the keyboard is hidden. */
    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        Log.d(TAG, "onFinishInputView")
        LvglEngine.instance.pause()
    }

    /**
     * Destroys the service.
     *
     * The process-wide engine is only torn down when [ownsProcess] is `true`;
     * otherwise the host app shares it and must keep it alive.
     */
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        /* Only tear down the process-wide engine when this IME owns the process;
         * otherwise the app's activities share it and must keep it alive. */
        if (ownsProcess) {
            LvglEngine.instance.shutdown()
        }
    }

    /**
     * The input connection of the currently focused editor, if any.
     *
     * @return the active connection, or `null` when no editor is bound.
     */
    protected fun getActiveInputConnection(): InputConnection? {
        return currentInputConnection
    }

    /**
     * Commits [text] to the active editor.
     *
     * @param text the text to insert at the cursor.
     */
    protected fun commitText(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    /**
     * Deletes [beforeLength] characters before the cursor and [afterLength]
     * characters after it.
     */
    protected fun deleteSurroundingText(beforeLength: Int, afterLength: Int) {
        currentInputConnection?.deleteSurroundingText(beforeLength, afterLength)
    }

    /**
     * Sends a down/up key event pair for [keyEventCode] to the active editor.
     *
     * @param keyEventCode an Android [android.view.KeyEvent] key code.
     */
    protected fun sendKeyDownUp(keyEventCode: Int) {
        currentInputConnection?.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyEventCode))
        currentInputConnection?.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyEventCode))
    }

    /** Holds the private log tag. */
    companion object {
        private const val TAG = "LvglInputMethodService"
    }
}

