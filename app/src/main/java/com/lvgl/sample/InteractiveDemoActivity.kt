package com.lvgl.sample

import android.os.Bundle
import com.lvgl.android.activity.LvglActivity
import com.lvgl.android.navigation.LvglNavigation

class InteractiveDemoActivity : LvglActivity() {
    override fun onCreateLvgl() {
        LvglNavigation.pushScreen("InteractiveDemo")
        com.lvgl.android.engine.LvglEngine.instance.loadDemo(2)
    }
}
