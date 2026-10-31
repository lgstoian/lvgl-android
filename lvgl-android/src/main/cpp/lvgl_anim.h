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
 *     duration: approximate settle time in seconds */
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

    static SpringConfig snappy()  { return from_bounce_duration(0.1f, 0.3f); }
    static SpringConfig default_(){ return from_bounce_duration(0.2f, 0.45f); }
    static SpringConfig bouncy()  { return from_bounce_duration(0.55f, 0.6f); }
    static SpringConfig wobbly()  { return from_bounce_duration(0.85f, 0.8f); }
};

/* -------------------------------------------------------------------------- */
/* Scalar spring                                                              */
/* -------------------------------------------------------------------------- */

class SpringValue {
public:
    SpringValue(float start = 0.0f, SpringConfig config = SpringConfig::default_());

    void set_config(SpringConfig config);
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

    /* Per-frame callback, invoked once after every tick(). Lets a screen (e.g.
     * the showcase) drive its animated widgets from the same frame cadence. */
    void set_frame_callback(void (*fn)(float dt));
    void clear_frame_callback();

    void reset();

private:
    Engine() = default;
    void (*frame_callback_)(float dt) = nullptr;
    std::vector<SpringValue*>  springs_;
    std::vector<SpringPoint*>  points_;
    std::vector<SpringColor*>  colors_;
    float last_dt_ = 0.016f;
};

/* Convenience: create an Engine-managed spring (heap-allocated, owned by engine).
 * Caller uses the pointer; Engine frees nothing — these are meant to live as
 * long as the screen, e.g. as member objects accessed via raw pointers. */
SpringValue*  make_spring(float start, SpringConfig config = SpringConfig::default_());
SpringPoint*  make_point(lv_point_t start, SpringConfig config = SpringConfig::default_());
SpringColor*  make_color(lv_color_t start, SpringConfig config = SpringConfig::default_());

} /* namespace lvgl_anim */
} /* namespace lvgl_android */
