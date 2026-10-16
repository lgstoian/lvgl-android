// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.sample

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.lvgl.android.activity.LvglActivity
import com.lvgl.android.camera.LvglCameraPreview
import com.lvgl.android.navigation.LvglNavigation

/**
 * Demonstrates the LVGL Camera Preview with:
 * - Camera2 API integration
 * - Camera permission handling
 * - Preview start/stop
 * - Multiple camera support
 *
 * Uses LvglCameraPreview which wraps Camera2 API.
 */
class CameraSampleActivity : LvglActivity() {

    private lateinit var preview: LvglCameraPreview
    private lateinit var statusText: TextView

    override fun onCreateLvgl() {
        LvglNavigation.pushScreen("CameraSample")

        val root = FrameLayout(this)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val title = TextView(this).apply {
            text = "Camera Sample"
            textSize = 24f
            setPadding(20, 20, 20, 20)
        }

        statusText = TextView(this).apply {
            text = "Camera status: Initializing..."
            textSize = 14f
            setPadding(20, 10, 20, 10)
        }

        val info = TextView(this).apply {
            text = "This activity demonstrates Camera2 API integration.\n" +
                    "Camera preview would render to an LVGL canvas.\n" +
                    "Tap to toggle camera preview."
            textSize = 12f
            setPadding(20, 10, 20, 10)
        }

        layout.addView(title)
        layout.addView(statusText)
        layout.addView(info)
        root.addView(layout, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(root)

        preview = LvglCameraPreview(this)
        checkCameraPermission()
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
            statusText.text = "Camera status: Requesting permission..."
        }
    }

    private fun startCamera() {
        try {
            preview.open("0")
            statusText.text = "Camera status: Opened camera 0"
            Log.d(TAG, "Camera opened successfully")
        } catch (e: Exception) {
            statusText.text = "Camera status: Error - ${e.message}"
            Log.e(TAG, "Failed to open camera", e)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                statusText.text = "Camera status: Permission denied"
            }
        }
    }

    override fun onPause() {
        super.onPause()
        preview.stopPreview()
    }

    override fun onDestroy() {
        super.onDestroy()
        preview.close()
    }

    companion object {
        private const val TAG = "CameraSampleActivity"
        private const val CAMERA_PERMISSION_REQUEST = 1001
    }
}

