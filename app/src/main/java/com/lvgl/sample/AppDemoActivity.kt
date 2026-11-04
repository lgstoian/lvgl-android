// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.sample

import com.lvgl.android.activity.LvglActivity
import com.lvgl.android.navigation.LvglNavigation

/**
 * Demonstrates the Kotlin UI builder.
 *
 * The whole screen is declared in Kotlin with `LvglUi.registerScreen` (see
 * [AppDemoScreen]) and rendered by the native UI kit, so it follows the
 * Material 3 palette and is covered by the layout lint like every built-in
 * screen. Node callbacks and `LvglUi.updateNode` make it interactive without
 * writing any C++.
 */
class AppDemoActivity : LvglActivity() {

    override fun onCreateLvgl() {
        LvglNavigation.pushScreen("AppDemo")
        AppDemoScreen.register(this)
        setLvglScreen(AppDemoScreen.SCREEN_ID)
    }
}
