// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.sample

import android.os.Handler
import android.os.Looper
import com.lvgl.android.game.LvglGameActivity
import com.lvgl.android.navigation.LvglNavigation

/**
 * Demonstrates [LvglGameActivity] and the native game screen.
 *
 * The screen itself (target, score, hit testing) is rendered and updated by the
 * native UI kit in `demo_ui.cpp`; this activity supplies the vsync-aware frame
 * loop that a real game would use for its own simulation.
 */
class GameSampleActivity : LvglGameActivity() {

    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            onGameLoop(16L)
            handler.postDelayed(this, 16L)
        }
    }

    override fun onCreateLvgl() {
        LvglNavigation.pushScreen("GameSample")
        targetFps = 60
        immersiveMode = true
        setLvglScreen(5)
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    override fun onGameLoop(deltaMs: Long) {
        /* A real game would advance its simulation here. The LVGL render thread
         * already drives the UI; the native game screen handles input. */
    }
}
