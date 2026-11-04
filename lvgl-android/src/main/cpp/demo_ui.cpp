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
#include <cstdio>
#include <cstdlib>
#include <cmath>
#include <cstring>
#include <ctime>
#include <string>
#include <utility>
#include <vector>

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

/* ---- System-control hooks (dashboard panels -> Android) --------------------
 * Installed once by the port so the backlight / volume panels can drive real
 * Android APIs through the ScreenNavigator bridge. Both may be null, in which
 * case the panels stay visual only. @breadcrumb BC-023 */
static void (*g_on_set_brightness)(int percent) = nullptr;
static void (*g_on_set_volume)(int percent) = nullptr;

void ui_set_system_hooks(void (*set_brightness_percent)(int percent),
                         void (*set_volume_percent)(int percent)) {
    g_on_set_brightness = set_brightness_percent;
    g_on_set_volume     = set_volume_percent;
}

/* ---- Theme hook (dashboard FAB -> Android) --------------------------------
 * Installed once by the port so the dashboard's theme FAB can ask Kotlin to
 * toggle dark mode. May be null (the FAB then does nothing). */
static void (*g_on_toggle_dark_mode)(void) = nullptr;

void ui_set_theme_hook(void (*toggle_dark_mode)(void)) {
    g_on_toggle_dark_mode = toggle_dark_mode;
}

/* ---- Status-bar state ------------------------------------------------------
 * The clock is driven by a 1 s lv_timer; the battery level is pushed from
 * Kotlin. Labels are re-created on every dashboard build, so the pointers are
 * cleared in make_screen(). @breadcrumb BC-022 */
static lv_obj_t* g_clock_label      = nullptr;
static lv_obj_t* g_date_label       = nullptr;
static lv_obj_t* g_rtc_clock_label  = nullptr;
static lv_obj_t* g_rtc_date_label   = nullptr;
static lv_obj_t* g_battery_label    = nullptr;
static lv_obj_t* g_battery_glyph    = nullptr;
static lv_timer_t* g_clock_timer    = nullptr;
static int  g_battery_level         = -1;
static bool g_battery_charging      = false;

/* Snackbar overlay + its auto-dismiss timer. Cleared in make_screen() before
 * the screen is cleaned, so a pending timer can never touch a freed object. */
static lv_obj_t*  g_snackbar        = nullptr;
static lv_timer_t* g_snackbar_timer = nullptr;

static void clock_tick(lv_timer_t* /*timer*/) {
    std::time_t now = std::time(nullptr);
    struct tm local;
    localtime_r(&now, &local);
    char time_buf[16];
    char date_buf[24];
    std::strftime(time_buf, sizeof(time_buf), "%H:%M:%S", &local);
    std::strftime(date_buf, sizeof(date_buf), "%Y/%m/%d", &local);
    if (g_clock_label)     lv_label_set_text(g_clock_label, time_buf);
    if (g_rtc_clock_label) lv_label_set_text(g_rtc_clock_label, time_buf);
    if (g_date_label)      lv_label_set_text(g_date_label, date_buf);
    if (g_rtc_date_label)  lv_label_set_text(g_rtc_date_label, date_buf);
}

void ui_set_battery_level(int level, bool charging) {
    g_battery_level    = level;
    g_battery_charging = charging;
    if (g_battery_label) {
        lv_label_set_text_fmt(g_battery_label, "%d%%", level < 0 ? 0 : level);
    }
    if (g_battery_glyph) {
        const char* sym = LV_SYMBOL_BATTERY_EMPTY;
        if (level >= 80)      sym = LV_SYMBOL_BATTERY_FULL;
        else if (level >= 55) sym = LV_SYMBOL_BATTERY_3;
        else if (level >= 30) sym = LV_SYMBOL_BATTERY_2;
        else if (level >= 10) sym = LV_SYMBOL_BATTERY_1;
        lv_label_set_text(g_battery_glyph, sym);
    }
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

/* Palette for the active theme (UiPalette is declared in demo_ui.h). */
static UiPalette make_palette(const UiTheme& theme) {
    UiPalette p;
    const bool dark = theme.dark_mode;
    /* Material 3 tonal roles drive the palette; the schematic chips stay dark
     * on both themes so their white labels keep contrast. */
    p.bg          = to_lv_color(theme.roles[MATERIAL_BACKGROUND]);
    p.card        = to_lv_color(theme.roles[MATERIAL_SURFACE]);
    p.text        = to_lv_color(theme.roles[MATERIAL_ON_SURFACE]);
    p.dim         = to_lv_color(theme.roles[MATERIAL_ON_SURFACE_VARIANT]);
    p.on_primary  = to_lv_color(theme.roles[MATERIAL_ON_PRIMARY]);
    p.divider     = to_lv_color(theme.roles[MATERIAL_OUTLINE_VARIANT]);
    p.primary     = to_lv_color(theme.roles[MATERIAL_PRIMARY]);
    p.accent2     = to_lv_color(theme.roles[MATERIAL_SECONDARY]);
    p.danger      = to_lv_color(theme.roles[MATERIAL_ERROR]);
    p.chip        = dark ? lv_color_hex(0x0B0E16)
                        : to_lv_color(theme.roles[MATERIAL_ON_SURFACE]);
    p.chip_text   = dark ? lv_color_hex(0xECEDEF) : lv_color_hex(0xFFFFFFFF);
    p.chip_border = to_lv_color(theme.roles[MATERIAL_OUTLINE]);
    return p;
}

/* Active context for the helpers below (set once per screen build). */
static UiPalette g_pal = make_palette(UiTheme());
static int     g_dpi   = 440;
static UiTheme g_theme = UiTheme();

const UiPalette& ui_palette() {
    return g_pal;
}

/* -------------------------------------------------------------------------- */
/* Design tokens                                                               */
/* -------------------------------------------------------------------------- */

static UiTokens make_tokens(int dpi) {
    UiTokens t;
    t.xs   = ui_dpx(4,  dpi);
    t.sm   = ui_dpx(8,  dpi);
    t.md   = ui_dpx(12, dpi);
    t.lg   = ui_dpx(16, dpi);
    t.xl   = ui_dpx(24, dpi);
    t.xxl  = ui_dpx(32, dpi);
    t.r_sm = ui_dpx(8,  dpi);
    t.r_md = ui_dpx(12, dpi);
    t.r_lg = ui_dpx(16, dpi);
    t.r_xl = ui_dpx(24, dpi);
    t.f_caption  = 11;
    t.f_body     = 14;
    t.f_subtitle = 16;
    t.f_title    = 20;
    t.f_headline = 28;
    return t;
}

static UiTokens g_tx = make_tokens(440);

const UiTokens& ui_tokens() {
    return g_tx;
}

int ui_space(UiSpace s) {
    switch (s) {
        case UI_SPACE_XS: return g_tx.xs;
        case UI_SPACE_SM: return g_tx.sm;
        case UI_SPACE_MD: return g_tx.md;
        case UI_SPACE_LG: return g_tx.lg;
        case UI_SPACE_XL: return g_tx.xl;
        default:          return g_tx.xxl;
    }
}

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

/* -------------------------------------------------------------------------- */
/* Layout primitives (token-driven; children can never pile up at 0,0)         */
/* -------------------------------------------------------------------------- */

lv_obj_t* ui_make_row(lv_obj_t* parent, UiSpace gap) {
    lv_obj_t* r = lv_obj_create(parent);
    lv_obj_set_layout(r, LV_LAYOUT_FLEX);
    lv_obj_set_flex_flow(r, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(r, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER,
                          LV_FLEX_ALIGN_CENTER);
    lv_obj_set_style_pad_column(r, ui_space(gap), LV_PART_MAIN);
    lv_obj_set_width(r, LV_PCT(100));
    lv_obj_set_height(r, LV_SIZE_CONTENT);
    flatten(r);
    return r;
}

lv_obj_t* ui_make_column(lv_obj_t* parent, UiSpace gap) {
    lv_obj_t* c = lv_obj_create(parent);
    lv_obj_set_layout(c, LV_LAYOUT_FLEX);
    lv_obj_set_flex_flow(c, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_flex_align(c, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_START,
                          LV_FLEX_ALIGN_START);
    lv_obj_set_style_pad_row(c, ui_space(gap), LV_PART_MAIN);
    lv_obj_set_height(c, LV_SIZE_CONTENT);
    flatten(c);
    return c;
}

/* Fixed-size cells in a wrapping grid; children are sized as they are added. */
static void grid_child_created(lv_event_t* e) {
    lv_obj_t* child = static_cast<lv_obj_t*>(lv_event_get_current_target(e));
    lv_obj_t* grid  = static_cast<lv_obj_t*>(lv_event_get_user_data(e));
    intptr_t packed = reinterpret_cast<intptr_t>(lv_obj_get_user_data(grid));
    int w = static_cast<int>((packed >> 16) & 0xFFFF);
    int h = static_cast<int>(packed & 0xFFFF);
    lv_obj_set_size(child, w, h);
}

lv_obj_t* ui_make_grid(lv_obj_t* parent, int cols, UiSpace gap,
                       int cell_w_dp, int cell_h_dp) {
    (void)cols;   /* the wrap is driven by the fixed cell width */
    lv_obj_t* g = lv_obj_create(parent);
    lv_obj_set_layout(g, LV_LAYOUT_FLEX);
    lv_obj_set_flex_flow(g, LV_FLEX_FLOW_ROW_WRAP);
    lv_obj_set_flex_align(g, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_START,
                          LV_FLEX_ALIGN_START);
    lv_obj_set_style_pad_row(g, ui_space(gap), LV_PART_MAIN);
    lv_obj_set_style_pad_column(g, ui_space(gap), LV_PART_MAIN);
    lv_obj_set_width(g, LV_PCT(100));
    lv_obj_set_height(g, LV_SIZE_CONTENT);
    flatten(g);
    const int w = ui_dpx(cell_w_dp, g_dpi);
    const int h = ui_dpx(cell_h_dp, g_dpi);
    lv_obj_set_user_data(g, reinterpret_cast<void*>(
                             static_cast<intptr_t>((w << 16) | h)));
    lv_obj_add_event_cb(g, grid_child_created, LV_EVENT_CHILD_CREATED, g);
    return g;
}

lv_obj_t* ui_make_card(lv_obj_t* parent) {
    return ui_make_panel(parent);
}

lv_obj_t* ui_make_section(lv_obj_t* parent, const char* caption) {
    lv_obj_t* card = ui_make_panel(parent);
    lv_obj_set_layout(card, LV_LAYOUT_FLEX);
    lv_obj_set_flex_flow(card, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_flex_align(card, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_START,
                          LV_FLEX_ALIGN_START);
    lv_obj_set_style_pad_row(card, ui_space(UI_SPACE_MD), LV_PART_MAIN);
    if (caption) {
        ui_make_label(card, caption, g_tx.f_caption, g_pal.dim);
    }
    return card;
}

lv_obj_t* ui_make_list_row(lv_obj_t* parent, const char* icon,
                           const char* label, const char* value) {
    lv_obj_t* row = ui_make_row(parent, UI_SPACE_MD);
    lv_obj_set_style_pad_all(row, g_tx.sm, LV_PART_MAIN);
    lv_obj_set_style_radius(row, g_tx.r_md, LV_PART_MAIN);
    lv_obj_set_style_bg_color(row, g_pal.card, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(row, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_border_width(row, 1, LV_PART_MAIN);
    lv_obj_set_style_border_color(row, g_pal.divider, LV_PART_MAIN);
    if (icon) {
        ui_make_label(row, icon, g_tx.f_body, g_pal.primary);
    }
    if (label) {
        lv_obj_t* l = ui_make_label(row, label, g_tx.f_body, g_pal.text);
        lv_obj_set_flex_grow(l, 1);
        lv_label_set_long_mode(l, LV_LABEL_LONG_MODE_DOTS);
    }
    if (value) {
        ui_make_label(row, value, g_tx.f_caption, g_pal.dim);
    }
    return row;
}

lv_obj_t* ui_make_spacer(lv_obj_t* parent, UiSpace size) {
    lv_obj_t* s = lv_obj_create(parent);
    lv_obj_set_size(s, ui_space(size), ui_space(size));
    flatten(s);
    return s;
}

/* -------------------------------------------------------------------------- */
/* Text auto-fit                                                               */
/* -------------------------------------------------------------------------- */

/* Single-line width of `text` in `font` (px). */
static int text_width(const char* text, const lv_font_t* font) {
    lv_point_t size = {0, 0};
    lv_text_get_size(&size, text, font, 0, 0, LV_COORD_MAX, LV_TEXT_FLAG_NONE);
    return size.x;
}

int ui_fit_label(lv_obj_t* label, int max_w, int max_dp) {
    const char* text = lv_label_get_text(label);
    if (!text || !*text || max_w <= 0) return 0;
    const int limit_px = ui_dpx(max_dp, g_dpi);
    const int count = static_cast<int>(sizeof(kFontTable) / sizeof(kFontTable[0]));

    /* Descending: the first font that fits is the largest usable one. */
    int best_px = kFontTable[0].px;
    for (int i = count - 1; i >= 0; i--) {
        if (kFontTable[i].px > limit_px) continue;
        if (text_width(text, kFontTable[i].font) <= max_w) {
            best_px = kFontTable[i].px;
            break;
        }
    }
    for (int i = 0; i < count; i++) {
        if (kFontTable[i].px == best_px) {
            lv_obj_set_style_text_font(label, kFontTable[i].font, 0);
            break;
        }
    }
    /* Even the smallest font overflows: truncate instead of clipping. */
    const lv_font_t* applied = lv_obj_get_style_text_font(label, LV_PART_MAIN);
    if (text_width(text, applied) > max_w) {
        lv_label_set_long_mode(label, LV_LABEL_LONG_MODE_DOTS);
    }
    return best_px;
}

lv_obj_t* ui_fit_text(lv_obj_t* parent, const char* text, int max_w,
                      int max_dp, lv_color_t color) {
    lv_obj_t* l = ui_make_label(parent, text, max_dp, color);
    ui_fit_label(l, max_w, max_dp);
    return l;
}

/* -------------------------------------------------------------------------- */
/* Material 3 component kit                                                   */
/* -------------------------------------------------------------------------- */

/* Tonal role lookup — keeps the component code readable. */
static lv_color_t mrole(MaterialRole r) {
    return to_lv_color(ui_current_theme().roles[r]);
}

static void on_stop_propagate(lv_event_t* e) {
    lv_event_stop_bubbling(e);
}

static void on_theme_fab_clicked(lv_event_t* /*e*/) {
    if (g_on_toggle_dark_mode) g_on_toggle_dark_mode();
}

lv_obj_t* ui_make_button(lv_obj_t* parent, const char* text, UiButtonStyle style,
                         bool danger) {
    lv_obj_t* b = lv_button_create(parent);
    lv_obj_set_height(b, ui_dpx(40, g_dpi));
    lv_obj_set_style_pad_left(b, ui_dpx(24, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_pad_right(b, ui_dpx(24, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_pad_top(b, 0, LV_PART_MAIN);
    lv_obj_set_style_pad_bottom(b, 0, LV_PART_MAIN);
    lv_obj_set_style_radius(b, LV_RADIUS_CIRCLE, LV_PART_MAIN);
    lv_obj_set_style_border_width(b, 0, LV_PART_MAIN);
    lv_obj_set_style_shadow_width(b, 0, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(b, LV_OPA_COVER, LV_PART_MAIN);

    lv_color_t fg;
    switch (style) {
        case UI_BUTTON_TONAL:
            lv_obj_set_style_bg_color(b, mrole(MATERIAL_SECONDARY_CONTAINER), LV_PART_MAIN);
            fg = mrole(MATERIAL_ON_SECONDARY_CONTAINER);
            break;
        case UI_BUTTON_OUTLINED:
            lv_obj_set_style_bg_opa(b, LV_OPA_TRANSP, LV_PART_MAIN);
            lv_obj_set_style_border_width(b, ui_dpx(1, g_dpi), LV_PART_MAIN);
            lv_obj_set_style_border_color(b, danger ? g_pal.danger : g_pal.divider, LV_PART_MAIN);
            fg = danger ? g_pal.danger : g_pal.primary;
            break;
        case UI_BUTTON_TEXT:
            lv_obj_set_style_bg_opa(b, LV_OPA_TRANSP, LV_PART_MAIN);
            lv_obj_set_style_pad_left(b, ui_dpx(12, g_dpi), LV_PART_MAIN);
            lv_obj_set_style_pad_right(b, ui_dpx(12, g_dpi), LV_PART_MAIN);
            fg = danger ? g_pal.danger : g_pal.primary;
            break;
        case UI_BUTTON_FILLED:
        default:
            lv_obj_set_style_bg_color(b, danger ? g_pal.danger : g_pal.primary, LV_PART_MAIN);
            fg = danger ? mrole(MATERIAL_ON_ERROR) : g_pal.on_primary;
            break;
    }
    /* Pressed state: a soft overlay instead of the default theme flash. */
    lv_obj_set_style_bg_opa(b, LV_OPA_80, LV_PART_MAIN | LV_STATE_PRESSED);

    if (text && *text) {
        lv_obj_t* l = ui_make_label(b, text, 14, fg);
        lv_obj_center(l);
    }
    return b;
}

lv_obj_t* ui_make_icon_button(lv_obj_t* parent, const char* icon) {
    lv_obj_t* b = lv_button_create(parent);
    lv_obj_set_size(b, ui_dpx(40, g_dpi), ui_dpx(40, g_dpi));
    lv_obj_set_style_radius(b, LV_RADIUS_CIRCLE, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(b, LV_OPA_TRANSP, LV_PART_MAIN);
    lv_obj_set_style_border_width(b, 0, LV_PART_MAIN);
    lv_obj_set_style_shadow_width(b, 0, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(b, LV_OPA_20, LV_PART_MAIN | LV_STATE_PRESSED);
    if (icon && *icon) {
        lv_obj_t* l = ui_make_label(b, icon, 18, g_pal.text);
        lv_obj_center(l);
    }
    return b;
}

lv_obj_t* ui_make_chip(lv_obj_t* parent, const char* label, bool selected) {
    lv_obj_t* c = lv_obj_create(parent);
    lv_obj_set_height(c, ui_dpx(32, g_dpi));
    lv_obj_set_style_pad_left(c, ui_dpx(12, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_pad_right(c, ui_dpx(12, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_pad_top(c, 0, LV_PART_MAIN);
    lv_obj_set_style_pad_bottom(c, 0, LV_PART_MAIN);
    lv_obj_set_style_radius(c, ui_dpx(8, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_border_width(c, ui_dpx(1, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_border_color(c, g_pal.divider, LV_PART_MAIN);
    lv_obj_set_style_shadow_width(c, 0, LV_PART_MAIN);
    if (selected) {
        lv_obj_set_style_bg_color(c, mrole(MATERIAL_SECONDARY_CONTAINER), LV_PART_MAIN);
        lv_obj_set_style_bg_opa(c, LV_OPA_COVER, LV_PART_MAIN);
        lv_obj_set_style_border_width(c, 0, LV_PART_MAIN);
    } else {
        lv_obj_set_style_bg_opa(c, LV_OPA_TRANSP, LV_PART_MAIN);
    }
    lv_obj_set_layout(c, LV_LAYOUT_FLEX);
    lv_obj_set_flex_flow(c, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(c, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER,
                          LV_FLEX_ALIGN_CENTER);
    lv_obj_set_style_pad_column(c, ui_dpx(6, g_dpi), LV_PART_MAIN);
    if (selected) {
        ui_make_label(c, LV_SYMBOL_OK, 12, mrole(MATERIAL_ON_SECONDARY_CONTAINER));
    }
    if (label && *label) {
        ui_make_label(c, label, 13,
                      selected ? mrole(MATERIAL_ON_SECONDARY_CONTAINER) : g_pal.dim);
    }
    return c;
}

lv_obj_t* ui_make_fab(lv_obj_t* parent, const char* icon, const char* label) {
    lv_obj_t* f = lv_button_create(parent);
    lv_obj_add_flag(f, LV_OBJ_FLAG_FLOATING);
    lv_obj_set_height(f, ui_dpx(56, g_dpi));
    if (label && *label) {
        lv_obj_set_width(f, LV_SIZE_CONTENT);
        lv_obj_set_style_pad_left(f, ui_dpx(16, g_dpi), LV_PART_MAIN);
        lv_obj_set_style_pad_right(f, ui_dpx(20, g_dpi), LV_PART_MAIN);
    } else {
        lv_obj_set_width(f, ui_dpx(56, g_dpi));
    }
    lv_obj_set_style_radius(f, ui_dpx(16, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_bg_color(f, mrole(MATERIAL_PRIMARY_CONTAINER), LV_PART_MAIN);
    lv_obj_set_style_bg_opa(f, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_border_width(f, 0, LV_PART_MAIN);
    lv_obj_set_style_shadow_width(f, ui_dpx(6, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_shadow_opa(f, LV_OPA_30, LV_PART_MAIN);
    lv_obj_set_style_shadow_offset_y(f, ui_dpx(3, g_dpi), LV_PART_MAIN);
    lv_obj_set_layout(f, LV_LAYOUT_FLEX);
    lv_obj_set_flex_flow(f, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(f, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER,
                          LV_FLEX_ALIGN_CENTER);
    lv_obj_set_style_pad_column(f, ui_dpx(8, g_dpi), LV_PART_MAIN);
    if (icon && *icon) {
        ui_make_label(f, icon, 20, mrole(MATERIAL_ON_PRIMARY_CONTAINER));
    }
    if (label && *label) {
        ui_make_label(f, label, 15, mrole(MATERIAL_ON_PRIMARY_CONTAINER));
    }
    return f;
}

lv_obj_t* ui_make_m3_card(lv_obj_t* parent, UiCardStyle style) {
    lv_obj_t* c = lv_obj_create(parent);
    lv_obj_set_width(c, LV_PCT(100));
    lv_obj_set_height(c, LV_SIZE_CONTENT);
    lv_obj_set_style_radius(c, ui_dpx(16, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_pad_all(c, ui_dpx(16, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_pad_row(c, ui_dpx(8, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_bg_opa(c, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_border_width(c, 0, LV_PART_MAIN);
    lv_obj_set_style_shadow_width(c, 0, LV_PART_MAIN);
    if (style == UI_CARD_FILLED) {
        lv_obj_set_style_bg_color(c, mrole(MATERIAL_SURFACE_VARIANT), LV_PART_MAIN);
    } else if (style == UI_CARD_OUTLINED) {
        lv_obj_set_style_bg_color(c, g_pal.card, LV_PART_MAIN);
        lv_obj_set_style_border_width(c, ui_dpx(1, g_dpi), LV_PART_MAIN);
        lv_obj_set_style_border_color(c, g_pal.divider, LV_PART_MAIN);
    } else {
        lv_obj_set_style_bg_color(c, g_pal.card, LV_PART_MAIN);
        lv_obj_set_style_shadow_width(c, ui_dpx(8, g_dpi), LV_PART_MAIN);
        lv_obj_set_style_shadow_opa(c, LV_OPA_20, LV_PART_MAIN);
        lv_obj_set_style_shadow_offset_y(c, ui_dpx(2, g_dpi), LV_PART_MAIN);
    }
    lv_obj_set_layout(c, LV_LAYOUT_FLEX);
    lv_obj_set_flex_flow(c, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_flex_align(c, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_START,
                          LV_FLEX_ALIGN_START);
    lv_obj_clear_flag(c, LV_OBJ_FLAG_SCROLLABLE);
    return c;
}

lv_obj_t* ui_make_text_field(lv_obj_t* parent, const char* label, const char* value) {
    lv_obj_t* col = ui_make_column(parent, UI_SPACE_XS);
    lv_obj_set_width(col, LV_PCT(100));
    if (label && *label) {
        ui_make_label(col, label, 12, g_pal.dim);
    }
    lv_obj_t* box = lv_obj_create(col);
    lv_obj_set_width(box, LV_PCT(100));
    lv_obj_set_height(box, ui_dpx(52, g_dpi));
    lv_obj_set_style_radius(box, ui_dpx(4, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_bg_opa(box, LV_OPA_TRANSP, LV_PART_MAIN);
    lv_obj_set_style_border_width(box, ui_dpx(1, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_border_color(box, g_pal.divider, LV_PART_MAIN);
    lv_obj_set_style_pad_all(box, 0, LV_PART_MAIN);
    lv_obj_set_style_pad_left(box, ui_dpx(12, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_pad_right(box, ui_dpx(12, g_dpi), LV_PART_MAIN);
    lv_obj_set_layout(box, LV_LAYOUT_FLEX);
    lv_obj_set_flex_flow(box, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(box, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER,
                          LV_FLEX_ALIGN_CENTER);
    ui_make_label(box, (value && *value) ? value : "—", 15, g_pal.text);
    return col;
}

lv_obj_t* ui_make_divider(lv_obj_t* parent) {
    lv_obj_t* d = lv_obj_create(parent);
    lv_obj_set_width(d, LV_PCT(100));
    lv_obj_set_height(d, ui_dpx(1, g_dpi));
    lv_obj_set_style_bg_color(d, g_pal.divider, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(d, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_border_width(d, 0, LV_PART_MAIN);
    lv_obj_set_style_pad_all(d, 0, LV_PART_MAIN);
    lv_obj_set_style_radius(d, 0, LV_PART_MAIN);
    ui_decorative(d);
    return d;
}

/* ---- Bottom navigation ---------------------------------------------------- */

static UiIntCallback g_nav_select = nullptr;
static void* g_nav_user_data = nullptr;

static void nav_refresh(lv_obj_t* bar, int selected) {
    const int n = static_cast<int>(lv_obj_get_child_count(bar));
    for (int i = 0; i < n; i++) {
        lv_obj_t* item = lv_obj_get_child(bar, i);
        if (!item) continue;
        lv_obj_t* pill  = lv_obj_get_child(item, 0);
        lv_obj_t* icon  = pill ? lv_obj_get_child(pill, 0) : nullptr;
        lv_obj_t* label = lv_obj_get_child(item, 1);
        const bool on = (i == selected);
        if (pill) {
            lv_obj_set_style_bg_opa(pill, on ? LV_OPA_COVER : LV_OPA_TRANSP, LV_PART_MAIN);
        }
        if (label) {
            lv_obj_set_style_text_color(label, on ? g_pal.text : g_pal.dim, LV_PART_MAIN);
        }
        if (icon) {
            lv_obj_set_style_text_color(icon,
                                        on ? mrole(MATERIAL_ON_SECONDARY_CONTAINER)
                                           : g_pal.dim, LV_PART_MAIN);
        }
    }
}

static void on_nav_item_clicked(lv_event_t* e) {
    lv_obj_t* item = static_cast<lv_obj_t*>(lv_event_get_current_target(e));
    lv_obj_t* bar  = lv_obj_get_parent(item);
    if (!bar) return;
    const int n = static_cast<int>(lv_obj_get_child_count(bar));
    int index = -1;
    for (int i = 0; i < n; i++) {
        if (lv_obj_get_child(bar, i) == item) { index = i; break; }
    }
    if (index < 0) return;
    nav_refresh(bar, index);
    if (g_nav_select) g_nav_select(index, g_nav_user_data);
}

lv_obj_t* ui_make_bottom_nav(lv_obj_t* parent, const UiNavItem* items, int count,
                             int selected, UiIntCallback on_select, void* user_data) {
    lv_obj_t* bar = lv_obj_create(parent);
    lv_obj_set_width(bar, LV_PCT(100));
    lv_obj_set_height(bar, ui_dpx(80, g_dpi));
    lv_obj_set_style_bg_color(bar, g_pal.card, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(bar, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_border_width(bar, ui_dpx(1, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_border_color(bar, g_pal.divider, LV_PART_MAIN);
    lv_obj_set_style_border_side(bar, LV_BORDER_SIDE_TOP, LV_PART_MAIN);
    lv_obj_set_style_pad_all(bar, 0, LV_PART_MAIN);
    lv_obj_set_layout(bar, LV_LAYOUT_FLEX);
    lv_obj_set_flex_flow(bar, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(bar, LV_FLEX_ALIGN_SPACE_EVENLY, LV_FLEX_ALIGN_CENTER,
                          LV_FLEX_ALIGN_CENTER);
    lv_obj_clear_flag(bar, LV_OBJ_FLAG_SCROLLABLE);
    g_nav_select = on_select;
    g_nav_user_data = user_data;

    for (int i = 0; i < count; i++) {
        lv_obj_t* item = lv_obj_create(bar);
        lv_obj_set_flex_grow(item, 1);
        lv_obj_set_height(item, LV_PCT(100));
        lv_obj_set_layout(item, LV_LAYOUT_FLEX);
        lv_obj_set_flex_flow(item, LV_FLEX_FLOW_COLUMN);
        lv_obj_set_flex_align(item, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER,
                              LV_FLEX_ALIGN_CENTER);
        lv_obj_set_style_pad_all(item, 0, LV_PART_MAIN);
        lv_obj_set_style_pad_row(item, ui_dpx(4, g_dpi), LV_PART_MAIN);
        lv_obj_set_style_bg_opa(item, LV_OPA_TRANSP, LV_PART_MAIN);
        lv_obj_set_style_border_width(item, 0, LV_PART_MAIN);
        lv_obj_add_event_cb(item, on_nav_item_clicked, LV_EVENT_CLICKED, nullptr);

        lv_obj_t* pill = lv_obj_create(item);
        lv_obj_set_size(pill, ui_dpx(64, g_dpi), ui_dpx(32, g_dpi));
        lv_obj_set_style_radius(pill, ui_dpx(16, g_dpi), LV_PART_MAIN);
        lv_obj_set_style_bg_color(pill, mrole(MATERIAL_SECONDARY_CONTAINER), LV_PART_MAIN);
        lv_obj_set_style_bg_opa(pill, LV_OPA_TRANSP, LV_PART_MAIN);
        lv_obj_set_style_border_width(pill, 0, LV_PART_MAIN);
        lv_obj_set_style_pad_all(pill, 0, LV_PART_MAIN);
        ui_decorative(pill);
        if (items[i].icon && *items[i].icon) {
            lv_obj_t* ic = ui_make_label(pill, items[i].icon, 16,
                                         mrole(MATERIAL_ON_SECONDARY_CONTAINER));
            lv_obj_center(ic);
        }
        if (items[i].label && *items[i].label) {
            ui_make_label(item, items[i].label, 12, g_pal.dim);
        }
    }
    nav_refresh(bar, selected);
    return bar;
}

/* ---- Dialog + snackbar overlays ------------------------------------------- */

static const char kDialogMarker[] = "ui_dialog";
static UiActionCallback g_dialog_action = nullptr;
static void* g_dialog_user_data = nullptr;

static lv_obj_t* find_dialog_scrim(lv_obj_t* o) {
    while (o) {
        if (lv_obj_get_user_data(o) == static_cast<const void*>(kDialogMarker)) return o;
        o = lv_obj_get_parent(o);
    }
    return nullptr;
}

static void on_dialog_scrim_clicked(lv_event_t* e) {
    lv_obj_t* scrim = static_cast<lv_obj_t*>(lv_event_get_current_target(e));
    if (g_dialog_action) g_dialog_action(0, g_dialog_user_data);
    lv_obj_delete(scrim);
}

static void on_dialog_action_clicked(lv_event_t* e) {
    lv_obj_t* btn = static_cast<lv_obj_t*>(lv_event_get_current_target(e));
    intptr_t index = reinterpret_cast<intptr_t>(lv_obj_get_user_data(btn));
    lv_obj_t* scrim = find_dialog_scrim(btn);
    if (g_dialog_action) g_dialog_action(static_cast<int>(index), g_dialog_user_data);
    if (scrim) lv_obj_delete(scrim);
}

lv_obj_t* ui_make_dialog(lv_obj_t* parent, const char* title, const char* message,
                         const char* const* actions, int action_count,
                         UiActionCallback on_action, void* user_data) {
    g_dialog_action = on_action;
    g_dialog_user_data = user_data;

    lv_obj_t* scrim = lv_obj_create(parent);
    lv_obj_add_flag(scrim, LV_OBJ_FLAG_FLOATING);
    lv_obj_set_size(scrim, LV_PCT(100), LV_PCT(100));
    lv_obj_set_pos(scrim, 0, 0);
    lv_obj_set_style_bg_color(scrim, lv_color_black(), LV_PART_MAIN);
    lv_obj_set_style_bg_opa(scrim, LV_OPA_30, LV_PART_MAIN);
    lv_obj_set_style_border_width(scrim, 0, LV_PART_MAIN);
    lv_obj_set_style_pad_all(scrim, 0, LV_PART_MAIN);
    lv_obj_set_style_radius(scrim, 0, LV_PART_MAIN);
    lv_obj_set_layout(scrim, LV_LAYOUT_FLEX);
    lv_obj_set_flex_flow(scrim, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_flex_align(scrim, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER,
                          LV_FLEX_ALIGN_CENTER);
    lv_obj_set_style_pad_row(scrim, ui_dpx(16, g_dpi), LV_PART_MAIN);
    lv_obj_clear_flag(scrim, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_user_data(scrim, static_cast<void*>(const_cast<char*>(kDialogMarker)));
    lv_obj_add_event_cb(scrim, on_dialog_scrim_clicked, LV_EVENT_CLICKED, nullptr);

    lv_obj_t* card = ui_make_m3_card(scrim, UI_CARD_ELEVATED);
    lv_obj_set_width(card, LV_PCT(85));
    lv_obj_set_style_radius(card, ui_dpx(28, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_shadow_width(card, ui_dpx(16, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_shadow_opa(card, LV_OPA_40, LV_PART_MAIN);
    lv_obj_set_style_shadow_offset_y(card, ui_dpx(4, g_dpi), LV_PART_MAIN);
    lv_obj_add_event_cb(card, on_stop_propagate, LV_EVENT_ALL, nullptr);

    if (title && *title) {
        lv_obj_t* t = ui_make_label(card, title, 22, g_pal.text);
        lv_obj_set_width(t, LV_PCT(100));
    }
    if (message && *message) {
        lv_obj_t* m = ui_make_label(card, message, 14, g_pal.dim);
        lv_obj_set_width(m, LV_PCT(100));
        lv_label_set_long_mode(m, LV_LABEL_LONG_MODE_WRAP);
    }
    if (action_count > 0 && actions) {
        lv_obj_t* row = lv_obj_create(card);
        lv_obj_set_width(row, LV_PCT(100));
        lv_obj_set_height(row, LV_SIZE_CONTENT);
        lv_obj_set_layout(row, LV_LAYOUT_FLEX);
        lv_obj_set_flex_flow(row, LV_FLEX_FLOW_ROW);
        lv_obj_set_flex_align(row, LV_FLEX_ALIGN_END, LV_FLEX_ALIGN_CENTER,
                              LV_FLEX_ALIGN_CENTER);
        lv_obj_set_style_pad_all(row, 0, LV_PART_MAIN);
        lv_obj_set_style_pad_column(row, ui_dpx(8, g_dpi), LV_PART_MAIN);
        lv_obj_set_style_bg_opa(row, LV_OPA_TRANSP, LV_PART_MAIN);
        lv_obj_set_style_border_width(row, 0, LV_PART_MAIN);
        ui_decorative(row);
        for (int i = 0; i < action_count; i++) {
            lv_obj_t* b = ui_make_button(row, actions[i], UI_BUTTON_TEXT, false);
            lv_obj_set_user_data(b, reinterpret_cast<void*>(static_cast<intptr_t>(i + 1)));
            lv_obj_add_event_cb(b, on_dialog_action_clicked, LV_EVENT_CLICKED, nullptr);
        }
    }
    return scrim;
}

static UiActionCallback g_snackbar_action = nullptr;
static void* g_snackbar_user_data = nullptr;

static void on_snackbar_action_clicked(lv_event_t* e) {
    lv_obj_t* btn = static_cast<lv_obj_t*>(lv_event_get_current_target(e));
    lv_obj_t* s = lv_obj_get_parent(btn);
    if (g_snackbar_action) g_snackbar_action(1, g_snackbar_user_data);
    if (s) lv_obj_delete(s);
}

static void snackbar_timeout(lv_timer_t* timer) {
    lv_obj_t* s = static_cast<lv_obj_t*>(lv_timer_get_user_data(timer));
    if (g_snackbar == s && s) lv_obj_delete(s);
    if (g_snackbar_timer == timer) g_snackbar_timer = nullptr;
    g_snackbar = nullptr;
}

lv_obj_t* ui_make_snackbar(lv_obj_t* parent, const char* message, const char* action,
                           UiActionCallback on_action, void* user_data) {
    g_snackbar_action = on_action;
    g_snackbar_user_data = user_data;

    const int h = ui_dpx(48, g_dpi);
    const int margin = ui_dpx(12, g_dpi);
    const int pw = lv_obj_get_width(parent);
    const int ph = lv_obj_get_height(parent);

    lv_obj_t* s = lv_obj_create(parent);
    lv_obj_add_flag(s, LV_OBJ_FLAG_FLOATING);
    lv_obj_set_size(s, pw > 0 ? pw - margin * 2 : LV_PCT(100), h);
    if (pw > 0 && ph > 0) {
        lv_obj_set_pos(s, margin, ph - h - margin);
    } else {
        lv_obj_align(s, LV_ALIGN_BOTTOM_LEFT, margin, -margin);
    }
    lv_obj_set_style_radius(s, ui_dpx(4, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_bg_color(s, g_pal.text, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(s, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_border_width(s, 0, LV_PART_MAIN);
    lv_obj_set_style_shadow_width(s, ui_dpx(6, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_shadow_opa(s, LV_OPA_30, LV_PART_MAIN);
    lv_obj_set_style_shadow_offset_y(s, ui_dpx(2, g_dpi), LV_PART_MAIN);
    lv_obj_set_layout(s, LV_LAYOUT_FLEX);
    lv_obj_set_flex_flow(s, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(s, LV_FLEX_ALIGN_SPACE_BETWEEN, LV_FLEX_ALIGN_CENTER,
                          LV_FLEX_ALIGN_CENTER);
    lv_obj_set_style_pad_all(s, 0, LV_PART_MAIN);
    lv_obj_set_style_pad_left(s, ui_dpx(16, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_pad_right(s, ui_dpx(4, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_pad_column(s, ui_dpx(8, g_dpi), LV_PART_MAIN);
    lv_obj_clear_flag(s, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_add_event_cb(s, on_stop_propagate, LV_EVENT_ALL, nullptr);

    if (message && *message) {
        lv_obj_t* m = ui_make_label(s, message, 14, g_pal.card);
        lv_obj_set_flex_grow(m, 1);
        lv_label_set_long_mode(m, LV_LABEL_LONG_MODE_DOTS);
    }
    if (action && *action) {
        lv_obj_t* b = ui_make_button(s, action, UI_BUTTON_TEXT, false);
        lv_obj_set_user_data(b, reinterpret_cast<void*>(static_cast<intptr_t>(1)));
        lv_obj_add_event_cb(b, on_snackbar_action_clicked, LV_EVENT_CLICKED, nullptr);
    }

    if (g_snackbar_timer) {
        lv_timer_delete(g_snackbar_timer);
        g_snackbar_timer = nullptr;
    }
    g_snackbar = s;
    g_snackbar_timer = lv_timer_create(snackbar_timeout, 4000, s);
    lv_timer_set_repeat_count(g_snackbar_timer, 1);
    return s;
}

/* ---- Top app bar ---------------------------------------------------------- */

lv_obj_t* ui_make_app_bar(lv_obj_t* parent, const char* title, bool show_back) {
    lv_obj_t* h = lv_obj_create(parent);
    lv_obj_set_width(h, LV_PCT(100));
    lv_obj_set_height(h, ui_dpx(56, g_dpi));
    lv_obj_set_layout(h, LV_LAYOUT_FLEX);
    lv_obj_set_flex_flow(h, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(h, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER,
                          LV_FLEX_ALIGN_CENTER);
    lv_obj_set_style_pad_left(h, ui_dpx(4, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_pad_right(h, ui_dpx(16, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_pad_column(h, ui_dpx(8, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_bg_color(h, g_pal.card, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(h, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_border_width(h, 0, LV_PART_MAIN);
    lv_obj_set_style_shadow_width(h, ui_dpx(2, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_shadow_opa(h, LV_OPA_10, LV_PART_MAIN);
    lv_obj_set_style_shadow_offset_y(h, ui_dpx(1, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_radius(h, 0, LV_PART_MAIN);
    ui_decorative(h);
    if (show_back) {
        lv_obj_t* btn = ui_make_icon_button(h, LV_SYMBOL_LEFT);
        lv_obj_add_event_cb(btn, on_back_clicked, LV_EVENT_CLICKED, nullptr);
    }
    lv_obj_t* label = ui_make_label(h, title ? title : "", 22, g_pal.text);
    lv_obj_set_flex_grow(label, 1);
    lv_label_set_long_mode(label, LV_LABEL_LONG_MODE_DOTS);
    return h;
}

/* -------------------------------------------------------------------------- */
/* UI kit                                                                     */
/* -------------------------------------------------------------------------- */

static lv_obj_t* make_screen(const UiTheme& theme, int dpi) {
    g_dpi = dpi > 0 ? dpi : 440;
    g_pal = make_palette(theme);
    g_tx  = make_tokens(g_dpi);

    lv_obj_t* screen = lv_screen_active();
    /* A snackbar is a child of the screen: drop its auto-dismiss timer before
     * cleaning, otherwise the timer would fire on a freed object. */
    if (g_snackbar_timer) {
        lv_timer_delete(g_snackbar_timer);
        g_snackbar_timer = nullptr;
    }
    g_snackbar = nullptr;
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

/* Every screen is built on this scaffold: status bar + optional header + a
 * scrollable body. Using it means a new screen is inset, scrolled and themed
 * correctly by construction. */
UiScaffold ui_make_scaffold(lv_obj_t* parent, const char* title, bool show_back) {
    UiScaffold s;
    s.root   = parent;
    s.header = nullptr;
    s.body   = nullptr;

    ui_make_status_bar(parent);
    if (title) {
        s.header = ui_make_app_bar(parent, title, show_back);
    }

    lv_obj_t* body = lv_obj_create(parent);
    lv_obj_set_width(body, LV_PCT(100));
    lv_obj_set_flex_grow(body, 1);
    lv_obj_set_layout(body, LV_LAYOUT_FLEX);
    lv_obj_set_flex_flow(body, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_flex_align(body, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER,
                          LV_FLEX_ALIGN_START);
    lv_obj_set_style_pad_left(body, g_tx.lg, LV_PART_MAIN);
    lv_obj_set_style_pad_right(body, g_tx.lg, LV_PART_MAIN);
    lv_obj_set_style_pad_top(body, g_tx.md, LV_PART_MAIN);
    lv_obj_set_style_pad_bottom(body, g_tx.lg, LV_PART_MAIN);
    lv_obj_set_style_pad_row(body, g_tx.md, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(body, LV_OPA_TRANSP, LV_PART_MAIN);
    lv_obj_set_style_border_width(body, 0, LV_PART_MAIN);
    lv_obj_set_scroll_dir(body, LV_DIR_VER);
    lv_obj_set_scrollbar_mode(body, LV_SCROLLBAR_MODE_OFF);
    lv_obj_clear_flag(body, LV_OBJ_FLAG_CLICKABLE);
    s.body = body;
    return s;
}

/* A rounded surface card ("elevated" panel). */
lv_obj_t* ui_make_panel(lv_obj_t* parent) {
    lv_obj_t* card = lv_obj_create(parent);
    lv_obj_set_width(card, LV_PCT(100));
    lv_obj_set_height(card, LV_SIZE_CONTENT);
    lv_obj_set_style_pad_all(card, g_tx.lg, LV_PART_MAIN);
    lv_obj_set_style_radius(card, g_tx.r_lg, LV_PART_MAIN);
    lv_obj_set_style_bg_color(card, g_pal.card, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(card, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_border_width(card, 0, LV_PART_MAIN);
    lv_obj_set_style_shadow_width(card, g_tx.lg, LV_PART_MAIN);
    lv_obj_set_style_shadow_opa(card, LV_OPA_10, LV_PART_MAIN);
    lv_obj_set_style_shadow_offset_y(card, ui_dpx(2, g_dpi), LV_PART_MAIN);
    lv_obj_clear_flag(card, LV_OBJ_FLAG_SCROLLABLE);
    return card;
}

/* A labelled row: left caption (fixed width) + widget(s) appended after. */
static lv_obj_t* make_labeled_row(lv_obj_t* parent, const char* caption) {
    lv_obj_t* row = ui_make_row(parent, UI_SPACE_LG);
    lv_obj_t* l = ui_make_label(row, caption, g_tx.f_body, g_pal.text);
    lv_obj_set_width(l, ui_dpx(88, g_dpi));
    lv_label_set_long_mode(l, LV_LABEL_LONG_MODE_DOTS);
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
/* M5Tab5-style device dashboard kit                                          */
/* -------------------------------------------------------------------------- */

/* Steppers need per-instance state. The dashboard is a single screen rebuilt
 * on every entry, so a small static registry is simpler and leak-free than
 * heap-allocating a context per stepper. */
struct StepperState {
    lv_obj_t*     value_label;
    int           value;
    int           min;
    int           max;
    int           step;
    UiIntCallback on_change;
    void*         user_data;
};
static StepperState g_steppers[8];
static int g_stepper_count = 0;

static void stepper_clicked(lv_event_t* e) {
    lv_obj_t* btn = static_cast<lv_obj_t*>(lv_event_get_current_target(e));
    auto* state = static_cast<StepperState*>(lv_obj_get_user_data(lv_obj_get_parent(btn)));
    if (!state) return;
    int dir = static_cast<int>(reinterpret_cast<intptr_t>(lv_obj_get_user_data(btn)));
    int value = state->value + dir * state->step;
    if (value < state->min) value = state->min;
    if (value > state->max) value = state->max;
    if (value == state->value) return;
    state->value = value;
    lv_label_set_text_fmt(state->value_label, "%d", value);
    if (state->on_change) state->on_change(value, state->user_data);
}

lv_obj_t* ui_make_stepper(lv_obj_t* parent, int value, int min, int max, int step,
                          UiIntCallback on_change, void* user_data) {
    if (g_stepper_count >= static_cast<int>(sizeof(g_steppers) / sizeof(g_steppers[0]))) {
        return nullptr;
    }
    StepperState* st = &g_steppers[g_stepper_count++];

    lv_obj_t* row = lv_obj_create(parent);
    lv_obj_set_width(row, LV_PCT(100));
    lv_obj_set_height(row, LV_SIZE_CONTENT);
    lv_obj_set_layout(row, LV_LAYOUT_FLEX);
    lv_obj_set_flex_flow(row, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(row, LV_FLEX_ALIGN_SPACE_BETWEEN, LV_FLEX_ALIGN_CENTER,
                          LV_FLEX_ALIGN_CENTER);
    lv_obj_set_style_pad_all(row, 0, LV_PART_MAIN);
    lv_obj_set_style_pad_column(row, ui_dpx(10, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_bg_opa(row, LV_OPA_TRANSP, LV_PART_MAIN);
    lv_obj_set_style_border_width(row, 0, LV_PART_MAIN);
    ui_decorative(row);
    lv_obj_set_user_data(row, st);

    lv_obj_t* minus = lv_button_create(row);
    lv_obj_set_size(minus, ui_dpx(52, g_dpi), ui_dpx(44, g_dpi));
    lv_obj_set_style_radius(minus, ui_dpx(12, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_bg_color(minus, g_pal.card, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(minus, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_border_width(minus, ui_dpx(1, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_border_color(minus, g_pal.divider, LV_PART_MAIN);
    lv_obj_set_user_data(minus, reinterpret_cast<void*>(static_cast<intptr_t>(-1)));
    lv_obj_add_event_cb(minus, stepper_clicked, LV_EVENT_CLICKED, nullptr);
    lv_obj_t* minus_lbl = ui_make_label(minus, LV_SYMBOL_MINUS, 18, g_pal.text);
    lv_obj_center(minus_lbl);

    st->value_label = lv_label_create(row);
    lv_label_set_text_fmt(st->value_label, "%d", value);
    lv_obj_set_style_text_font(st->value_label, ui_font(26, g_dpi), 0);
    lv_obj_set_style_text_color(st->value_label, g_pal.text, 0);
    lv_obj_set_flex_grow(st->value_label, 1);
    lv_obj_set_style_text_align(st->value_label, LV_TEXT_ALIGN_CENTER, 0);
    ui_decorative(st->value_label);

    lv_obj_t* plus = lv_button_create(row);
    lv_obj_set_size(plus, ui_dpx(52, g_dpi), ui_dpx(44, g_dpi));
    lv_obj_set_style_radius(plus, ui_dpx(12, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_bg_color(plus, g_pal.card, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(plus, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_border_width(plus, ui_dpx(1, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_border_color(plus, g_pal.divider, LV_PART_MAIN);
    lv_obj_set_user_data(plus, reinterpret_cast<void*>(static_cast<intptr_t>(1)));
    lv_obj_add_event_cb(plus, stepper_clicked, LV_EVENT_CLICKED, nullptr);
    lv_obj_t* plus_lbl = ui_make_label(plus, LV_SYMBOL_PLUS, 18, g_pal.text);
    lv_obj_center(plus_lbl);

    st->value = value;
    st->min = min;
    st->max = max;
    st->step = step;
    st->on_change = on_change;
    st->user_data = user_data;
    return row;
}

/* Size a label to exactly fit `sample` so live text does not jitter. */
static void size_label_to(lv_obj_t* label, const char* sample) {
    const lv_font_t* font = lv_obj_get_style_text_font(label, LV_PART_MAIN);
    lv_point_t size = {0, 0};
    lv_text_get_size(&size, sample, font, 0, 0, LV_COORD_MAX, LV_TEXT_FLAG_NONE);
    if (size.x > 0) lv_obj_set_width(label, size.x);
}

/* A transparent spacer that absorbs free space in a flex row. */
static lv_obj_t* make_grow_spacer(lv_obj_t* parent) {
    lv_obj_t* s = lv_obj_create(parent);
    lv_obj_set_flex_grow(s, 1);
    /* An empty LV_SIZE_CONTENT box in a flex row falls back to a 480 px
     * default height in this LVGL build, so give it an explicit height. */
    lv_obj_set_height(s, 1);
    lv_obj_set_style_bg_opa(s, LV_OPA_TRANSP, LV_PART_MAIN);
    lv_obj_set_style_border_width(s, 0, LV_PART_MAIN);
    lv_obj_set_style_pad_all(s, 0, LV_PART_MAIN);
    ui_decorative(s);
    return s;
}

lv_obj_t* ui_make_status_bar(lv_obj_t* parent) {
    lv_obj_t* bar = lv_obj_create(parent);
    lv_obj_set_width(bar, LV_PCT(100));
    lv_obj_set_height(bar, ui_dpx(52, g_dpi));
    lv_obj_set_layout(bar, LV_LAYOUT_FLEX);
    lv_obj_set_flex_flow(bar, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(bar, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER,
                          LV_FLEX_ALIGN_CENTER);
    lv_obj_set_style_pad_left(bar, g_tx.lg, LV_PART_MAIN);
    lv_obj_set_style_pad_right(bar, g_tx.lg, LV_PART_MAIN);
    lv_obj_set_style_pad_column(bar, g_tx.sm, LV_PART_MAIN);
    lv_obj_set_style_bg_color(bar, g_pal.card, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(bar, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_border_width(bar, 0, LV_PART_MAIN);
    lv_obj_set_style_radius(bar, 0, LV_PART_MAIN);
    ui_decorative(bar);

    /* Every label has an explicit width, so nothing can push another out. */
    ui_make_label(bar, LV_SYMBOL_WIFI, g_tx.f_body, g_pal.text);
    const UiDeviceInfo& di = ui_device_info();
    lv_obj_t* title = ui_make_label(bar, di.model[0] ? di.model : "LVGL",
                                    g_tx.f_subtitle, g_pal.text);
    lv_obj_set_width(title, ui_dpx(80, g_dpi));
    lv_obj_set_height(title, ui_dpx(24, g_dpi));
    lv_label_set_long_mode(title, LV_LABEL_LONG_MODE_DOTS);

    make_grow_spacer(bar);

    g_clock_label = ui_make_label(bar, "--:--:--", g_tx.f_subtitle, g_pal.text);
    size_label_to(g_clock_label, "00:00:00");
    g_date_label = ui_make_label(bar, "----/--/--", g_tx.f_caption, g_pal.dim);
    size_label_to(g_date_label, "0000/00/00");

    make_grow_spacer(bar);

    g_battery_glyph = ui_make_label(bar, LV_SYMBOL_BATTERY_FULL, g_tx.f_body,
                                    g_pal.accent2);
    g_battery_label = ui_make_label(bar, "--%", g_tx.f_body, g_pal.text);
    size_label_to(g_battery_label, "100%");

    clock_tick(nullptr);
    g_clock_timer = lv_timer_create(clock_tick, 1000, nullptr);
    ui_set_battery_level(g_battery_level, g_battery_charging);
    return bar;
}

lv_obj_t* ui_make_dashboard_panel(lv_obj_t* parent, const char* caption) {
    lv_obj_t* panel = lv_obj_create(parent);
    lv_obj_set_width(panel, LV_PCT(100));
    lv_obj_set_height(panel, LV_SIZE_CONTENT);
    lv_obj_set_layout(panel, LV_LAYOUT_FLEX);
    lv_obj_set_flex_flow(panel, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_flex_align(panel, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_START,
                          LV_FLEX_ALIGN_START);
    lv_obj_set_style_pad_all(panel, ui_dpx(14, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_pad_row(panel, ui_dpx(10, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_radius(panel, ui_dpx(16, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_bg_color(panel, g_pal.card, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(panel, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_border_width(panel, 0, LV_PART_MAIN);
    lv_obj_set_style_shadow_width(panel, ui_dpx(10, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_shadow_opa(panel, LV_OPA_20, LV_PART_MAIN);
    lv_obj_set_style_shadow_offset_y(panel, ui_dpx(2, g_dpi), LV_PART_MAIN);
    lv_obj_clear_flag(panel, LV_OBJ_FLAG_SCROLLABLE);
    if (caption) {
        ui_make_label(panel, caption, 12, g_pal.dim);
    }
    return panel;
}

/* ---- Android device map ----------------------------------------------------
 * A phone outline filled with the components actually detected on the device
 * (SoC, RAM, storage, display, cameras, sensors). Drawn in a 100x100 design
 * space and scaled to the caller's pixel rectangle, so one layout adapts to
 * portrait and landscape. @breadcrumb BC-021 @breadcrumb BC-028 */

/* Device field shown as a chip's sub-label. */
enum DeviceField {
    FIELD_SOC, FIELD_MEMORY, FIELD_STORAGE,
    FIELD_DISPLAY, FIELD_CAMERA, FIELD_MODEM, FIELD_WIFI,
    FIELD_BATTERY, FIELD_SENSORS, FIELD_AUDIO, FIELD_USB,
};

struct ChipDef {
    float       x, y;      /* center, percent of the diagram */
    float       w, h;      /* size, percent of the diagram */
    uint32_t    bus;
    const char* name;      /* static label */
    DeviceField value;     /* dynamic value from UiDeviceInfo */
};

/* Inside the phone body. */
static const ChipDef kInside[] = {
    { 23.0f, 60.0f, 34.0f, 14.0f, 0x1976D2, "SoC",     FIELD_SOC },
    { 23.0f, 73.0f, 34.0f, 11.0f, 0x7B1FA2, "Memory",  FIELD_MEMORY },
    { 23.0f, 85.0f, 34.0f, 11.0f, 0x00838F, "Storage", FIELD_STORAGE },
};
static constexpr int kInsideCount = sizeof(kInside) / sizeof(kInside[0]);

/* A single column of peripherals, each on its own row so no branch crosses
 * another chip. */
static const ChipDef kOutside[] = {
    { 78.0f, 12.0f, 32.0f, 9.0f, 0x1976D2, "Display", FIELD_DISPLAY },
    { 78.0f, 22.0f, 32.0f, 9.0f, 0x1976D2, "Camera",  FIELD_CAMERA },
    { 78.0f, 32.0f, 32.0f, 9.0f, 0x00838F, "Modem",   FIELD_MODEM },
    { 78.0f, 42.0f, 32.0f, 9.0f, 0x00838F, "WiFi/BT", FIELD_WIFI },
    { 78.0f, 52.0f, 32.0f, 9.0f, 0xC62828, "Battery", FIELD_BATTERY },
    { 78.0f, 62.0f, 32.0f, 9.0f, 0x388E3C, "Sensors", FIELD_SENSORS },
    { 78.0f, 72.0f, 32.0f, 9.0f, 0xF57C00, "Audio",   FIELD_AUDIO },
    { 78.0f, 82.0f, 32.0f, 9.0f, 0xC62828, "USB-C",   FIELD_USB },
};
static constexpr int kOutsideCount = sizeof(kOutside) / sizeof(kOutside[0]);

/* Persistent storage for the pushed device info (JNI strings are temporary). */
static std::string g_di_manufacturer, g_di_model, g_di_soc, g_di_memory,
                   g_di_storage, g_di_display, g_di_android, g_di_cameras,
                   g_di_sensors;
static UiDeviceInfo g_di = { "", "", "", "", "", "", "", "", "" };

void ui_set_device_info(const UiDeviceInfo& info) {
    g_di_manufacturer = info.manufacturer ? info.manufacturer : "";
    g_di_model        = info.model        ? info.model        : "";
    g_di_soc          = info.soc          ? info.soc          : "";
    g_di_memory       = info.memory       ? info.memory       : "";
    g_di_storage      = info.storage      ? info.storage      : "";
    g_di_display      = info.display      ? info.display      : "";
    g_di_android      = info.android_version ? info.android_version : "";
    g_di_cameras      = info.cameras      ? info.cameras      : "";
    g_di_sensors      = info.sensors      ? info.sensors      : "";
    g_di = {
        g_di_manufacturer.c_str(), g_di_model.c_str(), g_di_soc.c_str(),
        g_di_memory.c_str(), g_di_storage.c_str(), g_di_display.c_str(),
        g_di_android.c_str(), g_di_cameras.c_str(), g_di_sensors.c_str()
    };
}

const UiDeviceInfo& ui_device_info() {
    return g_di;
}

static const char* device_value(DeviceField field) {
    switch (field) {
        case FIELD_SOC:     return g_di.soc;
        case FIELD_MEMORY:  return g_di.memory;
        case FIELD_STORAGE: return g_di.storage;
        case FIELD_DISPLAY: return g_di.display;
        case FIELD_CAMERA:  return g_di.cameras;
        case FIELD_SENSORS: return g_di.sensors;
        /* Not exposed by the Android APIs; show a plausible default. */
        case FIELD_MODEM:   return "5G";
        case FIELD_WIFI:    return "WiFi";
        case FIELD_BATTERY: return "Li-Po";
        case FIELD_AUDIO:   return "I2S";
        case FIELD_USB:     return "USB 3.2";
    }
    return "";
}

/* Height of a label's text, used to centre the two lines without flex. */
static int label_text_height(lv_obj_t* label) {
    lv_point_t size = {0, 0};
    lv_text_get_size(&size, lv_label_get_text(label),
                     lv_obj_get_style_text_font(label, LV_PART_MAIN),
                     0, 0, LV_COORD_MAX, LV_TEXT_FLAG_NONE);
    return size.y;
}

static void draw_map_chip(lv_obj_t* d, const ChipDef& c, int width_px, int height_px) {
    const int bw = static_cast<int>(c.w / 100.0f * width_px);
    const int bh = static_cast<int>(c.h / 100.0f * height_px);
    const int cx = static_cast<int>(c.x / 100.0f * width_px);
    const int cy = static_cast<int>(c.y / 100.0f * height_px);
    lv_obj_t* box = lv_obj_create(d);
    lv_obj_set_pos(box, cx - bw / 2, cy - bh / 2);
    lv_obj_set_size(box, bw, bh);
    /* No layout: the two lines are positioned explicitly so they always fit
     * and never spread to the chip edges. */
    lv_obj_set_style_pad_all(box, 0, LV_PART_MAIN);
    lv_obj_set_style_radius(box, ui_dpx(7, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_bg_color(box, g_pal.chip, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(box, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_border_width(box, ui_dpx(1, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_border_color(box, g_pal.chip_border, LV_PART_MAIN);
    ui_decorative(box);

    const char* value = device_value(c.value);

    lv_obj_t* nm = ui_make_label(box, c.name, ui_tokens().f_body, g_pal.chip_text);
    ui_fit_label(nm, bw, ui_tokens().f_body);
    lv_obj_set_width(nm, bw);
    lv_obj_set_style_text_align(nm, LV_TEXT_ALIGN_CENTER, 0);

    lv_obj_t* sb = ui_make_label(box, (value && *value) ? value : "-",
                                 ui_tokens().f_caption, to_lv_color(c.bus));
    ui_fit_label(sb, bw, ui_tokens().f_caption);
    lv_obj_set_width(sb, bw);
    lv_obj_set_style_text_align(sb, LV_TEXT_ALIGN_CENTER, 0);

    const int gap = ui_dpx(2, g_dpi);
    const int nh = label_text_height(nm);
    const int sh = label_text_height(sb);
    const int top = (bh - (nh + gap + sh)) / 2;
    lv_obj_set_pos(nm, 0, top);
    lv_obj_set_pos(sb, 0, top + nh + gap);
}

lv_obj_t* ui_make_diagram(lv_obj_t* parent, int width_px, int height_px) {
    lv_obj_t* d = lv_obj_create(parent);
    lv_obj_set_size(d, width_px, height_px);
    lv_obj_set_style_bg_opa(d, LV_OPA_TRANSP, LV_PART_MAIN);
    lv_obj_set_style_border_width(d, 0, LV_PART_MAIN);
    lv_obj_set_style_pad_all(d, 0, LV_PART_MAIN);
    /* No layout: children are positioned in the 100x100 design space. */
    ui_decorative(d);
    if (width_px <= 0 || height_px <= 0) return d;

    auto px = [width_px](float pct) {
        return static_cast<int>(pct / 100.0f * width_px);
    };
    auto py = [height_px](float pct) {
        return static_cast<int>(pct / 100.0f * height_px);
    };

    /* ---- Phone body and screen ---- */
    lv_obj_t* body = lv_obj_create(d);
    lv_obj_set_pos(body, px(2), py(8));
    lv_obj_set_size(body, px(42), py(90));
    lv_obj_set_style_radius(body, ui_dpx(10, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_bg_opa(body, LV_OPA_TRANSP, LV_PART_MAIN);
    lv_obj_set_style_border_width(body, ui_dpx(2, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_border_color(body, g_pal.chip_border, LV_PART_MAIN);
    ui_decorative(body);

    lv_obj_t* screen = lv_obj_create(d);
    lv_obj_set_pos(screen, px(5), py(12));
    lv_obj_set_size(screen, px(36), py(32));
    lv_obj_set_style_radius(screen, ui_dpx(6, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_bg_color(screen, g_pal.divider, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(screen, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_border_width(screen, 0, LV_PART_MAIN);
    lv_obj_set_layout(screen, LV_LAYOUT_FLEX);
    lv_obj_set_flex_flow(screen, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_flex_align(screen, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER,
                          LV_FLEX_ALIGN_CENTER);
    lv_obj_set_style_pad_all(screen, ui_dpx(6, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_pad_row(screen, ui_dpx(2, g_dpi), LV_PART_MAIN);
    ui_decorative(screen);

    const int screen_w = px(36) - ui_dpx(12, g_dpi);
    lv_obj_t* os_lbl = ui_make_label(screen,
                                     g_di.android_version[0] ? g_di.android_version
                                                            : "Android",
                                     ui_tokens().f_title, g_pal.text);
    ui_fit_label(os_lbl, screen_w, ui_tokens().f_title);
    if (g_di.model[0]) {
        lv_obj_t* mdl = ui_make_label(screen, g_di.model, ui_tokens().f_caption,
                                      g_pal.dim);
        ui_fit_label(mdl, screen_w, ui_tokens().f_caption);
    }
    char dpi_buf[24];
    snprintf(dpi_buf, sizeof(dpi_buf), "%d dpi", g_dpi);
    lv_obj_t* dpi = ui_make_label(screen, dpi_buf, ui_tokens().f_caption, g_pal.dim);
    ui_fit_label(dpi, screen_w, ui_tokens().f_caption);

    /* ---- Components inside the phone ---- */
    for (const auto& c : kInside) {
        draw_map_chip(d, c, width_px, height_px);
    }

    /* ---- System bus: a vertical bar the SoC feeds, and branches ---- */
    lv_obj_t* bus_bar = lv_obj_create(d);
    lv_obj_set_pos(bus_bar, px(46), py(10));
    lv_obj_set_size(bus_bar, ui_dpx(2, g_dpi), py(84) - py(10));
    lv_obj_set_style_radius(bus_bar, ui_dpx(1, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_bg_color(bus_bar, g_pal.primary, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(bus_bar, LV_OPA_40, LV_PART_MAIN);
    lv_obj_set_style_border_width(bus_bar, 0, LV_PART_MAIN);
    ui_decorative(bus_bar);

    static lv_point_precise_t s_soc_link[2] = { {0, 0}, {0, 0} };
    static lv_point_precise_t s_branch[kOutsideCount][2];

    lv_obj_t* soc_link = lv_line_create(d);
    s_soc_link[0].x = static_cast<lv_value_precise_t>(px(23));
    s_soc_link[0].y = static_cast<lv_value_precise_t>(py(60));
    s_soc_link[1].x = static_cast<lv_value_precise_t>(px(46));
    s_soc_link[1].y = static_cast<lv_value_precise_t>(py(60));
    lv_line_set_points(soc_link, s_soc_link, 2);
    lv_obj_set_style_line_width(soc_link, ui_dpx(2, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_line_color(soc_link, g_pal.primary, LV_PART_MAIN);
    lv_obj_set_style_line_rounded(soc_link, true, LV_PART_MAIN);
    ui_decorative(soc_link);

    for (int i = 0; i < kOutsideCount; i++) {
        const ChipDef& c = kOutside[i];
        lv_obj_t* line = lv_line_create(d);
        s_branch[i][0].x = static_cast<lv_value_precise_t>(px(46));
        s_branch[i][0].y = static_cast<lv_value_precise_t>(py(c.y));
        s_branch[i][1].x = static_cast<lv_value_precise_t>(px(c.x - c.w / 2.0f));
        s_branch[i][1].y = static_cast<lv_value_precise_t>(py(c.y));
        lv_line_set_points(line, s_branch[i], 2);
        lv_obj_set_style_line_width(line, ui_dpx(2, g_dpi), LV_PART_MAIN);
        lv_obj_set_style_line_color(line, to_lv_color(c.bus), LV_PART_MAIN);
        lv_obj_set_style_line_rounded(line, true, LV_PART_MAIN);
        lv_obj_set_style_line_opa(line, LV_OPA_60, LV_PART_MAIN);
        ui_decorative(line);
    }

    /* ---- Peripheral chips ---- */
    for (const auto& c : kOutside) {
        draw_map_chip(d, c, width_px, height_px);
    }

    /* ---- Header row: title left, bus legend right ---- */
    lv_obj_t* header = lv_obj_create(d);
    lv_obj_set_pos(header, 0, 0);
    lv_obj_set_size(header, LV_PCT(100), py(7));
    lv_obj_set_layout(header, LV_LAYOUT_FLEX);
    lv_obj_set_flex_flow(header, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(header, LV_FLEX_ALIGN_SPACE_BETWEEN,
                          LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
    lv_obj_set_style_pad_left(header, px(2), LV_PART_MAIN);
    lv_obj_set_style_pad_right(header, px(2), LV_PART_MAIN);
    lv_obj_set_style_pad_column(header, ui_dpx(8, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_bg_opa(header, LV_OPA_TRANSP, LV_PART_MAIN);
    lv_obj_set_style_border_width(header, 0, LV_PART_MAIN);
    ui_decorative(header);

    lv_obj_t* cap = ui_make_label(header, "DEVICE MAP", ui_tokens().f_body,
                                  g_pal.dim);
    lv_obj_set_flex_grow(cap, 1);

    lv_obj_t* sample = lv_obj_create(header);
    lv_obj_set_size(sample, ui_dpx(14, g_dpi), ui_dpx(2, g_dpi));
    lv_obj_set_style_bg_color(sample, g_pal.primary, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(sample, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_border_width(sample, 0, LV_PART_MAIN);
    ui_decorative(sample);
    ui_make_label(header, "System Bus", ui_tokens().f_caption, g_pal.dim);

    return d;
}

/* ---- Dashboard state + callbacks ----------------------------------------- */

static int g_brightness = 80;
static int g_volume     = 60;
static lv_obj_t* g_power_status = nullptr;

static void on_brightness_changed(int percent, void* /*user_data*/) {
    g_brightness = percent;
    if (g_on_set_brightness) g_on_set_brightness(percent);
}

static void on_volume_changed(int percent, void* /*user_data*/) {
    g_volume = percent;
    if (g_on_set_volume) g_on_set_volume(percent);
}

static void on_power_clicked(lv_event_t* e) {
    const char* label = static_cast<const char*>(lv_event_get_user_data(e));
    if (g_power_status && label) {
        lv_label_set_text_fmt(g_power_status, "Last: %s", label);
    }
}

void build_home_screen(int /*dpi*/) {
    /* @breadcrumb BC-020 */
    lv_obj_t* screen = make_screen(g_theme, g_dpi);

    /* The dashboard is absolutely positioned: the screen's flex column would
     * fight lv_obj_set_pos() on the children (see BC-019), so an unlaid-out
     * root owns them instead. */
    lv_obj_t* root = lv_obj_create(screen);
    lv_obj_set_width(root, LV_PCT(100));
    lv_obj_set_flex_grow(root, 1);
    lv_obj_set_layout(root, LV_LAYOUT_NONE);
    lv_obj_set_style_bg_opa(root, LV_OPA_TRANSP, LV_PART_MAIN);
    lv_obj_set_style_border_width(root, 0, LV_PART_MAIN);
    lv_obj_set_style_pad_all(root, 0, LV_PART_MAIN);
    ui_decorative(root);

    const int W = LV_HOR_RES;
    const int H = LV_VER_RES;
    const int sb_h = ui_dpx(52, g_dpi);
    const int pad = ui_dpx(12, g_dpi);
    const bool landscape = W > H;

    lv_obj_t* bar = ui_make_status_bar(root);
    lv_obj_set_pos(bar, 0, 0);

    int dx, dy, dw, dh, sx, sy, sw, sh;
    if (landscape) {
        sw = ui_dpx(320, g_dpi);
        dw = W - sw - pad * 3;
        dh = H - sb_h - pad * 2;
        dx = pad;
        dy = sb_h + pad;
        sx = pad * 2 + dw;
        sy = sb_h + pad;
        sh = dh;
    } else {
        dh = (H - sb_h - pad * 3) * 44 / 100;
        dw = W - pad * 2;
        dx = pad;
        dy = sb_h + pad;
        sx = pad;
        sy = sb_h + pad * 2 + dh;
        sw = W - pad * 2;
        sh = H - sb_h - pad * 3 - dh;
    }
    if (dw < 80 || dh < 80 || sw < 80 || sh < 80) return;

    lv_obj_t* diagram = ui_make_diagram(root, dw, dh);
    lv_obj_set_pos(diagram, dx, dy);

    lv_obj_t* sidebar = lv_obj_create(root);
    lv_obj_set_pos(sidebar, sx, sy);
    lv_obj_set_size(sidebar, sw, sh);
    lv_obj_set_layout(sidebar, LV_LAYOUT_FLEX);
    lv_obj_set_flex_flow(sidebar, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_flex_align(sidebar, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_START,
                          LV_FLEX_ALIGN_START);
    lv_obj_set_style_pad_all(sidebar, 0, LV_PART_MAIN);
    lv_obj_set_style_pad_row(sidebar, ui_dpx(12, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_bg_opa(sidebar, LV_OPA_TRANSP, LV_PART_MAIN);
    lv_obj_set_style_border_width(sidebar, 0, LV_PART_MAIN);
    lv_obj_set_scroll_dir(sidebar, LV_DIR_VER);
    lv_obj_set_scrollbar_mode(sidebar, LV_SCROLLBAR_MODE_OFF);
    lv_obj_clear_flag(sidebar, LV_OBJ_FLAG_CLICKABLE);

    /* LCD backlight (functional: drives the window brightness). */
    lv_obj_t* backlight = ui_make_dashboard_panel(sidebar, "LCD %");
    ui_make_stepper(backlight, g_brightness, 1, 100, 10, on_brightness_changed, nullptr);

    /* Speaker volume (functional: drives the music stream volume). */
    lv_obj_t* volume = ui_make_dashboard_panel(sidebar, "SPEAKER VOLUME %");
    ui_make_stepper(volume, g_volume, 0, 100, 10, on_volume_changed, nullptr);

    /* Power panel: simulated (Android apps cannot power off the device). */
    lv_obj_t* power = ui_make_dashboard_panel(sidebar, "POWER");
    lv_obj_t* off = ui_make_button(power, "OFF", UI_BUTTON_FILLED, true);
    lv_obj_set_width(off, LV_PCT(100));
    lv_obj_add_event_cb(off, on_power_clicked, LV_EVENT_CLICKED, (void*)"OFF");
    lv_obj_t* sleep_touch = ui_make_button(power, "SLEEP & TOUCH TO WAKEUP",
                                           UI_BUTTON_OUTLINED, false);
    lv_obj_set_width(sleep_touch, LV_PCT(100));
    lv_obj_add_event_cb(sleep_touch, on_power_clicked, LV_EVENT_CLICKED,
                        (void*)"SLEEP & TOUCH TO WAKEUP");
    lv_obj_t* sleep_shake = ui_make_button(power, "SLEEP & SHAKE TO WAKEUP",
                                           UI_BUTTON_OUTLINED, false);
    lv_obj_set_width(sleep_shake, LV_PCT(100));
    lv_obj_add_event_cb(sleep_shake, on_power_clicked, LV_EVENT_CLICKED,
                        (void*)"SLEEP & SHAKE TO WAKEUP");
    lv_obj_t* sleep_10s = ui_make_button(power, "SLEEP 10 SECONDS", UI_BUTTON_TEXT, false);
    lv_obj_set_width(sleep_10s, LV_PCT(100));
    lv_obj_add_event_cb(sleep_10s, on_power_clicked, LV_EVENT_CLICKED,
                        (void*)"SLEEP 10 SECONDS");
    g_power_status = ui_make_label(power, "No power action yet", 11, g_pal.dim);

    /* RTC panel (live clock driven by the status-bar timer). */
    lv_obj_t* rtc = ui_make_dashboard_panel(sidebar, "RTC");
    g_rtc_clock_label = ui_make_label(rtc, "--:--:--", 30, g_pal.text);
    lv_obj_set_width(g_rtc_clock_label, LV_PCT(100));
    lv_obj_set_style_text_align(g_rtc_clock_label, LV_TEXT_ALIGN_CENTER, 0);
    g_rtc_date_label = ui_make_label(rtc, "----/--/--", 13, g_pal.dim);
    lv_obj_set_width(g_rtc_date_label, LV_PCT(100));
    lv_obj_set_style_text_align(g_rtc_date_label, LV_TEXT_ALIGN_CENTER, 0);

    /* Peripherals as M3 assist chips: compact and scannable. */
    lv_obj_t* periph = ui_make_dashboard_panel(sidebar, "PERIPHERALS");
    static const char* kPeriph[] = {
        "GPIO 12", "CAMERA", "BT SPP", "USB 3.2",
    };
    lv_obj_t* chip_row = ui_make_row(periph, UI_SPACE_SM);
    lv_obj_set_flex_flow(chip_row, LV_FLEX_FLOW_ROW_WRAP);
    lv_obj_set_style_pad_row(chip_row, ui_space(UI_SPACE_SM), LV_PART_MAIN);
    for (const char* p : kPeriph) {
        ui_make_chip(chip_row, p, false);
    }

    /* Demos: every existing sample stays reachable (no feature removals). */
    lv_obj_t* demos = ui_make_dashboard_panel(sidebar, "DEMOS");
    static const struct { const char* icon; const char* name; int id; } kDemos[] = {
        { LV_SYMBOL_SETTINGS, "Widget Showcase",  SCREEN_WIDGET },
        { LV_SYMBOL_LIST,     "Layout Showcase",  SCREEN_LAYOUT },
        { LV_SYMBOL_REFRESH,  "Interactive Demo", SCREEN_INTERACTIVE },
        { LV_SYMBOL_VIDEO,    "Camera Sample",    SCREEN_CAMERA },
        { LV_SYMBOL_PLAY,     "Game Sample",      SCREEN_GAME },
        { LV_SYMBOL_HOME,     "Launcher Sample",  SCREEN_LAUNCHER },
        { LV_SYMBOL_GPS,      "Showcase",         SCREEN_SHOWCASE },
        { LV_SYMBOL_EDIT,     "App Demo",         1000 },
    };
    static int demo_ids[8];
    for (int i = 0; i < 8; i++) demo_ids[i] = kDemos[i].id;
    for (int i = 0; i < 8; i++) {
        lv_obj_t* row = lv_obj_create(demos);
        lv_obj_set_width(row, LV_PCT(100));
        lv_obj_set_height(row, LV_SIZE_CONTENT);
        lv_obj_set_layout(row, LV_LAYOUT_FLEX);
        lv_obj_set_flex_flow(row, LV_FLEX_FLOW_ROW);
        lv_obj_set_flex_align(row, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER,
                              LV_FLEX_ALIGN_CENTER);
        lv_obj_set_style_pad_all(row, ui_dpx(8, g_dpi), LV_PART_MAIN);
        lv_obj_set_style_pad_column(row, ui_dpx(10, g_dpi), LV_PART_MAIN);
        lv_obj_set_style_radius(row, ui_dpx(10, g_dpi), LV_PART_MAIN);
        lv_obj_set_style_bg_color(row, g_pal.card, LV_PART_MAIN);
        lv_obj_set_style_bg_opa(row, LV_OPA_COVER, LV_PART_MAIN);
        lv_obj_set_style_border_width(row, ui_dpx(1, g_dpi), LV_PART_MAIN);
        lv_obj_set_style_border_color(row, g_pal.divider, LV_PART_MAIN);
        lv_obj_add_event_cb(row, on_menu_card_clicked, LV_EVENT_CLICKED, &demo_ids[i]);

        ui_make_label(row, kDemos[i].icon, 15, g_pal.primary);
        lv_obj_t* nm = ui_make_label(row, kDemos[i].name, 14, g_pal.text);
        lv_obj_set_flex_grow(nm, 1);
        ui_make_label(row, LV_SYMBOL_RIGHT, 14, g_pal.dim);
    }

    /* Theme FAB: toggles dark mode through Kotlin (see ui_set_theme_hook). */
    lv_obj_t* theme_fab = ui_make_fab(root, LV_SYMBOL_REFRESH, nullptr);
    lv_obj_align(theme_fab, LV_ALIGN_BOTTOM_RIGHT,
                 -ui_dpx(16, g_dpi), -ui_dpx(16, g_dpi));
    lv_obj_add_event_cb(theme_fab, on_theme_fab_clicked, LV_EVENT_CLICKED, nullptr);
}

/* -------------------------------------------------------------------------- */
/* Widget wrappers                                                            */
/* -------------------------------------------------------------------------- */

static lv_obj_t* ui_make_filled_button(lv_obj_t* parent, const char* text) {
    lv_obj_t* b = lv_button_create(parent);
    lv_obj_set_size(b, ui_dpx(140, g_dpi), ui_dpx(44, g_dpi));
    lv_obj_set_style_radius(b, ui_dpx(10, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_bg_color(b, g_pal.primary, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(b, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_border_width(b, 0, LV_PART_MAIN);
    lv_obj_t* l = ui_make_label(b, text, 15, g_pal.on_primary);
    lv_obj_center(l);
    return b;
}

lv_obj_t* ui_make_slider(lv_obj_t* parent, int value) {
    lv_obj_t* s = lv_slider_create(parent);
    lv_obj_set_flex_grow(s, 1);
    lv_obj_set_height(s, ui_dpx(20, g_dpi));
    lv_obj_set_style_bg_color(s, g_pal.divider, LV_PART_MAIN);
    lv_obj_set_style_radius(s, ui_dpx(4, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_bg_color(s, g_pal.primary, LV_PART_INDICATOR);
    lv_obj_set_style_radius(s, ui_dpx(4, g_dpi), LV_PART_INDICATOR);
    /* The knob diameter equals the slider height; a card fill with a primary
     * ring covers the indicator's rounded end cleanly. */
    lv_obj_set_style_radius(s, LV_RADIUS_CIRCLE, LV_PART_KNOB);
    lv_obj_set_style_bg_color(s, g_pal.card, LV_PART_KNOB);
    lv_obj_set_style_bg_opa(s, LV_OPA_COVER, LV_PART_KNOB);
    lv_obj_set_style_border_color(s, g_pal.primary, LV_PART_KNOB);
    lv_obj_set_style_border_width(s, ui_dpx(2, g_dpi), LV_PART_KNOB);
    lv_obj_set_style_pad_all(s, 0, LV_PART_KNOB);
    lv_slider_set_value(s, value, LV_ANIM_OFF);
    return s;
}

lv_obj_t* ui_make_switch(lv_obj_t* parent, bool on) {
    lv_obj_t* sw = lv_switch_create(parent);
    lv_obj_set_style_bg_color(sw, g_pal.primary, LV_PART_INDICATOR | LV_STATE_CHECKED);
    if (on) lv_obj_add_state(sw, LV_STATE_CHECKED);
    return sw;
}

lv_obj_t* ui_make_checkbox(lv_obj_t* parent, const char* text) {
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
    return cb;
}

lv_obj_t* ui_make_progress(lv_obj_t* parent, int value) {
    lv_obj_t* bar = lv_bar_create(parent);
    lv_obj_set_flex_grow(bar, 1);
    lv_obj_set_height(bar, ui_dpx(14, g_dpi));
    lv_obj_set_style_radius(bar, ui_dpx(7, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_radius(bar, ui_dpx(7, g_dpi), LV_PART_INDICATOR);
    lv_obj_set_style_bg_color(bar, g_pal.divider, LV_PART_MAIN);
    lv_obj_set_style_bg_color(bar, g_pal.primary, LV_PART_INDICATOR);
    lv_bar_set_value(bar, value, LV_ANIM_OFF);
    return bar;
}

lv_obj_t* ui_make_arc(lv_obj_t* parent, int value) {
    lv_obj_t* arc = lv_arc_create(parent);
    lv_obj_set_size(arc, ui_dpx(92, g_dpi), ui_dpx(92, g_dpi));
    lv_arc_set_bg_angles(arc, 135, 45);
    lv_obj_set_style_arc_color(arc, g_pal.divider, LV_PART_MAIN);
    lv_obj_set_style_arc_width(arc, ui_dpx(8, g_dpi), LV_PART_MAIN);
    lv_obj_set_style_arc_color(arc, g_pal.primary, LV_PART_INDICATOR);
    lv_obj_set_style_arc_width(arc, ui_dpx(8, g_dpi), LV_PART_INDICATOR);
    /* A small card-coloured knob with a primary ring, instead of the default
     * oversized blob. */
    lv_obj_set_style_bg_color(arc, g_pal.card, LV_PART_KNOB);
    lv_obj_set_style_bg_opa(arc, LV_OPA_COVER, LV_PART_KNOB);
    lv_obj_set_style_border_color(arc, g_pal.primary, LV_PART_KNOB);
    lv_obj_set_style_border_width(arc, ui_dpx(2, g_dpi), LV_PART_KNOB);
    lv_obj_set_style_radius(arc, LV_RADIUS_CIRCLE, LV_PART_KNOB);
    lv_obj_set_style_pad_all(arc, 0, LV_PART_KNOB);
    lv_arc_set_value(arc, value);
    return arc;
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

static void on_dialog_demo_clicked(lv_event_t* e) {
    lv_obj_t* screen = static_cast<lv_obj_t*>(lv_event_get_user_data(e));
    static const char* actions[] = { "Cancel", "OK" };
    ui_make_dialog(screen, "Material dialog",
                   "This dialog is built from LVGL widgets and follows the "
                   "active Material 3 palette.",
                   actions, 2, nullptr, nullptr);
}

static void on_snackbar_demo_clicked(lv_event_t* e) {
    lv_obj_t* screen = static_cast<lv_obj_t*>(lv_event_get_user_data(e));
    ui_make_snackbar(screen, "Material snackbar", "Dismiss", nullptr, nullptr);
}

static void on_nav_demo_selected(int index, void* /*user_data*/) {
    __android_log_print(ANDROID_LOG_INFO, "LVGLWidget", "nav selected %d", index);
}

void build_widget_screen(int /*dpi*/) {
    lv_obj_t* screen = make_screen(g_theme, g_dpi);
    lv_obj_t* body = ui_make_scaffold(screen, "Widget Showcase", true).body;

    lv_obj_t* controls = ui_make_section(body, "BASIC CONTROLS");
    ui_make_filled_button(make_labeled_row(controls, "Button"), "Tap me");
    ui_make_switch(make_labeled_row(controls, "Switch"), true);
    ui_make_checkbox(make_labeled_row(controls, "Checkbox"), "Enabled");

    lv_obj_t* values = ui_make_section(body, "VALUES");
    ui_make_slider(make_labeled_row(values, "Slider"), 40);
    ui_make_progress(make_labeled_row(values, "Progress"), 65);
    ui_make_arc(make_labeled_row(values, "Arc"), 70);

    lv_obj_t* selection = ui_make_section(body, "SELECTION");
    make_dropdown(make_labeled_row(selection, "Dropdown"), "Option A\nOption B\nOption C");

    /* Material 3 component gallery: every component reads the active palette. */
    lv_obj_t* material = ui_make_section(body, "MATERIAL 3");

    lv_obj_t* btn_row = ui_make_row(material, UI_SPACE_SM);
    lv_obj_set_flex_flow(btn_row, LV_FLEX_FLOW_ROW_WRAP);
    lv_obj_set_style_pad_row(btn_row, ui_space(UI_SPACE_SM), LV_PART_MAIN);
    ui_make_button(btn_row, "Filled", UI_BUTTON_FILLED, false);
    ui_make_button(btn_row, "Tonal", UI_BUTTON_TONAL, false);
    ui_make_button(btn_row, "Outlined", UI_BUTTON_OUTLINED, false);
    ui_make_button(btn_row, "Text", UI_BUTTON_TEXT, false);
    ui_make_button(btn_row, "Danger", UI_BUTTON_FILLED, true);

    lv_obj_t* chip_row = ui_make_row(material, UI_SPACE_SM);
    lv_obj_set_flex_flow(chip_row, LV_FLEX_FLOW_ROW_WRAP);
    lv_obj_set_style_pad_row(chip_row, ui_space(UI_SPACE_SM), LV_PART_MAIN);
    ui_make_chip(chip_row, "WiFi", true);
    ui_make_chip(chip_row, "Bluetooth", false);
    ui_make_chip(chip_row, "Location", false);
    ui_make_chip(chip_row, "NFC", false);

    ui_make_text_field(material, "Label", "Material text field");

    static const UiNavItem kNavItems[] = {
        { LV_SYMBOL_HOME, "Home" },
        { LV_SYMBOL_SETTINGS, "Settings" },
        { LV_SYMBOL_WIFI, "Network" },
    };
    ui_make_bottom_nav(material, kNavItems, 3, 0, on_nav_demo_selected, nullptr);

    lv_obj_t* dialog_btn = ui_make_button(material, "Open dialog", UI_BUTTON_TONAL, false);
    lv_obj_add_event_cb(dialog_btn, on_dialog_demo_clicked, LV_EVENT_CLICKED, screen);
    lv_obj_t* snackbar_btn = ui_make_button(material, "Show snackbar",
                                            UI_BUTTON_OUTLINED, false);
    lv_obj_add_event_cb(snackbar_btn, on_snackbar_demo_clicked, LV_EVENT_CLICKED, screen);

    lv_obj_t* fab = ui_make_fab(screen, LV_SYMBOL_PLUS, nullptr);
    lv_obj_align(fab, LV_ALIGN_BOTTOM_RIGHT, -ui_dpx(16, g_dpi), -ui_dpx(16, g_dpi));
    lv_obj_add_event_cb(fab, on_dialog_demo_clicked, LV_EVENT_CLICKED, screen);

    make_footer(screen, "Drag the slider, switch or arc");
}

void build_layout_screen(int /*dpi*/) {
    lv_obj_t* screen = make_screen(g_theme, g_dpi);
    lv_obj_t* body = ui_make_scaffold(screen, "Layout Showcase", true).body;

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
    lv_obj_t* body = ui_make_scaffold(screen, "Interactive Demo", true).body;

    lv_obj_t* hint = ui_make_label(body, "Touch anywhere to move the marker", g_tx.f_body,
                                   g_pal.dim);
    lv_obj_set_width(hint, LV_PCT(100));

    /* Full-size touch surface that catches presses. */
    lv_obj_t* surface = lv_obj_create(body);
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

/* Shared body for the "sample" screens: a panel with a title + live status
 * plus a details section, so the screen reads as a real status page instead of
 * a single card at the top. */
static void build_status_screen(const char* header, const char* title,
                                const char* initial_status, const char* footer) {
    lv_obj_t* screen = make_screen(g_theme, g_dpi);
    UiScaffold ui = ui_make_scaffold(screen, header, true);
    lv_obj_t* body = ui.body;
    lv_obj_set_flex_align(body, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER,
                          LV_FLEX_ALIGN_START);

    lv_obj_t* panel = ui_make_section(body, nullptr);
    lv_obj_set_width(panel, LV_PCT(100));

    lv_obj_t* heading = ui_make_label(panel, title, g_tx.f_title, g_pal.text);
    lv_obj_set_width(heading, LV_PCT(100));
    make_status_line(panel, initial_status);

    lv_obj_t* info = ui_make_section(body, "DETAILS");
    lv_obj_set_width(info, LV_PCT(100));
    static const struct { const char* key; const char* value; } rows[] = {
        { "Rendering", "LVGL native" },
        { "Thread", "Dedicated" },
        { "Refresh", "Vsync-aware" },
    };
    for (const auto& r : rows) {
        lv_obj_t* row = ui_make_row(info, UI_SPACE_MD);
        lv_obj_set_width(row, LV_PCT(100));
        lv_obj_t* k = ui_make_label(row, r.key, g_tx.f_body, g_pal.dim);
        lv_obj_set_width(k, ui_dpx(110, g_dpi));
        lv_obj_t* v = ui_make_label(row, r.value, g_tx.f_body, g_pal.text);
        lv_obj_set_flex_grow(v, 1);
        lv_obj_set_style_text_align(v, LV_TEXT_ALIGN_RIGHT, 0);
    }

    make_footer(screen, footer);
}

void build_camera_screen(int /*dpi*/) {
    build_status_screen("Camera Sample", "Camera2 Preview", "Camera status: idle",
                        "Status updates from the native Camera2 API");
}

/* ---- Game sample: tap the target ---------------------------------------- */

static lv_obj_t* g_game_field = nullptr;
static lv_obj_t* g_game_target = nullptr;
static lv_obj_t* g_game_score_label = nullptr;
static int g_game_score = 0;

static void game_place_target() {
    if (!g_game_target || !g_game_field) return;
    const int field_w = lv_obj_get_width(g_game_field);
    const int field_h = lv_obj_get_height(g_game_field);
    const int size = ui_dpx(56, g_dpi);
    if (field_w <= size || field_h <= size) return;
    const int x = rand() % (field_w - size + 1);
    const int y = rand() % (field_h - size + 1);
    lv_obj_set_pos(g_game_target, x, y);
}

static void on_game_target_tapped(lv_event_t* /*e*/) {
    g_game_score++;
    if (g_game_score_label) {
        lv_label_set_text_fmt(g_game_score_label, "Score: %d", g_game_score);
    }
    game_place_target();
}

void build_game_screen(int /*dpi*/) {
    lv_obj_t* screen = make_screen(g_theme, g_dpi);
    UiScaffold ui = ui_make_scaffold(screen, "Game Sample", true);
    lv_obj_t* body = ui.body;
    /* The play field handles presses itself; scrolling would steal them. */
    lv_obj_clear_flag(body, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_scrollbar_mode(body, LV_SCROLLBAR_MODE_OFF);
    lv_obj_set_flex_align(body, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER,
                          LV_FLEX_ALIGN_START);

    lv_obj_t* panel = ui_make_section(body, nullptr);
    lv_obj_set_width(panel, LV_PCT(100));

    lv_obj_t* heading = ui_make_label(panel, "Tap the Target", g_tx.f_title, g_pal.text);
    lv_obj_set_width(heading, LV_PCT(100));

    g_game_score = 0;
    g_game_score_label = ui_make_label(panel, "Score: 0", g_tx.f_headline, g_pal.primary);
    lv_obj_set_width(g_game_score_label, LV_PCT(100));
    lv_obj_set_style_text_align(g_game_score_label, LV_TEXT_ALIGN_CENTER, 0);

    /* Play field: no layout, so the target can be positioned absolutely. */
    lv_obj_t* field = lv_obj_create(body);
    lv_obj_set_width(field, LV_PCT(100));
    lv_obj_set_flex_grow(field, 1);
    lv_obj_set_style_bg_opa(field, LV_OPA_TRANSP, LV_PART_MAIN);
    lv_obj_set_style_border_width(field, 0, LV_PART_MAIN);
    lv_obj_set_style_pad_all(field, 0, LV_PART_MAIN);
    lv_obj_set_style_radius(field, 0, LV_PART_MAIN);
    lv_obj_set_scrollbar_mode(field, LV_SCROLLBAR_MODE_OFF);
    /* Not clickable: the screen handles presses so a tap anywhere is a miss. */
    lv_obj_clear_flag(field, LV_OBJ_FLAG_CLICKABLE);
    g_game_field = field;

    g_game_target = lv_button_create(field);
    lv_obj_set_size(g_game_target, ui_dpx(56, g_dpi), ui_dpx(56, g_dpi));
    lv_obj_set_style_radius(g_game_target, LV_RADIUS_CIRCLE, 0);
    lv_obj_set_style_bg_color(g_game_target, g_pal.primary, 0);
    lv_obj_set_style_bg_opa(g_game_target, LV_OPA_COVER, 0);
    lv_obj_set_style_border_width(g_game_target, ui_dpx(3, g_dpi), 0);
    lv_obj_set_style_border_color(g_game_target, g_pal.on_primary, 0);
    lv_obj_add_event_cb(g_game_target, on_game_target_tapped, LV_EVENT_CLICKED, nullptr);
    game_place_target();

    make_footer(screen, "Tap the circle to score");
}

/* ---- Launcher app grid ---------------------------------------------------- */

static std::vector<std::string> g_launcherLabels;
static std::vector<std::string> g_launcherPackages;

void ui_set_launcher_apps(const char* const* labels, const char* const* packages,
                          int count) {
    g_launcherLabels.clear();
    g_launcherPackages.clear();
    if (count <= 0) return;
    g_launcherLabels.reserve(static_cast<size_t>(count));
    g_launcherPackages.reserve(static_cast<size_t>(count));
    for (int i = 0; i < count; i++) {
        g_launcherLabels.emplace_back(labels && labels[i] ? labels[i] : "");
        g_launcherPackages.emplace_back(packages && packages[i] ? packages[i] : "");
    }
}

static void on_launcher_app_clicked(lv_event_t* e) {
    lv_obj_t* cell = static_cast<lv_obj_t*>(lv_event_get_current_target(e));
    const char* pkg = static_cast<const char*>(lv_obj_get_user_data(cell));
    if (pkg && *pkg) ui_emit_launcher_event(pkg);
}

void build_launcher_screen(int /*dpi*/) {
    if (g_launcherLabels.empty()) {
        build_status_screen("Launcher Sample", "Installed Apps",
                            "Loading installed apps...",
                            "List populated from the Android package manager");
        return;
    }

    lv_obj_t* screen = make_screen(g_theme, g_dpi);
    UiScaffold ui = ui_make_scaffold(screen, "Launcher Sample", true);
    lv_obj_t* body = ui.body;

    /* A scrollable list of app rows: robust at any density and width. */
    lv_obj_t* list = ui_make_column(body, UI_SPACE_SM);
    lv_obj_set_width(list, LV_PCT(100));
    const size_t count = g_launcherLabels.size();
    for (size_t i = 0; i < count; i++) {
        lv_obj_t* row = lv_obj_create(list);
        lv_obj_set_width(row, LV_PCT(100));
        lv_obj_set_height(row, LV_SIZE_CONTENT);
        lv_obj_set_layout(row, LV_LAYOUT_FLEX);
        lv_obj_set_flex_flow(row, LV_FLEX_FLOW_ROW);
        lv_obj_set_flex_align(row, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER,
                              LV_FLEX_ALIGN_CENTER);
        lv_obj_set_style_pad_all(row, ui_dpx(10, g_dpi), LV_PART_MAIN);
        lv_obj_set_style_pad_column(row, ui_dpx(12, g_dpi), LV_PART_MAIN);
        lv_obj_set_style_radius(row, ui_dpx(10, g_dpi), LV_PART_MAIN);
        lv_obj_set_style_bg_color(row, g_pal.card, LV_PART_MAIN);
        lv_obj_set_style_bg_opa(row, LV_OPA_COVER, LV_PART_MAIN);
        lv_obj_set_style_border_width(row, 1, LV_PART_MAIN);
        lv_obj_set_style_border_color(row, g_pal.divider, LV_PART_MAIN);
        /* The vector is stable for the lifetime of this screen. */
        lv_obj_set_user_data(row, (void*)g_launcherPackages[i].c_str());
        lv_obj_add_event_cb(row, on_launcher_app_clicked, LV_EVENT_CLICKED, nullptr);

        ui_make_label(row, LV_SYMBOL_HOME, 18, g_pal.primary);
        lv_obj_t* name = ui_make_label(row, g_launcherLabels[i].c_str(), 14,
                                       g_pal.text);
        lv_obj_set_flex_grow(name, 1);
        lv_label_set_long_mode(name, LV_LABEL_LONG_MODE_DOTS);
    }

    make_footer(screen, "Tap an app to launch it");
}

/* The animated showcase is a separate, self-contained demo and is implemented in
 * demo_showcase.cpp, not here, to keep the spring-physics demo code isolated. */
void build_showcase_screen(int dpi) {
    extern void build_showcase_screen_impl(int dpi);
    build_showcase_screen_impl(dpi);
}

/* -------------------------------------------------------------------------- */
/* Layout lint                                                                 */
/* -------------------------------------------------------------------------- */

static bool lint_intersects(const lv_area_t& a, const lv_area_t& b) {
    return a.x1 <= b.x2 && b.x1 <= a.x2 && a.y1 <= b.y2 && b.y1 <= a.y2;
}

/* Reject obviously-uninitialised rectangles (LVGL leaves coords at 0 or with
 * wild values until the first layout pass). */
static bool lint_valid_rect(const lv_area_t& r) {
    return r.x2 >= r.x1 && r.y2 >= r.y1 &&
           r.x1 > -100000 && r.x2 < 100000 &&
           r.y1 > -100000 && r.y2 < 100000;
}

static bool lint_overlay(const lv_obj_t* o) {
    return lv_obj_has_flag(o, LV_OBJ_FLAG_FLOATING) ||
           lv_obj_has_flag(o, LV_OBJ_FLAG_IGNORE_LAYOUT);
}

/* lv_obj_class_t is opaque in the public headers, so name the classes we know. */
static const char* ui_class_name(const lv_obj_t* o) {
    const lv_obj_class_t* c = lv_obj_get_class(o);
    if (c == &lv_obj_class)      return "obj";
    if (c == &lv_label_class)    return "label";
    if (c == &lv_button_class)   return "button";
    if (c == &lv_line_class)     return "line";
    if (c == &lv_arc_class)      return "arc";
    if (c == &lv_slider_class)   return "slider";
    if (c == &lv_switch_class)   return "switch";
    if (c == &lv_checkbox_class) return "checkbox";
    if (c == &lv_bar_class)      return "bar";
    if (c == &lv_dropdown_class) return "dropdown";
    return "other";
}

static void lint_walk(lv_obj_t* obj, UiLayoutIssue* out, int max_issues, int* count) {
    if (!obj || *count >= max_issues) return;
    const int n = static_cast<int>(lv_obj_get_child_count(obj));
    if (n == 0) return;

    const bool laid_out = lv_obj_get_style_layout(obj, LV_PART_MAIN) != 0;
    const bool scrollable = lv_obj_has_flag(obj, LV_OBJ_FLAG_SCROLLABLE);
    const bool overflow = lv_obj_is_overflow_visible(obj);
    lv_area_t parent_rect;
    lv_obj_get_coords(obj, &parent_rect);
    if (!lint_valid_rect(parent_rect)) return;

    /* Overlaps are only meaningful when the parent actually lays out children;
     * absolute-positioned containers (the diagram) are intentional. */
    if (laid_out) {
        for (int i = 0; i < n && *count < max_issues; i++) {
            lv_obj_t* a = lv_obj_get_child(obj, i);
            if (!a || lv_obj_has_flag(a, LV_OBJ_FLAG_HIDDEN) || lint_overlay(a)) continue;
            lv_area_t ra;
            lv_obj_get_coords(a, &ra);
            if (!lint_valid_rect(ra)) break;
            for (int j = i + 1; j < n && *count < max_issues; j++) {
                lv_obj_t* b = lv_obj_get_child(obj, j);
                if (!b || lv_obj_has_flag(b, LV_OBJ_FLAG_HIDDEN) || lint_overlay(b)) continue;
                lv_area_t rb;
                lv_obj_get_coords(b, &rb);
                if (!lint_valid_rect(rb) || !lint_intersects(ra, rb)) continue;
                UiLayoutIssue& it = out[(*count)++];
                it.type = 0; it.a = i; it.b = j;
                it.x1 = ra.x1; it.y1 = ra.y1; it.x2 = ra.x2; it.y2 = ra.y2;
                it.bx1 = rb.x1; it.by1 = rb.y1; it.bx2 = rb.x2; it.by2 = rb.y2;
                it.what = "overlapping siblings";
                it.parent_class = ui_class_name(obj);
                it.child_class = ui_class_name(a);
            }
        }
    }

    for (int i = 0; i < n && *count < max_issues; i++) {
        lv_obj_t* c = lv_obj_get_child(obj, i);
        if (!c || lv_obj_has_flag(c, LV_OBJ_FLAG_HIDDEN)) continue;
        lv_area_t rc;
        lv_obj_get_coords(c, &rc);
        if (!lint_valid_rect(rc)) {
            lint_walk(c, out, max_issues, count);
            continue;
        }

        if (laid_out && !lint_overlay(c) && !scrollable && !overflow &&
            (rc.x1 < parent_rect.x1 || rc.y1 < parent_rect.y1 ||
             rc.x2 > parent_rect.x2 || rc.y2 > parent_rect.y2)) {
            UiLayoutIssue& it = out[(*count)++];
            it.type = 1; it.a = i; it.b = -1;
            it.x1 = rc.x1; it.y1 = rc.y1; it.x2 = rc.x2; it.y2 = rc.y2;
            it.what = "child outside parent";
            it.px1 = parent_rect.x1; it.py1 = parent_rect.y1;
            it.px2 = parent_rect.x2; it.py2 = parent_rect.y2;
            it.parent_class = ui_class_name(obj);
            it.child_class = ui_class_name(c);
        }

        if (*count < max_issues &&
            lv_obj_check_type(c, &lv_label_class) &&
            lv_label_get_long_mode(c) == LV_LABEL_LONG_MODE_CLIP) {
            const char* text = lv_label_get_text(c);
            const lv_font_t* font = lv_obj_get_style_text_font(c, LV_PART_MAIN);
            if (text && *text && font) {
                lv_point_t size = {0, 0};
                lv_text_get_size(&size, text, font, 0, 0, LV_COORD_MAX,
                                 LV_TEXT_FLAG_NONE);
                if (size.x > lv_obj_get_width(c)) {
                    UiLayoutIssue& it = out[(*count)++];
                    it.type = 2; it.a = i; it.b = -1;
                    it.x1 = rc.x1; it.y1 = rc.y1; it.x2 = rc.x2; it.y2 = rc.y2;
                    it.what = "clipped text";
                    it.parent_class = ui_class_name(obj);
                    it.child_class = ui_class_name(c);
                }
            }
        }

        lint_walk(c, out, max_issues, count);
    }
}

int ui_layout_check(lv_obj_t* root, UiLayoutIssue* out, int max_issues) {
    if (!out || max_issues <= 0) return 0;
    int count = 0;
    lint_walk(root, out, max_issues, &count);
    return count;
}

void ui_layout_report(lv_obj_t* root) {
    UiLayoutIssue issues[32];
    int n = ui_layout_check(root, issues, 32);
    for (int i = 0; i < n; i++) {
        __android_log_print(ANDROID_LOG_WARN, "LVGLLayout",
                            "%s at (%d,%d %d,%d) parent (%d,%d %d,%d) [%s in %s]",
                            issues[i].what, issues[i].x1, issues[i].y1,
                            issues[i].x2, issues[i].y2,
                            issues[i].px1, issues[i].py1,
                            issues[i].px2, issues[i].py2,
                            issues[i].child_class ? issues[i].child_class : "?",
                            issues[i].parent_class ? issues[i].parent_class : "?");
    }
    if (n > 0) {
        __android_log_print(ANDROID_LOG_WARN, "LVGLLayout",
                            "%d layout issue(s) in this screen", n);
    }
}

void ui_layout_report_json(lv_obj_t* root, char* buf, size_t len) {
    UiLayoutIssue issues[32];
    int n = ui_layout_check(root, issues, 32);
    int pos = 0;
    if (!buf || len == 0) return;
    pos += snprintf(buf + pos, len - static_cast<size_t>(pos), "{\"issues\":[");
    for (int i = 0; i < n && pos < static_cast<int>(len) - 1; i++) {
        const char* type = issues[i].type == 0 ? "overlap"
                         : issues[i].type == 1 ? "bounds" : "text";
        pos += snprintf(buf + pos, len - static_cast<size_t>(pos),
                        "%s{\"type\":\"%s\",\"rect\":[%d,%d,%d,%d],"
                        "\"child\":\"%s\",\"parent\":\"%s\"",
                        i ? "," : "", type,
                        issues[i].x1, issues[i].y1, issues[i].x2, issues[i].y2,
                        issues[i].child_class ? issues[i].child_class : "",
                        issues[i].parent_class ? issues[i].parent_class : "");
        if (issues[i].b >= 0) {
            pos += snprintf(buf + pos, len - static_cast<size_t>(pos),
                            ",\"rect2\":[%d,%d,%d,%d]",
                            issues[i].bx1, issues[i].by1,
                            issues[i].bx2, issues[i].by2);
        }
        pos += snprintf(buf + pos, len - static_cast<size_t>(pos), "}");
    }
    snprintf(buf + pos, len - static_cast<size_t>(pos), "]}");
}

/* The lint runs on every screen build; disable it to silence the warnings. */
static bool g_lint_enabled = true;
void ui_layout_lint_set_enabled(bool enabled) { g_lint_enabled = enabled; }
bool ui_layout_lint_enabled() { return g_lint_enabled; }

static void lint_after_layout(lv_timer_t* timer) {
    auto* root = static_cast<lv_obj_t*>(lv_timer_get_user_data(timer));
    if (root) ui_layout_report(root);
}

/* -------------------------------------------------------------------------- */
/* Entry point                                                                */
/* -------------------------------------------------------------------------- */

typedef void (*ScreenBuilder)(int dpi);

/* Public: clean + theme the active screen and return it. Used by app-defined
 * screens (lvgl_ui.cpp), which build their own content instead of calling one
 * of the built-in builders below. */
lv_obj_t* ui_begin_screen(int dpi) {
    return make_screen(g_theme, dpi);
}

void launch_screen(int screen_id, int dpi, const UiTheme& theme) {
    static const ScreenBuilder builders[] = {
        build_home_screen,
        build_widget_screen,
        build_layout_screen,
        build_interactive_screen,
        build_camera_screen,
        build_game_screen,
        build_launcher_screen,
        build_showcase_screen,
    };

    if (screen_id < 0) {
        screen_id = SCREEN_HOME;   /* never show a blank screen */
    }

    /* Leaving the showcase must release its frame callback + animated state,
     * otherwise showcase_tick dereferences objects freed by lv_obj_clean(). */
    if (screen_id != SCREEN_SHOWCASE) {
        ui_reset_showcase();
    }

    g_dpi = dpi > 0 ? dpi : 440;
    g_theme = theme;
    g_pal = make_palette(theme);
    g_tx  = make_tokens(g_dpi);
    g_marker = nullptr;
    g_stepper_count = 0;   /* steppers re-register on every build */
    g_power_status = nullptr;

    /* The clock timer and status-bar labels outlive the screen that created
     * them, so tear them down here — launch_screen is the single entry point
     * for every screen (the showcase bypasses make_screen). Otherwise a
     * deleted label stays cached and the next timer tick touches freed memory.
     * @trap */
    if (g_clock_timer) {
        lv_timer_delete(g_clock_timer);
        g_clock_timer = nullptr;
    }
    g_clock_label = g_date_label = nullptr;
    g_rtc_clock_label = g_rtc_date_label = nullptr;
    g_battery_label = g_battery_glyph = nullptr;

    if (screen_id >= APP_SCREEN_BASE) {
        /* App-defined screen (Kotlin UI builder). */
        if (!ui_build_app_screen(screen_id, g_dpi)) {
            builders[SCREEN_HOME](dpi);
        }
    } else {
        if (screen_id >= SCREEN_COUNT) {
            screen_id = SCREEN_HOME;
        }
        builders[screen_id](dpi);
    }

    /* Lint after the first refresh so every object has real coordinates. The
     * screen object itself is stable across builds; its children are walked
     * live, so a fast screen switch simply lints the newer tree. */
    if (g_lint_enabled) {
        lv_timer_t* lint_timer = lv_timer_create(lint_after_layout, 500,
                                                 lv_screen_active());
        lv_timer_set_repeat_count(lint_timer, 1);
    }

    lv_obj_invalidate(lv_screen_active());
}

#if defined(__clang__) || defined(__GNUC__)
#pragma GCC diagnostic pop
#endif

} /* namespace lvgl_android */
