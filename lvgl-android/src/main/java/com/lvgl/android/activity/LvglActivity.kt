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
import com.lvgl.android.system.LvglDeviceInfo
import com.lvgl.android.system.LvglSystemControl
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

    /**
     * This activity's navigation bridge. Stored so [onResume] can restore it
     * after a child activity was destroyed (a destroyed child sets the engine's
     * navigator to null, which would otherwise leave the dashboard dead).
     * @breadcrumb BC-025
     */
    private var navigator: LvglEngine.ScreenNavigator? = null

    /** Subclasses build their LVGL screen here, typically by calling setLvglScreen(). */
    abstract fun onCreateLvgl()

    /**
     * Hide the Android system bars so the LVGL UI can draw its own status bar
     * (the M5Tab5-style dashboard look). The reference device has no system
     * bar; duplicating it would waste a row of content on every screen.
     */
    protected open var immersiveMode: Boolean = true

    protected open fun applyImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (immersiveMode) {
            (android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
        } else {
            android.view.View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    /** Requests an LVGL screen and remembers it for the next onResume. */
    protected fun setLvglScreen(screenId: Int) {
        lvglScreenId = screenId
        LvglEngine.instance.setScreen(screenId)
    }

    /** Pushes the real device facts so the dashboard map reflects this device. */
    private fun pushDeviceInfo() {
        val info = LvglDeviceInfo.collect(this)
        LvglEngine.instance.setDeviceInfo(
            info.manufacturer, info.model, info.soc, info.memory, info.storage,
            info.display, info.androidVersion, info.cameras, info.sensors
        )
    }

    /**
     * Called after the Material palette has been re-applied to LVGL (system
     * dark/light change, or [com.lvgl.android.theme.MaterialTheme.toggleDarkMode]).
     * The palette is already updated; override to update Android views.
     */
    open fun onLvglThemeChanged(theme: MaterialTheme) {}

    /**
     * Called after the display metrics changed (rotation, density, window size).
     * The metrics object is already updated.
     */
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
            MaterialTheme.instance.materialPalette()
        )

        applyImmersiveMode()
        applyWindowInsets()
        pushDeviceInfo()
        onCreateLvgl()

        /* Register the navigation bridge so native menu taps can start other
         * activities and the LVGL back button can pop this one. */
        navigator = object : LvglEngine.ScreenNavigator {
            override fun navigateTo(screenId: Int) {
                val target = screenResolver?.invoke(screenId) ?: return
                startActivity(android.content.Intent(this@LvglActivity, target))
            }

            override fun navigateBack() {
                onBackPressedDispatcher.onBackPressed()
            }

            override fun setBrightness(brightness: Float) {
                /* Native calls this on the LVGL thread; WindowManager asset
                 * changes must run on the UI thread. */
                val percent = (brightness * 100f).toInt()
                window.decorView.post { LvglSystemControl.applyBrightness(window, percent) }
            }

            override fun setVolumePercent(percent: Int) {
                LvglSystemControl.applyVolumePercent(this@LvglActivity, percent)
            }

            override fun toggleDarkMode() {
                MaterialTheme.instance.toggleDarkMode(this@LvglActivity)
            }
        }
        LvglEngine.instance.screenNavigator = navigator

        /* Seed the dashboard status bar with the current battery state. */
        LvglEngine.instance.setBatteryLevel(
            LvglSystemControl.batteryLevel(this),
            LvglSystemControl.isCharging(this)
        )
    }

    /**
     * Process-wide engine singleton. Safe to access from any thread; the native
     * library is loaded by [initialize], not by this property.
     */
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
        applyImmersiveMode()
        lvglView?.onResume()
        /* A child activity that was destroyed cleared the engine's navigator;
         * restore ours so dashboard taps keep working after returning. */
        if (LvglEngine.instance.screenNavigator !== navigator) {
            LvglEngine.instance.screenNavigator = navigator
        }
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
        LvglEngine.instance.applyTheme(theme.isDarkMode, theme.materialPalette())
        /* Refresh the dashboard battery readout in case it changed while paused. */
        LvglEngine.instance.setBatteryLevel(
            LvglSystemControl.batteryLevel(this),
            LvglSystemControl.isCharging(this)
        )
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause")
        lvglView?.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        /* Only clear the engine's navigator while it is still ours. The parent
         * activity's onResume runs BEFORE this onDestroy and has already
         * re-registered its own bridge; clearing unconditionally would wipe it
         * and leave the dashboard unable to navigate. */
        if (LvglEngine.instance.screenNavigator === navigator) {
            LvglEngine.instance.screenNavigator = null
        }
        lvglView?.destroy()
        lvglView = null
        MaterialTheme.instance.removeListener(this)
        displayMetrics.removeListener(this)
    }

    /**
     * Handles configuration changes (rotation, density, dark mode, window size)
     * without recreating the activity. The manifest routes those changes here;
     * the theme and metrics are refreshed and pushed to LVGL.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "onConfigurationChanged")
        MaterialTheme.instance.onConfigurationChanged(this, newConfig)
        /* Refresh density/DPI metrics and notify listeners: the manifest routes
         * config changes here instead of recreating the activity. */
        displayMetrics.onConfigurationChanged(newConfig)
        val theme = MaterialTheme.instance
        LvglEngine.instance.applyTheme(theme.isDarkMode, theme.materialPalette())
    }

    override fun onThemeChanged(theme: MaterialTheme) {
        /* Re-apply the palette immediately when the system dark mode changes. */
        LvglEngine.instance.applyTheme(theme.isDarkMode, theme.materialPalette())
        onLvglThemeChanged(theme)
    }

    override fun onDisplayMetricsChanged(metrics: DisplayMetricsManager) {
        onLvglMetricsChanged(metrics)
    }
}
