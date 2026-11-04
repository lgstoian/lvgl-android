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
 *
 * @param action masked [android.view.MotionEvent] action constant.
 * @param x pointer x coordinate in pixels.
 * @param y pointer y coordinate in pixels.
 * @param pointerCount number of pointers in the event.
 * @param pointerIndex index of the pointer the action applies to.
 * @param pointerId stable id of that pointer.
 * @param downTime time the gesture started.
 * @param eventTime time this event was generated.
 * @param pressure reported pressure, if any.
 * @param orientation reported orientation, if any.
 * @param size reported touch area size, if any.
 * @param toolType [android.view.MotionEvent] tool type.
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
 * A gesture detected from a stream of touch events.
 *
 * Each subtype carries the coordinates it was recognised at; use
 * [GestureRecognizer.handle] to classify events and
 * [GestureRecognizer.consumeLastGesture] to deliver each gesture once.
 */
sealed class Gestures {
    /**
     * A short touch without significant movement.
     *
     * @property x horizontal coordinate of the tap.
     * @property y vertical coordinate of the tap.
     */
    data class Tap(val x: Float, val y: Float) : Gestures()

    /**
     * A touch held longer than the configured long-press timeout.
     *
     * @property x horizontal coordinate of the press.
     * @property y vertical coordinate of the press.
     * @property duration how long the press lasted, in milliseconds.
     */
    data class LongPress(val x: Float, val y: Float, val duration: Long) : Gestures()

    /**
     * A fast drag in [direction].
     *
     * @property direction the dominant axis of the drag.
     * @property velocityX horizontal velocity in pixels per second.
     * @property velocityY vertical velocity in pixels per second.
     */
    data class Swipe(val direction: Direction, val velocityX: Float, val velocityY: Float) : Gestures()

    /**
     * A two-finger scale gesture; [scaleFactor] is relative to the initial span.
     *
     * @property scaleFactor the current span divided by the initial span.
     * @property centerX horizontal coordinate of the gesture centre.
     * @property centerY vertical coordinate of the gesture centre.
     */
    data class Pinch(val scaleFactor: Float, val centerX: Float, val centerY: Float) : Gestures()

    /**
     * A two-finger rotation gesture; [rotation] is in degrees.
     *
     * @property rotation the rotation relative to the initial orientation, in degrees.
     * @property centerX horizontal coordinate of the gesture centre.
     * @property centerY vertical coordinate of the gesture centre.
     */
    data class Rotate(val rotation: Float, val centerX: Float, val centerY: Float) : Gestures()

    /** Cardinal swipe directions. */
    enum class Direction {
        /** Upwards. */
        UP,

        /** Downwards. */
        DOWN,

        /** To the left. */
        LEFT,

        /** To the right. */
        RIGHT
    }
}

/**
 * Gesture recognizer that detects common touch gestures.
 *
 * Feed every [android.view.MotionEvent] to [handle]; the recognizer keeps its
 * own state and reports a gesture either directly or through
 * [consumeLastGesture], which clears the pending result so each gesture is
 * delivered exactly once.
 *
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

    /** Tuning thresholds; all distances are in pixels and times in milliseconds. */
    data class Config(
        /** Minimum travel for a drag to count as a swipe. */
        val minSwipeDistance: Float = 100f,
        /** Minimum velocity for a drag to count as a swipe. */
        val minSwipeVelocity: Float = 100f,
        /** How long a stationary press must be held to become a long press. */
        val longPressTimeout: Long = 500L,
        /** Maximum press duration that can still count as a tap. */
        val tapTimeout: Long = 200L,
        /** Relative span change that starts a pinch gesture. */
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

    /**
     * Feeds one [android.view.MotionEvent] into the recognizer.
     *
     * Tap and long-press results are returned directly; swipe and pinch results
     * are stored and delivered through [consumeLastGesture]. The recognizer
     * does not consume the event, so callers can still forward it to LVGL.
     *
     * @param event the touch event to classify.
     * @return a completed [Gestures] result, or `null` when nothing was
     *   recognised yet.
     */
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
                            pendingGesture = Gestures.Pinch(scale, cx, cy)
                            return null
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
                pendingGesture = Gestures.Swipe(
                    direction = dir,
                    velocityX = dx / (duration / 1000f + 0.001f),
                    velocityY = dy / (duration / 1000f + 0.001f)
                )
                return null
            }
        }
        return null
    }

    /**
     * Returns the most recent gesture and clears it, so each gesture is only
     * delivered once. Returns null if there is no pending gesture.
     */
    fun consumeLastGesture(): Gestures? {
        val g = pendingGesture
        pendingGesture = null
        return g
    }

    /**
     * Clears all in-progress gesture state.
     *
     * Call when the touch stream ends unexpectedly (for example when the view
     * is detached) so a later gesture is not misclassified.
     */
    fun reset() {
        isDown = false
        hadSecond = false
        pendingGesture = null
    }

    private var pendingGesture: Gestures? = null;
}

