/*
 * Copyright (c) 2026 LVGL Android Framework Contributors
 * SPDX-License-Identifier: MIT
 *
 * Reusable spring-animated window. Inspired by the M5Tab5 / smooth_ui_toolkit
 * ui::Window: a container that animates between a "closed" keyframe (small,
 * at its hotspot, transparent) and an "open" keyframe (large, on-screen, opaque)
 * using spring physics for position/size and linear easing for opacity.
 *
 * This is the centerpiece of the animated showcase — it is what makes panels
 * feel like they physically spring open from where you tapped.
 *
 * @breadcrumb BC-019
 */
#pragma once

#include "lvgl_anim.h"

namespace lvgl_android {
namespace lvgl_ui {

struct WindowKeyframe {
    int32_t x   = 0;
    int32_t y   = 0;
    int32_t w   = 800;
    int32_t h   = 480;
    int32_t opa = 255;
};

class Window {
public:
    enum State {
        Closed,
        Opening,
        Opened,
        Closing,
    };

    struct Config {
        WindowKeyframe kf_closed;
        WindowKeyframe kf_opened;
        const char*    title        = "";
        uint32_t       bg_color     = 0x1C1F29;
        uint32_t       border_color = 0x2A3140;
        uint32_t       title_color  = 0xD8D8D8;
        int32_t        radius       = 24;
    };

    Window();
    ~Window();

    void create(lv_obj_t* parent, const Config& config);
    void destroy();

    void open();
    void close();
    void teleport_open();     /* snap open without animation */
    void teleport_close();    /* snap closed without animation */

    void tick(float dt);      /* advance animation, apply to LVGL objects */

    State state() const { return state_; }
    lv_obj_t* content() const { return content_; }   /* inner content area */

private:
    void apply_keyframe(const WindowKeyframe& kf, bool teleport);
    void apply_anim_values();

    Config    config_;
    State     state_     = Closed;
    lv_obj_t* container_ = nullptr;   /* outer animated container */
    lv_obj_t* header_    = nullptr;   /* title bar */
    lv_obj_t* title_lbl_ = nullptr;
    lv_obj_t* close_btn_ = nullptr;   /* invisible close hit area */
    lv_obj_t* bg_close_  = nullptr;   /* full-screen behind, closes on tap */
    lv_obj_t* content_   = nullptr;   /* user content area */

    lvgl_anim::SpringValue x_;
    lvgl_anim::SpringValue y_;
    lvgl_anim::SpringValue w_;
    lvgl_anim::SpringValue h_;
    lvgl_anim::SpringValue opa_;
};

} /* namespace lvgl_ui */
} /* namespace lvgl_android */
