package com.lvgl.android.config

import android.content.Context
import android.util.Log

class DpiManager(private val context: Context) {

    fun getDpi(): Int {
        return context.resources.displayMetrics.densityDpi
    }

    fun getDensity(): Float {
        return context.resources.displayMetrics.density
    }

    fun dpToPx(dp: Float): Int {
        return (dp * getDensity()).toInt()
    }

    fun pxToDp(px: Float): Float {
        return px / getDensity()
    }

    fun spToPx(sp: Float): Int {
        return (sp * context.resources.displayMetrics.scaledDensity).toInt()
    }

    fun pxToSp(px: Float): Float {
        return px / context.resources.displayMetrics.scaledDensity
    }

    fun getScreenWidthPx(): Int {
        return context.resources.displayMetrics.widthPixels
    }

    fun getScreenHeightPx(): Int {
        return context.resources.displayMetrics.heightPixels
    }

    fun getScreenWidthDp(): Float {
        return pxToDp(getScreenWidthPx().toFloat())
    }

    fun getScreenHeightDp(): Float {
        return pxToDp(getScreenHeightPx().toFloat())
    }

    fun getOrientation(): Int {
        return context.resources.configuration.orientation
    }

    fun isLandscape(): Boolean {
        return getOrientation() == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    }

    fun isPortrait(): Boolean {
        return getOrientation() == android.content.res.Configuration.ORIENTATION_PORTRAIT
    }

    fun getSizeCategory(): ScreenSize {
        val widthDp = getScreenWidthDp()
        val heightDp = getScreenHeightDp()
        val smallest = minOf(widthDp, heightDp)

        return when {
            smallest >= 720 -> ScreenSize.EXTRA_LARGE
            smallest >= 600 -> ScreenSize.LARGE
            smallest >= 480 -> ScreenSize.MEDIUM
            else -> ScreenSize.SMALL
        }
    }

    enum class ScreenSize {
        SMALL, MEDIUM, LARGE, EXTRA_LARGE
    }

    companion object {
        private const val TAG = "DpiManager"
    }
}
