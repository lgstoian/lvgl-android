// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.input

import android.content.Context
import android.util.Log
import android.view.MotionEvent
import com.lvgl.android.engine.LvglEngine

/**
 * Combines Android MotionEvent handling with LVGL touch input.
 *
 * Raw touch events are ALWAYS forwarded to LVGL so widgets work. Gesture
 * detection runs alongside as a side-channel (LVGL does its own hit-testing),
 * so we never drop an UP event or re-send a DOWN at the wrong coordinate —
 * the previous version sent ACTION_DOWN on tap and never sent RELEASED, which
 * left widgets stuck in the pressed state.
 *
 * Call [processMotionEvent] from the view's `onTouchEvent`, on the UI thread.
 */
class LvglInputProcessor(private val context: Context) {

    private val recognizer = GestureRecognizer()
    private var latestX = 0f
    private var latestY = 0f

    /**
     * Forwards [event] to LVGL and returns any gesture it produced.
     *
     * Down/move/up are translated to LVGL input actions `0`, `2` and `1`
     * respectively; the raw coordinates are preserved for every pointer. The
     * gesture result is side-channel information for the caller and never
     * suppresses the touch itself.
     *
     * @param event the Android touch event to process.
     * @return the detected [Gestures], or `null` when no gesture was recognised.
     */
    fun processMotionEvent(event: MotionEvent): Gestures? {
        recognizer.handle(event)

        val action = event.actionMasked
        when (action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                latestX = event.getX(idx)
                latestY = event.getY(idx)
                LvglEngine.instance.onTouchEvent(0, latestX, latestY)
            }
            MotionEvent.ACTION_MOVE -> {
                latestX = event.x
                latestY = event.y
                LvglEngine.instance.onTouchEvent(2, latestX, latestY)
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> {
                LvglEngine.instance.onTouchEvent(1, latestX, latestY)
            }
        }

        return recognizer.consumeLastGesture()
    }

    /** Holds the private log tag. */
    companion object {
        private const val TAG = "LvglInputProcessor"
    }
}
