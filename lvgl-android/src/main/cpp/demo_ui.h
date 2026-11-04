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
    /* Home is the M5Tab5-style device dashboard (schematic + control panels);
     * it replaces the former list menu as the entry screen.
     * @breadcrumb BC-020 */
    SCREEN_HOME           = 0,
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

/* Material 3 tonal role indexes — must match MaterialPalette on the Kotlin side. */
enum MaterialRole {
    MATERIAL_BACKGROUND = 0,
    MATERIAL_ON_BACKGROUND = 1,
    MATERIAL_SURFACE = 2,
    MATERIAL_ON_SURFACE = 3,
    MATERIAL_SURFACE_VARIANT = 4,
    MATERIAL_ON_SURFACE_VARIANT = 5,
    MATERIAL_PRIMARY = 6,
    MATERIAL_ON_PRIMARY = 7,
    MATERIAL_PRIMARY_CONTAINER = 8,
    MATERIAL_ON_PRIMARY_CONTAINER = 9,
    MATERIAL_SECONDARY = 10,
    MATERIAL_ON_SECONDARY = 11,
    MATERIAL_SECONDARY_CONTAINER = 12,
    MATERIAL_ON_SECONDARY_CONTAINER = 13,
    MATERIAL_OUTLINE = 14,
    MATERIAL_OUTLINE_VARIANT = 15,
    MATERIAL_ERROR = 16,
    MATERIAL_ON_ERROR = 17,
    MATERIAL_ERROR_CONTAINER = 18,
    MATERIAL_ON_ERROR_CONTAINER = 19,
    MATERIAL_ROLE_COUNT = 20,
};

/* Current theme state, cached so it is re-applied on screen switch / config change.
 * The `roles` array is the Material 3 tonal palette; its index order is shared
 * with MaterialPalette on the Kotlin side (BC-029). */
struct UiTheme {
    bool dark_mode = false;
    int  primary   = 0x6750A4;   /* == roles[MaterialPalette.PRIMARY] */
    int  secondary = 0x625B71;   /* == roles[MaterialPalette.SECONDARY] */
    bool applied   = false;      /* true once a real theme has been applied */

    int roles[20] = {
        (int)0xFFFFFBFEu, (int)0xFF1C1B1Fu,   // background / on background
        (int)0xFFFFFBFEu, (int)0xFF1C1B1Fu,   // surface / on surface
        (int)0xFFE7E0ECu, (int)0xFF49454Fu,   // surface variant / on surface variant
        (int)0xFF6750A4u, (int)0xFFFFFFFFu,   // primary / on primary
        (int)0xFFEADDFFu, (int)0xFF21005Du,   // primary container / on primary container
        (int)0xFF625B71u, (int)0xFFFFFFFFu,   // secondary / on secondary
        (int)0xFFE8DEF8u, (int)0xFF1D192Bu,   // secondary container / on secondary container
        (int)0xFF79747Eu, (int)0xFFCAC4D0u,   // outline / outline variant
        (int)0xFFB3261Eu, (int)0xFFFFFFFFu,   // error / on error
        (int)0xFFF9DEDCu, (int)0xFF410E0Bu,   // error container / on error container
    };
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

/* Integer callback used by the stepper and the bottom navigation bar. */
typedef void (*UiIntCallback)(int value, void* user_data);

/* Resolved palette for the active theme. Lets secondary UI files (the
 * showcase) style themselves like the dashboard without duplicating colors. */
struct UiPalette {
    lv_color_t bg;
    lv_color_t card;
    lv_color_t text;
    lv_color_t dim;
    lv_color_t primary;
    lv_color_t on_primary;
    lv_color_t divider;
    lv_color_t chip;          /* schematic chip fill */
    lv_color_t chip_text;
    lv_color_t chip_border;
    lv_color_t accent2;       /* green */
    lv_color_t danger;        /* red */
};
const UiPalette& ui_palette();

/* ---- Device info (queried from Android) ------------------------------------
 * The dashboard schematic reflects the device it is running on. The Kotlin
 * side collects real values (SoC, RAM, storage, display, ...) and pushes them
 * here; the diagram renders them. Until then, or when a value is empty, the
 * diagram falls back to generic labels. */
struct UiDeviceInfo {
    const char* manufacturer;
    const char* model;
    const char* soc;
    const char* memory;
    const char* storage;
    const char* display;
    const char* android_version;
    const char* cameras;
    const char* sensors;
};
void ui_set_device_info(const UiDeviceInfo& info);
const UiDeviceInfo& ui_device_info();

/* ---- Design tokens ----------------------------------------------------------
 * The single source of truth for spacing, corner radii and type sizes. Every
 * default style and layout primitive reads from here, so a whole app can be
 * re-tuned (or re-themed) from one place instead of hunting magic numbers.
 * Spacing/radius are stored in *pixels* for the active dpi; fonts stay in dp
 * because LVGL fonts are fixed pixel sizes and ui_font() picks the nearest. */
struct UiTokens {
    int xs, sm, md, lg, xl, xxl;   /* spacing scale (px) */
    int r_sm, r_md, r_lg, r_xl;    /* corner radius scale (px) */
    int f_caption, f_body, f_subtitle, f_title, f_headline;  /* type scale (dp) */
};
const UiTokens& ui_tokens();

/* ---- Screen scaffold --------------------------------------------------------
 * Every screen is built on the same scaffold: status bar + optional header +
 * a scrollable body. Using it means a new screen is correctly inset, scrolled
 * and themed by construction rather than by hand. */
typedef struct {
    lv_obj_t* root;    /* fills the screen (transparent) */
    lv_obj_t* header;  /* header bar, or nullptr when title == nullptr */
    lv_obj_t* body;    /* scrollable content area — add children here */
} UiScaffold;
UiScaffold ui_make_scaffold(lv_obj_t* parent, const char* title, bool show_back);

/* Spacing steps from the token scale — primitives take these so callers never
 * hard-code a gap. */
enum UiSpace { UI_SPACE_XS, UI_SPACE_SM, UI_SPACE_MD, UI_SPACE_LG, UI_SPACE_XL, UI_SPACE_XXL };
int ui_space(UiSpace s);   /* px for the active dpi */

/* ---- Overlap-proof layout primitives ----------------------------------------
 * Every container sets a flex layout, gap and alignment by construction, so
 * children can never pile up at (0,0). Prefer these over raw lv_obj_create()
 * when building UI. */
lv_obj_t* ui_make_row(lv_obj_t* parent, UiSpace gap);
lv_obj_t* ui_make_column(lv_obj_t* parent, UiSpace gap);
lv_obj_t* ui_make_grid(lv_obj_t* parent, int cols, UiSpace gap,
                       int cell_w_dp, int cell_h_dp);
lv_obj_t* ui_make_card(lv_obj_t* parent);
lv_obj_t* ui_make_section(lv_obj_t* parent, const char* caption);
lv_obj_t* ui_make_list_row(lv_obj_t* parent, const char* icon,
                           const char* label, const char* value);
lv_obj_t* ui_make_spacer(lv_obj_t* parent, UiSpace size);

/* ---- Material 3 component kit ---------------------------------------------
 * Native-looking Material components built from the tonal palette. Every
 * component reads `ui_palette()`/`ui_tokens()`, so a whole app re-themes by
 * construction instead of per-widget colour plumbing. Prefer these over
 * hand-rolled widgets so an app looks and behaves like a native Material app
 * while staying pure LVGL underneath. @breadcrumb BC-030 */

/* Buttons. M3 filled / tonal / outlined / text styles; `danger` switches the
 * filled style to the error role (used for destructive actions). */
typedef enum {
    UI_BUTTON_FILLED = 0,
    UI_BUTTON_TONAL,
    UI_BUTTON_OUTLINED,
    UI_BUTTON_TEXT,
} UiButtonStyle;
lv_obj_t* ui_make_button(lv_obj_t* parent, const char* text, UiButtonStyle style,
                         bool danger);
lv_obj_t* ui_make_icon_button(lv_obj_t* parent, const char* icon);

/* Assist/filter chip. `selected` renders the chip in the secondary container
 * with a leading check. */
lv_obj_t* ui_make_chip(lv_obj_t* parent, const char* label, bool selected);

/* Floating action button. Pass a label for an extended FAB, or nullptr for the
 * standard square FAB. The caller positions it (it is FLOATING). */
lv_obj_t* ui_make_fab(lv_obj_t* parent, const char* icon, const char* label);

/* Cards: filled (surface variant), elevated (shadow) or outlined. */
typedef enum {
    UI_CARD_FILLED = 0,
    UI_CARD_ELEVATED,
    UI_CARD_OUTLINED,
} UiCardStyle;
lv_obj_t* ui_make_m3_card(lv_obj_t* parent, UiCardStyle style);

/* Outlined text field: a floating caption plus a bordered value box. Returns
 * the outer column, so it drops into the same layouts as any other row. */
lv_obj_t* ui_make_text_field(lv_obj_t* parent, const char* label, const char* value);

/* 1 dp divider in the outline-variant colour. */
lv_obj_t* ui_make_divider(lv_obj_t* parent);

/* Bottom navigation bar (M3). `on_select` may be null; the visual selection
 * updates either way. */
typedef struct {
    const char* icon;
    const char* label;
} UiNavItem;
lv_obj_t* ui_make_bottom_nav(lv_obj_t* parent, const UiNavItem* items, int count,
                             int selected, UiIntCallback on_select, void* user_data);

/* Dialog + snackbar overlays. Both are FLOATING full-screen layers: delete the
 * returned handle to close them. `on_action` receives the action index (0 for
 * a scrim tap on a dialog) and may be null. */
typedef void (*UiActionCallback)(int index, void* user_data);
lv_obj_t* ui_make_dialog(lv_obj_t* parent, const char* title, const char* message,
                         const char* const* actions, int action_count,
                         UiActionCallback on_action, void* user_data);
lv_obj_t* ui_make_snackbar(lv_obj_t* parent, const char* message, const char* action,
                           UiActionCallback on_action, void* user_data);

/* M3 top app bar (56 dp). Returns the bar; the title grows to fill it. */
lv_obj_t* ui_make_app_bar(lv_obj_t* parent, const char* title, bool show_back);

/* ---- Text auto-fit ----------------------------------------------------------
 * Pick the largest enabled font that makes `text` fit in `max_w` pixels, and
 * set it on the label (falling back to dot truncation). Prevents clipped or
 * overflowing text in chips, buttons and rows. Returns the chosen dp size. */
int  ui_fit_label(lv_obj_t* label, int max_w, int max_dp);
lv_obj_t* ui_fit_text(lv_obj_t* parent, const char* text, int max_w,
                      int max_dp, lv_color_t color);

/* ---- Shared widget helpers ------------------------------------------------
 * Exposed so app-defined screens (lvgl_ui.cpp) use the exact same widgets and
 * styling as the built-in screens — one source of truth for each component. */
lv_obj_t* ui_make_slider(lv_obj_t* parent, int value);
lv_obj_t* ui_make_switch(lv_obj_t* parent, bool on);
lv_obj_t* ui_make_checkbox(lv_obj_t* parent, const char* text);
lv_obj_t* ui_make_progress(lv_obj_t* parent, int value);
lv_obj_t* ui_make_arc(lv_obj_t* parent, int value);

/* ---- App-defined screens (Kotlin UI builder) -----------------------------
 * An app declares a screen tree in Kotlin (com.lvgl.android.ui.LvglUi), which
 * is serialised to JSON and pushed through ui_register_app_screen. Building is
 * deferred to the LVGL thread, where ui_build_app_screen renders it with the
 * shared UI kit. App screen ids start at APP_SCREEN_BASE to stay clear of the
 * built-in 0..7 range. @breadcrumb BC-033 */
constexpr int APP_SCREEN_BASE = 1000;

/* Clean + theme the active screen and return it (used by app-defined screens). */
lv_obj_t* ui_begin_screen(int dpi);

void ui_register_app_screen(int screen_id, const char* title, bool show_back,
                            const char* json);
void ui_unregister_app_screen(int screen_id);
bool ui_build_app_screen(int screen_id, int dpi);
void ui_update_node(int screen_id, const char* node_id, const char* json);

/* ---- Launcher app list ----------------------------------------------------
 * The Kotlin side collects the installed apps and pushes label/package pairs;
 * the launcher screen renders them as a tappable grid. A tap is reported
 * through ui_emit_launcher_event (implemented in lvgl_port.cpp). */
void ui_set_launcher_apps(const char* const* labels, const char* const* packages,
                          int count);
void ui_emit_launcher_event(const char* package_name);

/* Called by the native node builder when an app-defined node is tapped or
 * changes value; implemented in lvgl_port.cpp where the JNI bridge lives. */
void ui_emit_event(int screen_id, const char* node_id, const char* type, int value);

/* ---- Layout lint ------------------------------------------------------------
 * Walks a built UI tree and reports overlaps, children outside their parent
 * and text that overflows its label. Used by the demo screens (debug builds
 * log a warning) and by the CI harness, which pulls the JSON report. */
struct UiLayoutIssue {
    int         type;   /* 0 = overlap, 1 = out of bounds, 2 = text overflow */
    int         a;      /* child index within the walk */
    int         b;      /* second child for overlaps */
    int         x1, y1, x2, y2;   /* rect of a */
    int         bx1, by1, bx2, by2; /* rect of b (overlaps) */
    int         px1, py1, px2, py2; /* rect of the parent */
    const char* what;   /* human-readable description */
    const char* parent_class;   /* LVGL class name of the parent */
    const char* child_class;    /* LVGL class name of the offending child */
};
int  ui_layout_check(lv_obj_t* root, UiLayoutIssue* out, int max_issues);
void ui_layout_report(lv_obj_t* root);   /* logcat warnings (debug builds) */
void ui_layout_report_json(lv_obj_t* root, char* buf, size_t len);
void ui_layout_lint_set_enabled(bool enabled);
bool ui_layout_lint_enabled();

/* Reusable UI-kit helpers (implemented in demo_ui.cpp). */
lv_obj_t* ui_make_panel(lv_obj_t* parent);
lv_obj_t* ui_make_label(lv_obj_t* parent, const char* text, int dp, lv_color_t color);
void        ui_decorative(lv_obj_t* o);

/* ---- M5Tab5-style device dashboard kit ------------------------------------
 * The home screen is a full-screen Android system schematic with floating
 * white control panels over it (see BC-020, BC-021, BC-022). These helpers
 * are shared by the dashboard and the restyled sample screens. */

/* Top bar: Wi-Fi glyph, live clock/date and a battery readout. */
lv_obj_t* ui_make_status_bar(lv_obj_t* parent);

/* Rounded dashboard card with a small uppercase caption. */
lv_obj_t* ui_make_dashboard_panel(lv_obj_t* parent, const char* caption);

/* System schematic: SoC + peripheral chips joined by bus lines. The chips
 * depict a generic Android phone (display, camera, modem, PMIC, ...), not an
 * embedded MCU board. `width_px`/`height_px` are the diagram's pixel size. */
lv_obj_t* ui_make_diagram(lv_obj_t* parent, int width_px, int height_px);

/* A "- value +" stepper. `on_change` fires with the clamped value whenever
 * either button is tapped; the label updates itself. `step` is the increment. */
lv_obj_t* ui_make_stepper(lv_obj_t* parent, int value, int min, int max, int step,
                          UiIntCallback on_change, void* user_data);

/* ---- Screen builders ----------------------------------------------------- */

void build_home_screen(int dpi);
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

/* ---- System-control hooks ---------------------------------------------------
 * The dashboard's backlight / volume panels call these to drive real Android
 * APIs through the ScreenNavigator bridge (see BC-023). Installed once by the
 * port; both may be null (the panels then stay visual). */
void ui_set_system_hooks(void (*set_brightness_percent)(int percent),
                         void (*set_volume_percent)(int percent));

/* Theme hook: the dashboard FAB toggles dark mode through Kotlin. May be null
 * (the FAB then does nothing). */
void ui_set_theme_hook(void (*toggle_dark_mode)(void));

/* Push the Android battery level (0..100) into the status bar. Safe to call
 * before the dashboard is built; the value is cached and re-applied on build. */
void ui_set_battery_level(int level, bool charging);

/* ---- Entry point ---------------------------------------------------------
 * Clean the active screen and build the requested one. Must run on the LVGL
 * thread (see lvgl_port.cpp). `dpi` is the real display density. */
void launch_screen(int screen_id, int dpi, const UiTheme& theme);

} /* namespace lvgl_android */
