package com.lvgl.sample

import android.content.Context
import android.graphics.PixelFormat
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView

class LvglView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "LvglView"

        init {
            System.loadLibrary("lvgl_app")
        }
    }

    private var nativeInitialized = false

    init {
        holder.addCallback(this)
        holder.setFormat(PixelFormat.RGBA_8888)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "surfaceCreated")
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(TAG, "surfaceChanged: ${width}x${height}")

        if (nativeInitialized) {
            nativeDeinit()
            nativeInitialized = false
        }

        val dpi = resources.displayMetrics.densityDpi
        nativeInit(holder.surface, width, height, dpi)
        nativeInitialized = true
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "surfaceDestroyed")
        if (nativeInitialized) {
            nativeDeinit()
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
                nativeTouchEvent(0, x, y)  // ACTION_DOWN
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> {
                nativeTouchEvent(1, x, y)  // ACTION_UP
            }
            MotionEvent.ACTION_MOVE -> {
                nativeTouchEvent(2, x, y)  // ACTION_MOVE
            }
        }

        return true
    }

    fun onResume() {
        if (nativeInitialized) {
            nativeResume()
        }
    }

    fun onPause() {
        if (nativeInitialized) {
            nativePause()
        }
    }

    fun destroy() {
        if (nativeInitialized) {
            nativeDeinit()
            nativeInitialized = false
        }
    }

    private external fun nativeInit(surface: Any, width: Int, height: Int, dpi: Int)
    private external fun nativeDeinit()
    private external fun nativeResume()
    private external fun nativePause()
    private external fun nativeTouchEvent(action: Int, x: Float, y: Float)
}
