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
/* SpringValue                                                                */
/* -------------------------------------------------------------------------- */

SpringValue::SpringValue(float start, SpringConfig config)
    : value_(start), target_(start), config_(config) {}

void SpringValue::set_config(SpringConfig config) { config_ = config; }

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
void Engine::reset() {
    springs_.clear();
    points_.clear();
    colors_.clear();
    last_dt_ = 0.016f;
    /* Deliberately does NOT clear frame_callback_: the showcase sets it while
     * the screen is built (before the render thread starts), and this reset()
     * runs at thread start — clearing it here would leave the showcase without
     * its per-frame driver. ui_reset_showcase() clears it on screen exit. */
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
