// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.theme

import android.util.Log
import com.lvgl.android.engine.LvglEngine

class LvglThemeBridge : MaterialTheme.OnThemeChangedListener {

    fun applyCurrentTheme() {
        val theme = MaterialTheme.instance
        LvglEngine.instance.applyTheme(theme.isDarkMode, theme.primaryColor, theme.secondaryColor)
    }

    override fun onThemeChanged(theme: MaterialTheme) {
        LvglEngine.instance.applyTheme(theme.isDarkMode, theme.primaryColor, theme.secondaryColor)
        Log.d(TAG, "Theme applied to LVGL: dark=${theme.isDarkMode}")
    }

    companion object {
        private const val TAG = "LvglThemeBridge"
    }
}

