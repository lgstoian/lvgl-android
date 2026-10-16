package com.lvgl.android.activity

import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.lvgl.android.config.DisplayMetricsManager
import com.lvgl.android.engine.LvglEngine
import com.lvgl.android.theme.MaterialTheme
import com.lvgl.android.theme.LvglThemeBridge
import com.lvgl.android.view.LvglSurfaceView

abstract class LvglActivity : AppCompatActivity(),
    MaterialTheme.OnThemeChangedListener,
    DisplayMetricsManager.OnDisplayMetricsChangedListener {

    private var lvglView: LvglSurfaceView? = null
    private val displayMetrics = DisplayMetricsManager(this)
    private val themeBridge = LvglThemeBridge()

    abstract fun onCreateLvgl()

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

        onCreateLvgl()
        themeBridge.applyCurrentTheme()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
        lvglView?.onResume()
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause")
        lvglView?.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        lvglView?.destroy()
        lvglView = null
        MaterialTheme.instance.removeListener(this)
        displayMetrics.removeListener(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "onConfigurationChanged")
        MaterialTheme.instance.onConfigurationChanged(newConfig)
        displayMetrics.onConfigurationChanged(newConfig)
    }

    override fun onThemeChanged(theme: MaterialTheme) {
        onLvglThemeChanged(theme)
    }

    override fun onDisplayMetricsChanged(metrics: DisplayMetricsManager) {
        onLvglMetricsChanged(metrics)
    }

    companion object {
        private const val TAG = "LvglActivity"
    }
}
