package com.lvgl.sample

import com.lvgl.android.activity.LvglActivity
import com.lvgl.android.navigation.LvglNavigation

class WidgetShowcaseActivity : LvglActivity() {
    override fun onCreateLvgl() {
        LvglNavigation.pushScreen("WidgetShowcase")
    }
}
