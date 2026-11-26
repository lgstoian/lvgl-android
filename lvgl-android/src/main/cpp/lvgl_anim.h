/*
 * Copyright (c) 2026 LVGL Android Framework Contributors
 * SPDX-License-Identifier: MIT
 *
 * This file is part of the LVGL Android Framework.
 * See LICENSE in the project root for full license information.
 *
 * Spring + easing animation engine. Inspired by the M5Tab5 / smooth_ui_toolkit
 * approach: every animated value is a damped harmonic oscillator, integrated
 * semi-implicitly on the LVGL render thread. This is what gives the UI its
 * tactile "feel" — overshoot, settle, bounce — instead of static LVGL widgets.
 *
 * The Engine owns all active animations and advances them once per frame.
 * See Engine::tick() in lvgl_anim.cpp and its call site in lvgl_port.cpp.
 */
#pragma once

#include "lvgl/lvgl.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdlib>
#include <cstdint>
#include <chrono>
#include <vector>

/* Hide math globals that clash with LVGL's own math macros. */
#undef pow

namespace lvgl_android {
namespace lvgl_anim {

/* -------------------------------------------------------------------------- */
/* Configuration                                                              */
/* -------------------------------------------------------------------------- */

/* A spring is a damped harmonic oscillator:
 *     x'' = -k (x - target) - c x'
 * where k = stiffness, c = damping, m = mass (default 1).
 *
 * Two preset APIs:
 *   - stiffness/damping/mass  — raw physics (smooth_ui_toolkit style)
 *   - bounce + duration       — mapped to the above, motion.dev style
 *     bounce: 0 = critically damped (no overshoot), 1 = very bouncy
 *     duration: approximate settle time in seconds
 *
 * Standard UI transitions use MotionConfig::spring_for() instead: it calibrates
 * omega from the ROM interpolator so the guaranteed 2% settle time equals the
 * polled Android duration. from_bounce_duration() is reserved for deliberately
 * expressive effects (bouncy/wobbly), never for window/screen transitions. */
struct SpringConfig {
    float stiffness = 170.0f;
    float damping   = 26.0f;
    float mass      = 1.0f;

    static SpringConfig from_bounce_duration(float bounce, float duration) {
        /* Clamp bounce so the spring always settles. */
        if (bounce < 0.0f) bounce = 0.0f;
        if (bounce > 1.0f) bounce = 1.0f;
        if (duration <= 0.0f) duration = 0.4f;

        /* zeta = 1 - bounce * 0.7  →  bounce 0 = critical, bounce 1 = very bouncy */
        const float zeta   = 1.0f - bounce * 0.7f;
        const float omega_n = 6.2831853f / (duration * 0.6f);
        SpringConfig cfg;
        cfg.stiffness = omega_n * omega_n;
        cfg.damping   = 2.0f * zeta * omega_n;
        cfg.mass      = 1.0f;
        return cfg;
    }

    /* ROM-adaptive presets. Defined in lvgl_anim.cpp so they can read the
     * active MotionConfig; the hardcoded values above are only fallbacks. */
    static SpringConfig snappy();
    static SpringConfig default_();
    static SpringConfig bouncy();
    static SpringConfig wobbly();
};

/* -------------------------------------------------------------------------- */
/* ROM-adaptive motion configuration                                           */
/* -------------------------------------------------------------------------- */

/* Number of normalized fast_out_slow_in samples in the JNI wire payload and in
 * MotionConfig. Keep in sync with LvglMotionConfig.INTERPOLATOR_SAMPLES. */
constexpr int INTERPOLATOR_SAMPLES = 11;

/* Sane fallback when a ROM interpolator was not available: ease-out quadratic,
 * endpoints fixed at 0 and 1. Native animations still have a smooth curve. */
constexpr std::array<float, INTERPOLATOR_SAMPLES> fallback_interpolator_samples() {
    std::array<float, INTERPOLATOR_SAMPLES> out{};
    for (int i = 0; i < INTERPOLATOR_SAMPLES; i++) {
        const float t = (float)i / (float)(INTERPOLATOR_SAMPLES - 1);
        out[i] = 1.0f - (1.0f - t) * (1.0f - t);
    }
    out[0] = 0.0f;
    out[INTERPOLATOR_SAMPLES - 1] = 1.0f;
    return out;
}

/* Which ROM-derived spring a value follows. A Fixed spring keeps the config it
 * was constructed with; the other roles are re-applied when a new motion config
 * arrives (e.g. an animation-scale change from Settings.Global). */
enum class MotionRole { Fixed, Short, Medium, Long, Bouncy, Wobbly };

const SpringConfig& config_for_role(MotionRole role);

/* Android's fast_out_slow_in decelerates monotonically. Stand-ins for it must
 * be near-critically damped: the old `1.5 - mid` mapping produced zeta≈0.725
 * on some ROMs, which overshot and felt like slow motion. Keep zeta below 1 so
 * the envelope formula stays finite (0.98 already has no visible bounce). */
constexpr float MIN_ZETA = 0.90f;
constexpr float MAX_ZETA = 0.98f;
constexpr float AOSP_FAST_OUT_SLOW_IN_ZETA = 0.95f;
constexpr float AOSP_FAST_OUT_SLOW_IN_MID = 0.72f;
constexpr float ZETA_MID_SLOPE = 0.20f;
constexpr float SETTLE_TOLERANCE = 0.02f;
constexpr float CRITICAL_SETTLE_FACTOR = 5.83f; /* (1+x)e^-x = 0.02 */

/* Values resolved from the Android ROM by LvglMotionConfig and sent over JNI.
 * The pure mapping helpers mirror the Kotlin functions so native code can
 * derive the same numbers without a second source of truth. */
struct MotionConfig {
    SpringConfig short_spring =
        spring_for(100.0f, 1.0f, zeta_from_interpolator(AOSP_FAST_OUT_SLOW_IN_MID));
    SpringConfig medium_spring =
        spring_for(200.0f, 1.0f, zeta_from_interpolator(AOSP_FAST_OUT_SLOW_IN_MID));
    SpringConfig long_spring =
        spring_for(400.0f, 1.0f, zeta_from_interpolator(AOSP_FAST_OUT_SLOW_IN_MID));
    SpringConfig bouncy_spring = SpringConfig::from_bounce_duration(0.55f, 0.6f);
    SpringConfig wobbly_spring = SpringConfig::from_bounce_duration(0.85f, 0.8f);

    uint8_t scroll_throw         = 2;     /* percent of velocity lost per frame */
    uint8_t scroll_limit         = 8;     /* px needed to start a scroll */
    uint8_t gesture_min_velocity = 1;     /* px between consecutive indev reads */
    float   decay                = 0.98f; /* velocity retained per frame */
    float   animator_scale       = 1.0f;
    float   fast_out_slow_in_mid = 0.72f;
    /* Display pacing + ROM interpolator shape. The render loop paces to one
     * frame_interval_ms deadline; the LVGL animation path reads the samples. */
    float   refresh_hz       = 60.0f;
    float   frame_interval_ms = 1000.0f / 60.0f;
    std::array<float, INTERPOLATOR_SAMPLES> fast_out_slow_in =
        fallback_interpolator_samples();

    static MotionConfig defaults();

    /* Android DecelerateInterpolator(f): loss = 1 - 0.5^(2f). LVGL loses
     * scroll_throw percent per frame, so the two map directly. */
    static uint8_t scroll_throw_from_friction(float friction) {
        if (!(friction > 0.0f)) friction = 0.015f;
        const double loss = 1.0 - std::pow(0.5, 2.0 * (double)friction);
        int pct = (int)std::lround(loss * 100.0);
        if (pct < 1) pct = 1;
        if (pct > 95) pct = 95;
        return (uint8_t)pct;
    }

    static float decay_from_friction(float friction) {
        return 1.0f - scroll_throw_from_friction(friction) / 100.0f;
    }

    static uint8_t scroll_limit_from_touch_slop(int touch_slop) {
        if (touch_slop < 1) touch_slop = 24;
        if (touch_slop > 255) touch_slop = 255;
        return (uint8_t)touch_slop;
    }

    static uint8_t gesture_min_velocity_from_min(float px_per_sec) {
        return gesture_min_velocity_at(px_per_sec, 60.0f);
    }

    /* Bounded refresh-rate fallback: 30..240 Hz, 60 Hz when invalid. */
    static float safe_refresh_hz(float hz) {
        if (!std::isfinite(hz) || hz <= 0.0f) return 60.0f;
        if (hz < 30.0f) return 30.0f;
        if (hz > 240.0f) return 240.0f;
        return hz;
    }

    /* Single-deadline pacing target for the render loop, in milliseconds. */
    static float frame_interval_from_refresh_rate(float hz) {
        return 1000.0f / safe_refresh_hz(hz);
    }

    /* Frame-rate-aware scroll throw: retain r^(60/hz) per frame so a fling
     * decays over the same wall-clock time at any refresh rate. */
    static uint8_t scroll_throw_from_friction_at(float friction, float hz) {
        const double retained60 = 1.0 - scroll_throw_from_friction(friction) / 100.0;
        const double loss = 1.0 - std::pow(retained60, 60.0 / (double)safe_refresh_hz(hz));
        int pct = (int)std::lround(loss * 100.0);
        if (pct < 1) pct = 1;
        if (pct > 95) pct = 95;
        return (uint8_t)pct;
    }

    static uint8_t gesture_min_velocity_at(float px_per_sec, float hz) {
        if (!(px_per_sec > 0.0f)) px_per_sec = 50.0f;
        int v = (int)std::lround(px_per_sec / safe_refresh_hz(hz));
        if (v < 1) v = 1;
        if (v > 255) v = 255;
        return (uint8_t)v;
    }

    static float zeta_from_interpolator(float mid) {
        if (!std::isfinite(mid)) mid = AOSP_FAST_OUT_SLOW_IN_MID;
        float m = mid;
        if (m < 0.05f) m = 0.05f;
        if (m > 0.95f) m = 0.95f;
        float z = AOSP_FAST_OUT_SLOW_IN_ZETA +
                  (m - AOSP_FAST_OUT_SLOW_IN_MID) * ZETA_MID_SLOPE;
        if (z < MIN_ZETA) z = MIN_ZETA;
        if (z > MAX_ZETA) z = MAX_ZETA;
        return z;
    }

    /* Natural-frequency time constants until the 2% error envelope
     * e^(-zeta*omega*t)/sqrt(1-zeta^2) reaches SETTLE_TOLERANCE. Mirrors
     * LvglMotionConfig.settleFactorFromZeta(). Conservative: the last 2%
     * crossing of the actual step response is at or before this bound. */
    static float settle_factor_from_zeta(float zeta) {
        float z = std::isfinite(zeta) ? zeta : AOSP_FAST_OUT_SLOW_IN_ZETA;
        if (z < MIN_ZETA) z = MIN_ZETA;
        if (z > MAX_ZETA) z = MAX_ZETA;
        const double denom = SETTLE_TOLERANCE * std::sqrt(1.0 - (double)z * z);
        const double factor = std::log(1.0 / denom) / (double)z;
        return (float)std::min((double)CRITICAL_SETTLE_FACTOR, factor);
    }

    /* Recover zeta from k/c (mass 1): zeta = c / (2*sqrt(k)). */
    static float zeta_from_spring(const SpringConfig& s) {
        if (!(s.stiffness > 0.0f) || !(s.damping > 0.0f)) return AOSP_FAST_OUT_SLOW_IN_ZETA;
        float zeta = s.damping / (2.0f * std::sqrt(s.stiffness));
        if (zeta < MIN_ZETA) zeta = MIN_ZETA;
        if (zeta > MAX_ZETA) zeta = MAX_ZETA;
        return zeta;
    }

    /* Modelled 2% settle time (ms) of a spring received over JNI.
     * zeta*omega = damping/2, so settle_ms = factor * 1000 / (zeta*omega).
     * Used both to re-roll expressive springs and for the hardware INFO log. */
    static float settle_ms_from_spring(const SpringConfig& s) {
        if (!(s.damping > 0.0f)) return 0.0f;
        return settle_factor_from_zeta(zeta_from_spring(s)) * 2000.0f / s.damping;
    }

    static SpringConfig spring_for(float duration_ms, float scale, float zeta) {
        if (!(duration_ms > 0.0f)) duration_ms = 200.0f;
        if (!(scale > 0.0f) || !std::isfinite(scale)) scale = 1.0f;
        float settle_ms = duration_ms * scale;
        if (settle_ms < 40.0f) settle_ms = 40.0f;
        float ratio = std::isfinite(zeta) ? zeta : zeta_from_interpolator(AOSP_FAST_OUT_SLOW_IN_MID);
        if (ratio < MIN_ZETA) ratio = MIN_ZETA;
        if (ratio > MAX_ZETA) ratio = MAX_ZETA;
        /* No 0.6 shortcut: match the ROM's wall-clock duration exactly. */
        const float omega = settle_factor_from_zeta(ratio) /
                            (ratio * (settle_ms / 1000.0f));
        SpringConfig cfg;
        cfg.stiffness = omega * omega;
        cfg.damping   = 2.0f * ratio * omega;
        cfg.mass      = 1.0f;
        return cfg;
    }

    /* Decode the 12-float wire payload produced by LvglMotionConfig.toWire(). */
    static MotionConfig from_wire(const float* v, int count);
};

/* -------------------------------------------------------------------------- */
/* Scalar spring                                                              */
/* -------------------------------------------------------------------------- */

class SpringValue {
public:
    SpringValue(float start = 0.0f, SpringConfig config = SpringConfig::default_());

    void set_config(SpringConfig config);
    void set_role(MotionRole role);  /* adopt a ROM-derived spring preset */
    void apply_motion();             /* re-read the active config for this role */
    MotionRole role() const { return role_; }

    void set_target(float target);
    void snap(float value);          /* jump instantly, zero velocity */
    void set_velocity(float v);

    /* Advance the oscillator by dt seconds. Returns true while still moving. */
    bool tick(float dt);

    float value() const { return value_; }
    float target() const { return target_; }
    float velocity() const { return velocity_; }
    bool  settled() const { return settled_; }

private:
    float value_     = 0.0f;
    float target_    = 0.0f;
    float velocity_  = 0.0f;
    SpringConfig config_;
    MotionRole  role_    = MotionRole::Fixed;
    bool settled_    = true;

    void integrate(float dt);
};

/* -------------------------------------------------------------------------- */
/* 2D point spring (for positions / offsets)                                  */
/* -------------------------------------------------------------------------- */

class SpringPoint {
public:
    SpringPoint() = default;
    SpringPoint(lv_point_t start, SpringConfig config);

    void set_config(SpringConfig config);
    void set_role(MotionRole role);
    void apply_motion();
    void set_target(lv_point_t target);
    void snap(lv_point_t p);
    bool tick(float dt);

    lv_point_t value() const;
    bool settled() const;

private:
    SpringValue x_;
    SpringValue y_;
};

/* -------------------------------------------------------------------------- */
/* Color spring (per-channel)                                                 */
/* -------------------------------------------------------------------------- */

class SpringColor {
public:
    SpringColor() = default;
    SpringColor(lv_color_t start, SpringConfig config);

    void set_config(SpringConfig config);
    void set_role(MotionRole role);
    void apply_motion();
    void set_target(lv_color_t target);
    void snap(lv_color_t c);
    bool tick(float dt);

    lv_color_t value() const;
    bool settled() const;

private:
    SpringValue r_;
    SpringValue g_;
    SpringValue b_;
};

/* -------------------------------------------------------------------------- */
/* Easing helpers (for time-based entrance effects)                           */
/* -------------------------------------------------------------------------- */

constexpr float ease_out_cubic(float t) {
    const float inv = 1.0f - t;
    return 1.0f - inv * inv * inv;
}

constexpr float ease_in_out_cubic(float t) {
    return t < 0.5f ? 4.0f * t * t * t : 1.0f - (-2.0f * t + 2.0f) * (-2.0f * t + 2.0f) * (-2.0f * t + 2.0f) / 2.0f;
}

constexpr float ease_out_back(float t) {
    const float c1 = 1.70158f;
    const float c3 = c1 + 1.0f;
    const float inv = t - 1.0f;
    return 1.0f + c3 * inv * inv * inv + c1 * inv * inv;
}

/* -------------------------------------------------------------------------- */
/* LVGL animation path from the sampled ROM interpolator                       */
/* -------------------------------------------------------------------------- */

/* Linearly evaluate N normalized samples at t in [0,1]. */
float eval_interpolator(const std::array<float, INTERPOLATOR_SAMPLES>& samples, float t);

/* LVGL path callback (lv_anim_path_cb_t): applies the sampled fast_out_slow_in
 * curve from the active MotionConfig to any framework-driven transition. Falls
 * back to a sane curve when no samples were pushed over JNI. */
int32_t motion_path(const lv_anim_t* anim);
inline lv_anim_path_cb_t motion_path_cb() { return &motion_path; }

/* Install the callback on one LVGL animation. UI-kit/window/spring-role
 * transitions call this when they start; the curve updates itself whenever a
 * new motion config arrives. */
void apply_motion_path(lv_anim_t* anim);

/* A spring writes its value straight to the object with lv_obj_set_*(): no
 * LVGL animation is involved, so the sampled path must never be layered on
 * top (that would ease the same value twice). Mark those objects with the
 * USER_1 flag; lvgl_port.cpp skips them when rebinding time-based paths. */
void mark_spring_driven(lv_obj_t* obj);
bool is_spring_driven(lv_obj_t* obj);

/* -------------------------------------------------------------------------- */
/* Engine — owns and advances all active animations per frame                  */
/* -------------------------------------------------------------------------- */

class Engine {
public:
    static Engine& instance();

    void add_spring(SpringValue* s);
    void add_point(SpringPoint* p);
    void add_color(SpringColor* c);

    void remove_spring(SpringValue* s);
    void remove_point(SpringPoint* p);
    void remove_color(SpringColor* c);

    /* Advance every active animation by dt seconds. Called once per frame
     * from the LVGL thread (see lvgl_port.cpp). Settled entries are retained
     * so they can be re-activated by a later set_target(); call remove_*() to
     * release them. */
    void tick(float dt);

    /* Time since last tick, in seconds — for frame-rate-independent motion. */
    float last_dt() const { return last_dt_; }

    size_t active_count() const;

    /* ROM-derived motion config. set_motion_config() re-applies every registered
     * spring that follows a role, so a scale change takes effect live. */
    const MotionConfig& motion_config() const { return motion_config_; }
    void set_motion_config(const MotionConfig& cfg);
    uint32_t motion_generation() const { return motion_generation_; }

    /* Per-frame callback, invoked once after every tick(). Lets a screen (e.g.
     * the showcase) drive its animated widgets from the same frame cadence. */
    void set_frame_callback(void (*fn)(float dt));
    void clear_frame_callback();

    /* Clear all animated values. The frame callback is cleared too unless the
     * caller is starting the render thread with the showcase already built —
     * in that case the callback belongs to the live showcase screen and must
     * survive this reset (see BC-017). */
    void reset(bool keep_frame_callback = false);

private:
    Engine() = default;
    void (*frame_callback_)(float dt) = nullptr;
    std::vector<SpringValue*>  springs_;
    std::vector<SpringPoint*>  points_;
    std::vector<SpringColor*>  colors_;
    float last_dt_ = 0.016f;
    MotionConfig motion_config_;
    uint32_t motion_generation_ = 0;
};

/* Convenience accessors used by the JNI bridge and the UI kit. */
inline const MotionConfig& motion_config() { return Engine::instance().motion_config(); }
inline void set_motion_config(const MotionConfig& cfg) { Engine::instance().set_motion_config(cfg); }

/* Convenience: create an Engine-managed spring (heap-allocated, owned by engine).
 * Caller uses the pointer; Engine frees nothing — these are meant to live as
 * long as the screen, e.g. as member objects accessed via raw pointers. */
SpringValue*  make_spring(float start, SpringConfig config = SpringConfig::default_());
SpringPoint*  make_point(lv_point_t start, SpringConfig config = SpringConfig::default_());
SpringColor*  make_color(lv_color_t start, SpringConfig config = SpringConfig::default_());

} /* namespace lvgl_anim */
} /* namespace lvgl_android */
