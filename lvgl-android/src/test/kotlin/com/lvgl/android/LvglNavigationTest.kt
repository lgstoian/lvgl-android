package com.lvgl.android

import com.lvgl.android.navigation.LvglNavigation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LvglNavigationTest {

    @Test
    fun testPushScreen() {
        LvglNavigation.clearStack()
        LvglNavigation.pushScreen("Screen1")
        LvglNavigation.pushScreen("Screen2")

        val stack = LvglNavigation.getBackStack()
        assertEquals(2, stack.size)
        assertEquals("Screen1", stack[0])
        assertEquals("Screen2", stack[1])
    }

    @Test
    fun testPopScreen() {
        LvglNavigation.clearStack()
        LvglNavigation.pushScreen("Screen1")
        LvglNavigation.pushScreen("Screen2")

        val popped = LvglNavigation.popScreen()
        assertEquals("Screen2", popped)

        val stack = LvglNavigation.getBackStack()
        assertEquals(1, stack.size)
        assertEquals("Screen1", stack[0])
    }

    @Test
    fun testPopEmptyStack() {
        LvglNavigation.clearStack()
        val popped = LvglNavigation.popScreen()
        assertNull(popped)
    }

    @Test
    fun testClearStack() {
        LvglNavigation.pushScreen("Screen1")
        LvglNavigation.pushScreen("Screen2")
        LvglNavigation.clearStack()

        val stack = LvglNavigation.getBackStack()
        assertEquals(0, stack.size)
    }
}
