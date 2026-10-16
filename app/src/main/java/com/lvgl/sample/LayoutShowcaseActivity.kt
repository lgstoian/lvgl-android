// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.sample

import android.os.Bundle
import com.lvgl.android.activity.LvglActivity
import com.lvgl.android.navigation.LvglNavigation

class LayoutShowcaseActivity : LvglActivity() {
    override fun onCreateLvgl() {
        LvglNavigation.pushScreen("LayoutShowcase")
        com.lvgl.android.engine.LvglEngine.instance.loadDemo(1)
    }
}

