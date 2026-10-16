package com.lvgl.android.view

import android.content.Context
import android.graphics.PixelFormat
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.lvgl.android.engine.LvglEngine

class LvglSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    private val engine = LvglEngine.instance
    private var nativeInitialized = false

    init {
        holder.addCallback(this)
        holder.setFormat(PixelFormat.RGBA_8888)
        setZOrderOnTop(false)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "surfaceCreated")
        engine.initialize()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(TAG, "surfaceChanged: ${width}x${height}")

        if (nativeInitialized) {
            engine.deinitDisplay()
            nativeInitialized = false
        }

        val dpi = resources.displayMetrics.densityDpi
        engine.initDisplay(holder.surface, width, height, dpi)
        nativeInitialized = true
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "surfaceDestroyed")
        if (nativeInitialized) {
            engine.deinitDisplay()
            nativeInitialized = false
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val pointerIndex = event.actionIndex
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)

        when (action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                engine.onTouchEvent(0, x, y)
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> {
                engine.onTouchEvent(1, x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                engine.onTouchEvent(2, x, y)
            }
        }

        return true
    }

    fun onResume() {
        if (nativeInitialized) {
            engine.resume()
        }
    }

    fun onPause() {
        if (nativeInitialized) {
            engine.pause()
        }
    }

    fun destroy() {
        if (nativeInitialized) {
            engine.deinitDisplay()
            nativeInitialized = false
        }
        engine.shutdown()
    }

    companion object {
        private const val TAG = "LvglSurfaceView"
    }
}
