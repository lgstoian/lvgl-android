// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.input

import android.view.MotionEvent
import kotlin.math.atan2
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
 * A pure-Kotlin frame of pointer coordinates fed to [GestureRecognizer].
 *
 * [GestureRecognizer.handle] accepts this type so the recognition logic can be
 * unit-tested without mocking Android's [MotionEvent]. Production code calls
 * the [MotionEvent] overload, which builds one of these frames.
 *
 * @property action masked [android.view.MotionEvent] action constant.
 * @property x coordinate of the primary pointer (the one the action applies to).
 * @property y coordinate of the primary pointer.
 * @property pointerCount number of active pointers.
 * @property eventTime time this frame was generated, in milliseconds.
 * @property pointer0X x of the first pointer, used for two-finger gestures.
 * @property pointer0Y y of the first pointer.
 * @property pointer1X x of the second pointer.
 * @property pointer1Y y of the second pointer.
 * @property downTime time the gesture started, in milliseconds.
 */
data class GestureFrame(
    val action: Int,
    val x: Float,
    val y: Float,
    val pointerCount: Int,
    val eventTime: Long,
    val pointer0X: Float = x,
    val pointer0Y: Float = y,
    val pointer1X: Float = 0f,
    val pointer1Y: Float = 0f,
    val downTime: Long = eventTime
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
     * A two-finger rotation gesture; [rotation] is the angle delta in degrees.
     *
     * @property rotation signed rotation relative to the initial pointer line,
     *   normalised to -180..180 degrees.
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
        val pinchThreshold: Float = 0.05f,
        /** Signed angle change, in degrees, that starts a rotation gesture. */
        val rotationThreshold: Float = 15f
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
    private var initialAngle: Float = 0f
    private var hadSecond: Boolean = false
    private var hasInitialAngle: Boolean = false
    private var suppressSingleTouchResult: Boolean = false

    /**
     * Feeds one [android.view.MotionEvent] into the recognizer.
     *
     * Tap and long-press results are returned directly; swipe, pinch and rotate
     * results are stored and delivered through [consumeLastGesture]. The
     * recognizer does not consume the event, so callers can still forward it to
     * LVGL.
     *
     * @param event the touch event to classify.
     * @return a completed [Gestures] result, or `null` when nothing was
     *   recognised yet.
     */
    fun handle(event: MotionEvent): Gestures? {
        val count = event.pointerCount
        return handle(
            GestureFrame(
                action = event.actionMasked,
                x = event.x,
                y = event.y,
                pointerCount = count,
                eventTime = event.eventTime,
                pointer0X = if (count > 0) event.getX(0) else event.x,
                pointer0Y = if (count > 0) event.getY(0) else event.y,
                pointer1X = if (count > 1) event.getX(1) else 0f,
                pointer1Y = if (count > 1) event.getY(1) else 0f,
                downTime = event.downTime
            )
        )
    }

    /**
     * Feeds one pure-Kotlin [GestureFrame] into the recognizer.
     *
     * This is the same classification path as the [MotionEvent] overload; it
     * exists so the gesture logic can be tested without Android framework mocks.
     */
    fun handle(frame: GestureFrame): Gestures? {
        when (frame.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = frame.x
                downY = frame.y
                downTime = frame.eventTime
                isDown = true
                hadSecond = false
                hasInitialAngle = false
                suppressSingleTouchResult = false
                return null
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (frame.pointerCount >= 2 && frame.hasTwoPointers()) {
                    firstDownX = frame.pointer0X
                    firstDownY = frame.pointer0Y
                    secondDownX = frame.pointer1X
                    secondDownY = frame.pointer1Y
                    initialAngle = angleBetween(firstDownX, firstDownY, secondDownX, secondDownY)
                    hadSecond = true
                    hasInitialAngle = true
                    suppressSingleTouchResult = true
                }
                return null
            }
            MotionEvent.ACTION_MOVE -> {
                if (hadSecond && frame.pointerCount >= 2 && frame.hasTwoPointers()) {
                    val cx = (frame.pointer0X + frame.pointer1X) * 0.5f
                    val cy = (frame.pointer0Y + frame.pointer1Y) * 0.5f

                    if (hasInitialAngle) {
                        val delta = normalizeAngle(
                            angleBetween(frame.pointer0X, frame.pointer0Y,
                                frame.pointer1X, frame.pointer1Y) - initialAngle
                        )
                        if (Math.abs(delta) >= config.rotationThreshold) {
                            hadSecond = false
                            hasInitialAngle = false
                            pendingGesture = Gestures.Rotate(delta, cx, cy)
                            return null
                        }
                    }

                    val currDist = hypot(
                        frame.pointer0X - frame.pointer1X,
                        frame.pointer0Y - frame.pointer1Y
                    )
                    val initDist = hypot(firstDownX - secondDownX, firstDownY - secondDownY)
                    if (initDist > 1f && currDist > 1f) {
                        val scale = currDist / initDist
                        if (Math.abs(scale - 1f) > config.pinchThreshold) {
                            hadSecond = false
                            pendingGesture = Gestures.Pinch(scale, cx, cy)
                            return null
                        }
                    }
                }
                return null
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (suppressSingleTouchResult) {
                    /* A two-finger gesture already produced a result; the final
                     * UP must not also be classified as a tap. */
                    isDown = false
                    suppressSingleTouchResult = false
                    hadSecond = false
                    hasInitialAngle = false
                    return null
                }
                return if (!hadSecond) {
                    recognizeGesture(frame.x, frame.y, frame.eventTime - downTime)
                } else {
                    isDown = false
                    null
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (frame.pointerCount < 2) {
                    hadSecond = false
                    hasInitialAngle = false
                }
                return null
            }
        }
        return null
    }

    private fun GestureFrame.hasTwoPointers(): Boolean =
        pointerCount >= 2 &&
            (pointer0X != 0f || pointer0Y != 0f || pointer1X != 0f || pointer1Y != 0f)

    private fun angleBetween(x0: Float, y0: Float, x1: Float, y1: Float): Float =
        Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()

    private fun normalizeAngle(angle: Float): Float {
        var value = angle
        while (value > 180f) value -= 360f
        while (value < -180f) value += 360f
        return value
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
        hasInitialAngle = false
        suppressSingleTouchResult = false
        pendingGesture = null
    }

    private var pendingGesture: Gestures? = null
}
