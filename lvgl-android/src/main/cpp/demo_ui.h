/*
 * Copyright (c) 2026 LVGL Android Framework Contributors
 * SPDX-License-Identifier: MIT
 *
 * This file is part of the LVGL Android Framework.
 * See LICENSE in the project root for full license information.
 *
 * Single source of truth for all demo/sample screens rendered by LVGL.
 * Declarations live here; implementations are in demo_ui.cpp.
 *
 * @breadcrumb BC-006
 * @breadcrumb BC-007
 * @breadcrumb BC-013
 * @breadcrumb BC-016
 * @breadcrumb BC-017
 * @breadcrumb BC-018
 * @invariant to_lv_color() and the flush R/B swap agree on channel order
 * @guard-rule rule=demo-ui-single-source test=HardRuleGuardTest
 */
#pragma once

#include "lvgl/lvgl.h"

namespace lvgl_android {

/*
 * IMPORTANT: LVGL color format is {blue, green, red, alpha} in memory (BGRA).
 * Android ANativeWindow RGBA_8888 format expects {red, green, blue, alpha}.
 * The R/B swap is performed in the flush callback before copying to the window.
 */

/* LVGL does not define LV_THEME_DEFAULT_LIGHT in 9.6.0 (see ai-context.md). */
#ifndef LV_THEME_DEFAULT_LIGHT
#define LV_THEME_DEFAULT_LIGHT 0
#endif

/* Screen identifiers — stable API between Kotlin and native. */
enum ScreenId : int {
    SCREEN_MENU           = 0,
    SCREEN_WIDGET         = 1,
    SCREEN_LAYOUT         = 2,
    SCREEN_INTERACTIVE    = 3,
    SCREEN_CAMERA         = 4,
    SCREEN_GAME           = 5,
    SCREEN_LAUNCHER       = 6,
    /* Animated showcase demo — spring-physics launcher, NumberFlow, animated
     * controls. Lives in demo_showcase.cpp (separate from the static samples). */
    SCREEN_SHOWCASE       = 7,
    SCREEN_COUNT          = 8,
};

/* Current theme state, cached so it is re-applied on screen switch / config change. */
struct UiTheme {
    bool dark_mode = false;
    int  primary   = 0x1976D2;
    int  secondary = 0xDC004E;
    bool applied   = false;   /* true once a real theme has been applied */
};

/* ---- Density helpers ------------------------------------------------------
 * Density-independent pixel: scales a baseline (px @ 160dpi) size by dpi/160.
 * `ui_font` maps a dp size to the nearest *enabled* Montserrat font. LVGL fonts
 * are fixed pixel sizes, so text MUST be picked through this helper — using
 * lv_font_montserrat_14 directly renders ~5sp text on a 440dpi phone. */
int  ui_dpx(int base, int dpi);
const lv_font_t* ui_font(int dp, int dpi);
int         ui_dpi();   /* active display dpi */

lv_color_t to_lv_color(int rgb);              /* 0xRRGGBB -> lv_color_t */
const UiTheme& ui_current_theme();             /* active theme (dark/light + colors) */

/* Reusable UI-kit helpers (implemented in demo_ui.cpp). */
lv_obj_t* ui_make_panel(lv_obj_t* parent);
lv_obj_t* ui_make_column(lv_obj_t* parent, int gap_dp);
lv_obj_t* ui_make_label(lv_obj_t* parent, const char* text, int dp, lv_color_t color);
void        ui_decorative(lv_obj_t* o);

/* ---- Screen builders ----------------------------------------------------- */

void build_menu_screen(int dpi);
void build_widget_screen(int dpi);
void build_layout_screen(int dpi);
void build_interactive_screen(int dpi);
void build_camera_screen(int dpi);
void build_game_screen(int dpi);
void build_launcher_screen(int dpi);
void build_showcase_screen(int dpi);

/* Release the showcase's frame callback and animated state. Must be called
 * when the showcase screen is left (see BC-017). */
void ui_reset_showcase();

/* Returns the status label for the current screen, or nullptr if none. */
lv_obj_t* ui_current_status_label();

/* ---- Navigation hooks -------------------------------------------------------
 * Installed once by the port so LVGL event callbacks (menu cards, back button)
 * can request screen switches / back-presses handled by Kotlin. */
void ui_set_nav_hooks(void (*navigate_to)(int screen_id),
                      void (*navigate_back)(void));

/* ---- Entry point ---------------------------------------------------------
 * Clean the active screen and build the requested one. Must run on the LVGL
 * thread (see lvgl_port.cpp). `dpi` is the real display density. */
void launch_screen(int screen_id, int dpi, const UiTheme& theme);

} /* namespace lvgl_android */
