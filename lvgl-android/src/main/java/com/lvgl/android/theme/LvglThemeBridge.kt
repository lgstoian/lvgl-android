// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.theme

import android.util.Log
import com.lvgl.android.engine.LvglEngine

/**
 * Pushes resolved Material 3 palettes into the LVGL engine.
 *
 * Implements [MaterialTheme.OnThemeChangedListener] so every theme change
 * (initialisation, dark-mode toggle, dynamic-colour switch) is applied to the
 * native UI kit immediately. Create one instance, register it with
 * [MaterialTheme.addListener] and call [applyCurrentTheme] once to seed the
 * engine before the first frame.
 */
class LvglThemeBridge : MaterialTheme.OnThemeChangedListener {

    /**
     * Applies the current theme to LVGL.
     *
     * Safe to call from the UI thread at any time, including before a display
     * exists; the engine stores the palette for the next frame.
     */
    fun applyCurrentTheme() {
        val theme = MaterialTheme.instance
        LvglEngine.instance.applyTheme(theme.isDarkMode, theme.materialPalette())
    }

    /**
     * Applies the changed theme to LVGL.
     *
     * Called synchronously by [MaterialTheme] when its palette is re-resolved.
     */
    override fun onThemeChanged(theme: MaterialTheme) {
        LvglEngine.instance.applyTheme(theme.isDarkMode, theme.materialPalette())
        Log.d(TAG, "Theme applied to LVGL: dark=${theme.isDarkMode}")
    }

    /** Holds the private log tag. */
    companion object {
        private const val TAG = "LvglThemeBridge"
    }
}

