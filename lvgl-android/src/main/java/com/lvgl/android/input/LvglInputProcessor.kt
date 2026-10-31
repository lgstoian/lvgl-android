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
 * Raw touch events are ALWAYS forwarded to LVLV so widgets work. Gesture
 * detection runs alongside as a side-channel (LVGL does its own hit-testing),
 * so we never drop an UP event or re-send a DOWN at the wrong coordinate —
 * the previous version sent ACTION_DOWN on tap and never sent RELEASED, which
 * left widgets stuck in the pressed state.
 */
class LvglInputProcessor(private val context: Context) {

    private val recognizer = GestureRecognizer()
    private var latestX = 0f
    private var latestY = 0f

    /** Returns a detected gesture, or null. Raw touch is always forwarded. */
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

    companion object {
        private const val TAG = "LvglInputProcessor"
    }
}
