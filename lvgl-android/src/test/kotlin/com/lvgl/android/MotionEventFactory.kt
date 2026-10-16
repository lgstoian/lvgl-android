package com.lvgl.android

import android.os.SystemClock
import com.lvgl.android.input.GestureRecognizer
import com.lvgl.android.input.Gestures
import org.junit.Test
import java.lang.reflect.Method

/**
 * Helper to create MotionEvent without Android framework (for unit tests).
 */
object MotionEventFactory {
    fun obtain(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float, metaState: Int): Any {
        val method = Class.forName("android.view.MotionEvent").getMethod(
            "obtain", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )
        return method.invoke(null, downTime, eventTime, action, x, y, metaState)
    }
}
