// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.engine

import android.util.Log
import android.view.Surface
import com.lvgl.android.launcher.LauncherAppListener
import com.lvgl.android.ui.LvglUiEventReceiver

/**
 * Process-wide singleton that owns the native LVGL engine.
 *
 * The native engine (display + render thread) lives for the process lifetime.
 * An Activity "binds" its SurfaceView to the shared display via [initDisplay]
 * and releases it via [deinitDisplay]; the display itself is NOT torn down
 * between activities — the surface is merely rebound. That is what lets each
 * activity show its own screen instantly instead of rebuilding the display
 * (which used to show the previous screen and stall the UI thread).
 *
 * [deinitDisplay] takes the owning view so a *stale* surface callback from a
 * previous activity can never release the display that a newer activity just
 * bound (the activity lifecycle delivers the old `surfaceDestroyed` after the
 * new `surfaceCreated`).
 *
 * @breadcrumb BC-001
 * @decision ADR-002
 * @invariant activeOwner === null || activeOwner === the owner that bound the display
 * @do-not Never call shutdown() from an activity.
 * @machine-json {"scope":"process","display":"owner-scoped","rebinding":"same-size"}
 */
class LvglEngine private constructor() {

    private var nativeHandle: Long = 0
    private var activeOwner: Any? = null

    /** True once the native engine object has been allocated. */
    val initialized: Boolean get() = nativeHandle != 0L

    /** Set when the native library could not be loaded; `null` otherwise. */
    var nativeLoadError: String? = null
        private set

    /**
     * Loads `liblvgl_android.so` and allocates the native engine.
     *
     * A missing or incompatible native library is reported through
     * [nativeLoadError] instead of crashing: an app can show a friendly message
     * (and the UI kit still works for apps that never call this).
     */
    fun initialize() {
        if (nativeHandle != 0L) return
        try {
            System.loadLibrary("lvgl_android")
        } catch (t: Throwable) {
            nativeLoadError = t.toString()
            Log.e(TAG, "Failed to load liblvgl_android.so", t)
            return
        }
        nativeHandle = nativeCreate()
        screenNavigator?.let { nativeSetNavCallback(nativeHandle, it) }
        uiEventListener?.let { nativeSetUiListener(nativeHandle, it) }
    }

    /**
     * Binds (or re-binds) [owner]'s surface as the engine's display target. The
     * first call creates the display; later calls just swap the surface.
     */
    fun initDisplay(owner: Any, surface: Surface, width: Int, height: Int, dpi: Int) {
        if (nativeHandle == 0L) return
        activeOwner = owner
        nativeInitDisplay(nativeHandle, surface, width, height, dpi)
    }

    /** Releases the surface, but only if [owner] is still the active one. */
    fun deinitDisplay(owner: Any) {
        if (nativeHandle == 0L) return
        if (activeOwner !== owner) {
            Log.d(TAG, "deinitDisplay ignored for stale owner")
            return
        }
        activeOwner = null
        nativeDeinitDisplay(nativeHandle)
    }

    /** Resumes the render thread after [pause]. Safe to call before the display exists. */
    fun resume() {
        if (nativeHandle != 0L) nativeResume(nativeHandle)
    }

    /** Pauses the render thread (the surface is gone). Safe to call any time. */
    fun pause() {
        if (nativeHandle != 0L) nativePause(nativeHandle)
    }

    /**
     * Full teardown for an owner that truly owns the engine (an input-method
     * service, which runs in its own process). Normal activities must use
     * [deinitDisplay] instead so the engine survives activity switches.
     */
    fun shutdown() {
        if (nativeHandle == 0L) return
        activeOwner = null
        nativeDeinitDisplay(nativeHandle)   /* stop drawing so the join is fast */
        nativeDestroy(nativeHandle)
        nativeHandle = 0L
    }

    /**
     * Forwards a touch event to LVGL.
     *
     * @param action `0` = down, `1` = up, `2` = move (the `LvglSurfaceView`
     *   action codes).
     */
    fun onTouchEvent(action: Int, x: Float, y: Float) {
        if (nativeHandle != 0L) nativeTouchEvent(nativeHandle, action, x, y)
    }

    /**
     * Applies the Material 3 palette. The current screen is rebuilt so every
     * component picks up the new colours. Safe to call before the display
     * exists; the palette is cached natively.
     */
    fun applyTheme(darkMode: Boolean, palette: IntArray) {
        if (nativeHandle != 0L) nativeApplyTheme(nativeHandle, darkMode, palette)
    }

    /**
     * Requests a screen. Built-in screens are `0..7`; app-defined screens start
     * at `1000` (see `com.lvgl.android.ui.LvglUi`). The request is cached and
     * re-applied when the display is (re)created.
     */
    fun setScreen(screenId: Int) {
        if (nativeHandle != 0L) nativeSetScreen(nativeHandle, screenId)
    }

    /**
     * Sets the live status text for the current screen. The text is cached per
     * screen and restored if the screen is rebuilt.
     */
    fun setStatusText(text: String) {
        if (nativeHandle != 0L) nativeSetStatusText(nativeHandle, text)
    }

    /**
     * Pushes the Android battery level into the native dashboard status bar.
     * Safe to call before the display exists; the value is cached natively.
     */
    fun setBatteryLevel(level: Int, charging: Boolean) {
        if (nativeHandle != 0L) nativeSetBatteryLevel(nativeHandle, level, charging)
    }

    /**
     * Pushes the real device facts (SoC, RAM, storage, display, cameras,
     * sensors) so the dashboard device map reflects this device. Collected by
     * [LvglDeviceInfo]; safe to call before the display exists.
     */
    fun setDeviceInfo(
        manufacturer: String,
        model: String,
        soc: String,
        memory: String,
        storage: String,
        display: String,
        androidVersion: String,
        cameras: String,
        sensors: String,
    ) {
        if (nativeHandle != 0L) {
            nativeSetDeviceInfo(
                nativeHandle, manufacturer, model, soc, memory, storage,
                display, androidVersion, cameras, sensors
            )
        }
    }

    /** Introspection helpers used by tests. */
    fun isDisplayReady(): Boolean =
        nativeHandle != 0L && nativeIsDisplayReady(nativeHandle)

    /** The currently shown screen id, or `-1` when the engine is not initialised. */
    fun currentScreen(): Int =
        if (nativeHandle != 0L) nativeGetScreen(nativeHandle) else -1

    /** A copy of the current framebuffer in ARGB_8888, or `null` when unavailable. */
    fun captureFrame(): IntArray? =
        if (nativeHandle != 0L) nativeCaptureFrame(nativeHandle) else null

    /** The last native error string, or an empty string when there was none. */
    fun lastError(): String =
        if (nativeHandle != 0L) nativeGetLastError(nativeHandle) ?: "" else ""

    /**
     * Runs the native layout lint on the current screen and returns a JSON
     * report (`{"issues":[{"type":"overlap|bounds|text",...}]}`). Used by the
     * dynamic test harness and instrumented tests.
     */
    fun dumpLayoutReport(): String =
        if (nativeHandle != 0L) nativeDumpLayoutReport(nativeHandle) else "{\"issues\":[]}"

    /**
     * Registers an app-defined screen (Kotlin UI builder). The JSON tree is
     * rendered by the native UI kit; see `com.lvgl.android.ui.LvglUi`.
     */
    fun registerScreen(id: Int, title: String, showBack: Boolean, json: String) {
        if (nativeHandle != 0L) nativeRegisterScreen(nativeHandle, id, title, showBack, json)
    }

    /** Removes a previously registered app-defined screen. */
    fun unregisterScreen(id: Int) {
        if (nativeHandle != 0L) nativeUnregisterScreen(nativeHandle, id)
    }

    /** Updates a node of an already-built app-defined screen. */
    fun updateNode(screenId: Int, nodeId: String, json: String) {
        if (nativeHandle != 0L) nativeUpdateNode(nativeHandle, screenId, nodeId, json)
    }

    /**
     * Pushes the installed-app list to the native launcher grid. Labels are the
     * visible names; packages are used when an app is tapped.
     */
    fun setLauncherApps(labels: Array<String>, packages: Array<String>) {
        if (nativeHandle != 0L) nativeSetLauncherApps(nativeHandle, labels, packages)
    }

    /**
     * Receives app taps from the native launcher grid. The callback runs on the
     * LVGL thread.
     */
    var launcherAppListener: LauncherAppListener? = null
        set(value) {
            field = value
            if (nativeHandle != 0L) nativeSetLauncherListener(nativeHandle, value)
        }

    /**
     * Receives raw events from app-defined screens. Apps should use
     * `LvglUi.listener` instead of setting this directly.
     */
    var uiEventListener: LvglUiEventReceiver? = null
        set(value) {
            field = value
            if (nativeHandle != 0L) nativeSetUiListener(nativeHandle, value)
        }

    /**
     * Receives navigation requests originating from native LVGL UI (menu card
     * taps, the back button) and system-control requests from the dashboard
     * (backlight / volume). The activity registers itself here.
     */
    interface ScreenNavigator {
        /** A native menu card was tapped; [screenId] is the requested screen. */
        fun navigateTo(screenId: Int)

        /** The native back button was tapped; pop the current activity. */
        fun navigateBack()

        /**
         * Window brightness, 0f..1f. Default no-op so a navigator that does not
         * implement the dashboard controls stays source-compatible; the native
         * bridge resolves the method optionally.
         */
        fun setBrightness(brightness: Float) {}

        /** Music-stream volume, 0..100. Default no-op. */
        fun setVolumePercent(percent: Int) {}

        /**
         * Toggle the app-local dark mode. Default no-op so a navigator that does
         * not implement theming stays source-compatible.
         */
        fun toggleDarkMode() {}
    }

    /**
     * The activity that currently owns navigation and the dashboard's system
     * controls. The activity registers itself in `onCreate` and restores itself
     * in `onResume` (a destroyed child clears it).
     */
    var screenNavigator: ScreenNavigator? = null
        set(value) {
            field = value
            if (nativeHandle != 0L) nativeSetNavCallback(nativeHandle, value)
        }

    private external fun nativeSetNavCallback(handle: Long, navigator: ScreenNavigator?)
    private external fun nativeSetBatteryLevel(handle: Long, level: Int, charging: Boolean)
    private external fun nativeSetDeviceInfo(
        handle: Long,
        manufacturer: String,
        model: String,
        soc: String,
        memory: String,
        storage: String,
        display: String,
        androidVersion: String,
        cameras: String,
        sensors: String
    )

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeInitDisplay(handle: Long, surface: Surface, width: Int, height: Int, dpi: Int)
    private external fun nativeDeinitDisplay(handle: Long)
    private external fun nativeResume(handle: Long)
    private external fun nativePause(handle: Long)
    private external fun nativeTouchEvent(handle: Long, action: Int, x: Float, y: Float)
    private external fun nativeApplyTheme(handle: Long, darkMode: Boolean, palette: IntArray)
    private external fun nativeSetScreen(handle: Long, screenId: Int)
    private external fun nativeSetStatusText(handle: Long, text: String)
    private external fun nativeIsDisplayReady(handle: Long): Boolean
    private external fun nativeGetScreen(handle: Long): Int
    private external fun nativeCaptureFrame(handle: Long): IntArray
    private external fun nativeGetLastError(handle: Long): String?
    private external fun nativeDumpLayoutReport(handle: Long): String
    private external fun nativeRegisterScreen(
        handle: Long, id: Int, title: String, showBack: Boolean, json: String
    )
    private external fun nativeUnregisterScreen(handle: Long, id: Int)
    private external fun nativeUpdateNode(handle: Long, screenId: Int, nodeId: String, json: String)
    private external fun nativeSetLauncherApps(handle: Long, labels: Array<String>, packages: Array<String>)
    private external fun nativeSetLauncherListener(handle: Long, listener: LauncherAppListener?)
    private external fun nativeSetUiListener(handle: Long, listener: LvglUiEventReceiver?)

    /**
     * Process-wide engine singleton. Safe to access from any thread; the native
     * library is loaded by [initialize], not by this property.
     */
    companion object {
        private const val TAG = "LvglEngine"

        val instance: LvglEngine by lazy { LvglEngine() }
    }
}
