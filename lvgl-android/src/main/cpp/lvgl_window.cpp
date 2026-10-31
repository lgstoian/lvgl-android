/*
 * Copyright (c) 2026 LVGL Android Framework Contributors
 * SPDX-License-Identifier: MIT
 *
 * Implementation of the spring-animated window (lvgl_window.h).
 */
#include "lvgl_window.h"

#include <android/log.h>
#include <cmath>

/* LVGL 9.x still exposes lv_obj_add/clear_flag (aliased to add/remove_flag) and
 * emits deprecation noise. They are correct and stable on 9.x, so we silence the
 * warning locally rather than switch to internal APIs. */
#if defined(__clang__) || defined(__GNUC__)
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
#endif

using namespace lvgl_android::lvgl_anim;

namespace lvgl_android {
namespace lvgl_ui {

/* Forward declarations (defined at the bottom of this file). */
static void win_close_on_click(lv_event_t* e);
static void win_stop_propagate(lv_event_t* e);

Window::Window() : x_(0.0f), y_(0.0f), w_(800.0f), h_(480.0f), opa_(255.0f) {
    Engine::instance().add_spring(&x_);
    Engine::instance().add_spring(&y_);
    Engine::instance().add_spring(&w_);
    Engine::instance().add_spring(&h_);
    Engine::instance().add_spring(&opa_);
}

Window::~Window() {
    Engine::instance().remove_spring(&x_);
    Engine::instance().remove_spring(&y_);
    Engine::instance().remove_spring(&w_);
    Engine::instance().remove_spring(&h_);
    Engine::instance().remove_spring(&opa_);
    destroy();
}

void Window::create(lv_obj_t* parent, const Config& config) {
    if (container_) return;   /* guard against a double-create leaking the old tree */
    config_ = config;

    /* Full-screen dimmed backdrop that closes the window when tapped.
     * Starts hidden + non-clickable so it never covers hotspots underneath. */
    bg_close_ = lv_obj_create(parent);
    /* FLOATING so the backdrop ignores the parent's flex layout (otherwise a
     * 2000x2000 child consumes the column and pushes the tiles off-screen). */
    lv_obj_add_flag(bg_close_, LV_OBJ_FLAG_FLOATING);
    lv_obj_set_size(bg_close_, 2000, 2000);   /* oversized to cover screen */
    lv_obj_center(bg_close_);
    lv_obj_set_style_bg_color(bg_close_, lv_color_hex(0x000000), 0);
    lv_obj_set_style_bg_opa(bg_close_, lv_pct(40), 0);
    lv_obj_clear_flag(bg_close_, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_add_flag(bg_close_, LV_OBJ_FLAG_HIDDEN);
    lv_obj_add_event_cb(bg_close_, win_close_on_click, LV_EVENT_CLICKED, this);
    lv_obj_clear_flag(bg_close_, LV_OBJ_FLAG_SCROLLABLE);

    /* Outer container that we animate. FLOATING so lv_obj_set_pos() is honoured
     * even though the showcase screen/body use flex layouts (LVGL ignores
     * absolute positions for children of a laid-out parent otherwise). */
    container_ = lv_obj_create(parent);
    lv_obj_add_flag(container_, LV_OBJ_FLAG_FLOATING);
    lv_obj_set_style_radius(container_, config.radius, 0);
    lv_obj_set_style_bg_color(container_, lv_color_hex(config.bg_color), 0);
    lv_obj_set_style_bg_opa(container_, LV_OPA_COVER, 0);
    lv_obj_set_style_border_width(container_, 2, 0);
    lv_obj_set_style_border_color(container_, lv_color_hex(config.border_color), 0);
    lv_obj_set_style_shadow_width(container_, 32, 0);
    lv_obj_set_style_shadow_opa(container_, lv_pct(35), 0);
    lv_obj_set_style_shadow_offset_y(container_, 10, 0);
    lv_obj_set_style_pad_all(container_, 0, 0);
    lv_obj_clear_flag(container_, LV_OBJ_FLAG_SCROLLABLE);
    /* Flex column: header on top, content fills the rest. */
    lv_obj_set_layout(container_, LV_LAYOUT_FLEX);
    lv_obj_set_flex_flow(container_, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_flex_align(container_, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_START,
                          LV_FLEX_ALIGN_START);
    /* Start hidden + non-clickable so closed windows never cover hotspots. */
    lv_obj_add_flag(container_, LV_OBJ_FLAG_HIDDEN);
    lv_obj_remove_flag(container_, LV_OBJ_FLAG_CLICKABLE);

    /* Header / title bar (flex row: title grows, close button on the right). */
    header_ = lv_obj_create(container_);
    lv_obj_set_width(header_, LV_PCT(100));
    lv_obj_set_height(header_, LV_SIZE_CONTENT);
    lv_obj_set_layout(header_, LV_LAYOUT_FLEX);
    lv_obj_set_flex_flow(header_, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(header_, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER,
                          LV_FLEX_ALIGN_CENTER);
    lv_obj_set_style_bg_opa(header_, LV_OPA_TRANSP, 0);
    lv_obj_set_style_border_width(header_, 0, 0);
    lv_obj_set_style_pad_all(header_, 0, 0);
    lv_obj_set_style_pad_ver(header_, 16, 0);
    lv_obj_set_style_pad_left(header_, 28, 0);
    lv_obj_set_style_pad_right(header_, 28, 0);
    lv_obj_clear_flag(header_, LV_OBJ_FLAG_CLICKABLE);

    if (config.title && config.title[0]) {
        title_lbl_ = lv_label_create(header_);
        lv_label_set_text(title_lbl_, config.title);
        lv_obj_set_style_text_color(title_lbl_, lv_color_hex(config.title_color), 0);
        lv_obj_set_style_text_font(title_lbl_, &lv_font_montserrat_20, 0);
        lv_obj_set_flex_grow(title_lbl_, 1);
        /* Decorative: never intercept taps meant for the header/window. */
        lv_obj_clear_flag(title_lbl_, LV_OBJ_FLAG_CLICKABLE);
        lv_obj_clear_flag(title_lbl_, LV_OBJ_FLAG_SCROLLABLE);
    }

    /* Close hit area (top-right, inside the header row). */
    close_btn_ = lv_obj_create(header_);
    lv_obj_set_size(close_btn_, 64, 64);
    lv_obj_set_style_bg_opa(close_btn_, LV_OPA_TRANSP, 0);
    lv_obj_set_style_border_width(close_btn_, 0, 0);
    lv_obj_clear_flag(close_btn_, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_add_flag(close_btn_, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_add_event_cb(close_btn_, win_close_on_click, LV_EVENT_CLICKED, this);

    /* Content area fills the rest below the header. */
    content_ = lv_obj_create(container_);
    lv_obj_set_width(content_, LV_PCT(100));
    lv_obj_set_flex_grow(content_, 1);
    lv_obj_set_style_bg_opa(content_, LV_OPA_TRANSP, 0);
    lv_obj_set_style_border_width(content_, 0, 0);
    lv_obj_set_style_pad_all(content_, 28, 0);
    lv_obj_set_layout(content_, LV_LAYOUT_FLEX);
    lv_obj_set_flex_flow(content_, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_flex_align(content_, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_START,
                          LV_FLEX_ALIGN_START);
    lv_obj_clear_flag(content_, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_add_flag(content_, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_add_event_cb(content_, win_stop_propagate, LV_EVENT_ALL, this);

    /* Raise the backdrop and container above any existing children (tiles). */
    lv_obj_move_to_index(bg_close_, lv_obj_get_child_count(parent) - 1);
    lv_obj_move_to_index(container_, lv_obj_get_child_count(parent) - 1);

    apply_keyframe(config_.kf_closed, true);
    state_ = Closed;
}

void Window::destroy() {
    if (container_) { lv_obj_delete(container_); container_ = nullptr; }
    if (bg_close_)  { lv_obj_delete(bg_close_);  bg_close_  = nullptr; }
    content_ = header_ = title_lbl_ = close_btn_ = nullptr;
    state_ = Closed;
}

void Window::apply_keyframe(const WindowKeyframe& kf, bool teleport) {
    if (teleport) {
        x_.snap((float)kf.x);
        y_.snap((float)kf.y);
        w_.snap((float)kf.w);
        h_.snap((float)kf.h);
        opa_.snap((float)kf.opa);
    } else {
        x_.set_target((float)kf.x);
        y_.set_target((float)kf.y);
        w_.set_target((float)kf.w);
        h_.set_target((float)kf.h);
        opa_.set_target((float)kf.opa);
    }
    apply_anim_values();
}

void Window::apply_anim_values() {
    if (!container_) return;
    int32_t ix = (int32_t)std::lround(x_.value());
    int32_t iy = (int32_t)std::lround(y_.value());
    int32_t iw = (int32_t)std::lround(w_.value());
    int32_t ih = (int32_t)std::lround(h_.value());
    int32_t io = (int32_t)std::lround(opa_.value());

    lv_obj_set_pos(container_, ix, iy);
    lv_obj_set_size(container_, iw, ih);
    lv_obj_set_style_opa(container_, io, 0);
    if (bg_close_) lv_obj_set_style_opa(bg_close_, (int32_t)(io * 0.4f), 0);
}

void Window::tick(float dt) {
    if (state_ == Closed) return;
    apply_anim_values();
    if (x_.settled() && y_.settled() && w_.settled() && h_.settled() && opa_.settled()) {
        if (state_ == Opening) {
            state_ = Opened;
        } else if (state_ == Closing) {
            state_ = Closed;
            /* Hide the (now invisible) container so it stops eating touches
             * meant for hotspots underneath it on the base screen. */
            if (container_) lv_obj_add_flag(container_, LV_OBJ_FLAG_HIDDEN);
            if (bg_close_) {
                lv_obj_clear_flag(bg_close_, LV_OBJ_FLAG_CLICKABLE);
                lv_obj_add_flag(bg_close_, LV_OBJ_FLAG_HIDDEN);
            }
        }
    }
}

void Window::open() {
    state_ = Opening;
    if (container_) {
        lv_obj_clear_flag(container_, LV_OBJ_FLAG_HIDDEN);
        lv_obj_add_flag(container_, LV_OBJ_FLAG_CLICKABLE);
    }
    if (bg_close_) {
        lv_obj_clear_flag(bg_close_, LV_OBJ_FLAG_HIDDEN);
        lv_obj_add_flag(bg_close_, LV_OBJ_FLAG_CLICKABLE);
    }
    apply_keyframe(config_.kf_opened, false);
}

void Window::close() {
    state_ = Closing;
    apply_keyframe(config_.kf_closed, false);
}

void Window::teleport_open() {
    state_ = Opened;
    if (container_) lv_obj_clear_flag(container_, LV_OBJ_FLAG_HIDDEN);
    if (bg_close_) {
        lv_obj_clear_flag(bg_close_, LV_OBJ_FLAG_HIDDEN);
        lv_obj_add_flag(bg_close_, LV_OBJ_FLAG_CLICKABLE);
    }
    apply_keyframe(config_.kf_opened, true);
}

void Window::teleport_close() {
    state_ = Closed;
    if (container_) lv_obj_add_flag(container_, LV_OBJ_FLAG_HIDDEN);
    if (bg_close_) {
        lv_obj_clear_flag(bg_close_, LV_OBJ_FLAG_CLICKABLE);
        lv_obj_add_flag(bg_close_, LV_OBJ_FLAG_HIDDEN);
    }
    apply_keyframe(config_.kf_closed, true);
}

/* --- event callbacks ------------------------------------------------------ */

static void win_close_on_click(lv_event_t* e) {
    auto* w = static_cast<Window*>(lv_event_get_user_data(e));
    if (w && w->state() != Window::Closing && w->state() != Window::Closed) w->close();
}

static void win_stop_propagate(lv_event_t* e) {
    /* Stop the event bubbling so a tap inside the window does not reach
     * the hotspot underneath it on the base screen. */
    lv_event_stop_bubbling(e);
}

#if defined(__clang__) || defined(__GNUC__)
#pragma GCC diagnostic pop
#endif

} /* namespace lvgl_ui */
} /* namespace lvgl_android */
