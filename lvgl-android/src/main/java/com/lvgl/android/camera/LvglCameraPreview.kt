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
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.lvgl.android.system.LvglDeviceInfo

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

    /** All real camera ids, including physical sensors behind logical cameras. */
    fun allCameraIds(): List<String> {
        val ids = LvglDeviceInfo.collectCameraIds(manager).toList().sorted()
        ids.forEach { Log.d(TAG, describeCamera(it)) }
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
                            if (surface != null) {
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
                    Log.d(TAG, "Camera opened: $cameraId")
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
            pendingSurface = surface
            val device = cameraDevice ?: return
            releaseSessionLocked()
            createSessionLocked(device, surface)
        }
    }

    /** Starts the repeating preview into an internal ImageReader. */
    private fun startPreviewInternalLocked(cameraId: String) {
        val device = cameraDevice ?: return
        val size = pickPreviewSize(cameraId)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        reader.setOnImageAvailableListener({ r ->
            /* Drain and release; used only when no display surface was given. */
            r.acquireLatestImage()?.close()
        }, null)
        imageReader = reader
        createSessionLocked(device, reader.surface)
    }

    private fun pickPreviewSize(cameraId: String): Size = try {
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        map?.getOutputSizes(ImageFormat.YUV_420_888)?.firstOrNull() ?: Size(640, 480)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to query preview sizes", e)
        Size(640, 480)
    }

    private fun createSessionLocked(device: CameraDevice, surface: Surface) {
        try {
            device.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        synchronized(lock) {
                            if (cameraDevice !== device) {
                                session.close()
                                return@synchronized
                            }
                            captureSession = session
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            request.addTarget(surface)
                            session.setRepeatingRequest(request.build(), null, null)
                        }
                        Log.d(TAG, "Preview started on ${openedCameraId ?: "camera"}")
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
    }
}
