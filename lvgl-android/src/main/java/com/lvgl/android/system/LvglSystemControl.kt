// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.system

import android.content.Context
import android.media.AudioManager
import android.os.BatteryManager
import android.util.Log
import android.view.Window
import kotlin.math.roundToInt

/**
 * The single source of truth for the Android side of the LVGL dashboard's
 * functional panels (backlight + volume). Native calls these through the
 * [com.lvgl.android.engine.LvglEngine.ScreenNavigator] bridge; the app layer
 * only supplies data (battery level).
 *
 * @breadcrumb BC-023
 * @do-not Do not call PowerManager.goToSleep() from an app: it needs the
 *   system-only DEVICE_POWER permission, so the Power panel stays simulated.
 */
object LvglSystemControl {

    private const val TAG = "LvglSystemControl"

    /**
     * Applies [percent] (0..100) as this window's brightness.
     *
     * Values are clamped to 1..100 so the screen can never go fully black.
     * Must be called on the UI thread because it mutates [Window] attributes.
     *
     * @param window the window whose brightness is changed.
     * @param percent the requested brightness in the 0..100 range.
     */
    fun applyBrightness(window: Window, percent: Int) {
        val lp = window.attributes
        /* A floor of 1 keeps the window readable; 0 would trap the user on a
         * black screen with no way to restore the panel. */
        lp.screenBrightness = percent.coerceIn(1, 100) / 100f
        window.attributes = lp
        Log.d(TAG, "applyBrightness=$percent -> ${lp.screenBrightness}")
    }

    /**
     * Applies [percent] (0..100) to the music stream.
     *
     * No system volume UI is shown; the LVGL panel is the UI. The call is
     * ignored when the device has no music stream.
     *
     * @param context used to resolve the [AudioManager].
     * @param percent the requested volume in the 0..100 range.
     */
    fun applyVolumePercent(context: Context, percent: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        val target = (percent.coerceIn(0, 100) * max / 100f).roundToInt()
        /* No FLAG_SHOW_UI: the LVGL panel is the UI. */
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        Log.d(TAG, "applyVolumePercent=$percent -> $target/$max")
    }

    /**
     * Battery level 0..100, or -1 when unknown.
     *
     * @param context used to resolve the [BatteryManager].
     * @return the remaining charge percentage, or `-1` when unavailable.
     */
    fun batteryLevel(context: Context): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return -1
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    /**
     * True while the device is charging.
     *
     * @param context used to resolve the [BatteryManager].
     * @return `true` when the reported status is
     *   [BatteryManager.BATTERY_STATUS_CHARGING].
     */
    fun isCharging(context: Context): Boolean {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return false
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) ==
            BatteryManager.BATTERY_STATUS_CHARGING
    }
}
