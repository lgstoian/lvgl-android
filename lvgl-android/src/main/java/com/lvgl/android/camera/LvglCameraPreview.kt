// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.camera

import android.content.Context
import android.graphics.ImageFormat
import android.media.ImageReader
import android.util.Log
import android.util.Size
import android.view.Surface

/**
 * Camera2 preview helper.
 *
 * The preview is started automatically once the camera opens. Because an LVGL
 * activity renders through its own SurfaceView, the frames are drained into an
 * internal [ImageReader] rather than a display surface; this keeps the capture
 * session running (and the status accurate) without fighting LVGL for the
 * window. Callers that do have a Surface can pass it to [startPreview].
 */
class LvglCameraPreview(private val context: Context) {

    private val manager = context.getSystemService(Context.CAMERA_SERVICE)
        as android.hardware.camera2.CameraManager

    private var cameraDevice: android.hardware.camera2.CameraDevice? = null
    private var captureSession: android.hardware.camera2.CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    fun open(cameraId: String = "0") {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            manager.openCamera(cameraId, object : android.hardware.camera2.CameraDevice.StateCallback() {
                override fun onOpened(camera: android.hardware.camera2.CameraDevice) {
                    cameraDevice = camera
                    startPreviewInternal(cameraId)
                    Log.d(TAG, "Camera opened: $cameraId")
                }

                override fun onError(camera: android.hardware.camera2.CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    cameraDevice = null
                }

                override fun onDisconnected(camera: android.hardware.camera2.CameraDevice) {
                    Log.d(TAG, "Camera disconnected")
                    cameraDevice = null
                    camera.close()
                }
            }, null)
        }
    }

    /** Start the repeating preview into an internal ImageReader. */
    private fun startPreviewInternal(cameraId: String) {
        val device = cameraDevice ?: return
        val size = pickPreviewSize(cameraId)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        reader.setOnImageAvailableListener({ r ->
            /* Drain and release; the frames are not displayed here. */
            r.acquireLatestImage()?.close()
        }, null)
        imageReader = reader
        startSession(device, reader.surface)
    }

    private fun pickPreviewSize(cameraId: String): Size {
        return try {
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(
                android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )
            val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)
            sizes?.firstOrNull() ?: Size(640, 480)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query preview sizes", e)
            Size(640, 480)
        }
    }

    /** Start the repeating preview into a caller-supplied Surface. */
    fun startPreview(surface: Surface) {
        val device = cameraDevice ?: return
        startSession(device, surface)
    }

    private fun startSession(device: android.hardware.camera2.CameraDevice, surface: Surface) {
        device.createCaptureSession(listOf(surface),
            object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                    captureSession = session
                    val builder = device.createCaptureRequest(
                        android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW
                    )
                    builder.addTarget(surface)
                    session.setRepeatingRequest(builder.build(), null, null)
                    Log.d(TAG, "Preview started")
                }

                override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {
                    Log.e(TAG, "Preview configuration failed")
                }
            }, null)
    }

    fun stopPreview() {
        captureSession?.stopRepeating()
        captureSession?.close()
        captureSession = null
        imageReader?.close()
        imageReader = null
    }

    fun close() {
        stopPreview()
        cameraDevice?.close()
        cameraDevice = null
    }

    companion object {
        private const val TAG = "LvglCameraPreview"
    }
}
