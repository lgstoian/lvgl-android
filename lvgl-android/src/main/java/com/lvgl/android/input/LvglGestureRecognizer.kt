// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.input

import android.gesture.Gesture
import android.util.Log
import android.view.MotionEvent
import kotlin.math.hypot

/**
 * Wraps an LVGL-compatible touch event with Android MotionEvent extras.
 */
data class TouchEvent(
    val action: Int,
    val x: Float,
    val y: Float,
    val pointerCount: Int,
    val pointerIndex: Int,
    val pointerId: Int = 0,
    val downTime: Long = 0,
    val eventTime: Long = 0,
    val pressure: Float = 0f,
    val orientation: Float = 0f,
    val size: Float = 0f,
    val toolType: Int = MotionEvent.TOOL_TYPE_UNKNOWN
)

/**
 * Represents a detected gesture.
 */
sealed class Gestures {
    data class Tap(val x: Float, val y: Float) : Gestures()
    data class LongPress(val x: Float, val y: Float, val duration: Long) : Gestures()
    data class Swipe(val direction: Direction, val velocityX: Float, val velocityY: Float) : Gestures()
    data class Pinch(val scaleFactor: Float, val centerX: Float, val centerY: Float) : Gestures()
    data class Rotate(val rotation: Float, val centerX: Float, val centerY: Float) : Gestures()

    enum class Direction { UP, DOWN, LEFT, RIGHT }
}

/**
 * Gesture recognizer that detects common touch gestures.
 * Usage:
 * ```
 * val recognizer = GestureRecognizer()
 * val result = recognizer.handle(event)
 * when (result) {
 *   is Gestures.Swipe -> handleSwipe(result.direction)
 *   is Gestures.Pinch -> handlePinch(result.scaleFactor)
 * }
 * ```
 */
class GestureRecognizer(
    private val config: Config = Config()
) {

    data class Config(
        val minSwipeDistance: Float = 100f,
        val minSwipeVelocity: Float = 100f,
        val longPressTimeout: Long = 500L,
        val tapTimeout: Long = 200L,
        val pinchThreshold: Float = 0.05f
    )

    // Track first pointer down.
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var downTime: Long = 0L
    private var isDown: Boolean = false

    // Multi-touch tracking.
    private var firstDownX: Float = 0f
    private var firstDownY: Float = 0f
    private var secondDownX: Float = 0f
    private var secondDownY: Float = 0f
    private var hadSecond: Boolean = false

    fun handle(event: MotionEvent): Gestures? {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTime = event.eventTime
                isDown = true
                hadSecond = false
                return null
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    firstDownX = event.getX(0)
                    firstDownY = event.getY(0)
                    secondDownX = event.getX(1)
                    secondDownY = event.getY(1)
                    hadSecond = true
                }
                return null
            }
            MotionEvent.ACTION_MOVE -> {
                if (hadSecond && event.pointerCount >= 2) {
                    val dx0 = event.getX(0) - firstDownX
                    val dy0 = event.getY(0) - firstDownY
                    val dx1 = event.getX(1) - secondDownX
                    val dy1 = event.getY(1) - secondDownY
                    val currDist = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
                    val initDist = hypot(firstDownX - secondDownX, firstDownY - secondDownY)
                    if (initDist > 1f && currDist > 1f) {
                        val scale = currDist / initDist
                        if (Math.abs(scale - 1f) > config.pinchThreshold) {
                            val cx = (event.getX(0) + event.getX(1)) * 0.5f
                            val cy = (event.getY(0) + event.getY(1)) * 0.5f
                            hadSecond = false // reset after delivering
                            return Gestures.Pinch(scale, cx, cy)
                        }
                    }
                }
                return null
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                return if (!hadSecond) {
                    recognizeGesture(event.x, event.y, event.eventTime - downTime)
                } else null
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount < 2) {
                    hadSecond = false
                }
                return null
            }
        }
        return null
    }

    private fun recognizeGesture(x: Float, y: Float, duration: Long): Gestures? {
        if (!isDown) return null
        isDown = false

        val dx = x - downX
        val dy = y - downY
        val distance = hypot(dx, dy)

        // Tap.
        if (duration < config.tapTimeout && distance < config.minSwipeDistance * 0.5f) {
            return Gestures.Tap(x, y)
        }

        // Long press.
        if (duration >= config.longPressTimeout && distance < config.minSwipeDistance * 0.5f) {
            return Gestures.LongPress(x, y, duration)
        }

        // Swipe.
        if (distance >= config.minSwipeDistance) {
            val dir = if (Math.abs(dx) >= Math.abs(dy)) {
                if (dx > 0) Gestures.Direction.RIGHT else Gestures.Direction.LEFT
            } else {
                if (dy > 0) Gestures.Direction.DOWN else Gestures.Direction.UP
            }
            val velocity = distance / (duration / 1000f + 0.001f)
            if (velocity >= config.minSwipeVelocity) {
                return Gestures.Swipe(
                    direction = dir,
                    velocityX = dx / (duration / 1000f + 0.001f),
                    velocityY = dy / (duration / 1000f + 0.001f)
                )
            }
        }
        return null
    }

    fun reset() {
        isDown = false
        hadSecond = false
    }
}

