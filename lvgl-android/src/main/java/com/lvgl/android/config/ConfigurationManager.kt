// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.config

import android.content.res.Configuration
import android.util.Log

/**
 * Tracks the current [android.content.res.Configuration] and reports which
 * aspects changed when it is updated.
 *
 * Compare with the previous configuration and notify listeners with a
 * [ConfigurationEvent]; callers use the flags to re-layout only the affected
 * parts (orientation, dark mode, font scale, locale).
 *
 * Calls are expected on the UI thread, where Android delivers configuration
 * changes.
 */
class ConfigurationManager(private val context: android.content.Context) {

    private var currentConfig: Configuration = context.resources.configuration
    private val listeners = mutableListOf<OnConfigurationChangedListener>()

    /**
     * Stores [configuration] as current and notifies listeners with the
     * differences against the previous one.
     *
     * @param configuration the new configuration, typically from
     *   `onConfigurationChanged`.
     */
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

    /**
     * Registers [listener] for configuration change notifications.
     *
     * @param listener the listener to add; duplicates are stored as-is.
     */
    fun addListener(listener: OnConfigurationChangedListener) {
        listeners.add(listener)
    }

    /** Removes a previously registered [listener]. */
    fun removeListener(listener: OnConfigurationChangedListener) {
        listeners.remove(listener)
    }

    /**
     * Which aspects of the configuration changed, plus the resulting snapshot.
     *
     * @property orientationChanged whether the orientation changed.
     * @property uiModeChanged whether the UI mode (dark/light) changed.
     * @property screenSizeChanged whether `screenWidthDp` or `screenHeightDp` changed.
     * @property smallestScreenSizeChanged whether `smallestScreenWidthDp` changed.
     * @property fontScaleChanged whether the font scale changed.
     * @property localeChanged whether the locale list changed.
     * @property configuration the new configuration snapshot.
     */
    data class ConfigurationEvent(
        val orientationChanged: Boolean,
        val uiModeChanged: Boolean,
        val screenSizeChanged: Boolean,
        val smallestScreenSizeChanged: Boolean,
        val fontScaleChanged: Boolean,
        val localeChanged: Boolean,
        val configuration: Configuration
    )

    /** Receives a [ConfigurationEvent] after each [update]. */
    interface OnConfigurationChangedListener {
        /** Called with the differences and the new configuration. */
        fun onConfigurationChanged(event: ConfigurationEvent)
    }

    /** Holds the private log tag. */
    companion object {
        private const val TAG = "ConfigurationManager"
    }
}

