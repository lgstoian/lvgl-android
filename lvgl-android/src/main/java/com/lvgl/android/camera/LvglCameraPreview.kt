// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.camera

import android.content.Context
import android.util.Log
import android.view.Surface

class LvglCameraPreview(private val context: Context) {

    private var cameraDevice: android.hardware.camera2.CameraDevice? = null
    private var captureSession: android.hardware.camera2.CameraCaptureSession? = null

    fun open(cameraId: String = "0") {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            manager.openCamera(cameraId, object : android.hardware.camera2.CameraDevice.StateCallback() {
                override fun onOpened(camera: android.hardware.camera2.CameraDevice) {
                    cameraDevice = camera
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

    fun startPreview(surface: Surface) {
        val device = cameraDevice ?: return
        val builder = device.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW)
        builder.addTarget(surface)

        device.createCaptureSession(listOf(surface), object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                captureSession = session
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

