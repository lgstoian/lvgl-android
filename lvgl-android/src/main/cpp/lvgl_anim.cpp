/*
 * Copyright (c) 2026 LVGL Android Framework Contributors
 * SPDX-License-Identifier: MIT
 *
 * Implementation of the spring + easing animation engine (lvgl_anim.h).
 */
#include "lvgl_anim.h"

#include <algorithm>
#include <cmath>

namespace lvgl_android {
namespace lvgl_anim {

/* -------------------------------------------------------------------------- */
/* ROM-adaptive motion configuration                                           */
/* -------------------------------------------------------------------------- */

SpringConfig SpringConfig::snappy()  { return motion_config().short_spring; }
SpringConfig SpringConfig::default_(){ return motion_config().medium_spring; }
SpringConfig SpringConfig::bouncy()  { return motion_config().bouncy_spring; }
SpringConfig SpringConfig::wobbly()  { return motion_config().wobbly_spring; }

const SpringConfig& config_for_role(MotionRole role) {
    const MotionConfig& cfg = motion_config();
    switch (role) {
        case MotionRole::Short:  return cfg.short_spring;
        case MotionRole::Medium: return cfg.medium_spring;
        case MotionRole::Long:   return cfg.long_spring;
        case MotionRole::Bouncy: return cfg.bouncy_spring;
        case MotionRole::Wobbly: return cfg.wobbly_spring;
        case MotionRole::Fixed:  break;
    }
    /* Fixed callers keep their own config; never overwrite it from a role. */
    static const SpringConfig empty{};
    return empty;
}

MotionConfig MotionConfig::defaults() {
    MotionConfig cfg;
    const float z = zeta_from_interpolator(AOSP_FAST_OUT_SLOW_IN_MID);
    cfg.short_spring  = spring_for(100.0f, 1.0f, z);
    cfg.medium_spring = spring_for(200.0f, 1.0f, z);
    cfg.long_spring   = spring_for(400.0f, 1.0f, z);
    return cfg;
}

MotionConfig MotionConfig::from_wire(const float* v, int count) {
    MotionConfig cfg = defaults();
    if (!v || count < 12) return cfg;
    cfg.scroll_throw         = (uint8_t)std::lround(v[0]);
    cfg.scroll_limit         = (uint8_t)std::lround(v[1]);
    cfg.gesture_min_velocity = (uint8_t)std::lround(v[2]);
    cfg.short_spring.stiffness = v[3];
    cfg.short_spring.damping   = v[4];
    cfg.medium_spring.stiffness = v[5];
    cfg.medium_spring.damping   = v[6];
    cfg.long_spring.stiffness   = v[7];
    cfg.long_spring.damping     = v[8];
    cfg.decay                   = v[9];
    cfg.animator_scale          = v[10];
    cfg.fast_out_slow_in_mid    = v[11];

    /* New fields are optional: a legacy 12-float payload keeps 60 Hz pacing
     * and the fallback curve. */
    if (count >= 13) cfg.refresh_hz = safe_refresh_hz(v[12]);
    if (count >= 14) {
        float interval = v[13];
        if (!std::isfinite(interval) || interval <= 0.0f) {
            interval = frame_interval_from_refresh_rate(cfg.refresh_hz);
        }
        const float min_interval = 1000.0f / 240.0f;
        const float max_interval = 1000.0f / 30.0f;
        if (interval < min_interval) interval = min_interval;
        if (interval > max_interval) interval = max_interval;
        cfg.frame_interval_ms = interval;
    }
    if (count >= 14 + INTERPOLATOR_SAMPLES) {
        std::array<float, INTERPOLATOR_SAMPLES> samples{};
        for (int i = 0; i < INTERPOLATOR_SAMPLES; i++) {
            float s = v[14 + i];
            samples[i] = std::isfinite(s) ? std::max(0.0f, std::min(1.0f, s)) : 0.0f;
        }
        samples[0] = 0.0f;
        samples[INTERPOLATOR_SAMPLES - 1] = 1.0f;
        for (int i = 1; i < INTERPOLATOR_SAMPLES; i++) {
            if (samples[i] < samples[i - 1]) samples[i] = samples[i - 1];
        }
        cfg.fast_out_slow_in = samples;
    }
    /* Bouncy/wobbly reuse the ROM short/long durations with their characteristic
     * damping ratios, so they stay expressive while following device timing.
     * Derive the duration with the same settle model as the regular roles; the
     * old 2*pi/(omega*0.6) heuristic made them follow a different clock. */
    const float short_seconds = settle_ms_from_spring(cfg.short_spring) / 1000.0f;
    const float long_seconds = settle_ms_from_spring(cfg.long_spring) / 1000.0f;
    cfg.bouncy_spring = SpringConfig::from_bounce_duration(0.55f, short_seconds);
    cfg.wobbly_spring = SpringConfig::from_bounce_duration(0.85f, long_seconds);
    return cfg;
}

/* -------------------------------------------------------------------------- */
/* LVGL animation path from the sampled ROM interpolator                       */
/* -------------------------------------------------------------------------- */

float eval_interpolator(const std::array<float, INTERPOLATOR_SAMPLES>& samples, float t) {
    if (!std::isfinite(t)) t = 1.0f;
    if (t <= 0.0f) return samples[0];
    if (t >= 1.0f) return samples[INTERPOLATOR_SAMPLES - 1];
    const float pos = t * (float)(INTERPOLATOR_SAMPLES - 1);
    const int lo = std::min(INTERPOLATOR_SAMPLES - 2, (int)pos);
    const float f = pos - (float)lo;
    return samples[lo] * (1.0f - f) + samples[lo + 1] * f;
}

int32_t motion_path(const lv_anim_t* anim) {
    if (!anim) return 0;
    /* Same fixed-point resolution LVGL's own path callbacks use. */
    constexpr int32_t RESOLUTION = 1024;
    constexpr int RES_SHIFT = 10;
    const auto& cfg = motion_config();
    float t = 1.0f;
    if (anim->duration > 0) {
        t = (float)anim->act_time / (float)anim->duration;
        if (t < 0.0f) t = 0.0f;
        if (t > 1.0f) t = 1.0f;
    }
    const float eased = eval_interpolator(cfg.fast_out_slow_in, t);
    int32_t step = (int32_t)std::lround(eased * (float)RESOLUTION);
    int32_t value = step * (anim->end_value - anim->start_value);
    value >>= RES_SHIFT;
    return value + anim->start_value;
}

void apply_motion_path(lv_anim_t* anim) {
    if (anim) lv_anim_set_path_cb(anim, motion_path_cb());
}

#if defined(__clang__) || defined(__GNUC__)
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
#endif

void mark_spring_driven(lv_obj_t* obj) {
    if (obj) lv_obj_set_flag(obj, LV_OBJ_FLAG_USER_1, true);
}

bool is_spring_driven(lv_obj_t* obj) {
    return obj != nullptr && lv_obj_has_flag(obj, LV_OBJ_FLAG_USER_1);
}

#if defined(__clang__) || defined(__GNUC__)
#pragma GCC diagnostic pop
#endif

/* -------------------------------------------------------------------------- */
/* SpringValue                                                                */
/* -------------------------------------------------------------------------- */

SpringValue::SpringValue(float start, SpringConfig config)
    : value_(start), target_(start), config_(config) {}

void SpringValue::set_config(SpringConfig config) { config_ = config; }

void SpringValue::set_role(MotionRole role) {
    role_ = role;
    apply_motion();
}

void SpringValue::apply_motion() {
    if (role_ == MotionRole::Fixed) return;
    config_ = config_for_role(role_);
}

void SpringValue::set_target(float target) {
    if (std::abs(target - target_) > 0.001f || !settled_) {
        target_ = target;
        settled_ = false;
    }
}

void SpringValue::snap(float value) {
    value_ = value;
    target_ = value;
    velocity_ = 0.0f;
    settled_ = true;
}

void SpringValue::set_velocity(float v) { velocity_ = v; }

void SpringValue::integrate(float dt) {
    /* Semi-implicit (symplectic) Euler — stable for springs and conserves
     * energy far better than explicit Euler. */
    const float accel = -config_.stiffness * (value_ - target_) - config_.damping * velocity_;
    velocity_ += accel / config_.mass * dt;
    value_    += velocity_ * dt;
}

bool SpringValue::tick(float dt) {
    if (settled_) return false;

    /* Sub-step for stability at low frame rates (cap at 8 sub-steps). */
    const int   steps   = std::min(8, std::max(1, (int)(dt / 0.008f)));
    const float sub_dt  = dt / steps;
    for (int i = 0; i < steps; i++) integrate(sub_dt);

    /* Settle detection — snap once we're close enough to save CPU. */
    if (std::abs(value_ - target_) < 0.5f && std::abs(velocity_) < 5.0f) {
        value_ = target_;
        velocity_ = 0.0f;
        settled_ = true;
    }
    return !settled_;
}

/* -------------------------------------------------------------------------- */
/* SpringPoint                                                                */
/* -------------------------------------------------------------------------- */

SpringPoint::SpringPoint(lv_point_t start, SpringConfig config)
    : x_(start.x, config), y_(start.y, config) {}

void SpringPoint::set_config(SpringConfig config) {
    x_.set_config(config);
    y_.set_config(config);
}

void SpringPoint::set_role(MotionRole role) {
    x_.set_role(role);
    y_.set_role(role);
}

void SpringPoint::apply_motion() {
    x_.apply_motion();
    y_.apply_motion();
}

void SpringPoint::set_target(lv_point_t target) {
    x_.set_target((float)target.x);
    y_.set_target((float)target.y);
}

void SpringPoint::snap(lv_point_t p) {
    x_.snap((float)p.x);
    y_.snap((float)p.y);
}

bool SpringPoint::tick(float dt) {
    return x_.tick(dt) || y_.tick(dt);
}

lv_point_t SpringPoint::value() const {
    lv_point_t p;
    p.x = (int32_t)std::lround(x_.value());
    p.y = (int32_t)std::lround(y_.value());
    return p;
}

bool SpringPoint::settled() const {
    return x_.settled() && y_.settled();
}

/* -------------------------------------------------------------------------- */
/* SpringColor                                                                */
/* -------------------------------------------------------------------------- */

SpringColor::SpringColor(lv_color_t start, SpringConfig config) {
    r_ = SpringValue((float)start.red, config);
    g_ = SpringValue((float)start.green, config);
    b_ = SpringValue((float)start.blue, config);
}

void SpringColor::set_config(SpringConfig config) {
    r_.set_config(config);
    g_.set_config(config);
    b_.set_config(config);
}

void SpringColor::set_role(MotionRole role) {
    r_.set_role(role);
    g_.set_role(role);
    b_.set_role(role);
}

void SpringColor::apply_motion() {
    r_.apply_motion();
    g_.apply_motion();
    b_.apply_motion();
}

void SpringColor::set_target(lv_color_t target) {
    r_.set_target((float)target.red);
    g_.set_target((float)target.green);
    b_.set_target((float)target.blue);
}

void SpringColor::snap(lv_color_t c) {
    r_.snap((float)c.red);
    g_.snap((float)c.green);
    b_.snap((float)c.blue);
}

bool SpringColor::tick(float dt) {
    return r_.tick(dt) || g_.tick(dt) || b_.tick(dt);
}

lv_color_t SpringColor::value() const {
    lv_color_t c;
    c.red   = (uint8_t)std::lround(std::max(0.0f, std::min(255.0f, r_.value())));
    c.green = (uint8_t)std::lround(std::max(0.0f, std::min(255.0f, g_.value())));
    c.blue  = (uint8_t)std::lround(std::max(0.0f, std::min(255.0f, b_.value())));
    return c;
}

bool SpringColor::settled() const {
    return r_.settled() && g_.settled() && b_.settled();
}

/* -------------------------------------------------------------------------- */
/* Engine                                                                     */
/* -------------------------------------------------------------------------- */

Engine& Engine::instance() {
    static Engine engine;
    return engine;
}

void Engine::add_spring(SpringValue* s) {
    if (std::find(springs_.begin(), springs_.end(), s) == springs_.end())
        springs_.push_back(s);
}

void Engine::add_point(SpringPoint* p) {
    if (std::find(points_.begin(), points_.end(), p) == points_.end())
        points_.push_back(p);
}

void Engine::add_color(SpringColor* c) {
    if (std::find(colors_.begin(), colors_.end(), c) == colors_.end())
        colors_.push_back(c);
}

void Engine::remove_spring(SpringValue* s) {
    springs_.erase(std::remove(springs_.begin(), springs_.end(), s), springs_.end());
}

void Engine::remove_point(SpringPoint* p) {
    points_.erase(std::remove(points_.begin(), points_.end(), p), points_.end());
}

void Engine::remove_color(SpringColor* c) {
    colors_.erase(std::remove(colors_.begin(), colors_.end(), c), colors_.end());
}

size_t Engine::active_count() const {
    return springs_.size() + points_.size() + colors_.size();
}

void Engine::set_motion_config(const MotionConfig& cfg) {
    motion_config_ = cfg;
    motion_generation_++;
    /* Re-role live springs so a Settings.Global scale change takes effect
     * without rebuilding the current screen. Called on the LVGL thread. */
    for (auto* s : springs_)  s->apply_motion();
    for (auto* p : points_)  p->apply_motion();
    for (auto* c : colors_)  c->apply_motion();
}

void Engine::set_frame_callback(void (*fn)(float dt)) { frame_callback_ = fn; }
void Engine::clear_frame_callback() { frame_callback_ = nullptr; }

void Engine::tick(float dt) {
    last_dt_ = dt;

    for (auto* s : springs_)  s->tick(dt);
    for (auto* p : points_)  p->tick(dt);
    for (auto* c : colors_)  c->tick(dt);

    /* Per-frame hook for screen-level animation drivers (e.g. the showcase). */
    if (frame_callback_) frame_callback_(dt);

    /* Settled springs are intentionally NOT pruned: a settled spring can be
     * re-activated by a later set_target() (e.g. a Window that opens, closes and
     * opens again). Pruning it here would leave set_target() with no effect
     * because the Engine would never tick it again. Call remove_*() to release
     * a spring permanently. */
}

/* @breadcrumb BC-018 */
void Engine::reset(bool keep_frame_callback) {
    springs_.clear();
    points_.clear();
    colors_.clear();
    last_dt_ = 0.016f;
    /* A callback that outlives its screen is a use-after-free: the showcase
     * sets it while building, so only the thread-start reset of a live
     * showcase keeps it. Every other reset (engine destroy / shutdown) drops
     * it — ui_reset_showcase() has already released the window objects by then. */
    if (!keep_frame_callback) frame_callback_ = nullptr;
}

/* -------------------------------------------------------------------------- */
/* Factory helpers                                                            */
/* -------------------------------------------------------------------------- */

SpringValue* make_spring(float start, SpringConfig config) {
    auto* s = new SpringValue(start, config);
    Engine::instance().add_spring(s);
    return s;
}

SpringPoint* make_point(lv_point_t start, SpringConfig config) {
    auto* p = new SpringPoint(start, config);
    Engine::instance().add_point(p);
    return p;
}

SpringColor* make_color(lv_color_t start, SpringConfig config) {
    auto* c = new SpringColor(start, config);
    Engine::instance().add_color(c);
    return c;
}

} /* namespace lvgl_anim */
} /* namespace lvgl_android */
