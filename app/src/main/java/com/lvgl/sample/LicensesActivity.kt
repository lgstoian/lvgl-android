// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.sample

import com.lvgl.android.activity.LvglActivity
import com.lvgl.android.navigation.LvglNavigation
import com.lvgl.android.ui.LvglUi

/**
 * Shows the open-source notices for the software the app uses.
 *
 * Google Play requires apps that use open-source software to disclose the
 * applicable licenses. The screen is declared with the [LvglUi] DSL like any
 * other app screen.
 */
class LicensesActivity : LvglActivity() {

    override fun onCreateLvgl() {
        LvglNavigation.pushScreen("Licenses")
        LvglUi.registerScreen(SCREEN_ID, title = getString(R.string.app_demo_licenses), showBack = true) {
            column {
                label(getString(R.string.licenses_text), sizeDp = 12)
            }
        }
        setLvglScreen(SCREEN_ID)
    }

    companion object {
        /** App-defined screen id; built-in screens occupy `0..1000`. */
        const val SCREEN_ID = 1001
    }
}
