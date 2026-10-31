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
        // The processor both detects gestures AND forwards the raw event to
        // LVGL, so calling super.onTouchEvent() here would double-send every
        // touch (widgets saw each press twice).
        lastGestureResult = inputProcessor.processMotionEvent(event)
        return true
    }

    fun getLastGestureResult(): com.lvgl.android.input.Gestures? = lastGestureResult
}

