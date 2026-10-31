// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.view

import android.content.Context
import android.graphics.PixelFormat
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.lvgl.android.engine.LvglEngine

/**
 * SurfaceView that hosts the LVGL render surface.
 *
 * The LVGL engine is process-wide (see LvglEngine); this view only manages the
 * *display* bound to its surface. On surface recreation it re-initialises the
 * display without destroying the engine, so the current screen + theme survive.
 */
open class LvglSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    private val engine = LvglEngine.instance
    private var nativeInitialized = false

    init {
        holder.addCallback(this)
        holder.setFormat(PixelFormat.RGBA_8888)
        /* Surface sits behind window content so dialogs/permissions render on top.
         * setZOrderOnTop(true) is intentionally NOT used (it hides in-app
         * dialogs behind the LVGL surface and contributes to the ANR). */
        setZOrderMediaOverlay(false)
        setZOrderOnTop(false)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "surfaceCreated")
        engine.initialize()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(TAG, "surfaceChanged: ${width}x${height}")
        val dpi = resources.displayMetrics.densityDpi
        /* The native side rebinds the surface to the shared display when the
         * size is unchanged, and only rebuilds on a genuine size change. */
        engine.initDisplay(this, holder.surface, width, height, dpi)
        nativeInitialized = true
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "surfaceDestroyed")
        if (nativeInitialized) {
            engine.deinitDisplay(this)
            nativeInitialized = false
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        Log.d(TAG, "onTouchEvent action=${event.actionMasked} (${event.x},${event.y})")
        val action = event.actionMasked
        val pointerIndex = event.actionIndex
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)

        when (action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> { engine.onTouchEvent(0, x, y) }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> { engine.onTouchEvent(1, x, y) }
            MotionEvent.ACTION_MOVE -> { engine.onTouchEvent(2, x, y) }
        }

        return true
    }

    fun onResume() {
        if (nativeInitialized) { engine.resume() }
    }

    fun onPause() {
        if (nativeInitialized) { engine.pause() }
    }

    fun destroy() {
        if (nativeInitialized) {
            engine.deinitDisplay(this)
            nativeInitialized = false
        }
        /* The engine itself is process-wide and intentionally not destroyed
         * here — the display is only unbound, so a later activity can rebind. */
    }

    companion object {
        private const val TAG = "LvglSurfaceView"
    }
}
