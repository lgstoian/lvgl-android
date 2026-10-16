package com.lvgl.android

import com.lvgl.android.input.GestureRecognizer
import com.lvgl.android.input.Gestures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureRecognizerTest {

    @Test
    fun testTapDetection() {
        val recognizer = GestureRecognizer()
        val downEvent = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, 100f, 100f, 0)
        val upEvent = android.view.MotionEvent.obtain(0, 50, android.view.MotionEvent.ACTION_UP, 100f, 100f, 0)

        recognizer.handle(downEvent)
        val result = recognizer.handle(upEvent)

        assertTrue("Tap should be detected", result is Gestures.Tap)
        if (result is Gestures.Tap) {
            assertEquals(100f, result.x, 0.1f)
            assertEquals(100f, result.y, 0.1f)
        }
    }

    @Test
    fun testLongPressDetection() {
        val recognizer = GestureRecognizer()
        val downEvent = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, 100f, 100f, 0)
        val upEvent = android.view.MotionEvent.obtain(0, 600, android.view.MotionEvent.ACTION_UP, 100f, 100f, 0)

        recognizer.handle(downEvent)
        val result = recognizer.handle(upEvent)

        assertTrue("Long press should be detected", result is Gestures.LongPress)
    }

    @Test
    fun testSwipeDetection() {
        val recognizer = GestureRecognizer()
        val downEvent = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, 100f, 100f, 0)
        val upEvent = android.view.MotionEvent.obtain(0, 100, android.view.MotionEvent.ACTION_UP, 250f, 100f, 0)

        recognizer.handle(downEvent)
        val result = recognizer.handle(upEvent)

        assertTrue("Swipe should be detected", result is Gestures.Swipe)
        if (result is Gestures.Swipe) {
            assertEquals(Gestures.Direction.RIGHT, result.direction)
        }
    }

    @Test
    fun testNoGestureOnMoveOnly() {
        val recognizer = GestureRecognizer()
        val moveEvent = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_MOVE, 100f, 100f, 0)

        val result = recognizer.handle(moveEvent)
        assertEquals(null, result)
    }
}
