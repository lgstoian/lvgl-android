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

class DisplayMetricsManager(context: Context) {

    var densityDpi: Int = 0
        private set

    var density: Float = 0f
        private set

    var scaledDensity: Float = 0f
        private set

    var screenWidthDp: Int = 0
        private set

    var screenHeightDp: Int = 0
        private set

    var smallestScreenWidthDp: Int = 0
        private set

    var isLandscape: Boolean = false
        private set

    var screenWidthPx: Int = 0
        private set

    var screenHeightPx: Int = 0
        private set

    private val listeners = mutableListOf<OnDisplayMetricsChangedListener>()

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

    fun onConfigurationChanged(newConfig: Configuration) {
        val newLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (newLandscape != isLandscape) {
            isLandscape = newLandscape
            notifyListeners()
        }
    }

    fun addListener(listener: OnDisplayMetricsChangedListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: OnDisplayMetricsChangedListener) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        listeners.forEach { it.onDisplayMetricsChanged(this) }
    }

    interface OnDisplayMetricsChangedListener {
        fun onDisplayMetricsChanged(metrics: DisplayMetricsManager)
    }

    companion object {
        private const val TAG = "DisplayMetricsManager"
    }
}

