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

    /** Holds the private log tag. */
    companion object {
        private const val TAG = "MultiWindowManager"
    }
}

