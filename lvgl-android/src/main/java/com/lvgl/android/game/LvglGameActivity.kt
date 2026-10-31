// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.game

import android.os.Bundle
import android.view.WindowManager
import com.lvgl.android.activity.LvglActivity

abstract class LvglGameActivity : LvglActivity() {

    var targetFps: Int = 60
        set(value) {
            field = value.coerceIn(30, 144)
        }

    var immersiveMode: Boolean = true
        public set

    abstract fun onGameLoop(deltaMs: Long)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupImmersiveMode()
        setupFrameRate()
    }

    override fun onResume() {
        super.onResume()
        setupImmersiveMode()
    }

    private fun setupImmersiveMode() {
        if (immersiveMode) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun setupFrameRate() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val display = windowManager.defaultDisplay
            val current = display.mode
            /* Pick the highest refresh rate among modes that keep the current
             * resolution; switching resolution would force a surface rebuild. */
            val bestMode = display.supportedModes
                .filter { it.physicalWidth == current.physicalWidth &&
                          it.physicalHeight == current.physicalHeight }
                .maxByOrNull { it.refreshRate }
            if (bestMode != null && bestMode.modeId != current.modeId) {
                val layoutParams = window.attributes
                layoutParams.preferredDisplayModeId = bestMode.modeId
                window.attributes = layoutParams
            }
        }
    }

    fun lockOrientation(orientation: Int) {
        requestedOrientation = orientation
    }

    fun unlockOrientation() {
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}


