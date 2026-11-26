// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.motion

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.view.ViewConfiguration
import android.view.animation.AnimationUtils
import android.view.animation.Interpolator
import com.lvgl.android.engine.LvglEngine
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * ROM-adaptive motion snapshot.
 *
 * Android exposes the device's actual feel through ViewConfiguration, the
 * Settings.Global animation scales, the framework `config_*AnimTime` integers,
 * the system `fast_out_slow_in` interpolator and the display refresh rate.
 * LVGL ships fixed constants instead. This class polls those ROM values once
 * and maps them to LVGL's scroll-throw, velocity thresholds, spring durations
 * and frame pacing so the framework feels native on every device without
 * shipping per-ROM constants.
 *
 * Every mapping is a pure function so it can be verified on the JVM; only
 * [poll] touches Android APIs, and every individual read falls back to an
 * AOSP baseline if the ROM does not provide it.
 *
 * Wire format sent to native ([toWire]) is a fixed [WIRE_SIZE]-float array; the
 * indices are shared with `lvgl_anim.h` and must stay in sync.
 */
data class LvglMotionConfig(
    /* Raw ROM values (kept for logging and for re-polls). */
    val minFlingVelocity: Int,
    val maxFlingVelocity: Int,
    val scrollFriction: Float,
    val touchSlop: Int,
    val tapTimeoutMs: Int,
    val longPressTimeoutMs: Int,
    val animatorScale: Float,
    val transitionScale: Float,
    val windowScale: Float,
    val shortAnimMs: Int,
    val mediumAnimMs: Int,
    val longAnimMs: Int,
    val fastOutSlowInMid: Float,
    /* Display refresh rate (Hz) and the ROM interpolator sampled at
     * [INTERPOLATOR_SAMPLES] uniformly spaced points. Defaults keep the data
     * class usable on a plain JVM and for scale-only override copies. */
    val refreshHz: Float = FALLBACK_REFRESH_HZ,
    val fastOutSlowInSamples: FloatArray = sampleFastOutSlowIn(FALLBACK_FAST_OUT_SLOW_IN_MID),
) {

    /* Derived LVGL values. Recomputed by data-class copy() so a scale-only
     * override cannot leave a stale spring config behind. */
    val frameIntervalMs: Float = frameIntervalFromRefreshRate(refreshHz)
    val scrollThrow: Int = scrollThrowFromFrictionAtRefreshRate(scrollFriction, refreshHz)
    val scrollLimit: Int = scrollLimitFromTouchSlop(touchSlop)
    val gestureMinVelocity: Int = gestureMinVelocityFromMinVelocity(minFlingVelocity, refreshHz)
    val decay: Float = 1f - scrollThrow / 100f
    val zeta: Float = zetaFromInterpolator(fastOutSlowInMid)
    val shortSpring: Spring = springFromDuration(shortAnimMs, animatorScale, zeta)
    val mediumSpring: Spring = springFromDuration(mediumAnimMs, transitionScale, zeta)
    val longSpring: Spring = springFromDuration(longAnimMs, windowScale, zeta)

    /** Stiffness/damping/settle time of one spring role. */
    data class Spring(val stiffness: Float, val damping: Float, val settleMs: Float)

    /** Fixed JNI payload; see the index constants in [Companion]. */
    fun toWire(): FloatArray = floatArrayOf(
        scrollThrow.toFloat(),
        scrollLimit.toFloat(),
        gestureMinVelocity.toFloat(),
        shortSpring.stiffness,
        shortSpring.damping,
        mediumSpring.stiffness,
        mediumSpring.damping,
        longSpring.stiffness,
        longSpring.damping,
        decay,
        animatorScale,
        fastOutSlowInMid,
        refreshHz,
        frameIntervalMs,
        *fastOutSlowInSamples,
    )

    /** INFO log of the values actually read from the ROM. */
    fun logPolled() {
        Log.i(
            TAG,
            "ROM motion: friction=$scrollFriction minFling=$minFlingVelocity " +
                "maxFling=$maxFlingVelocity touchSlop=$touchSlop " +
                "tapTimeout=${tapTimeoutMs}ms longPress=${longPressTimeoutMs}ms " +
                "animatorScale=$animatorScale transitionScale=$transitionScale " +
                "windowScale=$windowScale short=${shortAnimMs}ms " +
                "medium=${mediumAnimMs}ms long=${longAnimMs}ms " +
                "fastOutSlowInMid=$fastOutSlowInMid " +
                "refreshHz=$refreshHz frameInterval=${frameIntervalMs}ms"
        )
    }

    /** INFO log of the values actually applied to LVGL (hardware verification). */
    fun logApplied() {
        Log.i(
            TAG,
            "LVGL motion: scrollThrow=${scrollThrow}% scrollLimit=${scrollLimit} " +
                "gestureMinVelocity=$gestureMinVelocity decay=$decay zeta=$zeta " +
                "frameInterval=${frameIntervalMs}ms " +
                "shortSpring=${shortSpring.stiffness}/${shortSpring.damping}@${shortSpring.settleMs}ms " +
                "mediumSpring=${mediumSpring.stiffness}/${mediumSpring.damping}@${mediumSpring.settleMs}ms " +
                "longSpring=${longSpring.stiffness}/${longSpring.damping}@${longSpring.settleMs}ms"
        )
    }

    /** Push this snapshot to the engine (cached natively until display init). */
    fun apply() {
        LvglEngine.instance.applyMotionConfig(toWire())
    }

    companion object {
        private const val TAG = "LvglMotionConfig"

        /* Number of normalized fast_out_slow_in samples sent to native: t = 0,
         * 0.1, …, 1.0. Eleven points keep the payload small and make the
         * midpoint land exactly on an array element. */
        const val INTERPOLATOR_SAMPLES = 11

        /* Wire indices — keep in sync with lvgl_anim.h. */
        const val WIRE_SIZE = 14 + INTERPOLATOR_SAMPLES
        const val I_SCROLL_THROW = 0
        const val I_SCROLL_LIMIT = 1
        const val I_GESTURE_MIN_VELOCITY = 2
        const val I_SHORT_STIFFNESS = 3
        const val I_SHORT_DAMPING = 4
        const val I_MEDIUM_STIFFNESS = 5
        const val I_MEDIUM_DAMPING = 6
        const val I_LONG_STIFFNESS = 7
        const val I_LONG_DAMPING = 8
        const val I_DECAY = 9
        const val I_ANIMATOR_SCALE = 10
        const val I_FAST_OUT_SLOW_IN_MID = 11
        const val I_REFRESH_HZ = 12
        const val I_FRAME_INTERVAL_MS = 13
        const val I_INTERPOLATOR_SAMPLES = 14

        /* AOSP baselines, used only when a ROM value is unavailable. */
        const val FALLBACK_MIN_FLING_VELOCITY = 50
        const val FALLBACK_MAX_FLING_VELOCITY = 8000
        const val FALLBACK_SCROLL_FRICTION = 0.015f
        const val FALLBACK_TOUCH_SLOP = 24
        const val FALLBACK_TAP_TIMEOUT_MS = 100
        const val FALLBACK_LONG_PRESS_TIMEOUT_MS = 500
        const val FALLBACK_ANIMATION_SCALE = 1.0f
        const val FALLBACK_SHORT_ANIM_MS = 100
        const val FALLBACK_MEDIUM_ANIM_MS = 200
        const val FALLBACK_LONG_ANIM_MS = 400
        const val FALLBACK_FAST_OUT_SLOW_IN_MID = 0.72f
        const val FALLBACK_REFRESH_HZ = 60f

        /* LVGL polls the indev about once per 60 Hz frame. */
        const val REFERENCE_FPS = 60f
        const val MIN_REFRESH_HZ = 30f
        const val MAX_REFRESH_HZ = 240f
        const val MIN_SCROLL_THROW = 1
        const val MAX_SCROLL_THROW = 95
        const val MIN_SCROLL_LIMIT = 1
        const val MAX_SCROLL_LIMIT = 255
        const val MIN_VELOCITY = 1
        const val MAX_VELOCITY = 255
        const val SPRING_DURATION_FACTOR = 0.6f
        const val MIN_SPRING_MS = 40f

        private fun safeRefresh(hz: Float): Float =
            if (hz.isFinite() && hz > 0f) hz.coerceIn(MIN_REFRESH_HZ, MAX_REFRESH_HZ)
            else FALLBACK_REFRESH_HZ

        /**
         * Target frame interval from the ROM refresh rate. The render loop paces
         * to this single deadline instead of sleeping twice per frame. Values
         * outside a sane 30..240 Hz range fall back to 60 Hz (16.67 ms).
         */
        fun frameIntervalFromRefreshRate(hz: Float): Float = 1000f / safeRefresh(hz)

        /**
         * Android's DecelerateInterpolator(f) decays to `1 - 0.5^(2f)` at its
         * midpoint; OverScroller applies that fraction as per-frame velocity
         * loss. LVGL loses `scroll_throw` percent per frame, so the two are
         * directly comparable. This is the 60 Hz base mapping.
         */
        fun scrollThrowFromFriction(friction: Float): Int {
            val f = if (friction > 0f && friction.isFinite()) friction else FALLBACK_SCROLL_FRICTION
            val loss = 1.0 - 0.5.pow(2.0 * f)
            return (loss * 100.0).roundToInt().coerceIn(MIN_SCROLL_THROW, MAX_SCROLL_THROW)
        }

        /**
         * Frame-rate-normalized version of [scrollThrowFromFriction]. A fling
         * retains `r = 1 - loss60` per 60 Hz frame; retaining `r^(60/hz)` per
         * frame at `hz` gives the same wall-clock decay. Faster screens lose a
         * smaller percentage per frame but run more frames per second.
         */
        fun scrollThrowFromFrictionAtRefreshRate(friction: Float, hz: Float): Int {
            val retainedAt60 = 1.0 - scrollThrowFromFriction(friction) / 100.0
            val loss = 1.0 - retainedAt60.pow(REFERENCE_FPS.toDouble() / safeRefresh(hz))
            return (loss * 100.0).roundToInt().coerceIn(MIN_SCROLL_THROW, MAX_SCROLL_THROW)
        }

        /** Fraction of velocity retained per frame (1 - LVGL's loss). */
        fun decayFromFriction(friction: Float): Float =
            1f - scrollThrowFromFriction(friction) / 100f

        /** Frame-rate-aware complement of [scrollThrowFromFrictionAtRefreshRate]. */
        fun decayFromFrictionAtRefreshRate(friction: Float, hz: Float): Float =
            1f - scrollThrowFromFrictionAtRefreshRate(friction, hz) / 100f

        /** LVGL gesture_min_velocity is px between consecutive indev reads. */
        fun gestureMinVelocityFromMinVelocity(minVelocity: Int): Int =
            gestureMinVelocityFromMinVelocity(minVelocity, REFERENCE_FPS)

        /** Same mapping as the 60 Hz overload, but px/second / refresh rate. */
        fun gestureMinVelocityFromMinVelocity(minVelocity: Int, hz: Float): Int {
            val v = if (minVelocity > 0) minVelocity else FALLBACK_MIN_FLING_VELOCITY
            return (v / safeRefresh(hz)).roundToInt().coerceIn(MIN_VELOCITY, MAX_VELOCITY)
        }

        /** Android touch slop is the direct analogue of LVGL's scroll_limit. */
        fun scrollLimitFromTouchSlop(touchSlop: Int): Int {
            val v = if (touchSlop > 0) touchSlop else FALLBACK_TOUCH_SLOP
            return v.coerceIn(MIN_SCROLL_LIMIT, MAX_SCROLL_LIMIT)
        }

        /**
         * Map the system interpolator's midpoint to a damping ratio. A linear
         * ROM (mid=0.5) is critically damped; a strongly eased Material curve
         * (mid≈0.72) is slightly underdamped so widgets have a tactile settle.
         */
        fun zetaFromInterpolator(mid: Float): Float {
            val m = if (mid.isFinite()) mid else FALLBACK_FAST_OUT_SLOW_IN_MID
            return (1.5f - m).coerceIn(0.45f, 1.0f)
        }

        /**
         * Convert an Android duration (ms) + animation scale + damping ratio to
         * the same damped-oscillator parameters used by lvgl_anim. The 0.6
         * factor matches SpringConfig::from_bounce_duration in native code.
         */
        fun springFromDuration(durationMs: Int, scale: Float, zeta: Float): Spring {
            val safeMs = if (durationMs > 0) durationMs.toFloat() else FALLBACK_MEDIUM_ANIM_MS.toFloat()
            val safeScale = if (scale > 0f && scale.isFinite()) scale else FALLBACK_ANIMATION_SCALE
            val settleMs = (safeMs * safeScale).coerceAtLeast(MIN_SPRING_MS)
            val omega = (2.0 * PI / (settleMs / 1000.0 * SPRING_DURATION_FACTOR)).toFloat()
            /* zeta is already the damping ratio derived from the ROM
             * interpolator; do not map it a second time. */
            val dampingRatio = if (zeta.isFinite()) {
                zeta.coerceIn(0.45f, 1.0f)
            } else {
                zetaFromInterpolator(FALLBACK_FAST_OUT_SLOW_IN_MID)
            }
            return Spring(
                stiffness = omega * omega,
                damping = 2f * dampingRatio * omega,
                settleMs = settleMs,
            )
        }

        /**
         * AOSP `fast_out_slow_in` is the cubic Bezier (0.4, 0, 0.2, 1). Solve
         * x(u) = t by bisection and return y(u). Pure, so native/JVM tests can
         * use it when the ROM interpolator is unavailable.
         */
        fun fastOutSlowInFallback(t: Float): Float {
            val x = if (t.isFinite()) t.coerceIn(0f, 1f) else 0f
            fun bezier(a: Float, b: Float, u: Float): Float {
                val inv = 1f - u
                return 3f * inv * inv * u * a + 3f * inv * u * u * b + u * u * u
            }
            var lo = 0f
            var hi = 1f
            var u = x
            for (i in 0 until 16) {
                u = (lo + hi) / 2f
                if (bezier(0.4f, 0.2f, u) < x) lo = u else hi = u
            }
            return bezier(0f, 1f, u).coerceIn(0f, 1f)
        }

        /** Force endpoints, clamp to 0..1 and keep the curve non-decreasing. */
        fun normalizeCurve(values: FloatArray): FloatArray {
            val out = FloatArray(INTERPOLATOR_SAMPLES)
            for (i in out.indices) {
                val t = i.toFloat() / (INTERPOLATOR_SAMPLES - 1)
                val sampled = when {
                    values.isEmpty() -> fastOutSlowInFallback(t)
                    values.size == INTERPOLATOR_SAMPLES -> values[i]
                    else -> {
                        val pos = t * (values.size - 1)
                        val lo = pos.toInt().coerceIn(0, values.size - 1)
                        val hi = (lo + 1).coerceIn(0, values.size - 1)
                        val f = pos - lo
                        values[lo] * (1f - f) + values[hi] * f
                    }
                }
                out[i] = if (sampled.isFinite()) sampled.coerceIn(0f, 1f) else fastOutSlowInFallback(t)
            }
            out[0] = 0f
            out[out.lastIndex] = 1f
            for (i in 1 until out.size) {
                if (out[i] < out[i - 1]) out[i] = out[i - 1]
            }
            return out
        }

        /** Sample the AOSP curve, warped so its midpoint matches a known ROM mid. */
        fun sampleFastOutSlowIn(mid: Float): FloatArray {
            val target = if (mid.isFinite()) mid.coerceIn(0.05f, 0.95f) else FALLBACK_FAST_OUT_SLOW_IN_MID
            val baseMid = fastOutSlowInFallback(0.5f)
            val exponent = ln(1f - target) / ln(1f - baseMid)
            return normalizeCurve(
                FloatArray(INTERPOLATOR_SAMPLES) { i ->
                    val t = i.toFloat() / (INTERPOLATOR_SAMPLES - 1)
                    1f - (1f - fastOutSlowInFallback(t)).pow(exponent)
                }
            )
        }

        /**
         * One-shot, bounded refresh-rate read. API 29 and below use the
         * deprecated default Display; API 30+ use WindowMetrics plus
         * Context.display. No retry loop: an unavailable value falls back.
         */
        fun refreshHzFromContext(context: Context): Float {
            fun read(block: () -> Float?): Float? = try {
                block()
            } catch (t: Throwable) {
                null
            }
            val rate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                read {
                    val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                    wm?.currentWindowMetrics ?: return@read null
                    context.display.refreshRate
                }
            } else {
                @Suppress("DEPRECATION")
                read {
                    (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                        ?.defaultDisplay?.refreshRate
                }
            }
            return if (rate != null && rate > 0f && rate.isFinite()) rate
            else FALLBACK_REFRESH_HZ
        }

        /** An all-fallback snapshot, used when Android APIs are unavailable. */
        fun defaults(): LvglMotionConfig = LvglMotionConfig(
            minFlingVelocity = FALLBACK_MIN_FLING_VELOCITY,
            maxFlingVelocity = FALLBACK_MAX_FLING_VELOCITY,
            scrollFriction = FALLBACK_SCROLL_FRICTION,
            touchSlop = FALLBACK_TOUCH_SLOP,
            tapTimeoutMs = FALLBACK_TAP_TIMEOUT_MS,
            longPressTimeoutMs = FALLBACK_LONG_PRESS_TIMEOUT_MS,
            animatorScale = FALLBACK_ANIMATION_SCALE,
            transitionScale = FALLBACK_ANIMATION_SCALE,
            windowScale = FALLBACK_ANIMATION_SCALE,
            shortAnimMs = FALLBACK_SHORT_ANIM_MS,
            mediumAnimMs = FALLBACK_MEDIUM_ANIM_MS,
            longAnimMs = FALLBACK_LONG_ANIM_MS,
            fastOutSlowInMid = FALLBACK_FAST_OUT_SLOW_IN_MID,
            refreshHz = FALLBACK_REFRESH_HZ,
            fastOutSlowInSamples = sampleFastOutSlowIn(FALLBACK_FAST_OUT_SLOW_IN_MID),
        )

        /** Poll the ROM once. Each read is independent and fails soft. */
        fun poll(context: Context): LvglMotionConfig {
            fun <T> read(default: T, block: () -> T): T = try {
                block()
            } catch (t: Throwable) {
                default
            }

            val viewConfig = read(null) { ViewConfiguration.get(context) }
            val resolver = context.contentResolver
            val scale = { name: String ->
                read(FALLBACK_ANIMATION_SCALE) {
                    Settings.Global.getFloat(resolver, name)
                        .takeIf { it >= 0f && it.isFinite() }
                        ?: FALLBACK_ANIMATION_SCALE
                }
            }
            val resources = context.resources
            val duration = { resId: Int, fallback: Int ->
                read(fallback) { resources.getInteger(resId) }
            }
            val interpolator = read<Interpolator?>(null) {
                AnimationUtils.loadInterpolator(context, android.R.interpolator.fast_out_slow_in)
            }
            /* Sample the ROM curve instead of a single hand-picked constant;
             * the native LVGL animation path reads this exact array. */
            val samples = normalizeCurve(
                FloatArray(INTERPOLATOR_SAMPLES) { i ->
                    val t = i.toFloat() / (INTERPOLATOR_SAMPLES - 1)
                    interpolator?.getInterpolation(t) ?: fastOutSlowInFallback(t)
                }
            )

            return LvglMotionConfig(
                minFlingVelocity = read(FALLBACK_MIN_FLING_VELOCITY) {
                    viewConfig?.scaledMinimumFlingVelocity ?: FALLBACK_MIN_FLING_VELOCITY
                },
                maxFlingVelocity = read(FALLBACK_MAX_FLING_VELOCITY) {
                    viewConfig?.scaledMaximumFlingVelocity ?: FALLBACK_MAX_FLING_VELOCITY
                },
                scrollFriction = read(FALLBACK_SCROLL_FRICTION) {
                    ViewConfiguration.getScrollFriction()
                },
                touchSlop = read(FALLBACK_TOUCH_SLOP) {
                    viewConfig?.scaledTouchSlop ?: FALLBACK_TOUCH_SLOP
                },
                tapTimeoutMs = read(FALLBACK_TAP_TIMEOUT_MS) {
                    ViewConfiguration.getTapTimeout()
                },
                longPressTimeoutMs = read(FALLBACK_LONG_PRESS_TIMEOUT_MS) {
                    ViewConfiguration.getLongPressTimeout()
                },
                animatorScale = scale(Settings.Global.ANIMATOR_DURATION_SCALE),
                transitionScale = scale(Settings.Global.TRANSITION_ANIMATION_SCALE),
                windowScale = scale(Settings.Global.WINDOW_ANIMATION_SCALE),
                shortAnimMs = duration(android.R.integer.config_shortAnimTime, FALLBACK_SHORT_ANIM_MS),
                mediumAnimMs = duration(android.R.integer.config_mediumAnimTime, FALLBACK_MEDIUM_ANIM_MS),
                longAnimMs = duration(android.R.integer.config_longAnimTime, FALLBACK_LONG_ANIM_MS),
                fastOutSlowInMid = samples[INTERPOLATOR_SAMPLES / 2],
                refreshHz = refreshHzFromContext(context),
                fastOutSlowInSamples = samples,
            )
        }

        /** When set, [install] pushes this snapshot instead of polling the ROM. */
        @Volatile
        var overrideConfig: LvglMotionConfig? = null

        /** Set false to opt out of default polling and Settings.Global observation. */
        @Volatile
        private var enabled: Boolean = true

        fun isEnabled(): Boolean = enabled

        fun setEnabled(value: Boolean) { enabled = value }

        /** Install a snapshot override (null clears it and resumes ROM polling). */
        fun setOverride(config: LvglMotionConfig?) { overrideConfig = config }

        /* Lazy so pure mapping functions can be called on a plain JVM: no
         * Android framework object is constructed at class-load time. */
        private val observerHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
        private var observerRegistered = false
        private var observedContext: Context? = null

        private fun newAnimationScaleObserver(): ContentObserver =
            object : ContentObserver(observerHandler) {
                override fun onChange(selfChange: Boolean) {
                    onChange(selfChange, null)
                }

                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    if (!enabled || overrideConfig != null) return
                    val context = observedContext ?: return
                    val config = poll(context)
                    config.logPolled()
                    config.apply()
                    config.logApplied()
                }
            }

        private val animationScaleObserver: ContentObserver by lazy { newAnimationScaleObserver() }

        /**
         * Default entry point: poll (or use [overrideConfig]), push to LVGL and
         * observe Settings.Global animation scales for the process lifetime.
         * Safe to call on every activity create/resume/configuration change;
         * observation is once-only. Re-polling here is what keeps the frame
         * interval and the interpolator in step with a display/config change.
         */
        fun install(context: Context): LvglMotionConfig {
            val config = overrideConfig ?: if (enabled) poll(context) else defaults()
            config.logPolled()
            config.apply()
            config.logApplied()
            if (enabled && overrideConfig == null) {
                observedContext = context.applicationContext
                if (!observerRegistered) {
                    val resolver = context.contentResolver
                    val keys = arrayOf(
                        Settings.Global.ANIMATOR_DURATION_SCALE,
                        Settings.Global.TRANSITION_ANIMATION_SCALE,
                        Settings.Global.WINDOW_ANIMATION_SCALE,
                    )
                    for (key in keys) {
                        try {
                            resolver.registerContentObserver(
                                Settings.Global.getUriFor(key), false, animationScaleObserver
                            )
                        } catch (t: Throwable) {
                            Log.w(TAG, "Cannot observe $key: $t")
                        }
                    }
                    observerRegistered = true
                }
            }
            return config
        }
    }
}
