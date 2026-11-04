// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.system

import android.app.ActivityManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.StatFs
import android.util.DisplayMetrics
import android.view.WindowManager
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Collects the real hardware and software facts that the dashboard device map
 * shows, and maps platform identifiers to marketing names.
 *
 * @breadcrumb BC-028
 * @do-not Do not hard-code device values in the UI; always go through [collect].
 */
object LvglDeviceInfo {

    /**
     * Display-ready facts about the running device.
     *
     * All values are already formatted for the dashboard labels; empty strings
     * mean the corresponding hardware was not reported (for example no camera).
     *
     * @property manufacturer `Build.MANUFACTURER`, or "Unknown".
     * @property model `Build.MODEL`, falling back to the device codename.
     * @property soc marketing name of the SoC (see [socMarketingName]).
     * @property memory total RAM, formatted with [formatSize].
     * @property storage total internal storage, formatted with [formatSize].
     * @property display resolution as `width x height` in pixels.
     * @property androidVersion release name plus API level.
     * @property cameras camera count, or an empty string when none.
     * @property sensors sensor count, or an empty string when none.
     */
    data class Facts(
        val manufacturer: String,
        val model: String,
        val soc: String,
        val memory: String,
        val storage: String,
        val display: String,
        val androidVersion: String,
        val cameras: String,
        val sensors: String,
    )

    /**
     * Snapshot of the device, with values formatted for the schematic labels.
     *
     * Safe to call at any time and from any thread; it queries the platform
     * services synchronously, so avoid calling it on the UI thread on slow
     * devices.
     *
     * @param context used to resolve window, activity, camera and sensor services.
     * @return the collected [Facts].
     */
    fun collect(context: Context): Facts {
        val dm = DisplayMetrics()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(dm)

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val storage = StatFs(context.filesDir.absolutePath).totalBytes

        val cameraCount = try {
            (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager)
                .cameraIdList.size
        } catch (t: Throwable) { 0 }

        val sensorCount = try {
            (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager)
                .getSensorList(Sensor.TYPE_ALL).size
        } catch (t: Throwable) { 0 }

        val androidVersion = Build.VERSION.RELEASE?.let { "Android $it" } ?: "Android"

        return Facts(
            manufacturer = Build.MANUFACTURER.notBlankOrElse { "Unknown" },
            model = Build.MODEL.notBlankOrElse { Build.DEVICE.notBlankOrElse { "Device" } },
            soc = socMarketingName(Build.HARDWARE),
            memory = formatSize(memInfo.totalMem),
            storage = formatSize(storage),
            display = "${dm.widthPixels} x ${dm.heightPixels}",
            androidVersion = "$androidVersion (API ${Build.VERSION.SDK_INT})",
            cameras = if (cameraCount > 0) "$cameraCount" else "",
            sensors = if (sensorCount > 0) "$sensorCount" else "",
        )
    }

    /**
     * Marketing name for a platform id (e.g. `sm8650` -> "Snapdragon 8 Gen 3").
     * Falls back to a readable form of the raw id so the map never shows junk.
     *
     * @param hardware the `Build.HARDWARE` platform id.
     * @return the marketing name, an empty string when [hardware] is blank, or
     *   a title-cased form of the raw id when it is not recognised.
     */
    fun socMarketingName(hardware: String): String {
        val h = hardware.lowercase(Locale.US)
        val digits = Regex("\\d{3,4}").find(h)?.value
        return when {
            h.isEmpty() -> ""
            h.contains("sm8750") -> "Snapdragon 8 Elite"
            h.contains("sm8650") -> "Snapdragon 8 Gen 3"
            h.contains("sm8550") -> "Snapdragon 8 Gen 2"
            h.contains("sm8450") -> "Snapdragon 8 Gen 1"
            h.contains("sm8350") -> "Snapdragon 888"
            h.contains("sm8250") -> "Snapdragon 865"
            h.contains("gs101") || h.contains("gs201") || h.contains("tensor") -> "Google Tensor"
            h.contains("exynos") -> if (digits != null) "Exynos $digits" else "Samsung Exynos"
            h.contains("kirin") -> if (digits != null) "Kirin $digits" else "Huawei Kirin"
            h.contains("dimensity") || h.contains("mt6") || h.contains("mt8") -> "MediaTek Dimensity"
            h.contains("t618") || h.contains("t606") || h.contains("t760") -> "Unisoc"
            h.contains("ranchu") || h.contains("goldfish") || h.contains("virtual") -> "Virtual SoC"
            else -> hardware.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString()
            }
        }
    }

    /**
     * Binary-unit size label (`8 GB`), matching how devices are marketed.
     *
     * @param bytes the size in bytes.
     * @return a label in GB, MB or KB, or an empty string for non-positive sizes.
     */
    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val gib = bytes / (1024.0 * 1024.0 * 1024.0)
        if (gib >= 1.0) return "${gib.roundToInt()} GB"
        val mib = bytes / (1024.0 * 1024.0)
        if (mib >= 1.0) return "${mib.roundToInt()} MB"
        return "${(bytes / 1024.0).roundToInt()} KB"
    }

    private fun String.notBlankOrElse(fallback: () -> String): String =
        if (isNotBlank()) this else fallback()
}
