package com.lvgl.android

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lvgl.android.config.DpiManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LvglActivityTest {

    @Test
    fun testDpiManagerContext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dpiManager = DpiManager(context)
        assertTrue("DPI should be > 0", dpiManager.getDpi() > 0)
        assertTrue("Density should be > 0", dpiManager.getDensity() > 0)
    }

    @Test
    fun testDpToPx() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dpiManager = DpiManager(context)
        val px = dpiManager.dpToPx(16f)
        assertTrue("16dp should convert to positive px", px > 0)
    }

    @Test
    fun testScreenSizeCategory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dpiManager = DpiManager(context)
        val category = dpiManager.getSizeCategory()
        assertTrue("Should return a valid category", category in DpiManager.ScreenSize.values())
    }
}
