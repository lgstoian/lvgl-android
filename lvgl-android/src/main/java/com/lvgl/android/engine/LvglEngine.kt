// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.engine

import android.content.Context
import android.util.DisplayMetrics
import android.view.Surface

class LvglEngine private constructor() {

    private var nativeHandle: Long = 0
    private var isInitialized = false

    val initialized: Boolean get() = isInitialized

    fun initialize() {
        if (!isInitialized) {
            nativeHandle = nativeCreate()
            isInitialized = true
        }
    }

    fun shutdown() {
        if (isInitialized && nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0
            isInitialized = false
        }
    }

    fun initDisplay(surface: Surface, width: Int, height: Int, dpi: Int) {
        if (isInitialized) {
            nativeInitDisplay(nativeHandle, surface, width, height, dpi)
        }
    }

    fun deinitDisplay() {
        if (isInitialized) {
            nativeDeinitDisplay(nativeHandle)
        }
    }

    fun resume() {
        if (isInitialized) {
            nativeResume(nativeHandle)
        }
    }

    fun pause() {
        if (isInitialized) {
            nativePause(nativeHandle)
        }
    }

    fun onTouchEvent(action: Int, x: Float, y: Float) {
        if (isInitialized) {
            nativeTouchEvent(nativeHandle, action, x, y)
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeInitDisplay(handle: Long, surface: Surface, width: Int, height: Int, dpi: Int)
    private external fun nativeDeinitDisplay(handle: Long)
    private external fun nativeResume(handle: Long)
    private external fun nativePause(handle: Long)
    private external fun nativeTouchEvent(handle: Long, action: Int, x: Float, y: Float)

    fun applyTheme(darkMode: Boolean, primaryColor: Int, secondaryColor: Int) {
        if (isInitialized && nativeHandle != 0L) {
            nativeApplyTheme(nativeHandle, darkMode, primaryColor, secondaryColor)
        }
    }

    private external fun nativeApplyTheme(handle: Long, darkMode: Boolean, primaryColor: Int, secondaryColor: Int)

    fun loadDemo(demoId: Int) {
        if (isInitialized && nativeHandle != 0L) {
            nativeLoadDemo(nativeHandle, demoId)
        }
    }

    private external fun nativeLoadDemo(handle: Long, demoId: Int)

    companion object {
        private const val TAG = "LvglEngine"

        val instance: LvglEngine by lazy { LvglEngine() }

        init {
            System.loadLibrary("lvgl_android")
        }
    }
}

