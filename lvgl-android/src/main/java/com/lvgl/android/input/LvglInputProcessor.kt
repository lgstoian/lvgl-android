// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.input

import android.content.Context
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
class LvglInputProcessor(private val context: Context?) {

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
        val count = event.pointerCount
        val index = event.actionIndex
        val useIndex = event.actionMasked != MotionEvent.ACTION_MOVE && count > 0
        val x = if (useIndex) event.getX(index) else event.x
        val y = if (useIndex) event.getY(index) else event.y
        return process(
            GestureFrame(
                action = event.actionMasked,
                x = x,
                y = y,
                pointerCount = count,
                eventTime = event.eventTime,
                pointer0X = if (count > 0) event.getX(0) else x,
                pointer0Y = if (count > 0) event.getY(0) else y,
                pointer1X = if (count > 1) event.getX(1) else 0f,
                pointer1Y = if (count > 1) event.getY(1) else 0f,
                downTime = event.downTime
            )
        )
    }

    /**
     * Pure-Kotlin equivalent of [processMotionEvent], used by unit tests and by
     * any non-Android host that can supply [GestureFrame]s directly.
     */
    fun process(frame: GestureFrame): Gestures? {
        /* Tap and long-press are returned directly by handle(); swipe, pinch and
         * rotate are queued for consumeLastGesture(). Keep BOTH paths: dropping
         * the direct result silently swallowed every tap and long-press. */
        val direct = recognizer.handle(frame)

        when (frame.action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                latestX = frame.x
                latestY = frame.y
                LvglEngine.instance.onTouchEvent(0, latestX, latestY)
            }
            MotionEvent.ACTION_MOVE -> {
                latestX = frame.x
                latestY = frame.y
                LvglEngine.instance.onTouchEvent(2, latestX, latestY)
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> {
                LvglEngine.instance.onTouchEvent(1, latestX, latestY)
            }
        }

        return direct ?: recognizer.consumeLastGesture()
    }

    /** Holds the private log tag. */
    companion object {
        private const val TAG = "LvglInputProcessor"
    }
}
