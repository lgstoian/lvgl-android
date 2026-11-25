// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.system

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.lvgl.android.util.LvglLog
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Collects the real hardware and software facts that the dashboard device map
 * shows, and maps platform identifiers to marketing names.
 *
 * A fact that could not be observed is reported as [UNKNOWN] ("?"), never as
 * zero or an empty string: "no camera" and "camera could not be queried" are
 * different states and the dashboard must not claim a hardware fact it did not
 * see. Failed probes also carry a [ProbeStatus] so callers can tell them apart.
 *
 * @breadcrumb BC-028
 * @do-not Do not hard-code device values in the UI; always go through [collect].
 */
object LvglDeviceInfo {

    /** Text used for every fact that could not be observed. */
    const val UNKNOWN = "?"

    /** Outcome of a hardware count probe. */
    enum class ProbeStatus {
        /** The service answered with at least one item. */
        AVAILABLE,

        /** The service answered with an empty list: genuine absence. */
        ABSENT,

        /** The service refused the query (permission/security). */
        DENIED,

        /** The service threw for any other reason. */
        ERROR,
    }

    /**
     * Result of a count probe. [count] is `null` when the probe failed; a
     * non-null count (including `0`) was actually observed.
     */
    data class CountResult(val count: Int?, val status: ProbeStatus, val failure: Throwable? = null) {
        /** Display-ready value: the observed count, or [UNKNOWN] on failure. */
        val label: String get() = count?.toString() ?: UNKNOWN
    }

    /**
     * Runs [source] and classifies its outcome. The classifier is supplied by
     * the caller so this function is pure and unit-testable without Android
     * services; collection passes a classifier that logs through [LvglLog].
     */
    fun probeCount(source: () -> Int, classifier: (Throwable) -> ProbeStatus): CountResult =
        try {
            val observed = source()
            CountResult(
                observed,
                if (observed > 0) ProbeStatus.AVAILABLE else ProbeStatus.ABSENT
            )
        } catch (t: Throwable) {
            CountResult(null, classifier(t), t)
        }

    /**
     * All Camera2 ids visible on a device: the public ids from
     * [CameraManager.getCameraIdList] plus every physical camera id exposed
     * behind them (`CameraCharacteristics.getPhysicalCameraIds()`, the public
     * API-28 equivalent of INFO_PHYSICAL_CAMERA_IDS).
     *
     * Logical cameras hide their physical sensors from the public list, so
     * counting only [cameraIds] under-reports real hardware. The lookup is
     * supplied by the caller, which keeps this function pure and unit-testable
     * without Android services. Blank and duplicate ids are ignored.
     */
    internal fun cameraIdUnion(
        cameraIds: Collection<String?>,
        physicalIdsFor: (String) -> Collection<String?>?
    ): Set<String> {
        val public = cameraIds.filterNotNull().filter { it.isNotBlank() }
        val result = LinkedHashSet<String>(public)
        public.forEach { id ->
            physicalIdsFor(id)?.forEach { physical ->
                if (!physical.isNullOrBlank()) result.add(physical)
            }
        }
        return result
    }

    /**
     * Reads the camera ids from [manager] through [cameraIdUnion] and logs the
     * full set at debug level. Physical ids are only available on API 28+.
     *
     * A failure to query characteristics propagates to the caller so the
     * enclosing probe can report [UNKNOWN] instead of a false zero.
     */
    internal fun collectCameraIds(manager: CameraManager): Set<String> {
        val publicIds = manager.cameraIdList.toList()
        val physicalIdsFor: (String) -> Collection<String?>? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                { id ->
                    /* Public API-28 equivalent of the hidden
                     * INFO_PHYSICAL_CAMERA_IDS key. */
                    manager.getCameraCharacteristics(id).getPhysicalCameraIds().toList()
                }
            } else {
                { null }
            }
        val ids = cameraIdUnion(publicIds, physicalIdsFor)
        LvglLog.d(
            TAG,
            "Camera ids: public=[${publicIds.joinToString()}] all=[${ids.joinToString()}]"
        )
        return ids
    }

    /**
     * Clean list of observed output device types. Unknown types are dropped
     * rather than concatenated as "?", duplicates are removed, and the first
     * types win so the label stays short enough for a dashboard chip.
     */
    internal fun audioLabel(types: Collection<Int>): String {
        val labels = types.map(::audioDeviceLabel).filter { it != UNKNOWN }.distinct().take(2)
        return if (labels.isEmpty()) UNKNOWN else labels.joinToString("+")
    }

    /** Capability-based USB label. A port/feature is never reported as "None". */
    internal fun usbCapabilityLabel(
        hasHostFeature: Boolean,
        hasAccessoryFeature: Boolean,
        hasConnectedDevice: Boolean
    ): String = if (hasHostFeature || hasAccessoryFeature || hasConnectedDevice) "USB" else UNKNOWN

    /**
     * Cellular label that never fabricates a generation. A permission-free
     * telephony feature proves capability; a specific generation is only used
     * when a caller has actually observed it through a guarded API.
     */
    internal fun modemLabel(hasTelephonyFeature: Boolean, observedGeneration: String?): String =
        if (!hasTelephonyFeature) UNKNOWN
        else observedGeneration?.takeIf { it.isNotBlank() } ?: "Cellular"

    /**
     * Wi-Fi state from a permission-free system setting. `WIFI_ON` is 1 (on),
     * 0 (off) or absent/unreadable; absence is never reported as a false "off".
     */
    internal fun wifiStateLabel(hasWifiFeature: Boolean, wifiOnSetting: Int?): String =
        if (!hasWifiFeature) UNKNOWN
        else when (wifiOnSetting) {
            1 -> "on"
            0 -> "off"
            else -> UNKNOWN
        }

    /**
     * Display-ready facts about the running device.
     *
     * All values are already formatted for the dashboard labels. [UNKNOWN]
     * means the corresponding hardware could not be observed; a zero count
     * means it was observed to be absent.
     *
     * @property manufacturer `Build.MANUFACTURER`, or "Unknown".
     * @property model `Build.MODEL`, falling back to the device codename.
     * @property soc marketing name of the SoC (see [socMarketingName]).
     * @property memory total RAM, formatted with [formatSize].
     * @property storage total storage of the shared device volume.
     * @property display physical resolution as `width x height` in pixels.
     * @property androidVersion release name plus API level.
     * @property cameras observed camera count, or [UNKNOWN] when the query failed.
     * @property sensors observed sensor count, or [UNKNOWN] when the query failed.
     * @property cameraStatus typed outcome of the camera probe.
     * @property sensorStatus typed outcome of the sensor probe.
     * @property modem observed cellular generation, or "Cellular" from the
     *   permission-free telephony feature, or [UNKNOWN] with no modem.
     * @property network Wi-Fi and Bluetooth state, e.g. `WiFi/BT: on/off`.
     * @property batteryTech battery technology from the sticky battery broadcast.
     * @property audio clean list of active output types, or [UNKNOWN].
     * @property usb "USB" when a port/feature exists, or [UNKNOWN].
     * @property bluetooth Bluetooth adapter state, or [UNKNOWN] when unavailable.
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
        val cameraStatus: ProbeStatus,
        val sensorStatus: ProbeStatus,
        val modem: String,
        val network: String,
        val batteryTech: String,
        val audio: String,
        val usb: String,
        val bluetooth: String,
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
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val cameraResult = probeCount(
            {
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                collectCameraIds(manager).size
            }
        ) { classifyFailure("Camera", it) }

        val sensorResult = probeCount(
            {
                (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager)
                    .getSensorList(Sensor.TYPE_ALL).size
            }
        ) { classifyFailure("Sensors", it) }

        val androidVersion = Build.VERSION.RELEASE?.let { "Android $it" } ?: "Android"

        return Facts(
            manufacturer = Build.MANUFACTURER.notBlankOrElse { "Unknown" },
            model = Build.MODEL.notBlankOrElse { Build.DEVICE.notBlankOrElse { "Device" } },
            soc = socMarketingName(Build.HARDWARE),
            memory = formatSize(memInfo.totalMem).ifBlank { UNKNOWN },
            storage = totalStorage(context),
            display = displayResolution(wm),
            androidVersion = "$androidVersion (API ${Build.VERSION.SDK_INT})",
            cameras = cameraResult.label,
            sensors = sensorResult.label,
            cameraStatus = cameraResult.status,
            sensorStatus = sensorResult.status,
            modem = modemGeneration(context),
            network = networkState(context),
            batteryTech = batteryTechnology(context),
            audio = audioDevices(context),
            usb = usbDevices(context),
            bluetooth = bluetoothState(context),
        )
    }

    /**
     * Maps a probe failure to a typed status and logs it. Camera access errors
     * are named explicitly so a denied camera query is never mistaken for a
     * camera-less device.
     */
    private fun classifyFailure(kind: String, failure: Throwable): ProbeStatus {
        val type = when (failure) {
            is CameraAccessException -> "CameraAccessException"
            is SecurityException -> "SecurityException"
            else -> failure.javaClass.simpleName
        }
        if (failure is SecurityException) {
            LvglLog.w(TAG, "$kind probe denied ($type): ${failure.message}")
            return ProbeStatus.DENIED
        }
        LvglLog.e(TAG, "$kind probe failed ($type): ${failure.message}")
        return ProbeStatus.ERROR
    }

    /** Runs [block], logging and returning [UNKNOWN] for any observed failure. */
    private fun safeFact(kind: String, block: () -> String): String = try {
        block().notBlankOrElse { UNKNOWN }
    } catch (t: Throwable) {
        val type = when (t) {
            is SecurityException -> "SecurityException"
            is CameraAccessException -> "CameraAccessException"
            else -> t.javaClass.simpleName
        }
        LvglLog.w(TAG, "$kind unavailable ($type): ${t.message}")
        UNKNOWN
    }

    /**
     * Physical panel resolution. Window metrics and `getRealMetrics()` include
     * system decor; `defaultDisplay.getMetrics()` would report the *window*
     * size instead of the panel.
     */
    private fun displayResolution(wm: WindowManager): String = safeFact("Display") {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            "${bounds.width()} x ${bounds.height()}"
        } else {
            @Suppress("DEPRECATION")
            val dm = DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
            "${dm.widthPixels} x ${dm.heightPixels}"
        }
    }

    /** Total size of the shared device volume, not the app-private partition. */
    private fun totalStorage(context: Context): String = safeFact("Storage") {
        @Suppress("DEPRECATION")
        val external = Environment.getExternalStorageDirectory().absolutePath
        val bytes = try {
            StatFs(external).totalBytes
        } catch (t: Throwable) {
            val fallback = context.getExternalFilesDir(null)?.absolutePath ?: return@safeFact UNKNOWN
            StatFs(fallback).totalBytes
        }
        formatSize(bytes).ifBlank { UNKNOWN }
    }

    /**
     * Cellular fact from permission-free hardware capability. A generation is
     * reported only when a guarded API actually observed it; otherwise the
     * presence of a modem is reported honestly as "Cellular".
     */
    @SuppressLint("MissingPermission")
    private fun modemGeneration(context: Context): String = safeFact("Modem") {
        val hasTelephony = context.packageManager
            .hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        val observed = if (hasTelephony &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (tm != null && tm.simState != TelephonyManager.SIM_STATE_ABSENT) {
                when (tm.networkType) {
                    TelephonyManager.NETWORK_TYPE_NR -> "5G"
                    TelephonyManager.NETWORK_TYPE_LTE -> "4G"
                    TelephonyManager.NETWORK_TYPE_UMTS,
                    TelephonyManager.NETWORK_TYPE_HSDPA,
                    TelephonyManager.NETWORK_TYPE_HSUPA,
                    TelephonyManager.NETWORK_TYPE_HSPA,
                    TelephonyManager.NETWORK_TYPE_HSPAP,
                    TelephonyManager.NETWORK_TYPE_TD_SCDMA,
                    TelephonyManager.NETWORK_TYPE_CDMA,
                    TelephonyManager.NETWORK_TYPE_1xRTT,
                    TelephonyManager.NETWORK_TYPE_EVDO_0,
                    TelephonyManager.NETWORK_TYPE_EVDO_A,
                    TelephonyManager.NETWORK_TYPE_EVDO_B,
                    TelephonyManager.NETWORK_TYPE_EHRPD -> "3G"
                    TelephonyManager.NETWORK_TYPE_GSM,
                    TelephonyManager.NETWORK_TYPE_GPRS,
                    TelephonyManager.NETWORK_TYPE_EDGE,
                    TelephonyManager.NETWORK_TYPE_IDEN -> "2G"
                    else -> null
                }
            } else {
                null
            }
        } else {
            null
        }
        modemLabel(hasTelephony, observed)
    }

    /** Set after the first failed Wi-Fi setting read so the warning logs once. */
    @Volatile
    private var wifiProbeUnobservable = false

    /**
     * Wi-Fi state from the permission-free global setting. The app declares no
     * network/Wi-Fi permission (Play compliance), so ConnectivityManager and
     * WifiManager cannot be queried; `Settings.Global.WIFI_ON` is world-readable.
     */
    private fun wifiState(context: Context): String = safeFact("Wi-Fi") {
        val hasWifi = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)
        if (!hasWifi || wifiProbeUnobservable) return@safeFact wifiStateLabel(hasWifi, null)
        val setting = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON, -1)
        } catch (t: SecurityException) {
            wifiProbeUnobservable = true
            LvglLog.w(TAG, "Wi-Fi setting denied (SecurityException): ${t.message}")
            return@safeFact UNKNOWN
        } catch (t: Throwable) {
            wifiProbeUnobservable = true
            LvglLog.w(TAG, "Wi-Fi setting unavailable (${t.javaClass.simpleName}): ${t.message}")
            return@safeFact UNKNOWN
        }
        wifiStateLabel(hasWifi, setting)
    }

    /** Bluetooth adapter state; transient turning-on/off states stay unknown. */
    private fun bluetoothState(context: Context): String = safeFact("Bluetooth") {
        val adapter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        } else {
            @Suppress("DEPRECATION")
            BluetoothAdapter.getDefaultAdapter()
        } ?: return@safeFact UNKNOWN
        when (adapter.state) {
            BluetoothAdapter.STATE_ON -> "on"
            BluetoothAdapter.STATE_OFF -> "off"
            else -> UNKNOWN
        }
    }

    /** Wi-Fi/Bluetooth summary; each half reports only what was observed. */
    private fun networkState(context: Context): String =
        "WiFi/BT: ${wifiState(context)}/${bluetoothState(context)}"

    /** Battery technology from the sticky ACTION_BATTERY_CHANGED broadcast. */
    private fun batteryTechnology(context: Context): String = safeFact("Battery") {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(null, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(null, filter)
        }
        sticky?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: UNKNOWN
    }

    /** Active audio output types (API 23+), or unknown on failure. */
    private fun audioDevices(context: Context): String = safeFact("Audio") {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return@safeFact UNKNOWN
        audioLabel(am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type })
    }

    private fun audioDeviceLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT"
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI"
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
        AudioDeviceInfo.TYPE_TELEPHONY -> "Phone"
        else -> UNKNOWN
    }

    /**
     * USB port capability, not connected-device count: getDeviceList() is empty
     * with nothing plugged in even though the port exists. "None" would be a
     * false claim of absence, so only a positive capability is reported.
     */
    private fun usbDevices(context: Context): String = safeFact("USB") {
        val pm = context.packageManager
        val um = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        usbCapabilityLabel(
            hasHostFeature = pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST),
            hasAccessoryFeature = pm.hasSystemFeature(PackageManager.FEATURE_USB_ACCESSORY),
            hasConnectedDevice = try {
                um?.deviceList?.isNotEmpty() == true
            } catch (t: Throwable) {
                false
            }
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

    private const val TAG = "LvglDeviceInfo"
}
