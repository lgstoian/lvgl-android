// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.config

import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import android.util.Log

/**
 * Caches display metrics and notifies listeners when the configuration
 * changes, so screens can adapt without recreating the activity.
 *
 * The activity forwards `onConfigurationChanged` here instead of rebuilding the
 * surface; the cached values are then read by [com.lvgl.android.view.LvglSurfaceView]
 * and the UI kit. All methods are intended to be called from the UI thread.
 */
class DisplayMetricsManager(private val context: Context) {

    /** Cached screen density in dpi. */
    var densityDpi: Int = 0
        private set

    /** Cached density factor (1.0 = mdpi). */
    var density: Float = 0f
        private set

    /** Cached font scale density. */
    var scaledDensity: Float = 0f
        private set

    /** Cached window width in density-independent pixels. */
    var screenWidthDp: Int = 0
        private set

    /** Cached window height in density-independent pixels. */
    var screenHeightDp: Int = 0
        private set

    /** Cached smallest screen width in dp (orientation-independent). */
    var smallestScreenWidthDp: Int = 0
        private set

    /** Whether the cached configuration is landscape. */
    var isLandscape: Boolean = false
        private set

    /** Cached window width in pixels. */
    var screenWidthPx: Int = 0
        private set

    /** Cached window height in pixels. */
    var screenHeightPx: Int = 0
        private set

    private val listeners = mutableListOf<OnDisplayMetricsChangedListener>()

    /**
     * Refreshes every cached value from [context]'s resources.
     *
     * Call once after the activity is attached and again whenever the
     * configuration changes.
     */
    fun update(context: Context) {
        val metrics = context.resources.displayMetrics
        val config = context.resources.configuration

        densityDpi = metrics.densityDpi
        density = metrics.density
        scaledDensity = metrics.scaledDensity
        screenWidthPx = metrics.widthPixels
        screenHeightPx = metrics.heightPixels
        screenWidthDp = (screenWidthPx / density).toInt()
        screenHeightDp = (screenHeightPx / density).toInt()
        smallestScreenWidthDp = config.smallestScreenWidthDp
        isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE

        Log.d(TAG, "DisplayMetrics: ${screenWidthPx}x${screenHeightPx} dpi=$densityDpi density=$density")
    }

    /**
     * Refreshes the cached metrics and notifies listeners.
     *
     * Route the activity's `onConfigurationChanged` here; it is invoked on the
     * UI thread by Android.
     *
     * @param newConfig the configuration Android delivered with the change.
     */
    fun onConfigurationChanged(newConfig: Configuration) {
        /* Refresh the cached metrics and notify listeners: the activity routes
         * config changes here instead of recreating. applicationContext is only
         * valid once the activity is attached, so resolve it lazily here. */
        val ctx = context.applicationContext ?: context
        update(ctx)
        notifyListeners()
    }

    /**
     * Registers [listener] to be notified on every metrics refresh.
     *
     * @param listener the listener to add; duplicates are stored as-is.
     */
    fun addListener(listener: OnDisplayMetricsChangedListener) {
        listeners.add(listener)
    }

    /** Removes a previously registered [listener]. */
    fun removeListener(listener: OnDisplayMetricsChangedListener) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        listeners.forEach { it.onDisplayMetricsChanged(this) }
    }

    /** Receives a snapshot of the updated metrics. */
    interface OnDisplayMetricsChangedListener {
        /** Called after the cached metrics were refreshed. */
        fun onDisplayMetricsChanged(metrics: DisplayMetricsManager)
    }

    /** Holds the private log tag. */
    companion object {
        private const val TAG = "DisplayMetricsManager"
    }
}

