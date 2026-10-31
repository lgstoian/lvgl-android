// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.sample

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.lvgl.android.activity.LvglActivity
import com.lvgl.android.camera.LvglCameraPreview
import com.lvgl.android.navigation.LvglNavigation

/**
 * Demonstrates the LVGL Camera Preview with Camera2 API integration.
 *
 * Note (see ai-context.md): LvglActivity subclasses must NOT call setContentView().
 * Camera status is rendered inside LVGL via the engine's status text API.
 */
class CameraSampleActivity : LvglActivity() {

    private lateinit var preview: LvglCameraPreview

    override fun onCreateLvgl() {
        LvglNavigation.pushScreen("CameraSample")
        setLvglScreen(4)
        com.lvgl.android.engine.LvglEngine.instance.setStatusText(getString(R.string.camera_status_idle))

        preview = LvglCameraPreview(this)
        checkCameraPermission()
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST
            )
            com.lvgl.android.engine.LvglEngine.instance.setStatusText(
                getString(R.string.camera_status_requesting)
            )
        }
    }

    private fun startCamera() {
        try {
            preview.open("0")
            com.lvgl.android.engine.LvglEngine.instance.setStatusText(
                getString(R.string.camera_status_opened)
            )
            Log.d(TAG, "Camera opened successfully")
        } catch (e: Exception) {
            com.lvgl.android.engine.LvglEngine.instance.setStatusText(
                getString(R.string.camera_status_error, e.message)
            )
            Log.e(TAG, "Failed to open camera", e)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                com.lvgl.android.engine.LvglEngine.instance.setStatusText(
                    getString(R.string.camera_status_denied)
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (::preview.isInitialized) preview.stopPreview()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::preview.isInitialized) preview.close()
    }

    companion object {
        private const val TAG = "CameraSampleActivity"
        private const val CAMERA_PERMISSION_REQUEST = 1001
    }
}
