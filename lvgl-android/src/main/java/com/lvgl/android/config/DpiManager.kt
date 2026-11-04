// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.config

import android.content.Context
import android.util.Log

/**
 * Converts between density-independent pixels and hardware pixels for the
 * current display, and reports the screen's size class and orientation.
 *
 * Wraps [android.content.res.Resources.getDisplayMetrics] so callers do not
 * have to resolve the metrics themselves. Values follow the configuration at
 * the time of the call, so they reflect rotations and window resize immediately.
 */
class DpiManager(private val context: Context) {

    /** Current screen density expressed as dots-per-inch. */
    fun getDpi(): Int {
        return context.resources.displayMetrics.densityDpi
    }

    /** Current display density factor (1.0 = mdpi baseline). */
    fun getDensity(): Float {
        return context.resources.displayMetrics.density
    }

    /** Converts [dp] to whole pixels using the current density. */
    fun dpToPx(dp: Float): Int {
        return (dp * getDensity()).toInt()
    }

    /** Converts [px] to density-independent pixels. */
    fun pxToDp(px: Float): Float {
        return px / getDensity()
    }

    /** Converts [sp] to whole pixels, honouring the user's font scale. */
    fun spToPx(sp: Float): Int {
        return (sp * context.resources.displayMetrics.scaledDensity).toInt()
    }

    /** Converts [px] to scale-independent pixels. */
    fun pxToSp(px: Float): Float {
        return px / context.resources.displayMetrics.scaledDensity
    }

    /** Current window width in pixels. */
    fun getScreenWidthPx(): Int {
        return context.resources.displayMetrics.widthPixels
    }

    /** Current window height in pixels. */
    fun getScreenHeightPx(): Int {
        return context.resources.displayMetrics.heightPixels
    }

    /** Current window width in density-independent pixels. */
    fun getScreenWidthDp(): Float {
        return pxToDp(getScreenWidthPx().toFloat())
    }

    /** Current window height in density-independent pixels. */
    fun getScreenHeightDp(): Float {
        return pxToDp(getScreenHeightPx().toFloat())
    }

    /** Current orientation, one of the [android.content.res.Configuration] constants. */
    fun getOrientation(): Int {
        return context.resources.configuration.orientation
    }

    /** Whether the current orientation is landscape. */
    fun isLandscape(): Boolean {
        return getOrientation() == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    }

    /** Whether the current orientation is portrait. */
    fun isPortrait(): Boolean {
        return getOrientation() == android.content.res.Configuration.ORIENTATION_PORTRAIT
    }

    /**
     * Classifies the screen by its smallest width in dp.
     *
     * @return the matching [ScreenSize] bucket.
     */
    fun getSizeCategory(): ScreenSize {
        val widthDp = getScreenWidthDp()
        val heightDp = getScreenHeightDp()
        val smallest = minOf(widthDp, heightDp)

        return when {
            smallest >= 720 -> ScreenSize.EXTRA_LARGE
            smallest >= 600 -> ScreenSize.LARGE
            smallest >= 480 -> ScreenSize.MEDIUM
            else -> ScreenSize.SMALL
        }
    }

    /** Coarse screen-size buckets derived from the smallest width in dp. */
    enum class ScreenSize {
        /** Smallest width below 480 dp (phones). */
        SMALL,

        /** Smallest width of at least 480 dp (small tablets). */
        MEDIUM,

        /** Smallest width of at least 600 dp (landscape phones, tablets). */
        LARGE,

        /** Smallest width of at least 720 dp (large tablets). */
        EXTRA_LARGE
    }

    /** Holds the private log tag. */
    companion object {
        private const val TAG = "DpiManager"
    }
}

