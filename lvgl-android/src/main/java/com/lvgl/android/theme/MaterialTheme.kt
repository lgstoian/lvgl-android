// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.theme

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Resolves the Material 3 tonal palette for the app, following the system
 * dark/light mode and (on Android 12+) the Material You dynamic colours.
 *
 * The palette is exposed as a fixed-order [IntArray] (see [MaterialPalette])
 * and pushed to the native UI kit, so every LVGL component follows the system
 * theme without per-screen colour plumbing.
 *
 * @breadcrumb BC-029
 * @invariant isDarkMode == (darkModeOverride ?: system dark mode)
 */
class MaterialTheme private constructor() {

    /** Whether the resolved theme is currently dark. */
    var isDarkMode: Boolean = false
        private set

    /** Material You dynamic colours; falls back to the baseline scheme below API 31. */
    var useDynamicColors: Boolean = true
        private set

    /**
     * App-local dark-mode override. `null` follows the system; `true`/`false`
     * pins the theme (the dashboard theme FAB uses this).
     */
    private var darkModeOverride: Boolean? = null

    /** Resolved primary colour. */
    var primaryColor: Int = 0x6750A4.toInt()
        private set

    /** Resolved secondary colour. */
    var secondaryColor: Int = 0xFF625B71.toInt()
        private set

    /** Resolved surface colour. */
    var surfaceColor: Int = 0xFFFFFBFE.toInt()
        private set

    /** Resolved on-surface colour. */
    var onSurfaceColor: Int = 0xFF1C1B1F.toInt()
        private set

    /** Background opacity percentage (0..100). */
    var backgroundOpacity: Int = 100
        private set

    private var palette: IntArray = MaterialPalette.light()
        private set

    private val listeners = CopyOnWriteArrayList<OnThemeChangedListener>()

    /** A copy of the resolved Material 3 tonal palette. */
    fun materialPalette(): IntArray = palette.copyOf()

    /**
     * Resolves the initial theme from the system configuration.
     *
     * Call once when the first activity is created, before rendering.
     *
     * @param context used to read the configuration and dynamic colours.
     */
    fun initialize(context: Context) {
        val config = context.resources.configuration
        isDarkMode = darkModeOverride ?: isSystemDark(config)
        updateColors(context)
        Log.d(TAG, "MaterialTheme initialized: darkMode=$isDarkMode dynamic=$useDynamicColors")
    }

    /**
     * Re-resolves the theme when the system configuration changes.
     *
     * Listeners are notified only when the dark/light result actually changes;
     * other configuration changes (locale, font scale) do not re-theme.
     *
     * @param context used to read dynamic colours.
     * @param config the new [android.content.res.Configuration].
     */
    fun onConfigurationChanged(context: Context, config: Configuration) {
        val newDarkMode = darkModeOverride ?: isSystemDark(config)
        if (newDarkMode != isDarkMode) {
            isDarkMode = newDarkMode
            updateColors(context)
            notifyListeners()
            Log.d(TAG, "Theme changed: darkMode=$isDarkMode")
        }
    }

    /**
     * Toggle the app-local dark mode. The new palette is pushed to LVGL by the
     * [LvglThemeBridge] listener, so the running screen rebuilds in place.
     *
     * @param context used to read dynamic colours.
     */
    fun toggleDarkMode(context: Context) {
        darkModeOverride = !isDarkMode
        isDarkMode = darkModeOverride ?: !isDarkMode
        updateColors(context)
        notifyListeners()
        Log.d(TAG, "Dark mode toggled: darkMode=$isDarkMode")
    }

    /** Clear the app-local override and follow the system again. */
    fun followSystemDarkMode() {
        darkModeOverride = null
    }

    private fun isSystemDark(config: Configuration): Boolean =
        (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /**
     * Enable or disable Material You dynamic colours; re-applies immediately.
     *
     * Calling with the current value is a no-op. When disabled, the baseline
     * light/dark scheme is used instead of the system accents.
     *
     * @param context used to read the system accent colours.
     * @param enabled `true` to follow the Material You palette.
     */
    fun setDynamicColors(context: Context, enabled: Boolean) {
        if (useDynamicColors == enabled) return
        useDynamicColors = enabled
        updateColors(context)
        notifyListeners()
    }

    /** Registers [listener] for theme changes. */
    fun addListener(listener: OnThemeChangedListener) {
        listeners.add(listener)
    }

    /** Removes a previously registered [listener]. */
    fun removeListener(listener: OnThemeChangedListener) {
        listeners.remove(listener)
    }

    private fun updateColors(context: Context) {
        palette = if (useDynamicColors) {
            MaterialPalette.dynamic(context, isDarkMode)
        } else if (isDarkMode) {
            MaterialPalette.dark()
        } else {
            MaterialPalette.light()
        }
        primaryColor = palette[MaterialPalette.PRIMARY]
        secondaryColor = palette[MaterialPalette.SECONDARY]
        surfaceColor = palette[MaterialPalette.SURFACE]
        onSurfaceColor = palette[MaterialPalette.ON_SURFACE]
    }

    private fun notifyListeners() {
        listeners.forEach { it.onThemeChanged(this) }
    }

    /** Receives a notification after the theme palette was re-resolved. */
    interface OnThemeChangedListener {
        /**
         * Called after the palette changed.
         *
         * @param theme the same [MaterialTheme] instance; read [MaterialTheme.materialPalette].
         */
        fun onThemeChanged(theme: MaterialTheme)
    }

    /** Holds the private log tag and the process-wide [instance]. */
    companion object {
        private const val TAG = "MaterialTheme"

        /** The process-wide theme instance; create it on the UI thread. */
        val instance: MaterialTheme by lazy { MaterialTheme() }
    }
}
