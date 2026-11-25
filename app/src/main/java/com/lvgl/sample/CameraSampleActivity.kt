// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.sample

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.lvgl.android.activity.LvglActivity
import com.lvgl.android.camera.LvglCameraPreview
import com.lvgl.android.navigation.LvglNavigation
import kotlin.math.roundToInt

/**
 * Demonstrates a real Camera2 preview composited above the LVGL camera screen.
 *
 * LvglActivity subclasses must NOT call setContentView() (see ai-context.md);
 * the preview is an addContentView() overlay that exists only on this screen.
 * Status text is rendered inside LVGL through the engine's status text API.
 */
class CameraSampleActivity : LvglActivity() {

    private lateinit var preview: LvglCameraPreview
    private var previewView: SurfaceView? = null
    private var hintView: TextView? = null
    private var previewSurface: Surface? = null
    private var cameraIds: List<String> = emptyList()
    private var cameraIndex = 0

    override fun onCreateLvgl() {
        LvglNavigation.pushScreen("CameraSample")
        setLvglScreen(4)
        setStatus(getString(R.string.camera_status_idle))

        preview = LvglCameraPreview(this)
        preview.onOpenResult = { id, opened ->
            runOnUiThread { onOpenResult(id, opened) }
        }
        checkCameraPermission()
    }

    private fun permissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun setStatus(text: String) {
        com.lvgl.android.engine.LvglEngine.instance.setStatusText(text)
    }

    private fun checkCameraPermission() {
        if (permissionGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST
            )
            setStatus(getString(R.string.camera_status_requesting))
        }
    }

    private fun startCamera() {
        if (!permissionGranted()) return
        try {
            cameraIds = preview.allCameraIds()
        } catch (e: Exception) {
            setStatus(getString(R.string.camera_status_error, e.message ?: ""))
            Log.e(TAG, "Camera id probe failed", e)
            return
        }
        if (cameraIds.isEmpty()) {
            removePreviewOverlay()
            setStatus(getString(R.string.camera_status_none))
            refreshDeviceInfo()
            return
        }
        cameraIndex = cameraIndex.coerceIn(cameraIds.indices)
        addPreviewOverlay()
        openCurrentCamera()
        refreshDeviceInfo()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    /** Adds the live preview in the empty band above the LVGL status cards. */
    private fun addPreviewOverlay() {
        if (previewView?.parent != null) return
        val view = SurfaceView(this).apply {
            /* The LVGL surface sits behind window content; a media overlay on
             * top of the window is required for camera frames to be visible. */
            setZOrderOnTop(true)
            setZOrderMediaOverlay(true)
            holder.setFormat(PixelFormat.RGBA_8888)
            isClickable = true
            setOnClickListener { switchCamera() }
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    previewSurface = holder.surface
                    if (permissionGranted()) preview.startPreview(holder.surface)
                }

                override fun surfaceChanged(
                    holder: SurfaceHolder, format: Int, width: Int, height: Int
                ) = Unit

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    previewSurface = null
                    preview.stopPreview()
                }
            })
        }
        val metrics = resources.displayMetrics
        addContentView(
            view,
            FrameLayout.LayoutParams(metrics.widthPixels - dp(48), dp(210)).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(112)
            }
        )
        previewView = view

        val hint = TextView(this).apply {
            text = getString(R.string.camera_hint)
            gravity = Gravity.CENTER
            textSize = 12f
        }
        addContentView(
            hint,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(326)
            }
        )
        hintView = hint
    }

    private fun removePreviewOverlay() {
        previewView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        previewView = null
        hintView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        hintView = null
        previewSurface = null
    }

    @SuppressLint("MissingPermission")
    private fun openCurrentCamera() {
        /* CAMERA was checked in checkCameraPermission() before this path runs. */
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val id = cameraIds.getOrNull(cameraIndex) ?: return
        previewSurface?.let { preview.startPreview(it) }
        setStatus(getString(R.string.camera_status_opening, cameraIndex + 1, cameraIds.size))
        try {
            preview.open(id)
        } catch (e: Exception) {
            setStatus(getString(R.string.camera_status_error, e.message ?: ""))
            Log.e(TAG, "Failed to open camera $id", e)
        }
    }

    private fun onOpenResult(id: String, opened: Boolean) {
        if (opened) {
            setStatus(getString(R.string.camera_status_opened, cameraIndex + 1, cameraIds.size))
            Log.d(TAG, "Live preview on camera $id")
        } else {
            setStatus(getString(R.string.camera_status_unavailable, id))
        }
    }

    private fun switchCamera() {
        if (cameraIds.size < 2) {
            setStatus(getString(R.string.camera_status_single))
            return
        }
        preview.close()
        cameraIndex = (cameraIndex + 1) % cameraIds.size
        openCurrentCamera()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                removePreviewOverlay()
                setStatus(getString(R.string.camera_status_denied))
            }
            /* CameraManager.cameraIdList can be restricted until the permission
             * is granted; re-collect so the dashboard cannot show a false zero. */
            refreshDeviceInfo()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::preview.isInitialized || !permissionGranted()) return
        if (cameraIds.isEmpty()) {
            startCamera()
        } else if (previewView?.parent == null) {
            addPreviewOverlay()
            openCurrentCamera()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::preview.isInitialized) preview.stopPreview()
        removePreviewOverlay()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::preview.isInitialized) preview.close()
        removePreviewOverlay()
    }

    companion object {
        private const val TAG = "CameraSampleActivity"
        private const val CAMERA_PERMISSION_REQUEST = 1001
    }
}
