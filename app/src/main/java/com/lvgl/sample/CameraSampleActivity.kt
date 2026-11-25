// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.sample

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.util.Log
import android.util.Size
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
    private var previewAspect = 4f / 3f
    /** False while paused/stopped so late surface callbacks cannot restart frames. */
    private var previewActive = false

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
        previewActive = true
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

    /** Pixel bounds of the fixed preview band above the LVGL status cards. */
    private fun previewBand(): Pair<Int, Int> =
        (resources.displayMetrics.widthPixels - dp(48)) to dp(180)

    /** Fits [aspect] inside [band] (letterbox/pillarbox) without stretching. */
    private fun fitPreview(aspect: Float, band: Pair<Int, Int>): Pair<Int, Int> {
        val (bandWidth, bandHeight) = band
        if (aspect <= 0f || !aspect.isFinite() || bandWidth <= 0 || bandHeight <= 0) {
            return band
        }
        return if (bandWidth.toFloat() / bandHeight <= aspect) {
            bandWidth to (bandWidth / aspect).roundToInt().coerceIn(1, bandHeight)
        } else {
            (bandHeight * aspect).roundToInt().coerceIn(1, bandWidth) to bandHeight
        }
    }

    /** Re-fits the overlay for the effective display aspect, centred in the band. */
    private fun applyPreviewLayout() {
        val view = previewView ?: return
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return
        val band = previewBand()
        val (width, height) = fitPreview(previewAspect, band)
        val topMargin = dp(112) + (band.second - height) / 2
        if (params.width == width && params.height == height && params.topMargin == topMargin) {
            return
        }
        params.width = width
        params.height = height
        params.topMargin = topMargin
        view.layoutParams = params
        Log.i(TAG, "Preview view ${width}x${height} px aspect=$previewAspect band=${band.first}x${band.second}")
    }

    /** Re-selects the sensor-matched output size and re-fits the preview view. */
    private fun refreshPreviewSize(cameraId: String) {
        val (bandWidth, bandHeight) = previewBand()
        if (bandWidth <= 0 || bandHeight <= 0) return
        val chosen = preview.selectPreviewSize(cameraId, Size(bandWidth, bandHeight))
        /* selectPreviewSize returns the EFFECTIVE on-screen size: the raw YUV
         * size rotated by SENSOR_ORIENTATION vs the current device rotation.
         * Letterboxing that aspect keeps the overlay un-stretched and oriented
         * like the phone (portrait frames in portrait, landscape in landscape). */
        previewAspect = if (chosen.height > 0) {
            chosen.width.toFloat() / chosen.height
        } else {
            4f / 3f
        }
        applyPreviewLayout()
    }

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
                    /* A callback that arrives after onPause must not open a
                     * session against a surface that is already going away. */
                    if (previewActive && permissionGranted()) {
                        preview.startPreview(holder.surface)
                    }
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
            FrameLayout.LayoutParams(metrics.widthPixels - dp(48), dp(180)).apply {
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
                topMargin = dp(296)
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
        refreshPreviewSize(id)
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
        if (!previewActive || isFinishing || isDestroyed) return
        if (opened) {
            setStatus(getString(R.string.camera_status_opened, cameraIndex + 1, cameraIds.size))
            Log.i(TAG, "Live preview on camera $id")
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::preview.isInitialized || !permissionGranted()) return
        val id = cameraIds.getOrNull(cameraIndex) ?: return
        /* Manifest routes rotation/density here instead of recreating the
         * activity, so the band size and sensor aspect must be recomputed. */
        refreshPreviewSize(id)
    }

    override fun onResume() {
        super.onResume()
        previewActive = true
        if (!::preview.isInitialized || !permissionGranted()) return
        if (cameraIds.isEmpty()) {
            startCamera()
        } else if (previewView?.parent == null) {
            addPreviewOverlay()
            openCurrentCamera()
        } else {
            /* A rotation/configuration change can land while paused. Re-fit
             * against the current display before rebinding the surface, so a
             * stale portrait/landscape aspect never survives the resume. */
            cameraIds.getOrNull(cameraIndex)?.let { refreshPreviewSize(it) }
            /* The surface can arrive while the activity is still starting,
             * before previewActive was true. Bind it now so the session targets
             * the overlay instead of the internal ImageReader. */
            previewSurface?.let { preview.startPreview(it) }
        }
    }

    /** Stops frames and releases the device; idempotent across lifecycle stops. */
    private fun stopCameraPreview() {
        previewActive = false
        previewSurface = null
        if (::preview.isInitialized) {
            preview.stopPreview()
            preview.close()
        }
        removePreviewOverlay()
    }

    override fun onPause() {
        super.onPause()
        stopCameraPreview()
    }

    override fun onStop() {
        super.onStop()
        stopCameraPreview()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCameraPreview()
    }

    companion object {
        private const val TAG = "CameraSampleActivity"
        private const val CAMERA_PERMISSION_REQUEST = 1001
    }
}
