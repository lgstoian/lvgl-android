// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.

package com.lvgl.android.activity

import android.content.Context
import android.content.res.Configuration
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.lvgl.android.accessibility.LvglAccessibilityManager
import com.lvgl.android.accessibility.LvglFocusNavigation
import com.lvgl.android.accessibility.LvglScreenReader
import com.lvgl.android.config.DisplayMetricsManager
import com.lvgl.android.engine.LvglEngine
import com.lvgl.android.input.HardwareKeyboardHandler
import com.lvgl.android.motion.LvglMotionConfig
import com.lvgl.android.system.LvglDeviceInfo
import com.lvgl.android.system.LvglSystemControl
import com.lvgl.android.theme.MaterialTheme
import com.lvgl.android.util.LvglLog
import com.lvgl.android.view.LvglSurfaceView
import com.lvgl.android.window.MultiWindowManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
     * Device-info collection queries several system services (camera, sensors,
     * storage, telephony) and must never run on the UI thread. The result is
     * posted back to the main thread before it crosses JNI.
     */
    private val deviceInfoHandler = Handler(Looper.getMainLooper())
    private var cameraManager: CameraManager? = null

    /** A camera appearing/disappearing changes a dashboard fact. @breadcrumb BC-028 */
    private val cameraAvailabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) = refreshDeviceInfo()
        override fun onCameraUnavailable(cameraId: String) = refreshDeviceInfo()
    }

    /**
     * Screen this activity owns. Re-applied in onResume because the display is
     * process-wide and shared: without this, returning from another activity
     * would leave the other activity's screen visible.
     */
    private var lvglScreenId = NO_SCREEN

    /**
     * This activity's navigation bridge. Stored so [onResume] can restore it
     * after a child activity was destroyed (a destroyed child sets the engine's
     * navigator to null, which would otherwise leave the dashboard dead).
     * @breadcrumb BC-025
     */
    private var navigator: LvglEngine.ScreenNavigator? = null

    /**
     * Multi-window / PiP / resumed state. Forwarded from the matching activity
     * callbacks so the LVGL UI can react to window size and mode changes.
     */
    protected val multiWindowManager: MultiWindowManager by lazy { MultiWindowManager(this) }

    /** Accessibility service wrapper; subclasses may register listeners on it. */
    protected val accessibilityManager: LvglAccessibilityManager by lazy {
        LvglAccessibilityManager(this)
    }

    /** D-pad/keyboard focus model shared by the hardware-key and a11y paths. */
    protected val focusNavigation: LvglFocusNavigation by lazy { LvglFocusNavigation(this) }

    /** Translates hardware key events into LVGL key codes and press/release state. */
    protected val hardwareKeyboardHandler: HardwareKeyboardHandler by lazy {
        HardwareKeyboardHandler(this) { code, pressed -> onLvglHardwareKey(code, pressed) }
    }

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

    /**
     * Called for each hardware key recognised by [HardwareKeyboardHandler],
     * on both press and release. Override to drive a custom key model; the
     * default is a no-op because LVGL's native indev has no key-code API.
     */
    protected open fun onLvglHardwareKey(
        code: HardwareKeyboardHandler.KeyCode,
        pressed: Boolean
    ) {
    }

    /**
     * Called when the focused element is activated with D-pad centre or Enter.
     *
     * Override to perform the app's real activation for [focusedId]; the
     * framework invokes this hook on every activation, independent of TalkBack.
     */
    protected open fun onLvglFocusActivated(focusedId: Int) {
    }

    /**
     * Routes hardware keys to the keyboard handler and the focus navigator on
     * every activity, not only when TalkBack is enabled. Unhandled keys (Back,
     * volume, system keys) still go to the normal activity path.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyboardHandled = hardwareKeyboardHandler.processKeyEvent(event)
        val focusHandled = event.action == KeyEvent.ACTION_DOWN &&
            focusNavigation.handleKeyEvent(event.keyCode)
        return keyboardHandled || focusHandled || super.dispatchKeyEvent(event)
    }

    /**
     * Collects fresh hardware facts off the UI thread, then pushes them and
     * redraws the dashboard on the UI thread. Called from `onCreate`, `onResume`,
     * configuration changes, the camera-availability callback and after a
     * camera-permission result. Subclasses (e.g. the camera sample) can call it
     * whenever a hardware fact may have changed.
     */
    protected fun refreshDeviceInfo() {
        val appContext = applicationContext
        deviceInfoExecutor.execute {
            val info = try {
                LvglDeviceInfo.collect(appContext)
            } catch (t: Throwable) {
                LvglLog.e(TAG, "Device info collection failed", t)
                return@execute
            }
            deviceInfoHandler.post {
                if (isFinishing || isDestroyed) return@post
                val engine = LvglEngine.instance
                engine.setDeviceInfo(
                    info.manufacturer, info.model, info.soc, info.memory, info.storage,
                    info.display, info.androidVersion, info.cameras, info.sensors,
                    info.modem, info.network, info.batteryTech, info.audio, info.usb,
                    info.bluetooth
                )
                engine.refreshDeviceInfo()
            }
        }
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
        /* Claim ownership before the SurfaceView below can deliver any surface
         * callbacks. The outgoing activity's late callbacks will now be
         * rejected instead of stealing the shared display back. */
        claimForeground(this)

        val engine = LvglEngine.instance
        engine.initialize()

        /* ROM-adaptive motion is the SDK default: poll ViewConfiguration,
         * Settings.Global animation scales and framework durations, then push
         * the resolved values to LVGL. Re-applied on config changes and when a
         * Settings.Global scale changes (see LvglMotionConfig). */
        LvglMotionConfig.install(this)

        MaterialTheme.instance.initialize(this)
        MaterialTheme.instance.addListener(this)

        displayMetrics.update(this)
        displayMetrics.addListener(this)

        /* Remember this activity as the announcement window. The screen reader
         * is safe to initialise even when no accessibility service is enabled. */
        LvglScreenReader.initialize(this)
        focusNavigation.onActivate = { id -> onLvglFocusActivated(id) }

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

        /* Camera availability changes after the permission is granted and when
         * an external camera is attached; neither is visible in onCreate. */
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        try {
            cameraManager?.registerAvailabilityCallback(cameraAvailabilityCallback, deviceInfoHandler)
        } catch (t: Throwable) {
            LvglLog.w(TAG, "Camera availability callback unavailable: $t")
        }

        refreshDeviceInfo()
        onCreateLvgl()

        /* Register the navigation bridge so native menu taps can start other
         * activities and the LVGL back button can pop this one. Native calls
         * arrive on the LVGL thread, so every Android UI/system effect is
         * marshalled to the main thread (BC-025). */
        navigator = object : LvglEngine.ScreenNavigator {
            override fun navigateTo(screenId: Int) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val target = screenResolver?.invoke(screenId) ?: return@runOnUiThread
                    startActivity(android.content.Intent(this@LvglActivity, target))
                }
            }

            override fun navigateBack() {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }

            override fun setBrightness(brightness: Float) {
                /* Native calls this on the LVGL thread; WindowManager asset
                 * changes must run on the UI thread. */
                val percent = (brightness * 100f).toInt()
                window.decorView.post { LvglSystemControl.applyBrightness(window, percent) }
            }

            override fun setVolumePercent(percent: Int) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        LvglSystemControl.applyVolumePercent(this@LvglActivity, percent)
                    }
                }
            }

            override fun toggleDarkMode() {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        MaterialTheme.instance.toggleDarkMode(this@LvglActivity)
                    }
                }
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

        /** One daemon thread for all activities; collection is synchronous I/O. */
        private val deviceInfoExecutor: ExecutorService =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "lvgl-device-info").apply { isDaemon = true }
            }

        /**
         * Maps a native screen id to the Activity class that hosts it, for
         * cross-activity navigation started from LVGL menu taps. Set once by
         * the sample app (the framework can't depend on app classes).
         */
        @Volatile
        var screenResolver: ((Int) -> Class<*>?)? = null

        /**
         * The activity instance that most recently entered creation/resume.
         * A newly created activity claims this *before* its surface callbacks
         * arrive, so [LvglSurfaceView] can reject a late `surfaceChanged` from
         * an outgoing activity instead of letting it re-bind — and then tear
         * down — the process-wide display. @breadcrumb BC-001
         */
        @Volatile
        private var foregroundActivity: LvglActivity? = null

        internal fun claimForeground(activity: LvglActivity) {
            foregroundActivity = activity
        }

        internal fun releaseForeground(activity: LvglActivity) {
            if (foregroundActivity === activity) foregroundActivity = null
        }

        internal fun isForeground(activity: LvglActivity): Boolean =
            foregroundActivity === activity
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
        /* A returning activity must own the display again: a child that just
         * finished had claimed it in its own onCreate. */
        claimForeground(this)
        applyImmersiveMode()
        lvglView?.onResume()
        multiWindowManager.onResumed()
        /* Re-poll ROM motion here as well as on configuration change: a display
         * with a different refresh rate (multi-window / external display) is not
         * always accompanied by a Configuration change. install() is a bounded
         * one-shot poll and is safe on every resume. */
        LvglMotionConfig.install(this)
        /* The announcement window can have been owned by a child activity that
         * just finished; point the screen reader back at this one. */
        LvglScreenReader.initialize(this)
        /* A child activity that was destroyed cleared the engine's navigator;
         * restore ours so dashboard taps keep working after returning. */
        if (LvglEngine.instance.screenNavigator !== navigator) {
            LvglEngine.instance.screenNavigator = navigator
        }
        /* The display is shared process-wide, so a returning activity must
         * re-assert its screen — but only when the display is actually showing
         * a different one, to avoid needless rebuilds (and the flicker they
         * cause). The equality check also covers the first resume: onCreate has
         * already requested the screen, so nothing is rebuilt then. Checking on
         * every resume fixes the stale screen left by a hardware Back from a
         * smoke-launched child (which set its own screen on the shared display). */
        val engine = LvglEngine.instance
        if (lvglScreenId != NO_SCREEN && engine.currentScreen() != lvglScreenId) {
            engine.setScreen(lvglScreenId)
        }
        /* Re-apply theme in case the system dark mode changed while paused. */
        val theme = MaterialTheme.instance
        LvglEngine.instance.applyTheme(theme.isDarkMode, theme.materialPalette())
        /* Refresh the dashboard battery readout in case it changed while paused. */
        LvglEngine.instance.setBatteryLevel(
            LvglSystemControl.batteryLevel(this),
            LvglSystemControl.isCharging(this)
        )
        /* Hardware facts can change while paused (camera permission granted in
         * a child activity, camera attached/removed, dock/USB state). */
        refreshDeviceInfo()
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause")
        lvglView?.onPause()
        multiWindowManager.onPaused()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        /* Do not steal ownership back from a newer activity: the parent's
         * onResume runs before this onDestroy and already reclaimed it. */
        releaseForeground(this)
        /* Only clear the engine's navigator while it is still ours. The parent
         * activity's onResume runs BEFORE this onDestroy and has already
         * re-registered its own bridge; clearing unconditionally would wipe it
         * and leave the dashboard unable to navigate. */
        if (LvglEngine.instance.screenNavigator === navigator) {
            LvglEngine.instance.screenNavigator = null
        }
        cameraManager?.let {
            try {
                it.unregisterAvailabilityCallback(cameraAvailabilityCallback)
            } catch (t: Throwable) {
                LvglLog.w(TAG, "Camera availability callback unregister failed: $t")
            }
        }
        cameraManager = null
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
        multiWindowManager.onConfigurationChanged(newConfig)
        val theme = MaterialTheme.instance
        LvglEngine.instance.applyTheme(theme.isDarkMode, theme.materialPalette())
        /* Animation scales and framework durations can change with the config. */
        LvglMotionConfig.install(this)
        /* Resolution, network and USB facts can all change with the config. */
        refreshDeviceInfo()
    }

    @Deprecated("Use onMultiWindowModeChanged(Boolean, Configuration) on API 26+.")
    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean) {
        super.onMultiWindowModeChanged(isInMultiWindowMode)
        multiWindowManager.onMultiWindowModeChanged(isInMultiWindowMode)
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        multiWindowManager.onMultiWindowModeChanged(isInMultiWindowMode)
        multiWindowManager.onConfigurationChanged(newConfig)
    }

    @Deprecated("Use onPictureInPictureModeChanged(Boolean, Configuration) on API 26+.")
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        multiWindowManager.onPictureInPictureModeChanged(isInPictureInPictureMode)
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        multiWindowManager.onPictureInPictureModeChanged(isInPictureInPictureMode)
        multiWindowManager.onConfigurationChanged(newConfig)
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
