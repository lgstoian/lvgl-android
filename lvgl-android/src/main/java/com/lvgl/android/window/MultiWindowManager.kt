// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.window

import android.app.Activity
import android.content.res.Configuration
import android.os.Build
import android.util.Log

/**
 * Tracks multi-window, picture-in-picture and resumed state for an activity.
 *
 * The activity forwards its `onMultiWindowModeChanged`,
 * `onPictureInPictureModeChanged`, `onResume` and `onPause` callbacks here so
 * the LVGL UI can adapt to window size changes. All methods must be called on
 * the UI thread by the owning activity.
 */
class MultiWindowManager(private val activity: Activity) {

    /** Whether the activity is currently in multi-window mode. */
    var isInMultiWindowMode: Boolean = false
        private set

    /** Whether the activity is currently in picture-in-picture mode. */
    var isInPictureInPictureMode: Boolean = false
        private set

    /** Whether the activity's `onResume` has been seen since the last `onPause`. */
    var isResumed: Boolean = false
        private set

    /**
     * Coarse layout hint derived from the current [Configuration].
     *
     * This is a dependency-free foldable/large-screen signal: a smallest width
     * of 600 dp or a `SCREENLAYOUT_SIZE_LARGE` configuration means the app should
     * use its two-pane layout. A hinge angle needs `androidx.window`, which is
     * intentionally not added to this library's dependency set.
     *
     * @property smallestWidthDp the configuration's smallest width in dp.
     * @property useTwoPaneLayout `true` when the available width warrants it.
     */
    data class LayoutHint(val smallestWidthDp: Int, val useTwoPaneLayout: Boolean)

    /** Receives a new [LayoutHint] when the configuration crosses a threshold. */
    fun interface OnLayoutHintChangedListener {
        fun onLayoutHintChanged(hint: LayoutHint)
    }

    private val layoutListeners = mutableListOf<OnLayoutHintChangedListener>()
    private var layoutHint: LayoutHint = hintFrom(activity.resources.configuration)

    /**
     * Updates the multi-window flag from the activity callback.
     *
     * @param isInMultiWindowMode `true` when the activity entered multi-window.
     */
    fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean) {
        this.isInMultiWindowMode = isInMultiWindowMode
        Log.d(TAG, "MultiWindow mode changed: $isInMultiWindowMode")
    }

    /**
     * Updates the picture-in-picture flag from the activity callback.
     *
     * @param isInPictureInPictureMode `true` when the activity entered PiP.
     */
    fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        this.isInPictureInPictureMode = isInPictureInPictureMode
        Log.d(TAG, "PiP mode changed: $isInPictureInPictureMode")
    }

    /** Marks the activity as resumed; forward `onResume` here. */
    fun onResumed() {
        isResumed = true
    }

    /** Marks the activity as paused; forward `onPause` here. */
    fun onPaused() {
        isResumed = false
    }

    /** Whether the device supports multi-window mode (Android 7.0+). */
    fun isMultiWindowSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
    }

    /**
     * Requests picture-in-picture mode with the given aspect ratio.
     *
     * Uses [android.app.PictureInPictureParams] on Android 8.0+, falling back
     * to the parameter-less call on Android 7.x. Does nothing below Android 7.0.
     *
     * @param aspectRatio width/height pair, defaults to 16:9.
     */
    fun enterPictureInPictureMode(aspectRatio: Pair<Int, Int> = Pair(16, 9)) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val aspect = android.util.Rational(aspectRatio.first, aspectRatio.second)
            activity.enterPictureInPictureMode(
                android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(aspect)
                    .build()
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            @Suppress("DEPRECATION")
            activity.enterPictureInPictureMode()
        }
    }

    /** The current layout hint; refresh it with [onConfigurationChanged]. */
    fun currentLayoutHint(): LayoutHint = layoutHint

    /** Registers a listener for coarse foldable/large-screen layout changes. */
    fun addLayoutHintListener(listener: OnLayoutHintChangedListener) {
        if (!layoutListeners.contains(listener)) layoutListeners.add(listener)
    }

    /** Removes a previously registered layout-hint listener. */
    fun removeLayoutHintListener(listener: OnLayoutHintChangedListener) {
        layoutListeners.remove(listener)
    }

    /**
     * Re-derives the layout hint from a configuration change and notifies
     * listeners when the two-pane decision actually changes.
     */
    fun onConfigurationChanged(config: Configuration) {
        val next = hintFrom(config)
        if (next != layoutHint) {
            layoutHint = next
            layoutListeners.toList().forEach { it.onLayoutHintChanged(next) }
        }
    }

    private fun hintFrom(config: Configuration): LayoutHint {
        val sw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.smallestScreenWidthDp
        } else {
            config.screenWidthDp
        }
        val screenSize = config.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK
        val twoPane = sw >= 600 || screenSize >= Configuration.SCREENLAYOUT_SIZE_LARGE
        return LayoutHint(sw, twoPane)
    }

    /** Holds the private log tag. */
    companion object {
        private const val TAG = "MultiWindowManager"
    }
}
