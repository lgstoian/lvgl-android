// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.game

import android.os.Bundle
import android.view.WindowManager
import com.lvgl.android.activity.LvglActivity

/**
 * Base activity for game-style LVGL apps that drive their own frame loop.
 *
 * Sets the window to the highest refresh rate available at the current
 * resolution on Android 11+, and applies the immersive mode inherited from
 * [LvglActivity]. Subclasses implement [onGameLoop] and call it from their own
 * loop (for example a `Handler.postDelayed` cycle) rather than relying on the
 * LVGL render thread.
 */
abstract class LvglGameActivity : LvglActivity() {

    /**
     * Desired frames per second, clamped to the 30..144 range on assignment.
     *
     * The value is advisory: it does not change the display mode by itself.
     */
    var targetFps: Int = 60
        set(value) {
            field = value.coerceIn(30, 144)
        }

    public override var immersiveMode: Boolean = true

    /**
     * Advances the game by one frame.
     *
     * @param deltaMs elapsed time in milliseconds since the previous call.
     */
    abstract fun onGameLoop(deltaMs: Long)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupFrameRate()
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

    /**
     * Locks the activity to [orientation].
     *
     * @param orientation an [android.content.pm.ActivityInfo] orientation constant.
     */
    fun lockOrientation(orientation: Int) {
        requestedOrientation = orientation
    }

    /** Clears any orientation lock, letting the system rotate freely. */
    fun unlockOrientation() {
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}


