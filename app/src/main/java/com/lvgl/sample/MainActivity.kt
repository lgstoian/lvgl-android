// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.sample

import android.os.Bundle
import com.lvgl.android.activity.LvglActivity
import com.lvgl.android.navigation.LvglNavigation

/**
 * App entry point. Renders the device dashboard in LVGL - no native Android views.
 *
 * Rules (see ai-context.md):
 *  - LvglActivity subclasses must NOT call setContentView().
 *  - Switch LVGL content via the engine, not by replacing the surface.
 *
 * This sample is the reference app for the developer toolkit (template/ and the
 * com.lvgl.android.app scaffolding plugin). @breadcrumb BC-032
 */
class MainActivity : LvglActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Register the screen-to-activity map for LVGL dashboard navigation
        // before the base activity wires up its navigator (which needs the resolver).
        LvglActivity.screenResolver = { screenId ->
            when (screenId) {
                1 -> WidgetShowcaseActivity::class.java
                2 -> LayoutShowcaseActivity::class.java
                3 -> InteractiveDemoActivity::class.java
                4 -> CameraSampleActivity::class.java
                5 -> GameSampleActivity::class.java
                6 -> LauncherSampleActivity::class.java
                7 -> com.lvgl.sample.showcase.DemoShowcaseActivity::class.java
                1000 -> AppDemoActivity::class.java
                1001 -> LicensesActivity::class.java
                else -> null
            }
        }
        super.onCreate(savedInstanceState)
    }

    override fun onCreateLvgl() {
        LvglNavigation.setMainActivity(this)
        LvglNavigation.pushScreen("Dashboard")
        setLvglScreen(0)
    }
}
