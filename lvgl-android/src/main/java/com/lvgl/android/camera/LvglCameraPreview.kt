// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Build
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.lvgl.android.system.LvglDeviceInfo
import java.util.Locale
import kotlin.math.abs

/**
 * Camera2 preview helper.
 *
 * The preview is started automatically once the camera opens. When no display
 * surface is supplied the frames are drained into an internal [ImageReader],
 * which keeps the capture session running without competing for the window.
 * Callers that do have a Surface (the camera sample's overlay SurfaceView)
 * should call [startPreview] before [open]: the surface is remembered and the
 * repeating request targets it as soon as the device opens, so real imagery is
 * visible. Callbacks arrive on a Camera2 handler thread; [onOpenResult] lets a
 * caller report the real open outcome on the UI thread.
 */
class LvglCameraPreview(private val context: Context) {

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val lock = Any()

    @Volatile
    private var cameraDevice: CameraDevice? = null

    @Volatile
    private var openedCameraId: String? = null

    @Volatile
    private var pendingSurface: Surface? = null

    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    /** Notified on the Camera2 callback thread when an open attempt settles. */
    var onOpenResult: ((cameraId: String, opened: Boolean) -> Unit)? = null

    /**
     * Effective on-screen size of the most recent [selectPreviewSize] choice.
     * Camera2 delivers YUV buffers in sensor coordinates, so this is the raw
     * output size rotated by the camera's relative rotation; it is the size a
     * preview surface should be letterboxed to.
     */
    @Volatile
    var chosenPreviewSize: Size? = null
        private set

    /**
     * Picks the camera output size whose DISPLAY aspect best fits [target] and
     * remembers its rotated (effective) size in [chosenPreviewSize], which is
     * also returned. SENSOR_ORIENTATION and the current device rotation decide
     * whether width/height are swapped relative to the raw YUV size.
     *
     * A caller with a preview surface calls this before sizing that surface,
     * so the view matches the visible frames instead of stretching them.
     * This only queries characteristics; it does not open the camera. A target
     * with a non-positive height falls back to a 4:3 target.
     */
    fun selectPreviewSize(cameraId: String, target: Size): Size {
        val targetAspect = if (target.height > 0) {
            target.width.toFloat() / target.height
        } else {
            DEFAULT_ASPECT
        }
        val chosen = pickPreviewSize(cameraId, targetAspect)
        chosenPreviewSize = chosen.display
        return chosen.display
    }

    /** All real camera ids, including physical sensors behind logical cameras. */
    fun allCameraIds(): List<String> {
        val ids = LvglDeviceInfo.collectCameraIds(manager).toList().sorted()
        /* One INFO line per probe: DEBUG is stripped on the device, and the id
         * list is needed to verify tap-to-cycle. */
        Log.i(TAG, "Camera ids: ${ids.joinToString { describeCamera(it) }}")
        return ids
    }

    /** Human-readable camera facts for diagnostics (facing, physical ids). */
    internal fun describeCamera(cameraId: String): String = try {
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val facing = when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_FRONT -> "front"
            CameraCharacteristics.LENS_FACING_BACK -> "back"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
            else -> "unknown"
        }
        val physical = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            characteristics.getPhysicalCameraIds().joinToString().ifBlank { "-" }
        } else {
            "-"
        }
        "camera $cameraId facing=$facing physical=[$physical]"
    } catch (t: Throwable) {
        "camera $cameraId characteristics unavailable (${t.javaClass.simpleName})"
    }

    /**
     * Opens [cameraId] and starts a preview. If [startPreview] was called first,
     * frames go straight to that surface; otherwise an internal ImageReader is
     * used. Reopening releases the previous device first.
     *
     * @param cameraId the Camera2 id to open; defaults to the first camera (`"0"`).
     * @throws SecurityException when the CAMERA permission has not been granted.
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    fun open(cameraId: String = "0") {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("CAMERA permission is required to open the camera")
        }
        synchronized(lock) {
            releaseSessionLocked()
            cameraDevice?.close()
            cameraDevice = null
            openedCameraId = cameraId
        }
        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    var accepted = false
                    synchronized(lock) {
                        if (openedCameraId == cameraId) {
                            cameraDevice = camera
                            accepted = true
                            val surface = pendingSurface
                            if (surface != null && surface.isValid) {
                                createSessionLocked(camera, surface)
                            } else {
                                startPreviewInternalLocked(cameraId)
                            }
                        }
                    }
                    if (!accepted) {
                        camera.close()
                        return
                    }
                    Log.i(TAG, "Camera opened: $cameraId")
                    onOpenResult?.invoke(cameraId, true)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    synchronized(lock) {
                        if (cameraDevice === camera) cameraDevice = null
                        camera.close()
                    }
                    Log.e(TAG, "Camera error: $error for $cameraId")
                    onOpenResult?.invoke(cameraId, false)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    synchronized(lock) {
                        if (cameraDevice === camera) cameraDevice = null
                        camera.close()
                    }
                    Log.d(TAG, "Camera disconnected: $cameraId")
                }

                override fun onClosed(camera: CameraDevice) {
                    synchronized(lock) {
                        if (cameraDevice === camera) cameraDevice = null
                    }
                }
            }, null)
        } catch (t: CameraAccessException) {
            Log.e(TAG, "Cannot open camera $cameraId", t)
            onOpenResult?.invoke(cameraId, false)
        } catch (t: SecurityException) {
            Log.e(TAG, "Camera permission denied for $cameraId", t)
            onOpenResult?.invoke(cameraId, false)
        }
    }

    /**
     * Targets a caller-supplied [surface] with the repeating preview. Calling
     * this before [open] is the reliable order: the surface is applied when the
     * device opens, so no internal ImageReader session is created first.
     */
    fun startPreview(surface: Surface) {
        synchronized(lock) {
            /* A surface already destroyed by rotation or onPause must never
             * become the target of a new capture session. */
            if (!surface.isValid) return
            pendingSurface = surface
            val device = cameraDevice ?: return
            releaseSessionLocked()
            createSessionLocked(device, surface)
        }
    }

    /** Starts the repeating preview into an internal ImageReader. */
    private fun startPreviewInternalLocked(cameraId: String) {
        val device = cameraDevice ?: return
        val size = pickPreviewSize(cameraId).raw
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        reader.setOnImageAvailableListener({ r ->
            /* Drain and release; used only when no display surface was given. */
            r.acquireLatestImage()?.close()
        }, null)
        imageReader = reader
        createSessionLocked(device, reader.surface)
    }

    /**
     * Best-fit preview size for [cameraId]. The no-target overload keeps the
     * default [open] path working by aiming at the current display aspect.
     * Selection compares the EFFECTIVE display aspect of each raw YUV size,
     * because a portrait sensor rotates the buffers by 90 degrees on screen.
     */
    private fun pickPreviewSize(
        cameraId: String,
        targetAspect: Float = defaultTargetAspect()
    ): PickedPreviewSize {
        val characteristics = try {
            manager.getCameraCharacteristics(cameraId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query camera characteristics for $cameraId", e)
            null
        }
        val sensorOrientation = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val deviceRotation = deviceRotationDegrees()
        val relativeRotation = PreviewOrientation.relativeRotation(sensorOrientation, deviceRotation)

        val candidates = try {
            characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888)
                ?.map { CameraSize(it.width, it.height) }
                ?.filter { it.width > 0 && it.height > 0 }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query preview sizes", e)
            null
        }
        if (candidates.isNullOrEmpty()) {
            Log.w(TAG, "No YUV_420_888 output sizes for camera $cameraId; using 640x480")
            val fallback = CameraSize(640, 480)
            val display = PreviewOrientation.displaySizeForOrientation(fallback, relativeRotation)
            return PickedPreviewSize(
                Size(fallback.width, fallback.height),
                Size(display.width, display.height)
            )
        }
        val raw = candidates[PreviewSizeSelector.bestFitIndex(candidates, targetAspect, relativeRotation)]
        val display = PreviewOrientation.displaySizeForOrientation(raw, relativeRotation)
        val effectiveAspect = "%.4f".format(Locale.US, display.aspect)
        val wantedAspect = "%.4f".format(Locale.US, targetAspect)
        Log.i(
            TAG,
            "Preview camera=$cameraId sensorOrientation=$sensorOrientation " +
                "deviceRotation=$deviceRotation relativeRotation=$relativeRotation " +
                "chosenRaw=${raw.width}x${raw.height} display=${display.width}x${display.height} " +
                "effectiveAspect=$effectiveAspect targetAspect=$wantedAspect"
        )
        return PickedPreviewSize(
            Size(raw.width, raw.height),
            Size(display.width, display.height)
        )
    }

    /** Device rotation in degrees, derived from the current display. */
    @Suppress("DEPRECATION")
    private fun deviceRotationDegrees(): Int {
        val rotation = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display.rotation
            } else {
                (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                    ?.defaultDisplay?.rotation ?: Surface.ROTATION_0
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot read display rotation; assuming portrait", t)
            Surface.ROTATION_0
        }
        return when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private fun defaultTargetAspect(): Float {
        val metrics = context.resources.displayMetrics
        return if (metrics.heightPixels > 0) {
            metrics.widthPixels.toFloat() / metrics.heightPixels
        } else {
            DEFAULT_ASPECT
        }
    }

    private fun createSessionLocked(device: CameraDevice, surface: Surface) {
        if (!surface.isValid) return
        try {
            device.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        var started = false
                        synchronized(lock) {
                            if (cameraDevice !== device || !surface.isValid) {
                                session.close()
                                return@synchronized
                            }
                            captureSession = session
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            request.addTarget(surface)
                            session.setRepeatingRequest(request.build(), null, null)
                            started = true
                        }
                        if (started) {
                            Log.i(TAG, "Preview started on ${openedCameraId ?: "camera"}")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Preview configuration failed on ${openedCameraId ?: "camera"}")
                        session.close()
                    }

                    override fun onClosed(session: CameraCaptureSession) {
                        synchronized(lock) {
                            if (captureSession === session) captureSession = null
                        }
                    }
                },
                null
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Capture session creation failed", t)
        }
    }

    private fun releaseSessionLocked() {
        try {
            captureSession?.stopRepeating()
        } catch (t: Throwable) {
            Log.w(TAG, "stopRepeating failed", t)
        }
        try {
            captureSession?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "captureSession.close failed", t)
        }
        captureSession = null
        try {
            imageReader?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "imageReader.close failed", t)
        }
        imageReader = null
    }

    /**
     * Stops the repeating request and releases the capture session and the
     * internal [ImageReader]. Safe before [open] and safe to call twice.
     */
    fun stopPreview() {
        synchronized(lock) {
            releaseSessionLocked()
            pendingSurface = null
        }
    }

    /** Stops the preview and closes the camera device. */
    fun close() {
        synchronized(lock) {
            releaseSessionLocked()
            cameraDevice?.close()
            cameraDevice = null
            openedCameraId = null
        }
    }

    companion object {
        private const val TAG = "LvglCameraPreview"
        private const val DEFAULT_ASPECT = 4f / 3f
    }
}

/** Android-free size value used by the pure selection helper below. */
internal data class CameraSize(val width: Int, val height: Int) {
    internal val aspect: Float
        get() = width.toFloat() / height
}

/** Raw YUV output size paired with its effective on-screen size. */
private data class PickedPreviewSize(val raw: Size, val display: Size)

/**
 * Pure sensor/display rotation math, kept free of Android types so it can be
 * unit tested on the JVM. Camera2 reports YUV sizes in sensor coordinates;
 * a 90/270 relative rotation swaps the displayed width and height.
 */
internal object PreviewOrientation {
    /** Rotation applied to the sensor buffers to match the device orientation. */
    fun relativeRotation(sensorOrientation: Int, deviceRotationDegrees: Int): Int {
        val rotation = (sensorOrientation - deviceRotationDegrees) % 360
        return if (rotation < 0) rotation + 360 else rotation
    }

    /** Size the frames occupy on screen, after the relative rotation. */
    fun displaySizeForOrientation(size: CameraSize, relativeRotation: Int): CameraSize {
        val rotation = ((relativeRotation % 360) + 360) % 360
        return if (rotation == 90 || rotation == 270) {
            CameraSize(size.height, size.width)
        } else {
            CameraSize(size.width, size.height)
        }
    }

    /** Aspect ratio of the rotated frames, which is what a surface must match. */
    fun displayAspect(size: CameraSize, relativeRotation: Int): Float =
        displaySizeForOrientation(size, relativeRotation).aspect
}

/**
 * Pure aspect-ratio matching, kept free of Android types so it can be unit
 * tested on the JVM. Ties (equal aspect delta) prefer the larger area, which
 * gives the sharpest preview among equally well-fitting sensor outputs.
 */
internal object PreviewSizeSelector {
    private const val EPSILON = 1e-4f

    fun bestFitIndex(
        candidates: List<CameraSize>,
        targetAspect: Float,
        relativeRotation: Int = 0
    ): Int {
        var bestIndex = 0
        var bestDelta = Float.POSITIVE_INFINITY
        var bestArea = -1L
        candidates.forEachIndexed { index, size ->
            val delta = abs(PreviewOrientation.displayAspect(size, relativeRotation) - targetAspect)
            val area = size.width.toLong() * size.height
            if (delta < bestDelta - EPSILON ||
                (abs(delta - bestDelta) <= EPSILON && area > bestArea)
            ) {
                bestDelta = delta
                bestArea = area
                bestIndex = index
            }
        }
        return bestIndex
    }
}
