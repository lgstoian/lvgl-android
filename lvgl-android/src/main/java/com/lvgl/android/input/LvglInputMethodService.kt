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
import com.lvgl.android.view.LvglSurfaceView

abstract class LvglInputMethodService : InputMethodService() {

    private var lvglView: LvglSurfaceView? = null

    abstract fun onCreateKeyboardView(): View?

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        LvglEngine.instance.initialize()
    }

    override fun onCreateInputView(): View? {
        Log.d(TAG, "onCreateInputView")
        return onCreateKeyboardView()
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.d(TAG, "onStartInputView")
        LvglEngine.instance.resume()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        Log.d(TAG, "onFinishInputView")
        LvglEngine.instance.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        LvglEngine.instance.shutdown()
    }

    protected fun getActiveInputConnection(): InputConnection? {
        return currentInputConnection
    }

    protected fun commitText(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    protected fun deleteSurroundingText(beforeLength: Int, afterLength: Int) {
        currentInputConnection?.deleteSurroundingText(beforeLength, afterLength)
    }

    protected fun sendKeyDownUp(keyEventCode: Int) {
        currentInputConnection?.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyEventCode))
        currentInputConnection?.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyEventCode))
    }

    companion object {
        private const val TAG = "LvglInputMethodService"
    }
}

