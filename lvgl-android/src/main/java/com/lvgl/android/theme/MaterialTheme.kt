// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.theme

import android.content.Context
import android.content.res.Configuration
import android.util.Log

class MaterialTheme private constructor() {

    var isDarkMode: Boolean = false
        private set

    var primaryColor: Int = 0x1976D2.toInt()
        private set

    var secondaryColor: Int = 0xDC004E.toInt()
        private set

    var surfaceColor: Int = 0xFFFBFE.toInt()
        private set

    var onSurfaceColor: Int = 0x1C1B1F.toInt()
        private set

    var backgroundOpacity: Int = 100
        private set

    private val listeners = mutableListOf<OnThemeChangedListener>()

    fun initialize(context: Context) {
        val config = context.resources.configuration
        isDarkMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        updateColors()
        Log.d(TAG, "MaterialTheme initialized: darkMode=$isDarkMode")
    }

    fun onConfigurationChanged(config: Configuration) {
        val newDarkMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (newDarkMode != isDarkMode) {
            isDarkMode = newDarkMode
            updateColors()
            notifyListeners()
            Log.d(TAG, "Theme changed: darkMode=$isDarkMode")
        }
    }

    fun addListener(listener: OnThemeChangedListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: OnThemeChangedListener) {
        listeners.remove(listener)
    }

    private fun updateColors() {
        if (isDarkMode) {
            primaryColor = 0x90CAF9.toInt()
            secondaryColor = 0xF48FB1.toInt()
            surfaceColor = 0x1C1B1F.toInt()
            onSurfaceColor = 0xE6E1E5.toInt()
        } else {
            primaryColor = 0x1976D2.toInt()
            secondaryColor = 0xDC004E.toInt()
            surfaceColor = 0xFFFBFE.toInt()
            onSurfaceColor = 0x1C1B1F.toInt()
        }
    }

    private fun notifyListeners() {
        listeners.forEach { it.onThemeChanged(this) }
    }

    interface OnThemeChangedListener {
        fun onThemeChanged(theme: MaterialTheme)
    }

    companion object {
        private const val TAG = "MaterialTheme"
        val instance: MaterialTheme by lazy { MaterialTheme() }
    }
}

