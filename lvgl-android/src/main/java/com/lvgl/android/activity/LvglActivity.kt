// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.

package com.lvgl.android.activity

import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.lvgl.android.config.DisplayMetricsManager
import com.lvgl.android.engine.LvglEngine
import com.lvgl.android.theme.MaterialTheme
import com.lvgl.android.view.LvglSurfaceView

/**
 * Base activity for all LVGL-rendered screens.
 *
 * Rules (from ai-context.md):
 *  - Subclasses MUST NOT call setContentView() — that replaces the LVGL
 *    SurfaceView and produces the greyed-out "System UI isn't responding"
 *    screen. Switch content via LvglEngine.setScreen(id).
 *  - Use AppCompatActivity for native Android views.
 *
 * @breadcrumb BC-010
 * @breadcrumb BC-011
 * @invariant lvglScreenId == NO_SCREEN || engine.currentScreen() == lvglScreenId on resume
 * @do-not Do not call setContentView() from a subclass.
 * @machine-json {"owns":"root layout + SurfaceView","rebind":"onResume"}
 */
abstract class LvglActivity : AppCompatActivity(),
    MaterialTheme.OnThemeChangedListener,
    DisplayMetricsManager.OnDisplayMetricsChangedListener {

    private var lvglView: LvglSurfaceView? = null
    private val displayMetrics = DisplayMetricsManager(this)

    /**
     * Screen this activity owns. Re-applied in onResume because the display is
     * process-wide and shared: without this, returning from another activity
     * would leave the other activity's screen visible.
     */
    private var lvglScreenId = NO_SCREEN
    private var firstResume = true

    /** Subclasses build their LVGL screen here, typically by calling setLvglScreen(). */
    abstract fun onCreateLvgl()

    /** Requests an LVGL screen and remembers it for the next onResume. */
    protected fun setLvglScreen(screenId: Int) {
        lvglScreenId = screenId
        LvglEngine.instance.setScreen(screenId)
    }

    open fun onLvglThemeChanged(theme: MaterialTheme) {}
    open fun onLvglMetricsChanged(metrics: DisplayMetricsManager) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")

        val engine = LvglEngine.instance
        engine.initialize()

        MaterialTheme.instance.initialize(this)
        MaterialTheme.instance.addListener(this)

        displayMetrics.update(this)
        displayMetrics.addListener(this)

        val rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        lvglView = LvglSurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        rootLayout.addView(lvglView)
        setContentView(rootLayout)

        /* Apply the current theme now that the activity exists but before the
         * LVGL display is up (surfaceChanged drives the actual apply). */
        engine.applyTheme(
            MaterialTheme.instance.isDarkMode,
            MaterialTheme.instance.primaryColor,
            MaterialTheme.instance.secondaryColor
        )

        applyWindowInsets()

        onCreateLvgl()

        /* Register the navigation bridge so native menu taps can start other
         * activities and the LVGL back button can pop this one. */
        LvglEngine.instance.screenNavigator = object : LvglEngine.ScreenNavigator {
            override fun navigateTo(screenId: Int) {
                val target = screenResolver?.invoke(screenId) ?: return
                startActivity(android.content.Intent(this@LvglActivity, target))
            }

            override fun navigateBack() {
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    companion object {
        private const val TAG = "LvglActivity"
        private const val NO_SCREEN = -1

        /**
         * Maps a native screen id to the Activity class that hosts it, for
         * cross-activity navigation started from LVGL menu taps. Set once by
         * the sample app (the framework can't depend on app classes).
         */
        @Volatile
        var screenResolver: ((Int) -> Class<*>?)? = null
    }

    private fun applyWindowInsets() {
        val root = (lvglView?.parent as? android.view.ViewGroup) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            /* WindowInsetsCompat (not android.view.WindowInsets.Type) so API 24-29
             * do not throw NoSuchFieldError/NoSuchMethodError. */
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
        lvglView?.onResume()
        /* The display is shared process-wide, so a returning activity must
         * re-assert its screen — but only when the display is actually showing
         * a different one, to avoid needless rebuilds (and the flicker they
         * cause). The first resume is skipped because onCreate already
         * requested the screen. */
        val engine = LvglEngine.instance
        if (!firstResume && lvglScreenId != NO_SCREEN && engine.currentScreen() != lvglScreenId) {
            engine.setScreen(lvglScreenId)
        }
        firstResume = false
        /* Re-apply theme in case the system dark mode changed while paused. */
        val theme = MaterialTheme.instance
        LvglEngine.instance.applyTheme(theme.isDarkMode, theme.primaryColor, theme.secondaryColor)
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause")
        lvglView?.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        /* The navigator captures this activity; clear it so a stale activity is
         * never invoked (or leaked) after destroy. */
        LvglEngine.instance.screenNavigator = null
        lvglView?.destroy()
        lvglView = null
        MaterialTheme.instance.removeListener(this)
        displayMetrics.removeListener(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "onConfigurationChanged")
        MaterialTheme.instance.onConfigurationChanged(newConfig)
        /* Refresh density/DPI metrics and notify listeners: the manifest routes
         * config changes here instead of recreating the activity. */
        displayMetrics.onConfigurationChanged(newConfig)
        val theme = MaterialTheme.instance
        LvglEngine.instance.applyTheme(theme.isDarkMode, theme.primaryColor, theme.secondaryColor)
    }

    override fun onThemeChanged(theme: MaterialTheme) {
        /* Re-apply the palette immediately when the system dark mode changes. */
        LvglEngine.instance.applyTheme(theme.isDarkMode, theme.primaryColor, theme.secondaryColor)
        onLvglThemeChanged(theme)
    }

    override fun onDisplayMetricsChanged(metrics: DisplayMetricsManager) {
        onLvglMetricsChanged(metrics)
    }
}
