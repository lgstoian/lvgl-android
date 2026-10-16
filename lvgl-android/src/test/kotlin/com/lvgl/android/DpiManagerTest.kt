package com.lvgl.android

import com.lvgl.android.config.DpiManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DpiManagerTest {

    @Test
    fun testScreenSizeCategoryValues() {
        assertEquals("SMALL", DpiManager.ScreenSize.SMALL.name)
        assertEquals("MEDIUM", DpiManager.ScreenSize.MEDIUM.name)
        assertEquals("LARGE", DpiManager.ScreenSize.LARGE.name)
        assertEquals("EXTRA_LARGE", DpiManager.ScreenSize.EXTRA_LARGE.name)
    }

    @Test
    fun testOrientationValues() {
        assertEquals(1, android.content.res.Configuration.ORIENTATION_PORTRAIT)
        assertEquals(2, android.content.res.Configuration.ORIENTATION_LANDSCAPE)
    }
}
