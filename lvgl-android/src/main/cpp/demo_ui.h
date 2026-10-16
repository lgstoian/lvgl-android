/*
 * Copyright (c) 2026 LVGL Android Framework Contributors
 * SPDX-License-Identifier: MIT
 */
#include "lvgl/lvgl.h"

namespace lvgl_android {

/*
 * IMPORTANT: LVGL color format is {blue, green, red, alpha} in memory (BGRA).
 * Android ANativeWindow RGBA_8888 format expects {red, green, blue, alpha}.
 * The R/B swap is performed in the flush callback before copying to the window.
 */

static void create_default_ui() {
    lv_obj_t* screen = lv_screen_active();

    /* Title label */
    lv_obj_t* title = lv_label_create(screen);
    lv_label_set_text(title, "LVGL v9.6.0 on Android");
    lv_obj_set_style_text_font(title, &lv_font_montserrat_20, 0);
    lv_obj_align(title, LV_ALIGN_TOP_MID, 0, 40);

    /* Subtitle */
    lv_obj_t* subtitle = lv_label_create(screen);
    lv_label_set_text(subtitle, "Light and Versatile Graphics Library");
    lv_obj_set_style_text_font(subtitle, &lv_font_montserrat_14, 0);
    lv_obj_align(subtitle, LV_ALIGN_TOP_MID, 0, 75);

    /* Button */
    lv_obj_t* btn = lv_button_create(screen);
    lv_obj_set_size(btn, 220, 60);
    lv_obj_align(btn, LV_ALIGN_CENTER, 0, -60);

    lv_obj_t* btn_label = lv_label_create(btn);
    lv_label_set_text(btn_label, "Click Me!");
    lv_obj_center(btn_label);

    /* Click counter */
    lv_obj_t* counter_label = lv_label_create(screen);
    lv_label_set_text(counter_label, "Clicks: 0");
    lv_obj_align(counter_label, LV_ALIGN_CENTER, 0, 10);

    /* Slider */
    lv_obj_t* slider = lv_slider_create(screen);
    lv_obj_set_width(slider, 350);
    lv_obj_align(slider, LV_ALIGN_BOTTOM_MID, 0, -80);
    lv_slider_set_value(slider, 50, LV_ANIM_OFF);

    /* Slider value label */
    lv_obj_t* slider_label = lv_label_create(screen);
    lv_label_set_text(slider_label, "Slider: 50");
    lv_obj_align(slider_label, LV_ALIGN_BOTTOM_MID, 0, -50);

    /* Arc indicator */
    lv_obj_t* arc = lv_arc_create(screen);
    lv_obj_set_size(arc, 140, 140);
    lv_obj_align(arc, LV_ALIGN_TOP_RIGHT, -25, 120);
    lv_arc_set_value(arc, 75);
    lv_arc_set_bg_angles(arc, 135, 45);

    /* Switch */
    lv_obj_t* sw = lv_switch_create(screen);
    lv_obj_align(sw, LV_ALIGN_TOP_LEFT, 25, 130);

    lv_obj_t* sw_label = lv_label_create(screen);
    lv_label_set_text(sw_label, "Toggle");
    lv_obj_align_to(sw_label, sw, LV_ALIGN_OUT_BOTTOM_MID, 0, 8);

    /* Bar indicator */
    lv_obj_t* bar = lv_bar_create(screen);
    lv_obj_set_size(bar, 200, 20);
    lv_obj_align(bar, LV_ALIGN_CENTER, 0, 70);
    lv_bar_set_value(bar, 60, LV_ANIM_ON);

    /* Footer */
    lv_obj_t* footer = lv_label_create(screen);
    lv_label_set_text(footer, "Build: Debug | ABI: arm64-v8a");
    lv_obj_align(footer, LV_ALIGN_BOTTOM_MID, 0, -15);
}

static void launch_demo_ui(int demo_id) {
    /* Clean the active screen before loading a new demo */
    lv_obj_t* screen = lv_screen_active();
    lv_obj_clean(screen);

    if (demo_id == 0) {
        /* Widget showcase */
    } else if (demo_id == 1) {
        /* Layout showcase */
        lv_obj_t* title = lv_label_create(screen);
        lv_label_set_text(title, "Layout Showcase");
        lv_obj_align(title, LV_ALIGN_TOP_MID, 0, 40);

        lv_obj_t* flex = lv_obj_create(screen);
        lv_obj_set_size(flex, 300, 150);
        lv_obj_align(flex, LV_ALIGN_CENTER, 0, 0);
        lv_obj_set_flex_flow(flex, LV_FLEX_FLOW_ROW_WRAP);

        static const char* labels[] = {"A", "B", "C", "D"};
        for (int i = 0; i < 4; i++) {
            lv_obj_t* l = lv_label_create(flex);
            lv_label_set_text(l, labels[i]);
            lv_obj_set_size(l, 40, 40);
        }
    } else if (demo_id == 2) {
        /* Interactive demo */
        lv_obj_t* title = lv_label_create(screen);
        lv_label_set_text(title, "Interactive Demo");
        lv_obj_align(title, LV_ALIGN_TOP_MID, 0, 40);
    }
}

} /* namespace lvgl_android */
