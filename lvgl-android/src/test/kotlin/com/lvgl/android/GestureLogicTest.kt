package com.lvgl.android

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
    }
}
