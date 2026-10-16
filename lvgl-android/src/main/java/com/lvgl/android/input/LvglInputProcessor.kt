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
 * Supports single and multi-touch with gesture detection.
 */
class LvglInputProcessor(private val context: Context) {

    private val recognizer = GestureRecognizer()
    private var latestX = 0f
    private var latestY = 0f

    fun processMotionEvent(event: MotionEvent): Boolean {
        val result = recognizer.handle(event)
        when (result) {
            is Gestures.Tap -> {
                Log.d(TAG, "Tap at (${result.x}, ${result.y})")
                LvglEngine.instance.onTouchEvent(MotionEvent.ACTION_DOWN, MotionEvents.toFloat(result.x), MotionEvents.toFloat(result.y))
                return true
            }
            is Gestures.LongPress -> {
                Log.d(TAG, "LongPress at (${result.x}, ${result.y}), duration=${result.duration}")
                return true
            }
            is Gestures.Swipe -> {
                Log.d(TAG, "Swipe ${result.direction}")
                return true
            }
            is Gestures.Pinch -> {
                Log.d(TAG, "Pinch scale=${result.scaleFactor}")
                return true
            }
            is Gestures.Rotate -> {
                Log.d(TAG, "Rotate angle=${result.rotation}")
                return true
            }
            null -> {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                        latestX = event.x
                        latestY = event.y
                        LvglEngine.instance.onTouchEvent(0, latestX, latestY)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        latestX = event.x
                        latestY = event.y
                        LvglEngine.instance.onTouchEvent(2, latestX, latestY)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        LvglEngine.instance.onTouchEvent(1, latestX, latestY)
                    }
                }
                return true
            }
        }
        return true
    }

    companion object {
        private const val TAG = "LvglInputProcessor"
    }
}

/** Utility for motion event value conversions. */
object MotionEvents {
    fun toFloat(v: Float): Float = v
}

