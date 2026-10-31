/*
 * Copyright (c) 2026 LVGL Android Framework Contributors
 * SPDX-License-Identifier: MIT
 *
 * This file is part of the LVGL Android Framework.
 * See LICENSE in the project root for full license information.
 *
 * Single source of truth for all LVGL demo/sample screens. Implements the
 * reusable UI kit and every screen declared in demo_ui.h.
 *
 * Rendering rules learned the hard way (see ai-context.md):
 *  - Fonts are fixed *pixel* sizes in LVGL, so every label must pick a font via
 *    ui_font(dp, dpi). Using lv_font_montserrat_14 directly yields ~5sp text.
 *  - A plain lv_obj has NO layout, so its children all stack at (0,0). Every
 *    container that holds more than one child sets an explicit flex layout
 *    (this is what made card titles/subtitles overlap before).
 *  - A plain lv_obj is CLICKABLE by default, so decorative children (labels,
 *    inner containers) steal taps from a clickable ancestor. Every decorative
 *    object must go through ui_decorative().
 *
 * @breadcrumb BC-004
 * @breadcrumb BC-005
 * @breadcrumb BC-006
 * @breadcrumb BC-016
 * @guard-rule rule=demo-ui-single-source test=HardRuleGuardTest
 * @do-not Do not build screens in lvgl_port.cpp; use this file.
 */
#include "demo_ui.h"

#include <android/log.h>
#include <cstring>

/* LVGL 9.x still exposes lv_obj_add/clear_flag (aliased to add/remove_flag) and
 * emits deprecation noise. They are correct and stable on 9.x, so we silence the
 * warning locally rather than switch to internal APIs. */
#if defined(__clang__) || defined(__GNUC__)
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
#endif

namespace lvgl_android {

/* -------------------------------------------------------------------------- */
/* Navigation hooks wired up by the port when the engine is created.          */
/* -------------------------------------------------------------------------- */
static void (*g_on_navigate_to)(int screen_id) = nullptr;
static void (*g_on_navigate_back)(void) = nullptr;

void ui_set_nav_hooks(void (*to)(int), void (*back)(void)) {
    g_on_navigate_to = to;
    g_on_navigate_back = back;
}

static void on_menu_card_clicked(lv_event_t* e);
static void on_back_clicked(lv_event_t* e);
static void on_screen_touch(lv_event_t* e);

/* -------------------------------------------------------------------------- */
/* Density + colour helpers                                                   */
/* -------------------------------------------------------------------------- */

int ui_dpx(int base, int dpi) {
    if (dpi <= 0) dpi = 160;
    int value = (base * dpi + 80) / 160;   /* round to nearest */
    return value < 1 ? 1 : value;
}

lv_color_t to_lv_color(int rgb) {
    return lv_color_make((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
}

/* Montserrat ladder — must match the LV_FONT_MONTSERRAT_* enabled in lv_conf.h.
 * LVGL ships Montserrat only up to size 48, so that is the cap. */
#define FONT_ENTRY(n) { n, &lv_font_montserrat_##n }
static const struct { int px; const lv_font_t* font; } kFontTable[] = {
    FONT_ENTRY(14), FONT_ENTRY(16), FONT_ENTRY(18), FONT_ENTRY(20),
    FONT_ENTRY(24), FONT_ENTRY(28), FONT_ENTRY(32), FONT_ENTRY(36),
    FONT_ENTRY(40), FONT_ENTRY(44), FONT_ENTRY(48),
};

const lv_font_t* ui_font(int dp, int dpi) {
    const int px = ui_dpx(dp, dpi);
    const lv_font_t* best = kFontTable[0].font;
    int best_delta = 1 << 30;
    for (const auto& entry : kFontTable) {
        int delta = entry.px > px ? entry.px - px : px - entry.px;
        if (delta < best_delta) {
            best_delta = delta;
            best = entry.font;
        }
    }
    return best;
}

/* Palette for the active theme. */
struct Palette {
    lv_color_t bg;
    lv_color_t card;
    lv_color_t text;
    lv_color_t dim;
    lv_color_t primary;
    lv_color_t on_primary;
    lv_color_t divider;
};

static Palette make_palette(const UiTheme& theme) {
    Palette p;
    if (theme.dark_mode) {
        p.bg         = lv_color_hex(0x101216);
        p.card       = lv_color_hex(0x1C1F26);
        p.text       = lv_color_hex(0xECEDEF);
        p.dim        = lv_color_hex(0x9AA1AC);
        p.on_primary = lv_color_hex(0xFFFFFF);
        p.divider    = lv_color_hex(0x2A2E37);
    } else {
        p.bg         = lv_color_hex(0xF4F5F9);
        p.card       = lv_color_hex(0xFFFFFF);
        p.text       = lv_color_hex(0x1B1B1F);
        p.dim        = lv_color_hex(0x6B7280);
        p.on_primary = lv_color_hex(0xFFFFFF);
        p.divider    = lv_color_hex(0xE3E6EB);
    }
    p.primary = to_lv_color(theme.primary);
    return p;
}

/* Active context for the helpers below (set once per screen build). */
static Palette g_pal   = make_palette(UiTheme());
static int     g_dpi   = 440;
static UiTheme g_theme = UiTheme();

/* Public accessor for the active display density (declared in demo_ui.h). */
int ui_dpi() {
    return g_dpi;
}

/* The mutable status label of the current screen (camera/game/launcher). */
static lv_obj_t* g_status_label = nullptr;

lv_obj_t* ui_current_status_label() {
    return g_status_label;
}

const UiTheme& ui_current_theme() {
    return g_theme;
}

/* -------------------------------------------------------------------------- */
/* Low-level container helpers                                                */
/* -------------------------------------------------------------------------- */

/* Make an object purely decorative: never focusable, never scrolling, and —
 * critically — never clickable, so it cannot swallow taps meant for a
 * clickable ancestor. */
void ui_decorative(lv_obj_t* o) {
    lv_obj_clear_flag(o, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_clear_flag(o, LV_OBJ_FLAG_SCROLLABLE);
}

/* Strip an object down to a transparent, borderless, non-interactive box. */
static void flatten(lv_obj_t* o) {
    lv_obj_set_style_bg_opa(o, LV_OPA_TRANSP, LV_PART_MAIN);
    lv_obj_set_style_border_width(o, 0, LV_PART_MAIN);
    lv_obj_set_style_radius(o, 0, LV_PART_MAIN);
    lv_obj_set_style_pad_all(o, 0, LV_PART_MAIN);
    ui_decorative(o);
}

/* Labelled text that never intercepts input. */
lv_obj_t* ui_make_label(lv_obj_t* parent, const char* text, int dp, lv_color_t color) {
    lv_obj_t* l = lv_label_create(parent);
    lv_label_set_text(l, text);
    lv_obj_set_style_text_color(l, color, 0);
    lv_obj_set_style_text_font(l, ui_font(dp, g_dpi), 0);
    ui_decorative(l);
    return l;
}

static lv_obj_t* make_row(lv_obj_t* parent, int gap_dp) {
    lv_obj_t* r = lv_obj_create(parent);
    lv_obj_set_layout(r, LV_LAYOUT_FLEX);
    lv_obj_set_flex_flow(r, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(r, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER,
                          LV_FLEX_ALIGN_CENTER);
    lv_obj_set_style_pad_column(r, ui_dpx(gap_dp, g_dpi), LV_PART_MAIN);
    lv_obj_set_width(r, LV_PCT(100));
    lv_obj_set_height(r, LV_SIZE_CONTENT);
    flatten(r);
    return r;
}

lv_obj_t* ui_make_column(lv_obj_t* parent, int gap_dp) {
    lv_obj_t* c = lv_obj_create(parent);
    lv_obj_set_layout(c, LV_LAYOUT_FLEX);
    lv_obj_set_flex_flow(c, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_flex_align(c, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_START,
                          LV_FLEX_ALIGN_START);
    lv_obj_set_style_pad_row(c, ui_dpx(gap_dp, g_dpi), LV_PART_MAIN);
    lv_obj_set_height(c, LV_SIZE_CONTENT);
    flatten(c);
    return c;
}

/* -------------------------------------------------------------------------- */
/* UI kit                                                                     */
/* -------------------------------------------------------------------------- */

static lv_obj_t* make_screen(const UiTheme& theme, int dpi) {
    g_dpi = dpi > 0 ? dpi : 440;
    g_pal = make_palette(theme);

    lv_obj_t* screen = lv_screen_active();
    lv_obj_clean(screen);
    lv_obj_set_width(screen, LV_PCT(100));
    lv_obj_set_height(screen, LV_PCT(100));
    lv_obj_set_layout(screen, LV_LAYOUT_FLEX);
    lv_obj_set_flex_flow(screen, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_flex_align(screen, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER,
                          LV_FLEX_ALIGN_CENTER);
    lv_obj_set_style_pad_all(screen, 0, LV_PART_MAIN);
    lv_obj_set_style_pad_row(screen, 0, LV_PART_MAIN);
    lv_obj_set_style_border_width(screen, 0, LV_PART_MAIN);
    lv_obj_set_style_radius(screen, 0, LV_PART_MAIN);
    lv_obj_set_style_bg_color(screen, g_pal.bg, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(screen, LV_OPA_COVER, LV_PART_MAIN);
    ui_decorative(screen);

    g_status_label = nullptr;
    lv_obj_set_user_data(screen, nullptr);
    return screen;
}

/* Header bar with an optional back button. Returns the title label. */
static lv_obj_t* make_header(lv_obj_t* parent, const char* title, bool show_back) {
    lv_obj_t* h = lv_obj_create(parent);
    lv_obj_set_width(h, LV_PCT(100));
    lv_obj_set_height(h, ui_dpx(64, g_dpi));
    lv_obj_set_layout(h, LV_LAYOUT_FLEX);
    lv_obj_set_flex_flow(h, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(h, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER,
                          LV_FLEX_ALIGN_CENTER);
    lv_obj_set_style_pad_left(h, ui_dpx(16, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_pad_right(h, ui_dpx(16, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_pad_column(h, ui_dpx(12, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_bg_opa(h, LV_OPA_TRANSP, LV_PART_MAIN);
    lv_obj_set_style_border_width(h, 0, LV_PART_MAIN);
    lv_obj_set_style_radius(h, 0, LV_PART_MAIN);
    ui_decorative(h);

    if (show_back) {
        lv_obj_t* btn = lv_button_create(h);
        lv_obj_set_size(btn, ui_dpx(40, g_dpi), ui_dpx(40, g_dpi));
        lv_obj_set_style_radius(btn, LV_RADIUS_CIRCLE, LV_PART_MAIN);
        lv_obj_set_style_bg_color(btn, g_pal.card, LV_PART_MAIN);
        lv_obj_set_style_bg_opa(btn, LV_OPA_COVER, LV_PART_MAIN);
        lv_obj_set_style_border_width(btn, 0, LV_PART_MAIN);
        lv_obj_set_style_shadow_width(btn, ui_dpx(4, g_dpi), LV_PART_MAIN);
        lv_obj_set_style_shadow_opa(btn, LV_OPA_20, LV_PART_MAIN);
        lv_obj_add_event_cb(btn, on_back_clicked, LV_EVENT_CLICKED, nullptr);
        lv_obj_t* glyph = ui_make_label(btn, LV_SYMBOL_LEFT, 18, g_pal.text);
        lv_obj_center(glyph);
    }

    lv_obj_t* label = ui_make_label(h, title, 21, g_pal.text);
    lv_obj_set_flex_grow(label, 1);
    lv_label_set_long_mode(label, LV_LABEL_LONG_DOT);
    return label;
}

/* Scrollable content region that fills the space between header and footer. */
static lv_obj_t* make_body(lv_obj_t* parent) {
    lv_obj_t* body = lv_obj_create(parent);
    lv_obj_set_width(body, LV_PCT(100));
    lv_obj_set_flex_grow(body, 1);
    lv_obj_set_layout(body, LV_LAYOUT_FLEX);
    lv_obj_set_flex_flow(body, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_flex_align(body, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER,
                          LV_FLEX_ALIGN_CENTER);
    lv_obj_set_style_pad_left(body, ui_dpx(16, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_pad_right(body, ui_dpx(16, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_pad_top(body, ui_dpx(8, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_pad_bottom(body, ui_dpx(16, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_pad_row(body, ui_dpx(12, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_bg_opa(body, LV_OPA_TRANSP, LV_PART_MAIN);
    lv_obj_set_style_border_width(body, 0, LV_PART_MAIN);
    lv_obj_set_style_radius(body, 0, LV_PART_MAIN);
    lv_obj_set_scroll_dir(body, LV_DIR_VER);
    lv_obj_set_scrollbar_mode(body, LV_SCROLLBAR_MODE_AUTO);
    lv_obj_clear_flag(body, LV_OBJ_FLAG_CLICKABLE);   /* let children receive taps */
    return body;
}

/* A rounded surface card ("elevated" panel). */
lv_obj_t* ui_make_panel(lv_obj_t* parent) {
    lv_obj_t* card = lv_obj_create(parent);
    lv_obj_set_width(card, LV_PCT(100));
    lv_obj_set_height(card, LV_SIZE_CONTENT);
    lv_obj_set_style_pad_all(card, ui_dpx(16, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_radius(card, ui_dpx(16, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_bg_color(card, g_pal.card, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(card, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_border_width(card, 0, LV_PART_MAIN);
    lv_obj_set_style_shadow_width(card, ui_dpx(8, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_shadow_opa(card, LV_OPA_10, LV_PART_MAIN);
    lv_obj_set_style_shadow_offset_y(card, ui_dpx(2, g_dpi), LV_PART_MAIN);
    lv_obj_clear_flag(card, LV_OBJ_FLAG_SCROLLABLE);
    return card;
}

/* A panel laid out as a vertical group with an optional section caption. */
static lv_obj_t* make_group(lv_obj_t* parent, const char* caption) {
    lv_obj_t* card = ui_make_panel(parent);
    lv_obj_set_layout(card, LV_LAYOUT_FLEX);
    lv_obj_set_flex_flow(card, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_flex_align(card, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_START,
                          LV_FLEX_ALIGN_START);
    lv_obj_set_style_pad_row(card, ui_dpx(14, g_dpi), LV_PART_MAIN);
    if (caption) {
        ui_make_label(card, caption, 13, g_pal.dim);
    }
    return card;
}

/* A labelled row: left caption (fixed width) + widget(s) appended after. */
static lv_obj_t* make_labeled_row(lv_obj_t* parent, const char* caption) {
    lv_obj_t* row = make_row(parent, 16);
    lv_obj_t* l = ui_make_label(row, caption, 15, g_pal.text);
    lv_obj_set_width(l, ui_dpx(120, g_dpi));
    lv_label_set_long_mode(l, LV_LABEL_LONG_DOT);
    return row;
}

/* Attach a status label to the current screen so nativeSetStatusText works. */
static lv_obj_t* make_status_line(lv_obj_t* parent, const char* initial) {
    lv_obj_t* status = ui_make_label(parent, initial, 15, g_pal.primary);
    lv_label_set_long_mode(status, LV_LABEL_LONG_WRAP);
    lv_obj_set_width(status, LV_PCT(100));
    lv_obj_set_user_data(status, (void*)"ui_status");
    lv_obj_set_user_data(lv_screen_active(), status);
    g_status_label = status;
    return status;
}

static void make_footer(lv_obj_t* parent, const char* text) {
    lv_obj_t* l = ui_make_label(parent, text, 12, g_pal.dim);
    lv_obj_set_style_pad_all(l, ui_dpx(12, g_dpi), 0);
    lv_obj_set_style_text_align(l, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_set_width(l, LV_PCT(100));
}

/* -------------------------------------------------------------------------- */
/* Widget wrappers                                                            */
/* -------------------------------------------------------------------------- */

static lv_obj_t* make_button(lv_obj_t* parent, const char* text) {
    lv_obj_t* b = lv_button_create(parent);
    lv_obj_set_size(b, ui_dpx(140, g_dpi), ui_dpx(44, g_dpi));
    lv_obj_set_style_radius(b, ui_dpx(22, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_bg_color(b, g_pal.primary, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(b, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_shadow_width(b, ui_dpx(6, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_shadow_opa(b, LV_OPA_20, LV_PART_MAIN);
    lv_obj_t* l = ui_make_label(b, text, 15, g_pal.on_primary);
    lv_obj_center(l);
    return b;
}

static void make_slider(lv_obj_t* parent, int value) {
    lv_obj_t* s = lv_slider_create(parent);
    lv_obj_set_flex_grow(s, 1);
    lv_obj_set_height(s, ui_dpx(14, g_dpi));
    lv_obj_set_style_bg_color(s, g_pal.divider, LV_PART_MAIN);
    lv_obj_set_style_radius(s, ui_dpx(7, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_bg_color(s, g_pal.primary, LV_PART_INDICATOR);
    lv_obj_set_style_radius(s, ui_dpx(7, g_dpi), LV_PART_INDICATOR);
    /* Knob: a light circle with a primary ring, so it reads on both palettes. */
    lv_obj_set_style_radius(s, LV_RADIUS_CIRCLE, LV_PART_KNOB);
    lv_obj_set_style_bg_color(s, g_pal.card, LV_PART_KNOB);
    lv_obj_set_style_bg_opa(s, LV_OPA_COVER, LV_PART_KNOB);
    lv_obj_set_style_border_color(s, g_pal.primary, LV_PART_KNOB);
    lv_obj_set_style_border_width(s, ui_dpx(3, g_dpi), LV_PART_KNOB);
    lv_obj_set_style_pad_all(s, ui_dpx(6, g_dpi), LV_PART_KNOB);
    lv_slider_set_value(s, value, LV_ANIM_OFF);
}

static void make_switch(lv_obj_t* parent, bool on) {
    lv_obj_t* sw = lv_switch_create(parent);
    lv_obj_set_style_bg_color(sw, g_pal.primary, LV_PART_INDICATOR | LV_STATE_CHECKED);
    if (on) lv_obj_add_state(sw, LV_STATE_CHECKED);
}

static void make_checkbox(lv_obj_t* parent, const char* text) {
    lv_obj_t* cb = lv_checkbox_create(parent);
    lv_checkbox_set_text(cb, text);
    lv_obj_set_style_text_color(cb, g_pal.text, 0);
    lv_obj_set_style_text_font(cb, ui_font(15, g_dpi), 0);
    /* Theme the box (the theme default is a light background that clashes with
     * dark cards). */
    lv_obj_set_style_bg_color(cb, g_pal.card, LV_PART_INDICATOR);
    lv_obj_set_style_bg_opa(cb, LV_OPA_COVER, LV_PART_INDICATOR);
    lv_obj_set_style_border_color(cb, g_pal.dim, LV_PART_INDICATOR);
    lv_obj_set_style_border_width(cb, ui_dpx(2, g_dpi), LV_PART_INDICATOR);
    lv_obj_set_style_radius(cb, ui_dpx(6, g_dpi), LV_PART_INDICATOR);
    lv_obj_set_style_bg_color(cb, g_pal.primary, LV_PART_INDICATOR | LV_STATE_CHECKED);
    lv_obj_set_style_text_color(cb, g_pal.on_primary, LV_PART_INDICATOR | LV_STATE_CHECKED);
}

static void make_bar(lv_obj_t* parent, int value) {
    lv_obj_t* bar = lv_bar_create(parent);
    lv_obj_set_flex_grow(bar, 1);
    lv_obj_set_height(bar, ui_dpx(14, g_dpi));
    lv_obj_set_style_radius(bar, ui_dpx(7, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_radius(bar, ui_dpx(7, g_dpi), LV_PART_INDICATOR);
    lv_obj_set_style_bg_color(bar, g_pal.divider, LV_PART_MAIN);
    lv_obj_set_style_bg_color(bar, g_pal.primary, LV_PART_INDICATOR);
    lv_bar_set_value(bar, value, LV_ANIM_OFF);
}

static void make_arc(lv_obj_t* parent, int value) {
    lv_obj_t* arc = lv_arc_create(parent);
    lv_obj_set_size(arc, ui_dpx(92, g_dpi), ui_dpx(92, g_dpi));
    lv_arc_set_bg_angles(arc, 135, 45);
    lv_obj_set_style_arc_color(arc, g_pal.divider, LV_PART_MAIN);
    lv_obj_set_style_arc_width(arc, ui_dpx(8, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_arc_color(arc, g_pal.primary, LV_PART_INDICATOR);
    lv_obj_set_style_arc_width(arc, ui_dpx(8, g_dpi), LV_PART_INDICATOR);
    lv_obj_set_style_bg_color(arc, g_pal.primary, LV_PART_KNOB);
    lv_arc_set_value(arc, value);
}

static void make_dropdown(lv_obj_t* parent, const char* options) {
    lv_obj_t* dd = lv_dropdown_create(parent);
    lv_obj_set_width(dd, ui_dpx(170, g_dpi));
    lv_dropdown_set_options(dd, options);
    lv_obj_set_style_text_font(dd, ui_font(15, g_dpi), 0);
    lv_obj_set_style_text_color(dd, g_pal.text, 0);
    /* The theme leaves the box light; without an explicit background it stays
     * white (with pale text) on a dark card. */
    lv_obj_set_style_bg_color(dd, g_pal.card, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(dd, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_border_width(dd, ui_dpx(1, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_border_color(dd, g_pal.divider, LV_PART_MAIN);
    lv_obj_set_style_radius(dd, ui_dpx(10, g_dpi), LV_PART_MAIN);
    /* Arrow indicator. */
    lv_obj_set_style_text_color(dd, g_pal.dim, LV_PART_INDICATOR);
    /* The popup list is a separate object with its own (light) defaults. */
    lv_obj_t* list = lv_dropdown_get_list(dd);
    if (list) {
        lv_obj_set_style_bg_color(list, g_pal.card, LV_PART_MAIN);
        lv_obj_set_style_bg_opa(list, LV_OPA_COVER, LV_PART_MAIN);
        lv_obj_set_style_text_color(list, g_pal.text, LV_PART_MAIN);
        lv_obj_set_style_text_font(list, ui_font(15, g_dpi), LV_PART_MAIN);
        lv_obj_set_style_border_width(list, ui_dpx(1, g_dpi), LV_PART_MAIN);
        lv_obj_set_style_border_color(list, g_pal.divider, LV_PART_MAIN);
    }
}

/* -------------------------------------------------------------------------- */
/* Event callbacks                                                            */
/* -------------------------------------------------------------------------- */

static void on_menu_card_clicked(lv_event_t* e) {
    int* id = static_cast<int*>(lv_event_get_user_data(e));
    __android_log_print(ANDROID_LOG_INFO, "LVGLMenu", "card clicked id=%d", id ? *id : -1);
    if (id && g_on_navigate_to) g_on_navigate_to(*id);
}



static void on_back_clicked(lv_event_t* /*e*/) {
    if (g_on_navigate_back) g_on_navigate_back();
}

/* -------------------------------------------------------------------------- */
/* Screen builders                                                            */
/* -------------------------------------------------------------------------- */

void build_menu_screen(int /*dpi*/) {
    lv_obj_t* screen = make_screen(g_theme, g_dpi);
    make_header(screen, "LVGL Android Framework", false);

    lv_obj_t* body = make_body(screen);

    lv_obj_t* caption = ui_make_label(body, "Tap a sample to open it", 14, g_pal.dim);
    lv_obj_set_width(caption, LV_PCT(100));

    static const struct { const char* title; const char* sub; int id; int accent; } items[] = {
        {"Widget Showcase",  "Buttons, sliders, switches and more", SCREEN_WIDGET,      0x1976D2},
        {"Layout Showcase",  "Flexbox layout with wrapping tiles",  SCREEN_LAYOUT,      0x388E3C},
        {"Interactive Demo", "Touch to move the on-screen marker",  SCREEN_INTERACTIVE, 0xDC004E},
        {"Camera Sample",    "Live Camera2 preview status",         SCREEN_CAMERA,      0xF57C00},
        {"Game Sample",      "60 fps loop with a score counter",    SCREEN_GAME,        0x7B1FA2},
        {"Launcher Sample",  "Scrollable list of installed apps",   SCREEN_LAUNCHER,    0x00838F},
        {"Showcase",         "Spring-physics animated demo",        SCREEN_SHOWCASE,    0x00BFA5},
    };
    static int ids[7];
    for (int i = 0; i < 7; i++) ids[i] = items[i].id;

    for (int i = 0; i < 7; i++) {
        lv_obj_t* card = ui_make_panel(body);
        lv_obj_set_layout(card, LV_LAYOUT_FLEX);
        lv_obj_set_flex_flow(card, LV_FLEX_FLOW_ROW);
        lv_obj_set_flex_align(card, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER,
                              LV_FLEX_ALIGN_CENTER);
        lv_obj_set_style_pad_all(card, ui_dpx(14, g_dpi), LV_PART_MAIN);
        lv_obj_set_style_pad_column(card, ui_dpx(14, g_dpi), LV_PART_MAIN);
        lv_obj_add_event_cb(card, on_menu_card_clicked, LV_EVENT_CLICKED, &ids[i]);

        /* Leading accent bar. */
        lv_obj_t* accent = lv_obj_create(card);
        lv_obj_set_size(accent, ui_dpx(6, g_dpi), ui_dpx(38, g_dpi));
        lv_obj_set_style_radius(accent, ui_dpx(3, g_dpi), LV_PART_MAIN);
        lv_obj_set_style_bg_color(accent, lv_color_hex(items[i].accent), LV_PART_MAIN);
        lv_obj_set_style_bg_opa(accent, LV_OPA_COVER, LV_PART_MAIN);
        lv_obj_set_style_border_width(accent, 0, LV_PART_MAIN);
        ui_decorative(accent);

        /* Title + subtitle column (this is what used to overlap). */
        lv_obj_t* col = ui_make_column(card, 4);
        lv_obj_set_flex_grow(col, 1);

        lv_obj_t* title = ui_make_label(col, items[i].title, 17, g_pal.text);
        lv_obj_set_width(title, LV_PCT(100));
        lv_label_set_long_mode(title, LV_LABEL_LONG_DOT);

        lv_obj_t* sub = ui_make_label(col, items[i].sub, 13, g_pal.dim);
        lv_obj_set_width(sub, LV_PCT(100));
        lv_label_set_long_mode(sub, LV_LABEL_LONG_DOT);

        lv_obj_t* chevron = ui_make_label(card, LV_SYMBOL_RIGHT, 16, g_pal.dim);
        (void)chevron;
    }
}

void build_widget_screen(int /*dpi*/) {
    lv_obj_t* screen = make_screen(g_theme, g_dpi);
    make_header(screen, "Widget Showcase", true);

    lv_obj_t* body = make_body(screen);

    lv_obj_t* controls = make_group(body, "BASIC CONTROLS");
    make_button(make_labeled_row(controls, "Button"), "Tap me");
    make_switch(make_labeled_row(controls, "Switch"), true);
    make_checkbox(make_labeled_row(controls, "Checkbox"), "Enabled");

    lv_obj_t* values = make_group(body, "VALUES");
    make_slider(make_labeled_row(values, "Slider"), 40);
    make_bar(make_labeled_row(values, "Progress"), 65);
    make_arc(make_labeled_row(values, "Arc"), 70);

    lv_obj_t* selection = make_group(body, "SELECTION");
    make_dropdown(make_labeled_row(selection, "Dropdown"), "Option A\nOption B\nOption C");

    make_footer(screen, "Drag the slider, switch or arc");
}

void build_layout_screen(int /*dpi*/) {
    lv_obj_t* screen = make_screen(g_theme, g_dpi);
    make_header(screen, "Layout Showcase", true);

    lv_obj_t* body = make_body(screen);

    lv_obj_t* caption = ui_make_label(body, "Flex row-wrap container", 14, g_pal.dim);
    lv_obj_set_width(caption, LV_PCT(100));

    lv_obj_t* flex = ui_make_panel(body);
    lv_obj_set_layout(flex, LV_LAYOUT_FLEX);
    lv_obj_set_flex_flow(flex, LV_FLEX_FLOW_ROW_WRAP);
    lv_obj_set_flex_align(flex, LV_FLEX_ALIGN_SPACE_EVENLY, LV_FLEX_ALIGN_CENTER,
                          LV_FLEX_ALIGN_CENTER);
    lv_obj_set_style_pad_all(flex, ui_dpx(16, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_pad_row(flex, ui_dpx(12, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_pad_column(flex, ui_dpx(12, g_dpi), LV_PART_MAIN);

    static const char* labels[] = {"A", "B", "C", "D", "E", "F"};
    static const int tile_colors[] = {
        0x1976D2, 0x388E3C, 0xDC004E, 0xF57C00, 0x7B1FA2, 0x00838F,
    };
    for (int i = 0; i < 6; i++) {
        lv_obj_t* tile = lv_obj_create(flex);
        lv_obj_set_size(tile, ui_dpx(72, g_dpi), ui_dpx(72, g_dpi));
        lv_obj_set_style_radius(tile, ui_dpx(16, g_dpi), LV_PART_MAIN);
        lv_obj_set_style_bg_color(tile, lv_color_hex(tile_colors[i]), LV_PART_MAIN);
        lv_obj_set_style_bg_opa(tile, LV_OPA_COVER, LV_PART_MAIN);
        lv_obj_set_style_border_width(tile, 0, LV_PART_MAIN);
        ui_decorative(tile);
        lv_obj_t* l = ui_make_label(tile, labels[i], 22, lv_color_hex(0xFFFFFF));
        lv_obj_center(l);
    }

    make_footer(screen, "Tiles wrap with LV_FLEX_FLOW_ROW_WRAP");
}

/* Interactive demo: a marker that follows touch. */
static lv_obj_t* g_marker = nullptr;

static void on_screen_touch(lv_event_t* /*e*/) {
    if (!g_marker) return;
    lv_indev_t* indev = lv_indev_active();
    if (!indev) return;
    lv_point_t p;
    lv_indev_get_point(indev, &p);
    lv_obj_set_pos(g_marker, p.x - ui_dpx(20, g_dpi), p.y - ui_dpx(20, g_dpi));
}

void build_interactive_screen(int /*dpi*/) {
    lv_obj_t* screen = make_screen(g_theme, g_dpi);
    make_header(screen, "Interactive Demo", true);

    lv_obj_t* hint = ui_make_label(screen, "Touch anywhere to move the marker", 14, g_pal.dim);
    lv_obj_set_width(hint, LV_PCT(100));
    lv_obj_set_style_pad_left(hint, ui_dpx(16, g_dpi), 0);
    lv_obj_set_style_pad_right(hint, ui_dpx(16, g_dpi), 0);
    lv_obj_set_style_pad_top(hint, ui_dpx(4, g_dpi), 0);
    lv_obj_set_style_pad_bottom(hint, ui_dpx(8, g_dpi), 0);

    /* Full-size touch surface that catches presses. */
    lv_obj_t* surface = lv_obj_create(screen);
    lv_obj_set_width(surface, LV_PCT(100));
    lv_obj_set_flex_grow(surface, 1);
    lv_obj_set_style_bg_opa(surface, LV_OPA_TRANSP, LV_PART_MAIN);
    lv_obj_set_style_border_width(surface, 0, LV_PART_MAIN);
    lv_obj_set_style_radius(surface, 0, LV_PART_MAIN);
    lv_obj_set_style_pad_all(surface, 0, LV_PART_MAIN);
    lv_obj_set_scrollbar_mode(surface, LV_SCROLLBAR_MODE_OFF);
    lv_obj_add_event_cb(surface, on_screen_touch, LV_EVENT_PRESSED, nullptr);
    lv_obj_add_event_cb(surface, on_screen_touch, LV_EVENT_PRESSING, nullptr);

    g_marker = lv_obj_create(screen);
    lv_obj_add_flag(g_marker, LV_OBJ_FLAG_IGNORE_LAYOUT);
    lv_obj_set_size(g_marker, ui_dpx(40, g_dpi), ui_dpx(40, g_dpi));
    lv_obj_set_style_radius(g_marker, LV_RADIUS_CIRCLE, 0);
    lv_obj_set_style_bg_color(g_marker, lv_color_hex(0xDC004E), 0);
    lv_obj_set_style_bg_opa(g_marker, LV_OPA_COVER, 0);
    lv_obj_set_style_border_width(g_marker, ui_dpx(4, g_dpi), 0);
    lv_obj_set_style_border_color(g_marker, lv_color_hex(0xFFFFFF), 0);
    ui_decorative(g_marker);
    lv_obj_set_pos(g_marker, LV_HOR_RES / 2 - ui_dpx(20, g_dpi),
                   LV_VER_RES / 2 - ui_dpx(20, g_dpi));

    make_footer(screen, "The marker follows your finger");
}

/* Shared body for the "sample" screens: a panel with a title + live status. */
static void build_status_screen(const char* header, const char* title,
                                const char* initial_status, const char* footer) {
    lv_obj_t* screen = make_screen(g_theme, g_dpi);
    make_header(screen, header, true);

    lv_obj_t* body = make_body(screen);
    lv_obj_t* panel = make_group(body, nullptr);

    ui_make_label(panel, title, 17, g_pal.text);
    make_status_line(panel, initial_status);
    make_footer(screen, footer);
}

void build_camera_screen(int /*dpi*/) {
    build_status_screen("Camera Sample", "Camera2 Preview", "Camera status: idle",
                        "Status updates from the native Camera2 API");
}

void build_game_screen(int /*dpi*/) {
    build_status_screen("Game Sample", "Game Loop (60 FPS)", "Score: 0  |  Frames: 0",
                        "Vsync-aware loop driven from Choreographer");
}

void build_launcher_screen(int /*dpi*/) {
    build_status_screen("Launcher Sample", "Installed Apps", "Loading installed apps...",
                        "List populated from the Android package manager");
}

/* The animated showcase is a separate, self-contained demo and is implemented in
 * demo_showcase.cpp, not here, to keep the spring-physics demo code isolated. */
void build_showcase_screen(int dpi) {
    extern void build_showcase_screen_impl(int dpi);
    build_showcase_screen_impl(dpi);
}

/* -------------------------------------------------------------------------- */
/* Entry point                                                                */
/* -------------------------------------------------------------------------- */

typedef void (*ScreenBuilder)(int dpi);

void launch_screen(int screen_id, int dpi, const UiTheme& theme) {
    static const ScreenBuilder builders[] = {
        build_menu_screen,
        build_widget_screen,
        build_layout_screen,
        build_interactive_screen,
        build_camera_screen,
        build_game_screen,
        build_launcher_screen,
        build_showcase_screen,
    };

    if (screen_id < 0 || screen_id >= SCREEN_COUNT) {
        screen_id = SCREEN_MENU;   /* never show a blank screen */
    }

    /* Leaving the showcase must release its frame callback + animated state,
     * otherwise showcase_tick dereferences objects freed by lv_obj_clean(). */
    if (screen_id != SCREEN_SHOWCASE) {
        ui_reset_showcase();
    }

    g_dpi = dpi > 0 ? dpi : 440;
    g_theme = theme;
    g_pal = make_palette(theme);
    g_marker = nullptr;

    builders[screen_id](dpi);

    lv_obj_invalidate(lv_screen_active());
}

#if defined(__clang__) || defined(__GNUC__)
#pragma GCC diagnostic pop
#endif

} /* namespace lvgl_android */
