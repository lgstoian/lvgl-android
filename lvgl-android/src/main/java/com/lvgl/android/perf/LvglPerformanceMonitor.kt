// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.perf

import android.util.Log
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

object LvglPerformanceMonitor {

    private val frameCount = AtomicLong(0)
    private var lastFpsTime = SystemClock.elapsedRealtime()
    private var lastFps = 0
    private var frameTimeSum = 0L
    private var frameTimeCount = 0
    private var minFrameTime = Long.MAX_VALUE
    private var maxFrameTime = 0L
    private var lastFrameTime = SystemClock.elapsedRealtimeNanos()
    private var running = false
    private var startTime = 0L

    fun start() {
        running = true
        startTime = SystemClock.elapsedRealtime()
        lastFpsTime = startTime
        frameCount.set(0)
        Log.i(TAG, "Performance monitor started")
    }

    fun stop() {
        running = false
        Log.i(TAG, "Performance monitor stopped. Total frames: ${frameCount.get()}")
    }

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

        val elapsed = SystemClock.elapsedRealtime() - lastFpsTime
        if (elapsed >= 1000) {
            val frames = frameCount.get()
            lastFps = (frames * 1000 / elapsed).toInt()
            val avgFrameTime = if (frameTimeCount > 0) frameTimeSum / frameTimeCount else 0
            Log.i(TAG, "FPS: $lastFps | Avg: ${avgFrameTime}ms | Min: ${minFrameTime}ms | Max: ${maxFrameTime}ms | Total: $frames frames")
            lastFpsTime = SystemClock.elapsedRealtime()
        }
    }

    fun getFps(): Int = lastFps
    fun getFrameCount(): Long = frameCount.get()
    fun getStats(): Map<String, Any> = mapOf(
        "fps" to lastFps,
        "frameCount" to frameCount.get(),
        "avgFrameTimeMs" to if (frameTimeCount > 0) frameTimeSum / frameTimeCount else 0,
        "minFrameTimeMs" to minFrameTime,
        "maxFrameTimeMs" to maxFrameTime
    )

    fun resetStats() {
        frameTimeSum = 0
        frameTimeCount = 0
        minFrameTime = Long.MAX_VALUE
        maxFrameTime = 0
    }

    private const val TAG = "LvglPerfMon"
}

