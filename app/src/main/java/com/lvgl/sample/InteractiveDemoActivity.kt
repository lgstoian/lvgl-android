package com.lvgl.sample

import com.lvgl.android.activity.LvglActivity
import com.lvgl.android.navigation.LvglNavigation

class InteractiveDemoActivity : LvglActivity() {
    override fun onCreateLvgl() {
        LvglNavigation.pushScreen("InteractiveDemo")
    }
}
