// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.

package com.lvgl.android.activity

import com.lvgl.android.engine.LvglEngine

import com.lvgl.android.view.LvglSurfaceView


import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.lvgl.android.config.DisplayMetricsManager
import com.lvgl.android.theme.MaterialTheme
import com.lvgl.android.theme.LvglThemeBridge

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

        // Set dark window background to match LVGL dark theme and prevent
        // Android's light theme from showing through transparent SurfaceView areas
        window.setBackgroundDrawableResource(android.R.color.black)

        // Make the window fullscreen (hide status bar and navigation bar)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )

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
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        lvglView = LvglSurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.BLACK)
            setZOrderOnTop(true)
            holder.setFormat(android.graphics.PixelFormat.RGBA_8888)
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








