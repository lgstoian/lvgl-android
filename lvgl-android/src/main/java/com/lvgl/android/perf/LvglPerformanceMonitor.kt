// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.perf

import android.util.Log
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight frame-rate and frame-time monitor for LVGL rendering.
 *
 * Call [start] once rendering begins, [onFrameRendered] once per frame and
 * [stop] when rendering ends. The monitor logs one line per second with the
 * current FPS and frame-time statistics, and exposes the same values through
 * [getFps], [getFrameCount] and [getStats].
 *
 * The state is intentionally not synchronised: call every method from the same
 * thread (normally the LVGL render thread). It is a debugging aid, not a
 * production profiling tool.
 */
object LvglPerformanceMonitor {

    private val frameCount = AtomicLong(0)
    private var windowFrameCount = 0L
    private var lastFpsTime = SystemClock.elapsedRealtime()
    private var lastFps = 0
    private var frameTimeSum = 0L
    private var frameTimeCount = 0
    private var minFrameTime = Long.MAX_VALUE
    private var maxFrameTime = 0L
    private var lastFrameTime = SystemClock.elapsedRealtimeNanos()
    private var running = false
    private var startTime = 0L

    /** Starts a new measurement window and resets the cumulative frame count. */
    fun start() {
        running = true
        startTime = SystemClock.elapsedRealtime()
        lastFpsTime = startTime
        frameCount.set(0)
        Log.i(TAG, "Performance monitor started")
    }

    /** Stops the monitor and logs the total frame count. */
    fun stop() {
        running = false
        Log.i(TAG, "Performance monitor stopped. Total frames: ${frameCount.get()}")
    }

    /**
     * Records one rendered frame.
     *
     * Does nothing unless [start] has been called. Called once per frame from
     * the render thread.
     */
    fun onFrameRendered() {
        if (!running) return
        val now = SystemClock.elapsedRealtimeNanos()
        val frameTimeNs = now - lastFrameTime
        lastFrameTime = now
        val frameTimeMs = frameTimeNs / 1_000_000
        frameTimeSum += frameTimeMs
        frameTimeCount++
        if (frameTimeMs < minFrameTime) minFrameTime = frameTimeMs
        if (frameTimeMs > maxFrameTime) maxFrameTime = frameTimeMs
        frameCount.incrementAndGet()
        windowFrameCount++

        val elapsed = SystemClock.elapsedRealtime() - lastFpsTime
        if (elapsed >= 1000) {
            /* Count only the frames rendered in this window, not the cumulative
             * total, otherwise the reported FPS grows every second. */
            val frames = windowFrameCount
            lastFps = (frames * 1000 / elapsed).toInt()
            val avgFrameTime = if (frameTimeCount > 0) frameTimeSum / frameTimeCount else 0
            Log.i(TAG, "FPS: $lastFps | Avg: ${avgFrameTime}ms | Min: ${minFrameTime}ms | Max: ${maxFrameTime}ms | Total: ${frameCount.get()} frames")
            lastFpsTime = SystemClock.elapsedRealtime()
            windowFrameCount = 0
        }
    }

    /** Frames per second over the last completed one-second window. */
    fun getFps(): Int = lastFps

    /** Total frames recorded since [start]. */
    fun getFrameCount(): Long = frameCount.get()

    /**
     * A snapshot of the current statistics.
     *
     * Keys: `fps`, `frameCount`, `avgFrameTimeMs`, `minFrameTimeMs` and
     * `maxFrameTimeMs`.
     *
     * @return the statistics map; frame-time values are in milliseconds.
     */
    fun getStats(): Map<String, Any> = mapOf(
        "fps" to lastFps,
        "frameCount" to frameCount.get(),
        "avgFrameTimeMs" to if (frameTimeCount > 0) frameTimeSum / frameTimeCount else 0,
        "minFrameTimeMs" to if (frameTimeCount > 0) minFrameTime else 0,
        "maxFrameTimeMs" to maxFrameTime
    )

    /**
     * Clears the accumulated frame-time statistics.
     *
     * The cumulative [getFrameCount] and the running flag are left untouched.
     */
    fun resetStats() {
        frameTimeSum = 0
        frameTimeCount = 0
        minFrameTime = Long.MAX_VALUE
        maxFrameTime = 0
    }

    private const val TAG = "LvglPerfMon"
}

