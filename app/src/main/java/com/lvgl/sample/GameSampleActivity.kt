package com.lvgl.sample

import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.lvgl.android.activity.LvglActivity
import com.lvgl.android.navigation.LvglNavigation

/**
 * Demonstrates the LVGL Game Activity with:
 * - Vsync-aware rendering loop
 * - Immersive mode
 * - Orientation lock
 * - Game loop callback for state updates
 *
 * Subclasses LvglGameActivity which provides:
 * - targetFps property (30-144)
 * - immersiveMode property
 * - lockOrientation() / unlockOrientation()
 * - onGameLoop(deltaMs) callback
 */
class GameSampleActivity : com.lvgl.android.game.LvglGameActivity() {

    private var score = 0
    private var frameCount = 0
    private lateinit var scoreLabel: TextView

    override fun onCreateLvgl() {
        LvglNavigation.pushScreen("GameSample")
        targetFps = 60
        immersiveMode = true

        val root = FrameLayout(this)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val title = TextView(this).apply {
            text = "Game Sample"
            textSize = 24f
            setPadding(20, 20, 20, 20)
        }

        scoreLabel = TextView(this).apply {
            text = "Score: 0 | Frames: 0"
            textSize = 18f
            setPadding(20, 10, 20, 10)
        }

        val instructions = TextView(this).apply {
            text = "This activity runs a game loop at 60 FPS.\nFrame count increments automatically."
            textSize = 14f
            setPadding(20, 10, 20, 10)
        }

        layout.addView(title)
        layout.addView(scoreLabel)
        layout.addView(instructions)

        root.addView(layout, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        Log.d(TAG, "Game sample initialized with targetFps=$targetFps")
    }

    override fun onGameLoop(deltaMs: Long) {
        frameCount++
        if (frameCount % 60 == 0) {
            score += 10
            runOnUiThread {
                scoreLabel.text = "Score: $score | Frames: $frameCount"
            }
        }
    }

    companion object {
        private const val TAG = "GameSampleActivity"
    }
}
