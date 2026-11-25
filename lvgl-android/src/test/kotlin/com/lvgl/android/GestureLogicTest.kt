package com.lvgl.android

import com.lvgl.android.input.GestureFrame
import com.lvgl.android.input.GestureRecognizer
import com.lvgl.android.input.Gestures
import com.lvgl.android.input.LvglInputProcessor
import org.junit.Test

/**
 * Unit tests that don't depend on Android framework mocked classes.
 */
class GestureLogicTest {

    @Test
    fun testGestureConfig() {
        val config = GestureRecognizer.Config()
        assert(config.minSwipeDistance == 100f)
        assert(config.longPressTimeout == 500L)
        assert(config.rotationThreshold == 15f)
    }

    @Test
    fun testCustomConfig() {
        val custom = GestureRecognizer.Config(
            minSwipeDistance = 50f,
            minSwipeVelocity = 200f,
            longPressTimeout = 300L
        )
        val recognizer = GestureRecognizer(custom)
        assert(recognizer != null)
    }

    @Test
    fun testGesturesSealed() {
        val tap = Gestures.Tap(10f, 20f)
        assert(tap.x == 10f)
        assert(tap.y == 20f)

        val swipe = Gestures.Swipe(Gestures.Direction.UP, 100f, -200f)
        assert(swipe.direction == Gestures.Direction.UP)

        val pinch = Gestures.Pinch(1.5f, 50f, 50f)
        assert(pinch.scaleFactor == 1.5f)

        val rotate = Gestures.Rotate(90f, 50f, 50f)
        assert(rotate.rotation == 90f)
        assert(rotate.centerX == 50f)
    }

    private fun down(x: Float, y: Float, at: Long = 0L) = GestureFrame(
        action = 0, x = x, y = y, pointerCount = 1, eventTime = at,
        pointer0X = x, pointer0Y = y
    )

    private fun up(x: Float, y: Float, at: Long) = GestureFrame(
        action = 1, x = x, y = y, pointerCount = 1, eventTime = at,
        pointer0X = x, pointer0Y = y
    )

    @Test
    fun tapIsReturnedDirectly() {
        val recognizer = GestureRecognizer()
        assert(recognizer.handle(down(20f, 30f)) == null)
        val result = recognizer.handle(up(21f, 31f, 50L))
        assert(result is Gestures.Tap)
        assert((result as Gestures.Tap).x == 21f)
        /* A direct result is never also queued for consumeLastGesture(). */
        assert(recognizer.consumeLastGesture() == null)
    }

    @Test
    fun longPressIsReturnedDirectly() {
        val recognizer = GestureRecognizer()
        assert(recognizer.handle(down(20f, 30f, 0L)) == null)
        val result = recognizer.handle(up(22f, 31f, 600L))
        assert(result is Gestures.LongPress)
        assert((result as Gestures.LongPress).duration == 600L)
        assert(recognizer.consumeLastGesture() == null)
    }

    @Test
    fun swipeIsQueuedAndDeliveredOnce() {
        val recognizer = GestureRecognizer()
        assert(recognizer.handle(down(10f, 10f, 0L)) == null)
        assert(recognizer.handle(up(260f, 10f, 100L)) == null)
        val queued = recognizer.consumeLastGesture()
        assert(queued is Gestures.Swipe)
        assert((queued as Gestures.Swipe).direction == Gestures.Direction.RIGHT)
        assert(recognizer.consumeLastGesture() == null)
    }

    @Test
    fun pinchIsQueuedWithoutSpuriousTap() {
        val recognizer = GestureRecognizer()
        assert(recognizer.handle(down(100f, 100f, 0L)) == null)
        assert(
            recognizer.handle(
                GestureFrame(
                    action = 5, x = 0f, y = 0f, pointerCount = 2, eventTime = 20L,
                    pointer0X = 50f, pointer0Y = 100f,
                    pointer1X = 150f, pointer1Y = 100f
                )
            ) == null
        )
        assert(
            recognizer.handle(
                GestureFrame(
                    action = 2, x = 100f, y = 100f, pointerCount = 2, eventTime = 40L,
                    pointer0X = 0f, pointer0Y = 100f,
                    pointer1X = 200f, pointer1Y = 100f
                )
            ) == null
        )
        val queued = recognizer.consumeLastGesture()
        assert(queued is Gestures.Pinch)
        assert((queued as Gestures.Pinch).scaleFactor == 2f)
        /* The final UP must not produce a tap as well. */
        assert(recognizer.handle(up(100f, 100f, 60L)) == null)
        assert(recognizer.consumeLastGesture() == null)
    }

    @Test
    fun rotateIsDetectedFromTwoPointerAngleDelta() {
        val recognizer = GestureRecognizer()
        assert(recognizer.handle(down(100f, 100f, 0L)) == null)
        /* Initial pointer line: (50,100) -> (150,100) is 0 degrees. */
        assert(
            recognizer.handle(
                GestureFrame(
                    action = 5, x = 0f, y = 0f, pointerCount = 2, eventTime = 20L,
                    pointer0X = 50f, pointer0Y = 100f,
                    pointer1X = 150f, pointer1Y = 100f
                )
            ) == null
        )
        /* Rotated to (100,50) -> (100,150): 90 degrees clockwise in screen coords. */
        assert(
            recognizer.handle(
                GestureFrame(
                    action = 2, x = 100f, y = 100f, pointerCount = 2, eventTime = 40L,
                    pointer0X = 100f, pointer0Y = 50f,
                    pointer1X = 100f, pointer1Y = 150f
                )
            ) == null
        )
        val queued = recognizer.consumeLastGesture()
        assert(queued is Gestures.Rotate)
        assert((queued as Gestures.Rotate).rotation == 90f)
        assert(queued.centerX == 100f)
        assert(queued.centerY == 100f)
        assert(recognizer.handle(up(100f, 100f, 60L)) == null)
    }

    @Test
    fun processorForwardsDirectTapResult() {
        /* The processor keeps only an unused Context reference; null is safe
         * here and avoids needing an Android runtime in a plain JVM test. */
        val processor = LvglInputProcessor(null)
        assert(processor.process(down(5f, 6f, 0L)) == null)
        val tap = processor.process(up(5f, 6f, 50L))
        assert(tap is Gestures.Tap)
    }
}
