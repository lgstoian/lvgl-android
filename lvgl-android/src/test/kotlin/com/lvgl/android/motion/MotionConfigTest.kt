// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the ROM -> LVGL motion mappings (no Android runtime). */
class MotionConfigTest {

    @Test
    fun frictionMapsToDecelerateInterpolatorLoss() {
        // 1 - 0.5^(2*0.015) = 0.02058 -> 2% lost per LVGL frame.
        assertEquals(2, LvglMotionConfig.scrollThrowFromFriction(0.015f))
        assertEquals(7, LvglMotionConfig.scrollThrowFromFriction(0.05f))
        assertEquals(75, LvglMotionConfig.scrollThrowFromFriction(1.0f))
    }

    @Test
    fun frictionMappingIsMonotonicAndBounded() {
        val low = LvglMotionConfig.scrollThrowFromFriction(0.01f)
        val high = LvglMotionConfig.scrollThrowFromFriction(0.2f)
        assertTrue("higher friction must throw less far", high > low)
        // Invalid friction falls back to the AOSP baseline (2), not to an
        // extreme throw; only a genuinely high friction clamps at the max.
        assertEquals(LvglMotionConfig.scrollThrowFromFriction(LvglMotionConfig.FALLBACK_SCROLL_FRICTION),
            LvglMotionConfig.scrollThrowFromFriction(-1f))
        assertEquals(LvglMotionConfig.MAX_SCROLL_THROW, LvglMotionConfig.scrollThrowFromFriction(10f))
        assertEquals(LvglMotionConfig.scrollThrowFromFriction(LvglMotionConfig.FALLBACK_SCROLL_FRICTION),
            LvglMotionConfig.scrollThrowFromFriction(Float.NaN))
    }

    @Test
    fun decayIsComplementOfScrollThrow() {
        assertEquals(0.98f, LvglMotionConfig.decayFromFriction(0.015f), 0.0001f)
        assertEquals(1f - 7f / 100f, LvglMotionConfig.decayFromFriction(0.05f), 0.0001f)
    }

    @Test
    fun velocityAndSlopThresholdsAreClamped() {
        assertEquals(10, LvglMotionConfig.gestureMinVelocityFromMinVelocity(600))
        assertEquals(1, LvglMotionConfig.gestureMinVelocityFromMinVelocity(50))
        assertEquals(1, LvglMotionConfig.gestureMinVelocityFromMinVelocity(0))
        assertEquals(255, LvglMotionConfig.gestureMinVelocityFromMinVelocity(100_000))
        assertEquals(24, LvglMotionConfig.scrollLimitFromTouchSlop(24))
        assertEquals(LvglMotionConfig.FALLBACK_TOUCH_SLOP, LvglMotionConfig.scrollLimitFromTouchSlop(-4))
        assertEquals(255, LvglMotionConfig.scrollLimitFromTouchSlop(999))
    }

    @Test
    fun refreshRateMapsToOneFrameInterval() {
        assertEquals(1000f / 60f, LvglMotionConfig.frameIntervalFromRefreshRate(60f), 0.0001f)
        assertEquals(1000f / 120f, LvglMotionConfig.frameIntervalFromRefreshRate(120f), 0.0001f)
        assertEquals(1000f / 90f, LvglMotionConfig.frameIntervalFromRefreshRate(90f), 0.0001f)
        assertEquals(1000f / 144f, LvglMotionConfig.frameIntervalFromRefreshRate(144f), 0.0001f)
        // Out-of-range and invalid rates fall back / clamp, never divide by 0.
        assertEquals(1000f / 60f, LvglMotionConfig.frameIntervalFromRefreshRate(0f), 0.0001f)
        assertEquals(1000f / 60f, LvglMotionConfig.frameIntervalFromRefreshRate(Float.NaN), 0.0001f)
        assertEquals(1000f / 240f, LvglMotionConfig.frameIntervalFromRefreshRate(1000f), 0.0001f)
        assertEquals(1000f / 30f, LvglMotionConfig.frameIntervalFromRefreshRate(10f), 0.0001f)
    }

    @Test
    fun scrollThrowIsFrameRateNormalized() {
        // At the reference rate the normalized mapping equals the base one.
        assertEquals(
            LvglMotionConfig.scrollThrowFromFriction(0.015f),
            LvglMotionConfig.scrollThrowFromFrictionAtRefreshRate(0.015f, 60f),
        )
        // 2% loss per 60 Hz frame -> ~1% at 120 Hz, ~4% at 30 Hz. The retained
        // fraction per second stays the same: (1 - loss)^(hz) ~= constant.
        assertEquals(1, LvglMotionConfig.scrollThrowFromFrictionAtRefreshRate(0.015f, 120f))
        assertEquals(4, LvglMotionConfig.scrollThrowFromFrictionAtRefreshRate(0.015f, 30f))
        assertEquals(
            LvglMotionConfig.decayFromFriction(0.015f),
            LvglMotionConfig.decayFromFrictionAtRefreshRate(0.015f, 60f),
            0.0001f,
        )
        assertEquals(0.99f, LvglMotionConfig.decayFromFrictionAtRefreshRate(0.015f, 120f), 0.0001f)
        // Normalization never escapes LVGL's legal range.
        assertEquals(
            LvglMotionConfig.MAX_SCROLL_THROW,
            LvglMotionConfig.scrollThrowFromFrictionAtRefreshRate(10f, 30f),
        )
    }

    @Test
    fun gestureVelocityFollowsRefreshRate() {
        assertEquals(10, LvglMotionConfig.gestureMinVelocityFromMinVelocity(600, 60f))
        assertEquals(5, LvglMotionConfig.gestureMinVelocityFromMinVelocity(600, 120f))
        assertEquals(20, LvglMotionConfig.gestureMinVelocityFromMinVelocity(600, 30f))
        assertEquals(1, LvglMotionConfig.gestureMinVelocityFromMinVelocity(50, 120f))
    }

    @Test
    fun interpolatorMidpointMapsToDampingRatio() {
        assertEquals(1.0f, LvglMotionConfig.zetaFromInterpolator(0.5f), 0.0001f)
        assertEquals(0.5f, LvglMotionConfig.zetaFromInterpolator(1.0f), 0.0001f)
        assertEquals(0.78f, LvglMotionConfig.zetaFromInterpolator(0.72f), 0.0001f)
        assertEquals(0.78f, LvglMotionConfig.zetaFromInterpolator(Float.NaN), 0.0001f)
    }

    @Test
    fun interpolatorSamplesAreNormalizedAndMonotonic() {
        val samples = LvglMotionConfig.sampleFastOutSlowIn(0.72f)
        assertEquals(LvglMotionConfig.INTERPOLATOR_SAMPLES, samples.size)
        assertEquals(0f, samples[0], 0f)
        assertEquals(1f, samples[samples.lastIndex], 0f)
        // The warped AOSP curve hits the polled midpoint at t = 0.5.
        assertEquals(0.72f, samples[5], 0.01f)
        for (i in 1..samples.lastIndex) {
            assertTrue("curve must not reverse at $i", samples[i] >= samples[i - 1])
        }

        // Any input length normalizes to the fixed sample count with fixed
        // endpoints and no reversals; invalid entries use the fallback curve.
        val short = LvglMotionConfig.normalizeCurve(floatArrayOf(0.3f, 0.9f))
        assertEquals(LvglMotionConfig.INTERPOLATOR_SAMPLES, short.size)
        assertEquals(0f, short[0], 0f)
        assertEquals(1f, short.last(), 0f)
        for (i in 1..short.lastIndex) assertTrue(short[i] >= short[i - 1])
        val broken = LvglMotionConfig.normalizeCurve(floatArrayOf(Float.NaN))
        assertEquals(0f, broken[0], 0f)
        assertEquals(1f, broken.last(), 0f)
        assertTrue(broken.all { it.isFinite() })
    }

    @Test
    fun springDurationAndScaleFollowOscillatorMath() {
        val medium = LvglMotionConfig.springFromDuration(200, 1f, 0.78f)
        val long = LvglMotionConfig.springFromDuration(400, 1f, 0.78f)
        val fast = LvglMotionConfig.springFromDuration(200, 0.5f, 0.78f)

        // omega = 2π / (durationSec * 0.6); stiffness = omega².
        assertEquals(200f, medium.settleMs, 0.0001f)
        assertEquals(400f, long.settleMs, 0.0001f)
        assertEquals(100f, fast.settleMs, 0.0001f)
        assertEquals(medium.stiffness / 4f, long.stiffness, 0.5f)
        assertEquals(medium.stiffness * 4f, fast.stiffness, 0.5f)
        assertEquals(2f * 0.78f * (2 * Math.PI / (0.2 * 0.6)).toFloat(), medium.damping, 0.01f)
        assertEquals(
            LvglMotionConfig.MIN_SPRING_MS,
            LvglMotionConfig.springFromDuration(1, 0.01f, 0.78f).settleMs,
            0.0001f,
        )
    }

    @Test
    fun wireFormatIsFixedAndDeterministic() {
        val config = LvglMotionConfig.defaults()
        val wire = config.toWire()
        assertEquals(LvglMotionConfig.WIRE_SIZE, wire.size)
        assertEquals(25, LvglMotionConfig.WIRE_SIZE)

        assertEquals(config.scrollThrow.toFloat(), wire[LvglMotionConfig.I_SCROLL_THROW])
        assertEquals(config.scrollLimit.toFloat(), wire[LvglMotionConfig.I_SCROLL_LIMIT])
        assertEquals(config.gestureMinVelocity.toFloat(), wire[LvglMotionConfig.I_GESTURE_MIN_VELOCITY])
        assertEquals(config.shortSpring.stiffness, wire[LvglMotionConfig.I_SHORT_STIFFNESS])
        assertEquals(config.mediumSpring.damping, wire[LvglMotionConfig.I_MEDIUM_DAMPING])
        assertEquals(config.decay, wire[LvglMotionConfig.I_DECAY])
        assertEquals(config.animatorScale, wire[LvglMotionConfig.I_ANIMATOR_SCALE])
        assertEquals(config.fastOutSlowInMid, wire[LvglMotionConfig.I_FAST_OUT_SLOW_IN_MID])
        assertEquals(config.refreshHz, wire[LvglMotionConfig.I_REFRESH_HZ])
        assertEquals(config.frameIntervalMs, wire[LvglMotionConfig.I_FRAME_INTERVAL_MS], 0.0001f)

        val base = LvglMotionConfig.I_INTERPOLATOR_SAMPLES
        assertTrue(
            "interpolator samples must occupy the tail of the wire payload",
            wire.copyOfRange(base, LvglMotionConfig.WIRE_SIZE)
                .contentEquals(config.fastOutSlowInSamples),
        )
        assertEquals(0f, wire[base], 0f)
        assertEquals(1f, wire[LvglMotionConfig.WIRE_SIZE - 1], 0f)

        assertTrue(wire.contentEquals(config.toWire()))
        assertTrue(wire.contentEquals(LvglMotionConfig.defaults().toWire()))
    }

    @Test
    fun scaleOverrideRecomputesDerivedSprings() {
        val normal = LvglMotionConfig.defaults()
        val doubled = normal.copy(animatorScale = 2f, transitionScale = 2f, windowScale = 2f)
        assertEquals(200f, normal.mediumSpring.settleMs, 0.0001f)
        assertEquals(400f, doubled.mediumSpring.settleMs, 0.0001f)
        assertEquals(normal.mediumSpring.stiffness / 4f, doubled.mediumSpring.stiffness, 0.5f)
        // Scroll-throw is scale-independent: it comes from ROM friction only.
        assertEquals(normal.scrollThrow, doubled.scrollThrow)
        // Refresh-derived pacing survives a scale-only copy.
        assertEquals(normal.frameIntervalMs, doubled.frameIntervalMs, 0.0001f)
    }

    @Test
    fun derivedValuesUseThePacedRefreshRate() {
        val at60 = LvglMotionConfig.defaults()
        val at120 = at60.copy(refreshHz = 120f)
        assertEquals(1000f / 120f, at120.frameIntervalMs, 0.0001f)
        assertTrue("faster display must lose less velocity per frame", at120.scrollThrow < at60.scrollThrow)
        val fastFling60 = at60.copy(minFlingVelocity = 600)
        val fastFling120 = at60.copy(minFlingVelocity = 600, refreshHz = 120f)
        assertTrue("faster display must use a smaller per-frame velocity floor",
            fastFling120.gestureMinVelocity < fastFling60.gestureMinVelocity)
        assertEquals(at120.scrollThrow.toFloat(), 100f * (1f - at120.decay), 0.001f)
    }
}
