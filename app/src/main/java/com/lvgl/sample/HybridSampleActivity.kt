// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.sample

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.lvgl.android.engine.LvglEngine
import com.lvgl.android.theme.MaterialTheme
import com.lvgl.android.ui.LvglUi
import com.lvgl.android.ui.LvglUiEvent
import com.lvgl.android.ui.LvglUiListener
import com.lvgl.android.view.LvglSurfaceView

/**
 * Incremental-interop sample: a plain [AppCompatActivity] that hosts native
 * Android views and an [LvglSurfaceView] in the same view hierarchy.
 *
 * This is deliberately **not** an [com.lvgl.android.activity.LvglActivity]
 * subclass. The framework's base activity owns the window content frame and
 * therefore cannot coexist with native Android views; here we attach a normal
 * LinearLayout directly to that frame so both view worlds live side by side.
 * No new JNI or framework code is required - every call below is public API.
 *
 * Interop directions demonstrated:
 *  - Android -> LVGL: the native Buttons call [LvglEngine.setScreen] to switch
 *    between two LvglUi-defined screens and [LvglUi.updateNode] (plus the
 *    framework's status-text API) to push text into the running screen.
 *  - LVGL -> Android: an LVGL button and switch deliver typed
 *    [LvglUiEvent]s through [LvglUi.listener]. Native callbacks arrive on the
 *    LVGL thread, so they are marshalled to the UI thread before the native
 *    TextView is updated. This path exists today without native changes.
 *
 * The engine is process-wide: this activity only binds its surface (the view
 * owns that binding via its owner-scoped display) and never shuts it down.
 *
 * @breadcrumb BC-033
 */
class HybridSampleActivity : AppCompatActivity(), MaterialTheme.OnThemeChangedListener {

    private val engine = LvglEngine.instance
    private var lvglView: LvglSurfaceView? = null
    private lateinit var eventLabel: TextView
    private var activeScreen = SCREEN_INTRO
    private var androidSends = 0

    /**
     * A genuine surface size change rebuilds the shared display inside
     * [LvglSurfaceView.surfaceChanged]. That rebuild can land on the menu screen
     * even though this activity requested its app screen in [onCreate]. The
     * callback runs right after the rebuild, so the screen can be re-asserted
     * with public APIs only - no framework change is required.
     */
    private class ScreenReassertingView(
        context: Context,
        private val onDisplayChanged: () -> Unit
    ) : LvglSurfaceView(context) {
        override fun surfaceChanged(
            holder: SurfaceHolder, format: Int, width: Int, height: Int
        ) {
            super.surfaceChanged(holder, format, width, height)
            onDisplayChanged()
        }
    }

    /** Receives LVGL interactions; installed and cleared with this activity. */
    private val uiListener = LvglUiListener { screenId, event ->
        val message = if (screenId != SCREEN_INTRO && screenId != SCREEN_CONTROLS) {
            null
        } else when (event) {
            is LvglUiEvent.Click -> when (event.nodeId) {
                ID_LVGL_TAP -> getString(R.string.hybrid_event_tap)
                ID_LVGL_RESET -> getString(R.string.hybrid_event_reset)
                else -> null
            }
            is LvglUiEvent.Toggled -> getString(
                R.string.hybrid_event_toggle,
                getString(
                    if (event.checked) R.string.hybrid_toggle_on
                    else R.string.hybrid_toggle_off
                )
            )
            else -> null
        }
        if (message != null) {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) eventLabel.text = message
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")

        /* Initialise before registering screens or applying the theme: the
         * engine caches both until the surface is created and bound. */
        engine.initialize()

        val theme = MaterialTheme.instance
        theme.initialize(this)
        theme.addListener(this)

        registerHybridScreens()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val header = TextView(this).apply {
            text = getString(R.string.hybrid_title)
            textSize = 18f
            setTextColor(theme.onSurfaceColor)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        root.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        /* LVGL owns only this part of the hierarchy; the Android views around
         * it remain ordinary views with ordinary touch handling. */
        lvglView = ScreenReassertingView(this) { reassertScreen() }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        root.addView(lvglView)

        eventLabel = TextView(this).apply {
            text = getString(R.string.hybrid_native_status_hint)
            setTextColor(theme.onSurfaceColor)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        root.addView(
            eventLabel,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val caption = TextView(this).apply {
            text = getString(R.string.hybrid_native_controls)
            setTextColor(theme.onSurfaceColor)
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        root.addView(
            caption,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val sendButton = Button(this).apply {
            text = getString(R.string.hybrid_send_to_lvgl)
            setOnClickListener { sendToLvgl() }
        }
        val switchButton = Button(this).apply {
            text = getString(R.string.hybrid_switch_screen)
            setOnClickListener { switchScreen() }
        }
        buttonRow.addView(
            sendButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        buttonRow.addView(
            switchButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(
            buttonRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        /* Add to the content frame. An LvglActivity would own (and replace)
         * that frame, so this plain Activity keeps both view worlds together
         * without the conflicting content-view setter. */
        addContentView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            insets
        }

        engine.setScreen(activeScreen)
        engine.applyTheme(theme.isDarkMode, theme.materialPalette())
        /* Install last: the callback targets eventLabel, which now exists. */
        LvglUi.listener = uiListener
    }

    /** Declares the two app-defined screens rendered by the native UI kit. */
    private fun registerHybridScreens() {
        LvglUi.registerScreen(
            SCREEN_INTRO,
            title = getString(R.string.hybrid_screen_intro_title),
            showBack = false,
        ) {
            column(card = true) {
                label(getString(R.string.hybrid_intro_heading), sizeDp = 18)
                label(getString(R.string.hybrid_intro_body), sizeDp = 13)
                spacer(8)
                label(getString(R.string.hybrid_status_initial), sizeDp = 16, id = ID_STATUS)
                spacer(8)
                button(getString(R.string.hybrid_lvgl_tap), fillWidth = true, id = ID_LVGL_TAP)
            }
        }
        LvglUi.registerScreen(
            SCREEN_CONTROLS,
            title = getString(R.string.hybrid_screen_controls_title),
            showBack = false,
        ) {
            column(card = true) {
                label(getString(R.string.hybrid_controls_heading), sizeDp = 18)
                label(getString(R.string.hybrid_controls_body), sizeDp = 13)
                spacer(8)
                toggle(false, id = ID_LVGL_TOGGLE)
                spacer(8)
                button(getString(R.string.hybrid_lvgl_reset), fillWidth = true, id = ID_LVGL_RESET)
            }
        }
    }

    /** Android -> LVGL: update a live label and the framework status text. */
    private fun sendToLvgl() {
        val text = getString(R.string.hybrid_status_from_android, ++androidSends)
        LvglUi.updateNode(SCREEN_INTRO, ID_STATUS, text = text)
        engine.setStatusText(text)
    }

    /** Android -> LVGL: request a different screen on the shared display. */
    private fun switchScreen() {
        activeScreen = if (activeScreen == SCREEN_INTRO) SCREEN_CONTROLS else SCREEN_INTRO
        engine.setScreen(activeScreen)
        val title = if (activeScreen == SCREEN_INTRO) {
            getString(R.string.hybrid_screen_intro_title)
        } else {
            getString(R.string.hybrid_screen_controls_title)
        }
        eventLabel.text = getString(R.string.hybrid_screen_switched, title)
    }

    /** Re-asserts this activity's app screen after a shared-display rebuild. */
    private fun reassertScreen() {
        if (isFinishing || isDestroyed) return
        val current = engine.currentScreen()
        if (current != activeScreen) {
            Log.i(TAG, "Re-asserting app screen $activeScreen after display rebuild (was $current)")
            engine.setScreen(activeScreen)
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
        lvglView?.onResume()
        /* The display is shared process-wide: re-assert our screen and theme. */
        reassertScreen()
        val theme = MaterialTheme.instance
        engine.applyTheme(theme.isDarkMode, theme.materialPalette())
    }

    override fun onPause() {
        Log.d(TAG, "onPause")
        lvglView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        if (LvglUi.listener === uiListener) LvglUi.listener = null
        /* Unbind this view's display; the process-wide engine stays alive. */
        lvglView?.destroy()
        lvglView = null
        MaterialTheme.instance.removeListener(this)
        LvglUi.unregisterScreen(SCREEN_INTRO)
        LvglUi.unregisterScreen(SCREEN_CONTROLS)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        MaterialTheme.instance.onConfigurationChanged(this, newConfig)
        val theme = MaterialTheme.instance
        engine.applyTheme(theme.isDarkMode, theme.materialPalette())
    }

    override fun onThemeChanged(theme: MaterialTheme) {
        engine.applyTheme(theme.isDarkMode, theme.materialPalette())
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "HybridSample"

        /** App-defined screen ids; built-in screens occupy 0..7, other app
         *  screens use 1000 (App Demo), 1001 (Licenses) and 1100 (IME). */
        const val SCREEN_INTRO = 1002
        const val SCREEN_CONTROLS = 1003

        private const val ID_STATUS = "status"
        private const val ID_LVGL_TAP = "tap"
        private const val ID_LVGL_TOGGLE = "toggle"
        private const val ID_LVGL_RESET = "reset"
    }
}
