// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.config

import android.content.res.Configuration
import android.util.Log

class ConfigurationManager(private val context: android.content.Context) {

    private var currentConfig: Configuration = context.resources.configuration
    private val listeners = mutableListOf<OnConfigurationChangedListener>()

    fun update(configuration: Configuration) {
        val oldConfig = currentConfig
        currentConfig = Configuration(configuration)

        val orientationChanged = oldConfig.orientation != configuration.orientation
        val uiModeChanged = oldConfig.uiMode != configuration.uiMode
        val screenSizeChanged = oldConfig.screenWidthDp != configuration.screenWidthDp ||
                oldConfig.screenHeightDp != configuration.screenHeightDp
        val smallestScreenSizeChanged = oldConfig.smallestScreenWidthDp != configuration.smallestScreenWidthDp
        val fontScaleChanged = oldConfig.fontScale != configuration.fontScale
        val localeChanged = oldConfig.locales != configuration.locales

        val event = ConfigurationEvent(
            orientationChanged = orientationChanged,
            uiModeChanged = uiModeChanged,
            screenSizeChanged = screenSizeChanged,
            smallestScreenSizeChanged = smallestScreenSizeChanged,
            fontScaleChanged = fontScaleChanged,
            localeChanged = localeChanged,
            configuration = Configuration(currentConfig)
        )

        Log.d(TAG, "Configuration changed: orientation=$orientationChanged uiMode=$uiModeChanged screenSize=$screenSizeChanged")
        listeners.forEach { it.onConfigurationChanged(event) }
    }

    fun addListener(listener: OnConfigurationChangedListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: OnConfigurationChangedListener) {
        listeners.remove(listener)
    }

    data class ConfigurationEvent(
        val orientationChanged: Boolean,
        val uiModeChanged: Boolean,
        val screenSizeChanged: Boolean,
        val smallestScreenSizeChanged: Boolean,
        val fontScaleChanged: Boolean,
        val localeChanged: Boolean,
        val configuration: Configuration
    )

    interface OnConfigurationChangedListener {
        fun onConfigurationChanged(event: ConfigurationEvent)
    }

    companion object {
        private const val TAG = "ConfigurationManager"
    }
}

