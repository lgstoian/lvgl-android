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
 * Drop-in replacement for [LvglSurfaceView] that adds gesture recognition.
 *
 * Touches are routed through [LvglInputProcessor], which forwards the raw
 * event to LVGL and reports a detected gesture through
 * [getLastGestureResult]. Do not call `super.onTouchEvent` in addition: the
 * processor already forwards the event, so super would double-send every touch.
 */
open class LvglSurfaceViewWithGestures @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0
) : LvglSurfaceView(context, attrs, defStyleAttr) {

    private val inputProcessor = LvglInputProcessor(context)
    private var lastGestureResult: com.lvgl.android.input.Gestures? = null

    /** Processes the touch and returns `true` so the gesture stream continues. */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // The processor both detects gestures AND forwards the raw event to
        // LVGL, so calling super.onTouchEvent() here would double-send every
        // touch (widgets saw each press twice).
        lastGestureResult = inputProcessor.processMotionEvent(event)
        return true
    }

    /**
     * The gesture produced by the most recent touch event, if any.
     *
     * @return the last [com.lvgl.android.input.Gestures] result, or `null`.
     */
    fun getLastGestureResult(): com.lvgl.android.input.Gestures? = lastGestureResult
}

