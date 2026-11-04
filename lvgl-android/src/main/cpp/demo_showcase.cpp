/*
 * Copyright (c) 2026 LVGL Android Framework Contributors
 * SPDX-License-Identifier: MIT
 *
 * This file is part of the LVGL Android Framework.
 * See LICENSE in the project root for full license information.
 *
 * Animated showcase — M5Tab5-inspired, rich-content version.
 *
 * Architecture (from studying M5Tab5 source):
 *   - Full-screen background scene (gradient + decorative layers)
 *   - Visible launcher tiles (grid) — each has icon + label
 *   - Tap → tile bounces (spring) + window springs OPEN from tile position
 *   - Windows interpolate x/y/w/h/opa between closed (tiny at hotspot) and
 *     open (full panel) keyframes
 *   - Each window is COLOR-CODED with rich content (gauges, charts, buttons)
 *   - Startup animation: logo springs in, then tiles stagger-in
 *   - Everything uses spring physics (the lvgl_anim engine)
 *
 * @breadcrumb BC-017
 * @breadcrumb BC-018
 * @trap Leaving this screen must clear the frame callback and reset g_show,
 *       otherwise showcase_tick dereferences objects freed by lv_obj_clean().
 */
#include "demo_ui.h"
#include "lvgl_anim.h"
#include "lvgl_window.h"

/* LVGL 9.x still exposes lv_obj_add/clear_flag (aliased to add/remove_flag) and
 * emits deprecation noise. They are correct and stable on 9.x, so we silence the
 * warning locally rather than switch to internal APIs. */
#if defined(__clang__) || defined(__GNUC__)
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
#endif

#include <android/log.h>
#include <cmath>
#include <memory>
#include <vector>

using namespace lvgl_android::lvgl_anim;
using namespace lvgl_android::lvgl_ui;

namespace lvgl_android {

#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, "LVGLShow", __VA_ARGS__)

/* Convert a palette color back to the 0xRRGGBB form Window::Config expects. */
static uint32_t rgb_hex(lv_color_t c) {
    return (static_cast<uint32_t>(c.red)   << 16) |
           (static_cast<uint32_t>(c.green) << 8)  |
           (static_cast<uint32_t>(c.blue));
}

/* -------------------------------------------------------------------------- */
/* State                                                                      */
/* -------------------------------------------------------------------------- */

struct Tile {
    lv_obj_t*  container = nullptr;
    lv_obj_t*  icon      = nullptr;
    std::unique_ptr<Window> window;   /* created on-demand when tapped */
    Window::Config win_cfg;
    void (*content_fn)(Window&) = nullptr;
    /* LVGL scale is 256 = LV_SCALE_NONE (see lv_style.h). */
    SpringValue scale{256.0f, SpringConfig::snappy()};
};

struct ShowcaseState {
    lv_obj_t* screen = nullptr;
    lv_obj_t* boot_logo   = nullptr;
    lv_obj_t* boot_label  = nullptr;
    SpringValue boot_y{900.0f, SpringConfig::default_()};
    SpringValue boot_opa{0.0f, SpringConfig::snappy()};
    SpringValue cpu{40.0f, SpringConfig::default_()};
    SpringValue mem{60.0f, SpringConfig::default_()};
    std::vector<Tile> tiles;
    lv_obj_t* cpu_arc = nullptr;
    lv_obj_t* mem_arc = nullptr;
    float       time  = 0.0f;   /* accumulated seconds, for frame-rate-independent motion */
    int         tick_count = 0;
    bool        booting    = true;
} g_show;

/* -------------------------------------------------------------------------- */
/* Window content — rich, color-coded panels                                  */
/* -------------------------------------------------------------------------- */

static void add_stat_row(lv_obj_t* parent, const char* name, const char* value, uint32_t color) {
    const int sd = ui_dpi();
    auto* row = lv_obj_create(parent);
    lv_obj_set_width(row, LV_PCT(100));
    lv_obj_set_height(row, LV_SIZE_CONTENT);
    lv_obj_set_layout(row, LV_LAYOUT_FLEX);
    lv_obj_set_flex_flow(row, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(row, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER,
                          LV_FLEX_ALIGN_CENTER);
    lv_obj_set_style_bg_opa(row, LV_OPA_TRANSP, 0);
    lv_obj_set_style_border_width(row, 0, 0);
    lv_obj_set_style_pad_all(row, 0, 0);
    lv_obj_set_style_pad_column(row, ui_dpx(12, sd), 0);
    ui_decorative(row);

    auto* dot = lv_obj_create(row);
    lv_obj_set_size(dot, ui_dpx(12, sd), ui_dpx(12, sd));
    lv_obj_set_style_radius(dot, ui_dpx(6, sd), 0);
    lv_obj_set_style_bg_color(dot, lv_color_hex(color), 0);
    lv_obj_set_style_border_width(dot, 0, 0);
    ui_decorative(dot);

    auto* nm = lv_label_create(row);
    lv_label_set_text(nm, name);
    lv_obj_set_style_text_color(nm, ui_palette().dim, 0);
    lv_obj_set_style_text_font(nm, ui_font(ui_tokens().f_body, sd), 0);
    ui_decorative(nm);

    auto* val = lv_label_create(row);
    lv_label_set_text(val, value);
    lv_obj_set_style_text_color(val, ui_palette().text, 0);
    lv_obj_set_style_text_font(val, ui_font(ui_tokens().f_body, sd), 0);
    lv_obj_set_flex_grow(val, 1);
    lv_obj_set_style_text_align(val, LV_TEXT_ALIGN_RIGHT, 0);
    ui_decorative(val);
}

/* A labelled progress bar row, used to fill the window under the gauges. */
static void add_bar_row(lv_obj_t* parent, const char* name, int percent,
                        uint32_t color) {
    const int sd = ui_dpi();
    auto* row = lv_obj_create(parent);
    lv_obj_set_width(row, LV_PCT(100));
    lv_obj_set_height(row, LV_SIZE_CONTENT);
    lv_obj_set_layout(row, LV_LAYOUT_FLEX);
    lv_obj_set_flex_flow(row, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(row, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER,
                          LV_FLEX_ALIGN_CENTER);
    lv_obj_set_style_bg_opa(row, LV_OPA_TRANSP, 0);
    lv_obj_set_style_border_width(row, 0, 0);
    lv_obj_set_style_pad_all(row, 0, 0);
    lv_obj_set_style_pad_column(row, ui_dpx(12, sd), 0);
    ui_decorative(row);

    auto* nm = lv_label_create(row);
    lv_label_set_text(nm, name);
    lv_obj_set_style_text_color(nm, ui_palette().dim, 0);
    lv_obj_set_style_text_font(nm, ui_font(ui_tokens().f_body, sd), 0);
    lv_obj_set_width(nm, ui_dpx(70, sd));
    ui_decorative(nm);

    auto* bar = lv_bar_create(row);
    lv_obj_set_flex_grow(bar, 1);
    lv_obj_set_height(bar, ui_dpx(10, sd));
    lv_obj_set_style_radius(bar, ui_dpx(5, sd), LV_PART_MAIN);
    lv_obj_set_style_radius(bar, ui_dpx(5, sd), LV_PART_INDICATOR);
    lv_obj_set_style_bg_color(bar, ui_palette().divider, LV_PART_MAIN);
    lv_obj_set_style_bg_color(bar, lv_color_hex(color), LV_PART_INDICATOR);
    lv_bar_set_value(bar, percent, LV_ANIM_OFF);

    auto* val = lv_label_create(row);
    lv_label_set_text_fmt(val, "%d%%", percent);
    lv_obj_set_style_text_color(val, ui_palette().text, 0);
    lv_obj_set_style_text_font(val, ui_font(ui_tokens().f_body, sd), 0);
    ui_decorative(val);
}

static void build_system_content(Window& win) {
    const int sd = ui_dpi();
    lv_obj_t* c = win.content();

    auto* hdr = lv_label_create(c);
    lv_label_set_text(hdr, "Live System Stats");
    lv_obj_set_style_text_color(hdr, ui_palette().text, 0);
    lv_obj_set_style_text_font(hdr, ui_font(ui_tokens().f_title, sd), 0);
    ui_decorative(hdr);

    /* Two animated arc gauges side by side. */
    auto* row = lv_obj_create(c);
    lv_obj_set_width(row, LV_PCT(100));
    lv_obj_set_height(row, LV_SIZE_CONTENT);
    lv_obj_set_layout(row, LV_LAYOUT_FLEX);
    lv_obj_set_flex_flow(row, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(row, LV_FLEX_ALIGN_SPACE_EVENLY, LV_FLEX_ALIGN_CENTER,
                          LV_FLEX_ALIGN_CENTER);
    lv_obj_set_style_bg_opa(row, LV_OPA_TRANSP, 0);
    lv_obj_set_style_border_width(row, 0, 0);
    lv_obj_set_style_pad_all(row, 0, 0);
    ui_decorative(row);

    auto make_gauge = [&](lv_color_t accent, const char* name, lv_obj_t** out_arc) {
        auto* col = lv_obj_create(row);
        /* Equal halves of the row, no theme padding: the arcs are large and any
         * extra width makes the two columns overlap. */
        lv_obj_set_flex_grow(col, 1);
        lv_obj_set_layout(col, LV_LAYOUT_FLEX);
        lv_obj_set_flex_flow(col, LV_FLEX_FLOW_COLUMN);
        lv_obj_set_flex_align(col, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER,
                              LV_FLEX_ALIGN_CENTER);
        lv_obj_set_style_bg_opa(col, LV_OPA_TRANSP, 0);
        lv_obj_set_style_border_width(col, 0, 0);
        lv_obj_set_style_pad_all(col, 0, 0);
        lv_obj_set_style_pad_row(col, ui_dpx(8, sd), 0);
        /* The arc's stroke is centred on its radius, so it extends slightly past
         * the widget bounds; never clip it. */
        lv_obj_add_flag(col, LV_OBJ_FLAG_OVERFLOW_VISIBLE);
        ui_decorative(col);

        auto* arc = lv_arc_create(col);
        lv_obj_set_size(arc, ui_dpx(132, sd), ui_dpx(132, sd));
        lv_arc_set_bg_angles(arc, 0, 360);
        lv_arc_set_rotation(arc, 270);
        lv_arc_set_range(arc, 0, 100);
        lv_obj_set_style_arc_color(arc, ui_palette().divider, LV_PART_MAIN);
        lv_obj_set_style_arc_width(arc, ui_dpx(12, sd), LV_PART_MAIN);
        lv_obj_set_style_arc_color(arc, accent, LV_PART_INDICATOR);
        lv_obj_set_style_arc_width(arc, ui_dpx(12, sd), LV_PART_INDICATOR);
        lv_obj_remove_style(arc, nullptr, LV_PART_KNOB);
        *out_arc = arc;

        auto* lbl = lv_label_create(col);
        lv_label_set_text(lbl, name);
        lv_obj_set_style_text_color(lbl, ui_palette().dim, 0);
        lv_obj_set_style_text_font(lbl, ui_font(ui_tokens().f_body, sd), 0);
        ui_decorative(lbl);
    };

    make_gauge(lv_color_hex(0x4FC3F7), "CPU", &g_show.cpu_arc);
    make_gauge(lv_color_hex(0x81C784), "MEM", &g_show.mem_arc);

    add_stat_row(c, "Uptime", "42 min", 0x4FC3F7);
    add_stat_row(c, "Threads", "128", 0x81C784);
    add_stat_row(c, "Load", "37 %", 0xFFD54F);
    add_stat_row(c, "FPS", "60", 0xAB47BC);
    add_stat_row(c, "Temperature", "34 C", 0xFF7043);
    add_stat_row(c, "Processes", "96", 0x26A69A);
    add_bar_row(c, "MEM", 62, 0x4FC3F7);
    add_bar_row(c, "CPU", 41, 0x81C784);
}

static void build_sensors_content(Window& win) {
    const int sd = ui_dpi();
    lv_obj_t* c = win.content();
    auto* hdr = lv_label_create(c);
    lv_label_set_text(hdr, "Sensor Dashboard");
    lv_obj_set_style_text_color(hdr, ui_palette().text, 0);
    lv_obj_set_style_text_font(hdr, ui_font(ui_tokens().f_title, sd), 0);
    ui_decorative(hdr);

    const char* names[] = {"Temperature", "Humidity", "Pressure", "Light"};
    uint32_t colors[] = {0xFF7043, 0x29B6F6, 0xFFD54F, 0xAB47BC};
    for (int i = 0; i < 4; i++) {
        auto* row = lv_obj_create(c);
        lv_obj_set_width(row, LV_PCT(100));
        lv_obj_set_height(row, LV_SIZE_CONTENT);
        lv_obj_set_layout(row, LV_LAYOUT_FLEX);
        lv_obj_set_flex_flow(row, LV_FLEX_FLOW_ROW);
        lv_obj_set_flex_align(row, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER,
                              LV_FLEX_ALIGN_CENTER);
        lv_obj_set_style_bg_opa(row, LV_OPA_TRANSP, 0);
        lv_obj_set_style_border_width(row, 0, 0);
        lv_obj_set_style_pad_all(row, 0, 0);
        lv_obj_set_style_pad_column(row, ui_dpx(12, sd), 0);
        ui_decorative(row);

        /* Grow to fill the row so the name never sits on top of the bar. */
        auto* bar = lv_bar_create(row);
        lv_obj_set_flex_grow(bar, 1);
        lv_obj_set_height(bar, ui_dpx(16, sd));
        lv_obj_set_style_radius(bar, ui_dpx(8, sd), LV_PART_MAIN);
        lv_obj_set_style_radius(bar, ui_dpx(8, sd), LV_PART_INDICATOR);
        lv_obj_set_style_bg_color(bar, ui_palette().divider, LV_PART_MAIN);
        lv_obj_set_style_bg_color(bar, lv_color_hex(colors[i]), LV_PART_INDICATOR);
        lv_bar_set_range(bar, 0, 100);
        lv_bar_set_value(bar, 40 + i * 12, LV_ANIM_OFF);

        auto* nm = lv_label_create(row);
        lv_label_set_text(nm, names[i]);
        lv_obj_set_style_text_color(nm, ui_palette().dim, 0);
        lv_obj_set_style_text_font(nm, ui_font(ui_tokens().f_body, sd), 0);
        ui_decorative(nm);
    }
}

static void build_controls_content(Window& win) {
    const int sd = ui_dpi();
    lv_obj_t* c = win.content();
    auto* hdr = lv_label_create(c);
    lv_label_set_text(hdr, "Quick Controls");
    lv_obj_set_style_text_color(hdr, ui_palette().text, 0);
    lv_obj_set_style_text_font(hdr, ui_font(ui_tokens().f_title, sd), 0);
    ui_decorative(hdr);

    const char* labels[] = {"Wi-Fi", "Bluetooth", "Location", "Notifications"};
    for (int i = 0; i < 4; i++) {
        auto* row = lv_obj_create(c);
        lv_obj_set_width(row, LV_PCT(100));
        lv_obj_set_height(row, LV_SIZE_CONTENT);
        lv_obj_set_layout(row, LV_LAYOUT_FLEX);
        lv_obj_set_flex_flow(row, LV_FLEX_FLOW_ROW);
        lv_obj_set_flex_align(row, LV_FLEX_ALIGN_SPACE_BETWEEN, LV_FLEX_ALIGN_CENTER,
                              LV_FLEX_ALIGN_CENTER);
        lv_obj_set_style_bg_opa(row, LV_OPA_TRANSP, 0);
        lv_obj_set_style_border_width(row, 0, 0);
        lv_obj_set_style_pad_all(row, 0, 0);
        lv_obj_set_style_pad_row(row, ui_dpx(14, sd), 0);
        ui_decorative(row);

        auto* nm = lv_label_create(row);
        lv_label_set_text(nm, labels[i]);
        lv_obj_set_style_text_color(nm, ui_palette().dim, 0);
        lv_obj_set_style_text_font(nm, ui_font(ui_tokens().f_body, sd), 0);
        ui_decorative(nm);

        auto* sw = lv_switch_create(row);
        lv_obj_set_style_bg_color(sw, ui_palette().primary,
                                  LV_PART_INDICATOR | LV_STATE_CHECKED);
    }
}

static void build_audio_content(Window& win) {
    const int sd = ui_dpi();
    lv_obj_t* c = win.content();
    auto* lbl = lv_label_create(c);
    lv_obj_set_width(lbl, LV_PCT(100));
    lv_obj_set_style_text_align(lbl, LV_TEXT_ALIGN_CENTER, 0);
    lv_label_set_text(lbl, "Audio visualiser");
    lv_obj_set_style_text_color(lbl, ui_palette().primary, 0);
    lv_obj_set_style_text_font(lbl, ui_font(ui_tokens().f_title, sd), 0);
    ui_decorative(lbl);
}

/* -------------------------------------------------------------------------- */
/* Per-frame tick                                                             */
/* -------------------------------------------------------------------------- */

static void showcase_tick(float dt) {
    if (!g_show.screen) return;
    g_show.tick_count++;
    g_show.time += dt;
    float t = g_show.time;

    /* Boot sequence: logo springs up, then fades to reveal tiles. */
    if (g_show.booting) {
        g_show.boot_y.tick(dt);
        g_show.boot_opa.tick(dt);
        if (g_show.boot_logo) {
            lv_obj_set_y(g_show.boot_logo, (int)std::lround(g_show.boot_y.value()));
            lv_obj_set_style_opa(g_show.boot_logo, (int)std::lround(g_show.boot_opa.value()), 0);
        }
        if (g_show.boot_opa.settled() && g_show.boot_y.settled() &&
            std::abs(g_show.boot_y.target() - 120.0f) < 0.001f) {
            g_show.booting = false;
        }
    }

    /* Live system gauges. */
    g_show.cpu.tick(dt);
    g_show.mem.tick(dt);
    g_show.cpu.set_target(35.0f + 35.0f * std::sin(t * 0.7f));
    g_show.mem.set_target(50.0f + 30.0f * std::sin(t * 0.4f + 2.0f));
    if (g_show.cpu_arc) lv_arc_set_value(g_show.cpu_arc, (int)std::lround(g_show.cpu.value()));
    if (g_show.mem_arc) lv_arc_set_value(g_show.mem_arc, (int)std::lround(g_show.mem.value()));

    /* Tap-bounce on tiles. */
    for (auto& tile : g_show.tiles) {
        if (!tile.icon) continue;
        tile.scale.tick(dt);
        int32_t s = (int32_t)std::lround(tile.scale.value());
        lv_obj_set_style_transform_scale(tile.icon, s, 0);
    }

    /* Drive open windows. */
    for (auto& tile : g_show.tiles) {
        if (tile.window) tile.window->tick(dt);
    }
}

/* -------------------------------------------------------------------------- */
/* Tile                                                                       */
/* -------------------------------------------------------------------------- */

static void on_tile_event(lv_event_t* e) {
    if (lv_event_get_code(e) != LV_EVENT_CLICKED) return;
    auto* tile = static_cast<Tile*>(lv_event_get_user_data(e));
    if (!tile) return;
    if (!tile->window) {
        auto w = std::make_unique<Window>();
        w->create(g_show.screen, tile->win_cfg);
        if (tile->content_fn) tile->content_fn(*w);
        tile->window = std::move(w);
    }
    if (tile->window->state() == Window::Closed ||
        tile->window->state() == Window::Closing) {
        tile->window->open();
    }
}

static void make_tile(lv_obj_t* parent, const char* symbol, const char* name,
                      uint32_t accent, const Window::Config& win_cfg,
                      void (*content_fn)(Window&)) {
    /* Tile was already pushed by the caller; operate on the last element.
     * The vector is reserved by the caller so this pointer stays valid. */
    Tile* tp = &g_show.tiles.back();

    tp->container = ui_make_panel(parent);
    /* Two tiles per row in the wrapping flex body. */
    lv_obj_set_width(tp->container, LV_PCT(48));
    lv_obj_clear_flag(tp->container, LV_OBJ_FLAG_SCROLLABLE);

    lv_obj_set_style_bg_color(tp->container, ui_palette().card, 0);
    lv_obj_set_style_radius(tp->container, ui_dpx(18, ui_dpi()), 0);

    lv_obj_add_event_cb(tp->container, on_tile_event, LV_EVENT_PRESSED, tp);
    lv_obj_add_event_cb(tp->container, on_tile_event, LV_EVENT_CLICKED, tp);

    /* Accent + labels using working helpers. */
    lv_obj_set_layout(tp->container, LV_LAYOUT_FLEX);
    lv_obj_set_flex_flow(tp->container, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(tp->container, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER,
                          LV_FLEX_ALIGN_CENTER);
    const int SD = ui_dpi();
    lv_obj_set_style_pad_all(tp->container, ui_dpx(16, SD), 0);
    lv_obj_set_style_pad_column(tp->container, ui_dpx(16, SD), 0);

    lv_obj_t* accent_bar = lv_obj_create(tp->container);
    lv_obj_set_size(accent_bar, ui_dpx(6, SD), ui_dpx(40, SD));
    lv_obj_set_style_radius(accent_bar, ui_dpx(3, SD), 0);
    lv_obj_set_style_bg_color(accent_bar, lv_color_hex(accent), 0);
    lv_obj_set_style_border_width(accent_bar, 0, 0);
    ui_decorative(accent_bar);

    lv_obj_t* col = ui_make_column(tp->container, UI_SPACE_XS);
    lv_obj_set_flex_grow(col, 1);
    /* Centre the icon + name in the tile so the wide card reads as balanced. */
    lv_obj_set_flex_align(col, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER,
                          LV_FLEX_ALIGN_CENTER);
    tp->icon = ui_make_label(col, symbol, ui_tokens().f_subtitle, lv_color_hex(accent));
    ui_make_label(col, name, ui_tokens().f_body, ui_palette().dim);

    /* Store window config; window created on tap (avoids z-order blocking). */
    tp->win_cfg = win_cfg;
    tp->content_fn = content_fn;
}

/* -------------------------------------------------------------------------- */
/* Public entry                                                                */
/* -------------------------------------------------------------------------- */

void build_showcase_screen_impl(int /*dpi*/) {
    g_show = {};
    g_show.tiles.reserve(4);

    /* Inline the screen setup (replicates make_screen + make_header + make_body
     * from demo_ui.cpp, which are static and not linkable here). */
    const int SD = ui_dpi();
    lv_obj_t* screen = lv_screen_active();
    lv_obj_clean(screen);
    g_show.screen = screen;
    /* Replicate make_screen(): flex column, full-size, themed bg. */
    lv_obj_set_width(screen, LV_PCT(100));
    lv_obj_set_height(screen, LV_PCT(100));
    lv_obj_set_layout(screen, LV_LAYOUT_FLEX);
    lv_obj_set_flex_flow(screen, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_style_pad_all(screen, 0, 0);
    lv_obj_set_style_bg_color(screen, ui_palette().bg, 0);
    lv_obj_set_style_bg_opa(screen, LV_OPA_COVER, 0);

    /* Same status bar as the dashboard, so every screen shares the look. */
    ui_make_status_bar(screen);

    /* ---- App bar --------------------------------------------------------- */
    ui_make_app_bar(screen, "Showcase", true);

    auto* body = lv_obj_create(screen);
    lv_obj_set_width(body, LV_PCT(100));
    lv_obj_set_flex_grow(body, 1);
    lv_obj_set_style_bg_opa(body, LV_OPA_TRANSP, 0);
    lv_obj_set_style_border_width(body, 0, 0);
    lv_obj_set_style_pad_all(body, ui_dpx(16, SD), 0);
    /* Flex row-wrap so the tiles form a grid; without a layout every child is
     * placed at (0,0) and they overlap (only the last one is visible). */
    lv_obj_set_layout(body, LV_LAYOUT_FLEX);
    lv_obj_set_flex_flow(body, LV_FLEX_FLOW_ROW_WRAP);
    lv_obj_set_flex_align(body, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_START,
                          LV_FLEX_ALIGN_START);
    lv_obj_set_style_pad_row(body, ui_dpx(12, SD), 0);
    lv_obj_set_style_pad_column(body, ui_dpx(12, SD), 0);
    lv_obj_set_scrollbar_mode(body, LV_SCROLLBAR_MODE_AUTO);
    lv_obj_clear_flag(body, LV_OBJ_FLAG_CLICKABLE);

    /* ---- Launcher grid: 2x2 tiles, color-coded windows ----------------- */
    struct TileDef { const char* icon; const char* name; void(*fn)(Window&); uint32_t accent; };
    TileDef defs[] = {
        {LV_SYMBOL_AUDIO,   "Audio",   build_audio_content,   0x1976D2},
        {LV_SYMBOL_LIST,    "System",  build_system_content,  0x388E3C},
        {LV_SYMBOL_GPS,     "Sensors", build_sensors_content, 0xDC004E},
        {LV_SYMBOL_SETTINGS,"Config",  build_controls_content,0xF57C00},
    };

    for (int i = 0; i < 4; i++) {
        Window::Config cfg;
        cfg.kf_closed    = {LV_HOR_RES / 2 - 90, LV_VER_RES / 2 - 30 + i * 40, 180, 60, 0};
        /* Open to a centred rectangle that fits the actual screen (the old
         * hardcoded 1020x700 was sized for a desktop build). */
        const int sb = ui_dpx(52, SD);
        const int avail_w = LV_HOR_RES - ui_dpx(24, SD);
        const int avail_h = LV_VER_RES - sb - ui_dpx(24, SD);
        const int win_w = avail_w * 92 / 100;
        const int win_h = avail_h * 85 / 100;
        cfg.kf_opened    = {(LV_HOR_RES - win_w) / 2,
                            sb + (avail_h - win_h) / 2,
                            win_w, win_h, 255};
        cfg.title        = defs[i].name;
        cfg.bg_color     = rgb_hex(ui_palette().card);
        cfg.border_color = rgb_hex(ui_palette().divider);
        cfg.title_color  = rgb_hex(ui_palette().text);
        cfg.radius       = ui_dpx(28, SD);
        g_show.tiles.push_back(Tile());
        make_tile(body, defs[i].icon, defs[i].name, defs[i].accent, cfg, defs[i].fn);
    }

    Engine::instance().set_frame_callback(showcase_tick);

    ALOGI("Showcase built: %d tiles", (int)g_show.tiles.size());
}

/* Called when the showcase screen is left so the frame callback no longer
 * dereferences objects freed by lv_obj_clean(). */
void ui_reset_showcase() {
    Engine::instance().clear_frame_callback();
    g_show = {};
}

#if defined(__clang__) || defined(__GNUC__)
#pragma GCC diagnostic pop
#endif

} /* namespace lvgl_android */
