// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.sample

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lvgl.android.game.LvglGameActivity
import com.lvgl.android.navigation.LvglNavigation

/**
 * Demonstrates the LVGL Game Activity with a vsync-aware game loop.
 *
 * The score is rendered inside LVGL via the engine's status text API. The
 * game loop is driven by a Choreographer frame callback (vsync-aware), and
 * status updates are marshalled back to the LVGL thread.
 */
class GameSampleActivity : LvglGameActivity() {

    private var score = 0
    private var frameCount = 0
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
        com.lvgl.android.engine.LvglEngine.instance.setStatusText(getString(R.string.game_status_initial))
        Log.d(TAG, "Game sample initialised with targetFps=$targetFps")
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
        frameCount++
        if (frameCount % 60 == 0) {
            score += 10
            com.lvgl.android.engine.LvglEngine.instance.setStatusText(
                getString(R.string.game_status, score, frameCount)
            )
        }
    }

    companion object {
        private const val TAG = "GameSampleActivity"
    }
}
