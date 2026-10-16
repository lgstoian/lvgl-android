package com.lvgl.android.activity

import android.view.KeyEvent
import com.lvgl.android.accessibility.LvglAccessibilityManager
import com.lvgl.android.accessibility.LvglFocusNavigation
import com.lvgl.android.accessibility.LvglScreenReader

/**
 * Extension point for accessibility on LvglActivity.
 */
fun LvglActivity.setupAccessibility() {
    val accManager = LvglAccessibilityManager(this)
    val focusNavigation = LvglFocusNavigation(this)

    if (accManager.isEnabled()) {
        LvglScreenReader.initialize(this)
        this.onKeyDown(KeyEvent.KEYCODE_TAB, null) // trigger to register
    }
}
