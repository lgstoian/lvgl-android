// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.view

import android.content.Context
import android.view.MotionEvent
import com.lvgl.android.engine.LvglEngine
import com.lvgl.android.input.LvglInputProcessor

/**
 * Drop-in replacement for SurfaceView-based rendering that handles gesture recognition.
 */
open class LvglSurfaceViewWithGestures @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0
) : LvglSurfaceView(context, attrs, defStyleAttr) {

    private val inputProcessor = LvglInputProcessor(context)
    private var lastGestureResult: com.lvgl.android.input.Gestures? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        lastGestureResult = null
        val result = inputProcessor.processMotionEvent(event)
        return result || super.onTouchEvent(event)
    }

    fun getLastGestureResult(): com.lvgl.android.input.Gestures? = lastGestureResult
}

