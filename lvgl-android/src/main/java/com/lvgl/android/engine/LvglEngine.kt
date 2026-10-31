// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.engine

import android.util.Log
import android.view.Surface

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

    fun initialize() {
        if (nativeHandle == 0L) {
            nativeHandle = nativeCreate()
            screenNavigator?.let { nativeSetNavCallback(nativeHandle, it) }
        }
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

    fun resume() {
        if (nativeHandle != 0L) nativeResume(nativeHandle)
    }

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

    fun onTouchEvent(action: Int, x: Float, y: Float) {
        if (nativeHandle != 0L) nativeTouchEvent(nativeHandle, action, x, y)
    }

    fun applyTheme(darkMode: Boolean, primaryColor: Int, secondaryColor: Int) {
        if (nativeHandle != 0L) {
            nativeApplyTheme(nativeHandle, darkMode, primaryColor, secondaryColor)
        }
    }

    fun setScreen(screenId: Int) {
        if (nativeHandle != 0L) nativeSetScreen(nativeHandle, screenId)
    }

    fun setStatusText(text: String) {
        if (nativeHandle != 0L) nativeSetStatusText(nativeHandle, text)
    }

    /** Introspection helpers used by tests. */
    fun isDisplayReady(): Boolean =
        nativeHandle != 0L && nativeIsDisplayReady(nativeHandle)

    fun currentScreen(): Int =
        if (nativeHandle != 0L) nativeGetScreen(nativeHandle) else -1

    fun captureFrame(): IntArray? =
        if (nativeHandle != 0L) nativeCaptureFrame(nativeHandle) else null

    fun lastError(): String =
        if (nativeHandle != 0L) nativeGetLastError(nativeHandle) ?: "" else ""

    /**
     * Receives navigation requests originating from native LVGL UI (menu card
     * taps, the back button). The activity registers itself here.
     */
    interface ScreenNavigator {
        fun navigateTo(screenId: Int)
        fun navigateBack()
    }

    var screenNavigator: ScreenNavigator? = null
        set(value) {
            field = value
            if (nativeHandle != 0L) nativeSetNavCallback(nativeHandle, value)
        }

    private external fun nativeSetNavCallback(handle: Long, navigator: ScreenNavigator?)

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeInitDisplay(handle: Long, surface: Surface, width: Int, height: Int, dpi: Int)
    private external fun nativeDeinitDisplay(handle: Long)
    private external fun nativeResume(handle: Long)
    private external fun nativePause(handle: Long)
    private external fun nativeTouchEvent(handle: Long, action: Int, x: Float, y: Float)
    private external fun nativeApplyTheme(handle: Long, darkMode: Boolean, primaryColor: Int, secondaryColor: Int)
    private external fun nativeSetScreen(handle: Long, screenId: Int)
    private external fun nativeSetStatusText(handle: Long, text: String)
    private external fun nativeIsDisplayReady(handle: Long): Boolean
    private external fun nativeGetScreen(handle: Long): Int
    private external fun nativeCaptureFrame(handle: Long): IntArray
    private external fun nativeGetLastError(handle: Long): String?

    companion object {
        private const val TAG = "LvglEngine"

        val instance: LvglEngine by lazy { LvglEngine() }

        init {
            System.loadLibrary("lvgl_android")
        }
    }
}
