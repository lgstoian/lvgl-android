#include "lvgl/lvgl.h"

namespace lvgl_android {

static void create_widget_showcase_ui() {
    lv_obj_t* screen = lv_screen_active();

    lv_obj_set_style_bg_color(screen, lv_color_hex(0x1E1E1E), 0);
    lv_obj_set_style_bg_opa(screen, LV_OPA_COVER, 0);

    lv_obj_t* title = lv_label_create(screen);
    lv_label_set_text(title, "Widget Showcase");
    lv_obj_set_style_text_color(title, lv_color_hex(0xFFFFFF), 0);
    lv_obj_align(title, LV_ALIGN_TOP_MID, 0, 20);

    lv_obj_t* btn = lv_button_create(screen);
    lv_obj_set_size(btn, 120, 50);
    lv_obj_align(btn, LV_ALIGN_CENTER, -100, 0);
    lv_obj_t* lblBtn = lv_label_create(btn);
    lv_label_set_text(lblBtn, "Click Me!");
    lv_obj_center(lblBtn);

    lv_obj_t* sld = lv_slider_create(screen);
    lv_obj_set_width(sld, 120);
    lv_obj_align(sld, LV_ALIGN_CENTER, 100, 0);
    lv_slider_set_value(sld, 60, LV_ANIM_OFF);

    lv_obj_t* sw = lv_switch_create(screen);
    lv_obj_align(sw, LV_ALIGN_BOTTOM_MID, -50, -40);

    lv_obj_t* arch = lv_arc_create(screen);
    lv_obj_set_size(arch, 80, 80);
    lv_obj_align(arch, LV_ALIGN_BOTTOM_MID, 50, -40);
    lv_arc_set_value(arch, 75);
}

static void create_layout_showcase_ui() {
    lv_obj_t* screen = lv_screen_active();

    lv_obj_set_style_bg_color(screen, lv_color_hex(0x1E1E1E), 0);
    lv_obj_set_style_bg_opa(screen, LV_OPA_COVER, 0);

    lv_obj_t* title = lv_label_create(screen);
    lv_label_set_text(title, "Layout Showcase");
    lv_obj_set_style_text_color(title, lv_color_hex(0xFFFFFF), 0);
    lv_obj_align(title, LV_ALIGN_TOP_MID, 0, 20);

    lv_obj_t* flex = lv_obj_create(screen);
    lv_obj_set_size(flex, 300, 150);
    lv_obj_align(flex, LV_ALIGN_CENTER, 0, 0);
    lv_obj_set_flex_flow(flex, LV_FLEX_FLOW_ROW_WRAP);
    lv_obj_set_flex_align(flex, LV_FLEX_ALIGN_SPACE_EVENLY, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);

    static const char* labels[] = {"A", "B", "C", "D"};
    for (int i = 0; i < 4; i++) {
        lv_obj_t* l = lv_label_create(flex);
        lv_label_set_text(l, labels[i]);
        lv_obj_set_style_bg_color(l, lv_color_hex(0x2196F3), 0);
        lv_obj_set_style_bg_opa(l, LV_OPA_COVER, 0);
        lv_obj_set_size(l, 40, 40);
        lv_obj_set_style_radius(l, 5, 0);
        lv_obj_set_style_text_color(l, lv_color_hex(0xFFFFFF), 0);
    }
}

static void create_interactive_ui() {
    lv_obj_t* screen = lv_screen_active();

    lv_obj_set_style_bg_color(screen, lv_color_hex(0x1E1E1E), 0);
    lv_obj_set_style_bg_opa(screen, LV_OPA_COVER, 0);

    lv_obj_t* title = lv_label_create(screen);
    lv_label_set_text(title, "Interactive Demo");
    lv_obj_set_style_text_color(title, lv_color_hex(0xFFFFFF), 0);
    lv_obj_align(title, LV_ALIGN_TOP_MID, 0, 20);

    lv_obj_t* btn = lv_button_create(screen);
    lv_obj_set_size(btn, 180, 50);
    lv_obj_align(btn, LV_ALIGN_CENTER, 0, -40);

    lv_obj_t* lblBtn = lv_label_create(btn);
    lv_label_set_text(lblBtn, "Touch Me!");
    lv_obj_center(lblBtn);

    lv_obj_t* info = lv_label_create(screen);
    lv_label_set_text(info, "Touch the button to see state changes.");
    lv_obj_set_style_text_color(info, lv_color_hex(0xAAAAAA), 0);
    lv_obj_align(info, LV_ALIGN_CENTER, 0, 20);
}

void launch_demo_ui(int demo_id);

} /* namespace lvgl_android */
