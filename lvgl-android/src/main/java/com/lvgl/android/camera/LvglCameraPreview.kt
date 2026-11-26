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
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.lvgl.android.system.LvglDeviceInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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
 *
 * The capture session always includes a JPEG [ImageReader] alongside the preview
 * surface. [captureStillJpeg] therefore submits one non-repeating
 * `TEMPLATE_STILL_CAPTURE` request on the *same* session: the repeating preview
 * is never torn down and no session reconfiguration flicker occurs. Image reads
 * and capture callbacks run on a dedicated handler thread, never the UI thread.
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

    /** JPEG target kept inside the preview session so a still never stops it. */
    @Volatile
    private var stillReader: ImageReader? = null

    /** Handler for JPEG reads and still-capture callbacks (off the UI thread). */
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    /** The single capture awaiting an image; guarded by [lock]. */
    @Volatile
    private var pendingStill: PendingStill? = null

    /** Sensor facts cached when the current camera was opened. */
    @Volatile
    private var cachedSensorFacts: SensorFacts? = null

    /** Most recent streaming crop region; null until a capture result arrives. */
    @Volatile
    private var lastCropSize: CameraSize? = null

    /** Camera id [lastCropSize] belongs to; prevents a stale crop after switching. */
    @Volatile
    private var lastCropCameraId: String? = null

    @Volatile
    private var aspectInfo: DisplayAspectInfo = DisplayAspectInfo(DEFAULT_ASPECT, 4, 3, 0)

    @Volatile
    private var displayAspectListener: ((aspect: Float, width: Int, height: Int) -> Unit)? = null

    @Volatile
    private var lastViewAspect: Float = DEFAULT_ASPECT

    /** Notified on the Camera2 callback thread when an open attempt settles. */
    var onOpenResult: ((cameraId: String, opened: Boolean) -> Unit)? = null

    /**
     * Real display aspect of the active camera, derived from the sensor active
     * array (`CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE`) and, once
     * streaming, from the live `CaptureResult.SCALER_CROP_REGION`. The value
     * already accounts for `SENSOR_ORIENTATION` and the current device rotation.
     *
     * Unlike the aspect of a YUV [chosenPreviewSize], this value does not depend
     * on `getOutputSizes()`: some devices report only square YUV sizes although
     * the sensor itself is not square. Use this to size a preview surface.
     */
    val displayAspect: Float
        get() = aspectInfo.aspect

    /**
     * Installs (or clears, with null) the listener for runtime aspect changes.
     * The current [displayAspect] is delivered immediately on installation.
     * Later calls may arrive on the Camera2 callback thread (or on the calling
     * thread when [selectPreviewSize] refreshes the aspect), so a caller that
     * touches views must hop to the UI thread first. Values are emitted only
     * when the derived aspect or its display dimensions change (rotation,
     * zoom/crop or camera switch).
     *
     * @param listener receives the real aspect and its display-oriented width
     * and height in pixels, or null to remove the listener.
     */
    fun setDisplayAspectListener(listener: ((aspect: Float, width: Int, height: Int) -> Unit)?) {
        displayAspectListener = listener
        if (listener != null) {
            val current = aspectInfo
            listener(current.aspect, current.width, current.height)
        }
    }

    /**
     * Effective on-screen size of the most recent [selectPreviewSize] choice.
     * Camera2 delivers YUV buffers in sensor coordinates, so this is the raw
     * output size rotated by the camera's relative rotation. On devices whose
     * YUV output sizes are all square this may NOT match the real sensor aspect;
     * use [displayAspect] to letterbox the preview surface.
     */
    @Volatile
    var chosenPreviewSize: Size? = null
        private set

    /**
     * Picks the camera output size whose DISPLAY aspect best fits the REAL
     * sensor/crop aspect of [cameraId] (not merely the view aspect) and remembers
     * its rotated (effective) size in [chosenPreviewSize], which is also
     * returned. SENSOR_ORIENTATION and the current device rotation decide
     * whether width/height are swapped relative to the raw YUV size.
     *
     * Also refreshes [displayAspect] from the sensor active array (or the last
     * streaming crop region), so callers can size their surface from the same
     * source. This only queries characteristics; it does not open the camera.
     * A target with a non-positive height falls back to a 4:3 view aspect.
     */
    fun selectPreviewSize(cameraId: String, target: Size): Size {
        lastViewAspect = if (target.height > 0) {
            target.width.toFloat() / target.height
        } else {
            DEFAULT_ASPECT
        }
        val chosen = pickPreviewSize(cameraId, lastViewAspect)
        chosen.aspectInfo?.let { publishAspect(it, "source=characteristics") }
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
        val facts = sensorFacts(cameraId)
        synchronized(lock) {
            releaseSessionLocked()
            cameraDevice?.close()
            cameraDevice = null
            openedCameraId = cameraId
            cachedSensorFacts = facts
            lastCropSize = null
            lastCropCameraId = null
        }
        /* A capture still waiting on the previous device can never complete. */
        deliverStill(null)
        facts.aspectInfo?.let { publishAspect(it, "source=characteristics camera=$cameraId") }
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
                                createSessionLocked(cameraId, camera, surface)
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
            val cameraId = openedCameraId ?: return
            releaseSessionLocked()
            createSessionLocked(cameraId, device, surface)
        }
    }

    /** Starts the repeating preview into an internal ImageReader. */
    private fun startPreviewInternalLocked(cameraId: String) {
        val device = cameraDevice ?: return
        val picked = pickPreviewSize(cameraId, lastViewAspect)
        val size = picked.raw
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        reader.setOnImageAvailableListener({ r ->
            /* Drain and release; used only when no display surface was given. */
            r.acquireLatestImage()?.close()
        }, null)
        imageReader = reader
        createSessionLocked(cameraId, device, reader.surface)
    }

    /**
     * Captures one JPEG still from the currently open camera.
     *
     * The repeating preview keeps running: the still is a single
     * `TEMPLATE_STILL_CAPTURE` request targeting the JPEG reader that is already
     * part of the preview session. [JPEG_ORIENTATION][CaptureRequest.JPEG_ORIENTATION]
     * is derived from `SENSOR_ORIENTATION` and the current device rotation (front
     * cameras are mirrored), so the returned bytes carry the correct EXIF
     * orientation and need no Bitmap decode to display upright.
     *
     * @param onResult called exactly once, on a Camera2 handler thread, with the
     * captured bytes or null when no preview is active, a capture is already in
     * flight, or the request fails. Callers touching views must hop to the UI
     * thread first.
     * @throws SecurityException when the CAMERA permission has not been granted.
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    fun captureStillJpeg(onResult: (StillCaptureResult?) -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("CAMERA permission is required to capture a still")
        }
        val setup = synchronized(lock) {
            val device = cameraDevice
            val id = openedCameraId
            val session = captureSession
            val reader = stillReader
            if (device == null || id == null || session == null || reader == null ||
                pendingStill != null
            ) {
                null
            } else {
                StillSetup(device, id, session, reader)
            }
        }
        if (setup == null) {
            Log.w(TAG, "Still capture unavailable: preview inactive or capture in flight")
            onResult(null)
            return
        }
        val facts = cachedSensorFacts?.takeIf { it.cameraId == setup.cameraId }
            ?: sensorFacts(setup.cameraId)
        val facingFront = facts.characteristics?.get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_FRONT
        val rotation = CaptureOrientation.jpegOrientation(
            facts.sensorOrientation, deviceRotationDegrees(), facingFront
        )
        synchronized(lock) {
            pendingStill = PendingStill(onResult, rotation, setup.cameraId)
        }
        try {
            val request = setup.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                .apply {
                    addTarget(setup.reader.surface)
                    set(CaptureRequest.JPEG_ORIENTATION, rotation)
                }
            setup.session.capture(
                request.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        Log.e(TAG, "Still capture failed: ${failure.reason}")
                        deliverStill(null)
                    }

                    override fun onCaptureBufferLost(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        target: Surface,
                        frameNumber: Long
                    ) {
                        Log.e(TAG, "Still capture buffer lost on frame $frameNumber")
                        deliverStill(null)
                    }
                },
                captureHandlerLocked()
            )
            Log.i(TAG, "Still capture requested camera=${setup.cameraId} jpegOrientation=$rotation")
        } catch (t: Throwable) {
            Log.e(TAG, "Still capture request failed", t)
            deliverStill(null)
        }
    }

    /** Copies one JPEG buffer and hands it to the waiting caller. */
    private fun onStillImageAvailable(reader: ImageReader, cameraId: String) {
        val image = reader.acquireLatestImage() ?: return
        val width = image.width
        val height = image.height
        val bytes = try {
            val buffer = image.planes[0].buffer
            val copy = ByteArray(buffer.remaining())
            buffer.get(copy)
            copy
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to copy JPEG bytes", t)
            null
        } finally {
            image.close()
        }
        val pending = synchronized(lock) { pendingStill }
        if (pending == null) {
            Log.w(TAG, "Dropping unrequested still image on $cameraId")
            return
        }
        if (bytes == null) {
            deliverStill(null)
            return
        }
        Log.i(
            TAG,
            "Still captured camera=$cameraId jpeg=${width}x${height} bytes=${bytes.size} " +
                "jpegOrientation=${pending.rotation}"
        )
        deliverStill(StillCaptureResult(bytes, pending.rotation, cameraId, width, height))
    }

    /** Completes the pending capture exactly once, clearing the in-flight flag. */
    private fun deliverStill(result: StillCaptureResult?) {
        val pending = synchronized(lock) {
            val current = pendingStill
            pendingStill = null
            current
        } ?: return
        pending.callback(result)
    }

    /**
     * Best-fit preview size for [cameraId], using the REAL sensor/crop aspect.
     *
     * The selection target is the display aspect of the sensor active array (or
     * of the last streaming crop region) - not the caller's view aspect and not
     * the aspect of any YUV output size. Some sensors report only square YUV
     * sizes although the active array is not square. Candidates are still
     * compared in display orientation, because a portrait sensor rotates the
     * buffers by 90 degrees on screen.
     */
    private fun pickPreviewSize(
        cameraId: String,
        viewAspect: Float = defaultTargetAspect()
    ): PickedPreviewSize {
        val facts = sensorFacts(cameraId)
        val crop = if (lastCropCameraId == cameraId) lastCropSize else null
        val region = crop ?: CameraSize(facts.activeWidth, facts.activeHeight)
        val realInfo = DisplayAspectMath.fromRegion(
            region.width, region.height, facts.sensorOrientation, facts.deviceRotation
        )
        val targetAspect = realInfo?.aspect ?: viewAspect
        val relativeRotation = facts.relativeRotation

        val candidates = try {
            facts.characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888)
                ?.map { CameraSize(it.width, it.height) }
                ?.filter { it.width > 0 && it.height > 0 }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query preview sizes", e)
            null
        }
        val cropLabel = crop?.let { "${it.width}x${it.height}" } ?: "none"
        val chosen = if (candidates.isNullOrEmpty()) {
            Log.w(TAG, "No YUV_420_888 output sizes for camera $cameraId; using 640x480")
            CameraSize(640, 480)
        } else {
            candidates[PreviewSizeSelector.bestFitIndex(candidates, targetAspect, relativeRotation)]
        }
        val display = PreviewOrientation.displaySizeForOrientation(chosen, relativeRotation)
        Log.i(
            TAG,
            "Preview camera=$cameraId sensorOrientation=${facts.sensorOrientation} " +
                "deviceRotation=${facts.deviceRotation} relativeRotation=$relativeRotation " +
                "activeArray=${facts.activeWidth}x${facts.activeHeight} cropRegion=$cropLabel " +
                "outputSize=${chosen.width}x${chosen.height} " +
                "realAspect=${"%.4f".format(Locale.US, targetAspect)} " +
                "viewAspect=${"%.4f".format(Locale.US, viewAspect)}"
        )
        return PickedPreviewSize(
            Size(chosen.width, chosen.height),
            Size(display.width, display.height),
            realInfo
        )
    }

    /** Reads static sensor facts once; no capture data is available here. */
    private fun sensorFacts(cameraId: String): SensorFacts {
        val characteristics = try {
            manager.getCameraCharacteristics(cameraId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query camera characteristics for $cameraId", e)
            null
        }
        val sensorOrientation = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val deviceRotation = deviceRotationDegrees()
        val relativeRotation = PreviewOrientation.relativeRotation(sensorOrientation, deviceRotation)
        val active = characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val activeWidth = active?.width() ?: 0
        val activeHeight = active?.height() ?: 0
        return SensorFacts(
            cameraId,
            characteristics,
            sensorOrientation,
            deviceRotation,
            relativeRotation,
            activeWidth,
            activeHeight,
            DisplayAspectMath.fromRegion(
                activeWidth, activeHeight, sensorOrientation, deviceRotation
            )
        )
    }

    /** Publishes a new real aspect, deduplicating frame-by-frame callbacks. */
    private fun publishAspect(info: DisplayAspectInfo, detail: String) {
        val previous = aspectInfo
        if (previous.aspect == info.aspect &&
            previous.width == info.width && previous.height == info.height
        ) {
            return
        }
        aspectInfo = info
        Log.i(
            TAG,
            "Display aspect $detail realAspect=${"%.4f".format(Locale.US, info.aspect)} " +
                "display=${info.width}x${info.height} " +
                "relativeRotation=${info.relativeRotation} " +
                "viewAspect=${"%.4f".format(Locale.US, lastViewAspect)}"
        )
        displayAspectListener?.invoke(info.aspect, info.width, info.height)
    }

    /** Reads streaming crop/active-array data for the repeating preview. */
    private fun createCaptureCallback(
        cameraId: String,
        facts: SensorFacts
    ): CameraCaptureSession.CaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            if (openedCameraId != cameraId) return
            /* Android's CaptureResult has no active-array key: the static array
             * is read from CameraCharacteristics (see sensorFacts) and the live
             * visible region is the capture crop region, which changes with zoom. */
            val crop = try {
                result.get(CaptureResult.SCALER_CROP_REGION)
            } catch (t: Throwable) {
                Log.w(TAG, "Cannot read capture crop region", t)
                null
            }
            val region = if (crop != null && crop.width() > 0 && crop.height() > 0) {
                CameraSize(crop.width(), crop.height())
            } else {
                CameraSize(facts.activeWidth, facts.activeHeight)
            }
            if (region.width <= 0 || region.height <= 0) return
            lastCropSize = region
            lastCropCameraId = cameraId
            val rotation = deviceRotationDegrees()
            val info = DisplayAspectMath.fromRegion(
                region.width, region.height, facts.sensorOrientation, rotation
            ) ?: return
            publishAspect(
                info,
                "source=capture camera=$cameraId sensorOrientation=${facts.sensorOrientation} " +
                    "deviceRotation=$rotation " +
                    "activeArray=${facts.activeWidth}x${facts.activeHeight} " +
                    "cropRegion=${region.width}x${region.height}"
            )
        }
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

    @Suppress("DEPRECATION")
    private fun createSessionLocked(cameraId: String, device: CameraDevice, surface: Surface) {
        if (!surface.isValid) return
        val facts = cachedSensorFacts?.takeIf { it.cameraId == cameraId } ?: sensorFacts(cameraId)
        val jpegSize = jpegOutputSize(facts)
        val reader = try {
            ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
        } catch (t: Throwable) {
            Log.e(TAG, "Cannot create JPEG reader ${jpegSize.width}x${jpegSize.height}", t)
            return
        }
        reader.setOnImageAvailableListener(
            { r -> onStillImageAvailable(r, cameraId) },
            captureHandlerLocked()
        )
        try {
            stillReader?.close()
            stillReader = reader
            device.createCaptureSession(
                /* The JPEG surface is a second output of the SAME session as the
                 * repeating preview, so a still capture never tears the preview
                 * down. The reader is drained by the handler callback above. */
                listOf(surface, reader.surface),
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
                            session.setRepeatingRequest(
                                request.build(), createCaptureCallback(cameraId, facts), null
                            )
                            started = true
                        }
                        if (started) {
                            Log.i(TAG, "Preview started on $cameraId")
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
                captureHandlerLocked()
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Capture session creation failed", t)
            try {
                reader.close()
            } catch (closeError: Throwable) {
                Log.w(TAG, "JPEG reader.close failed", closeError)
            }
            if (stillReader === reader) stillReader = null
        }
    }

    /** Largest supported JPEG output; the still is never downscaled by default. */
    private fun jpegOutputSize(facts: SensorFacts): CameraSize {
        val candidates = try {
            facts.characteristics
                ?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.JPEG)
                ?.map { CameraSize(it.width, it.height) }
                ?.filter { it.width > 0 && it.height > 0 }
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot query JPEG output sizes", t)
            null
        }
        return CaptureSizing.largestByArea(candidates.orEmpty()) ?: CameraSize(1280, 960)
    }

    /** Lazily created handler so no camera/image callback ever runs on the UI thread. */
    private fun captureHandlerLocked(): Handler = synchronized(lock) {
        captureHandler ?: run {
            val thread = HandlerThread("LvglCameraCapture").apply { start() }
            val handler = Handler(thread.looper)
            captureThread = thread
            captureHandler = handler
            handler
        }
    }

    /** Quits the capture thread; a later [open] recreates it lazily. */
    private fun shutdownCaptureThread() {
        val (handler, thread) = synchronized(lock) {
            val h = captureHandler
            val t = captureThread
            captureHandler = null
            captureThread = null
            h to t
        }
        handler?.removeCallbacksAndMessages(null)
        thread?.quit()
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
        try {
            stillReader?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "stillReader.close failed", t)
        }
        stillReader = null
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
        deliverStill(null)
    }

    /** Stops the preview and closes the camera device. */
    fun close() {
        synchronized(lock) {
            releaseSessionLocked()
            cameraDevice?.close()
            cameraDevice = null
            openedCameraId = null
            cachedSensorFacts = null
            lastCropSize = null
            lastCropCameraId = null
        }
        deliverStill(null)
        shutdownCaptureThread()
    }

    companion object {
        private const val TAG = "LvglCameraPreview"
        internal const val DEFAULT_ASPECT = 4f / 3f
    }
}

/** Android-free size value used by the pure selection helper below. */
internal data class CameraSize(val width: Int, val height: Int) {
    internal val aspect: Float
        get() = width.toFloat() / height
}

/** Everything [captureStillJpeg] needs, captured atomically under [lock]. */
private data class StillSetup(
    val device: CameraDevice,
    val cameraId: String,
    val session: CameraCaptureSession,
    val reader: ImageReader
)

/** A capture awaiting its JPEG, with the orientation written into the request. */
private data class PendingStill(
    val callback: (StillCaptureResult?) -> Unit,
    val rotation: Int,
    val cameraId: String
)

/** Static camera facts needed to derive the real aspect, read once per camera. */
private data class SensorFacts(
    val cameraId: String,
    val characteristics: CameraCharacteristics?,
    val sensorOrientation: Int,
    val deviceRotation: Int,
    val relativeRotation: Int,
    val activeWidth: Int,
    val activeHeight: Int,
    val aspectInfo: DisplayAspectInfo?
)

/** Real display aspect derived from a sensor active array or streaming crop. */
internal data class DisplayAspectInfo(
    val aspect: Float,
    val width: Int,
    val height: Int,
    val relativeRotation: Int
)

/**
 * Pure aspect derivation from sensor regions, free of Android types so it can
 * be unit tested on the JVM. A preview surface must match the active array or
 * crop region, not the (possibly square) YUV output size.
 */
internal object DisplayAspectMath {
    /** Returns null when the region has a non-positive width or height. */
    fun fromRegion(
        regionWidth: Int,
        regionHeight: Int,
        sensorOrientation: Int,
        deviceRotationDegrees: Int
    ): DisplayAspectInfo? {
        if (regionWidth <= 0 || regionHeight <= 0) return null
        val rotation = PreviewOrientation.relativeRotation(sensorOrientation, deviceRotationDegrees)
        val display = PreviewOrientation.displaySizeForOrientation(
            CameraSize(regionWidth, regionHeight), rotation
        )
        return DisplayAspectInfo(display.aspect, display.width, display.height, rotation)
    }
}

/** Raw YUV output size, its effective on-screen size and the real sensor aspect. */
private data class PickedPreviewSize(
    val raw: Size,
    val display: Size,
    val aspectInfo: DisplayAspectInfo?
)

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

/**
 * One JPEG still and the orientation Camera2 wrote into its EXIF header.
 * The bytes need no Bitmap decode/rotate: a gallery honours [orientationDegrees].
 */
data class StillCaptureResult(
    val jpegBytes: ByteArray,
    val orientationDegrees: Int,
    val cameraId: String,
    val imageWidth: Int,
    val imageHeight: Int
)

/** Pure JPEG orientation math, free of Android types so it can be unit tested. */
internal object CaptureOrientation {
    /** Degrees the sensor must be rotated for the image to appear upright. */
    fun jpegOrientation(
        sensorOrientation: Int,
        deviceRotationDegrees: Int,
        facingFront: Boolean
    ): Int {
        val sensor = ((sensorOrientation % 360) + 360) % 360
        val device = ((deviceRotationDegrees % 360) + 360) % 360
        return if (facingFront) {
            (360 - ((sensor + device) % 360)) % 360
        } else {
            ((sensor - device) % 360 + 360) % 360
        }
    }
}

/** Pure JPEG size selection, free of Android types. */
internal object CaptureSizing {
    fun largestByArea(candidates: List<CameraSize>): CameraSize? =
        candidates.maxByOrNull { it.width.toLong() * it.height }
}

/**
 * Timestamped still filename shared with host apps that save captured bytes.
 * Pure JVM (no Android types) so the naming rule is unit testable.
 */
object CaptureNaming {
    const val JPEG_EXTENSION = ".jpg"

    fun timestampedJpegName(
        timestampMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        val format = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        format.timeZone = timeZone
        val millis = (timestampMillis % 1000 + 1000) % 1000
        return "IMG_" + format.format(Date(timestampMillis)) + "_" +
            millis.toString().padStart(3, '0') + JPEG_EXTENSION
    }
}
