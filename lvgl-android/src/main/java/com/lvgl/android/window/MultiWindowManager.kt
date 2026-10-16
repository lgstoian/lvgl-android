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

class MultiWindowManager(private val activity: Activity) {

    var isInMultiWindowMode: Boolean = false
        private set

    var isInPictureInPictureMode: Boolean = false
        private set

    var isResumed: Boolean = false
        private set

    fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean) {
        this.isInMultiWindowMode = isInMultiWindowMode
        Log.d(TAG, "MultiWindow mode changed: $isInMultiWindowMode")
    }

    fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        this.isInPictureInPictureMode = isInPictureInPictureMode
        Log.d(TAG, "PiP mode changed: $isInPictureInPictureMode")
    }

    fun onResumed() {
        isResumed = true
    }

    fun onPaused() {
        isResumed = false
    }

    fun isMultiWindowSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
    }

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

    companion object {
        private const val TAG = "MultiWindowManager"
    }
}

