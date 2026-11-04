/*
 * Copyright (c) 2026 LVGL Android Framework Contributors
 * SPDX-License-Identifier: MIT
 *
 * This file is part of the LVGL Android Framework.
 * See LICENSE in the project root for full license information.
 *
 * App-defined screens: a Kotlin DSL (com.lvgl.android.ui.LvglUi) serialises a
 * node tree to JSON and the native UI kit renders it here, reusing the exact
 * same widgets and palette as the built-in screens. Interactions are reported
 * back through ui_emit_event() (implemented in lvgl_port.cpp, which owns the
 * JNI bridge).
 *
 * @breadcrumb BC-033
 * @do-not Do not build app screens from the UI thread; ui_build_app_screen runs
 *         on the LVGL thread via launch_screen().
 */
#include "demo_ui.h"

#include <android/log.h>
#include <cstdlib>
#include <cstring>
#include <map>
#include <string>
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
/* Registry                                                                   */
/* -------------------------------------------------------------------------- */

struct AppScreen {
    std::string title;
    bool        show_back = true;
    std::string json;
};

static std::map<int, AppScreen> g_appScreens;
static int g_currentAppScreen = -1;

/* Node id -> LVGL object, so the event callback can recover the Kotlin id. */
static std::map<lv_obj_t*, std::string> g_nodeIds;
/* Reverse map for O(1) updates from Kotlin. */
static std::map<std::string, lv_obj_t*> g_idToNode;

void ui_register_app_screen(int screen_id, const char* title, bool show_back,
                            const char* json) {
    AppScreen s;
    s.title     = title ? title : "";
    s.show_back = show_back;
    s.json      = json ? json : "";
    g_appScreens[screen_id] = std::move(s);
}

void ui_unregister_app_screen(int screen_id) {
    g_appScreens.erase(screen_id);
}

/* -------------------------------------------------------------------------- */
/* Minimal JSON reader                                                        */
/* -------------------------------------------------------------------------- */

struct JsonValue {
    enum Type { Null, Bool, Number, String, Array, Object };

    Type type = Null;
    bool b = false;
    long num = 0;
    std::string str;
    std::vector<JsonValue> arr;
    std::map<std::string, JsonValue> obj;

    const JsonValue& get(const char* key) const {
        static const JsonValue nullValue;
        auto it = obj.find(key);
        return it == obj.end() ? nullValue : it->second;
    }
    long asInt(long def = 0) const { return type == Number ? num : def; }
    bool asBool(bool def = false) const { return type == Bool ? b : def; }
    std::string asString(const char* def = "") const {
        return type == String ? str : def;
    }
};

namespace {

struct JsonParser {
    const std::string& s;
    size_t i = 0;

    explicit JsonParser(const std::string& text) : s(text) {}

    void skipWs() {
        while (i < s.size()) {
            char c = s[i];
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
            else break;
        }
    }

    char peek() {
        skipWs();
        return i < s.size() ? s[i] : '\0';
    }

    char next() {
        skipWs();
        return i < s.size() ? s[i++] : '\0';
    }

    void expect(char c) {
        if (next() != c) {
            /* Tolerate malformed input: the builder falls back to defaults. */
        }
    }

    std::string parseString() {
        std::string out;
        if (next() != '"') return out;
        while (i < s.size()) {
            char c = s[i++];
            if (c == '"') break;
            if (c == '\\' && i < s.size()) {
                char e = s[i++];
                switch (e) {
                    case 'n': out.push_back('\n'); break;
                    case 't': out.push_back('\t'); break;
                    case 'r': out.push_back('\r'); break;
                    case 'b': out.push_back('\b'); break;
                    case 'f': out.push_back('\f'); break;
                    case 'u': {
                        if (i + 4 <= s.size()) {
                            long cp = strtol(s.substr(i, 4).c_str(), nullptr, 16);
                            i += 4;
                            if (cp < 0x80) {
                                out.push_back(static_cast<char>(cp));
                            } else if (cp < 0x800) {
                                out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
                                out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
                            } else {
                                out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
                                out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
                                out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
                            }
                        }
                        break;
                    }
                    default: out.push_back(e); break;
                }
            } else {
                out.push_back(c);
            }
        }
        return out;
    }

    JsonValue parse() {
        JsonValue v;
        char c = peek();
        if (c == '{') {
            next();
            v.type = JsonValue::Object;
            while (peek() != '}' && i < s.size()) {
                std::string key = parseString();
                expect(':');
                v.obj[key] = parse();
                if (peek() == ',') next();
            }
            expect('}');
        } else if (c == '[') {
            next();
            v.type = JsonValue::Array;
            while (peek() != ']' && i < s.size()) {
                v.arr.push_back(parse());
                if (peek() == ',') next();
            }
            expect(']');
        } else if (c == '"') {
            v.type = JsonValue::String;
            v.str = parseString();
        } else if (c == 't' || c == 'f') {
            v.type = JsonValue::Bool;
            if (i + 4 <= s.size() && s.compare(i, 4, "true") == 0) { i += 4; v.b = true; }
            else if (i + 5 <= s.size() && s.compare(i, 5, "false") == 0) { i += 5; v.b = false; }
        } else {
            v.type = JsonValue::Number;
            size_t start = i;
            if (i < s.size() && (s[i] == '-' || s[i] == '+')) i++;
            while (i < s.size() && ((s[i] >= '0' && s[i] <= '9') ||
                                    s[i] == '.' || s[i] == 'e' || s[i] == 'E' ||
                                    s[i] == '-' || s[i] == '+')) {
                i++;
            }
            v.num = strtol(s.substr(start, i - start).c_str(), nullptr, 10);
        }
        return v;
    }
};

/* -------------------------------------------------------------------------- */
/* Event dispatch                                                             */
/* -------------------------------------------------------------------------- */

static void ui_node_event(lv_event_t* e) {
    lv_obj_t* target = static_cast<lv_obj_t*>(lv_event_get_current_target(e));
    if (!target) return;

    auto it = g_nodeIds.find(target);
    if (it == g_nodeIds.end()) return;

    std::string type;
    int value = 0;
    switch (lv_event_get_code(e)) {
        case LV_EVENT_CLICKED:
            type = "click";
            break;
        case LV_EVENT_VALUE_CHANGED:
            if (lv_obj_check_type(target, &lv_slider_class)) {
                type = "value";
                value = lv_slider_get_value(target);
            } else if (lv_obj_check_type(target, &lv_switch_class) ||
                       lv_obj_check_type(target, &lv_checkbox_class)) {
                type = "toggle";
                value = lv_obj_has_state(target, LV_STATE_CHECKED) ? 1 : 0;
            } else if (lv_obj_check_type(target, &lv_dropdown_class)) {
                type = "select";
                value = lv_dropdown_get_selected(target);
            }
            break;
        default:
            return;
    }
    if (type.empty()) return;
    ui_emit_event(g_currentAppScreen, it->second.c_str(), type.c_str(), value);
}

/* -------------------------------------------------------------------------- */
/* Node builder                                                               */
/* -------------------------------------------------------------------------- */

static lv_obj_t* build_node(const JsonValue& node, lv_obj_t* parent);

static void build_children(const JsonValue& node, lv_obj_t* parent) {
    for (const auto& child : node.arr) {
        build_node(child, parent);
    }
}

static void remember_id(lv_obj_t* obj, const std::string& id) {
    if (obj && !id.empty()) {
        g_nodeIds[obj] = id;
        g_idToNode[id] = obj;
    }
}

static lv_obj_t* build_container(const JsonValue& node, lv_obj_t* parent,
                                 const std::string& type) {
    const bool card = node.get("card").asBool();
    /* Elevated (surface + shadow) reads as a native M3 card on both themes;
     * the dynamic surface-variant tone is too strong for a card fill. */
    lv_obj_t* box = card ? ui_make_m3_card(parent, UI_CARD_ELEVATED)
                         : ui_make_panel(parent);
    lv_obj_set_layout(box, LV_LAYOUT_FLEX);
    lv_obj_set_flex_flow(box, type == "column" ? LV_FLEX_FLOW_COLUMN
                                               : LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(box, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER,
                          LV_FLEX_ALIGN_CENTER);
    const int gap = ui_dpx(node.get("gap").asInt(8), ui_dpi());
    lv_obj_set_style_pad_row(box, gap, LV_PART_MAIN);
    lv_obj_set_style_pad_column(box, gap, LV_PART_MAIN);
    lv_obj_set_style_pad_all(box, ui_dpx(node.get("pad").asInt(16), ui_dpi()),
                            LV_PART_MAIN);

    const JsonValue& children = node.get("children");
    if (children.type == JsonValue::Array) build_children(children, box);
    return box;
}

static lv_obj_t* build_node(const JsonValue& node, lv_obj_t* parent) {
    const std::string type = node.get("t").asString();
    const std::string id = node.get("id").asString();
    lv_obj_t* obj = nullptr;

    if (type == "column" || type == "row") {
        obj = build_container(node, parent, type);
    } else if (type == "label") {
        obj = ui_make_label(parent, node.get("text").asString().c_str(),
                            node.get("size").asInt(14), ui_palette().text);
        lv_obj_set_width(obj, LV_PCT(100));
        lv_label_set_long_mode(obj, LV_LABEL_LONG_MODE_WRAP);
    } else if (type == "button") {
        const std::string style = node.get("style").asString("filled");
        UiButtonStyle s = UI_BUTTON_FILLED;
        if (style == "tonal")          s = UI_BUTTON_TONAL;
        else if (style == "outlined")  s = UI_BUTTON_OUTLINED;
        else if (style == "text")      s = UI_BUTTON_TEXT;
        obj = ui_make_button(parent, node.get("text").asString().c_str(), s,
                             node.get("danger").asBool());
        if (node.get("fill").asBool()) lv_obj_set_width(obj, LV_PCT(100));
        lv_obj_add_event_cb(obj, ui_node_event, LV_EVENT_CLICKED, nullptr);
    } else if (type == "slider") {
        obj = ui_make_slider(parent, node.get("value").asInt(0));
        lv_slider_set_range(obj, node.get("min").asInt(0), node.get("max").asInt(100));
        lv_obj_add_event_cb(obj, ui_node_event, LV_EVENT_VALUE_CHANGED, nullptr);
    } else if (type == "switch") {
        obj = ui_make_switch(parent, node.get("checked").asBool());
        lv_obj_add_event_cb(obj, ui_node_event, LV_EVENT_VALUE_CHANGED, nullptr);
    } else if (type == "checkbox") {
        obj = ui_make_checkbox(parent, node.get("text").asString().c_str());
        if (node.get("checked").asBool()) lv_obj_add_state(obj, LV_STATE_CHECKED);
        lv_obj_add_event_cb(obj, ui_node_event, LV_EVENT_VALUE_CHANGED, nullptr);
    } else if (type == "progress") {
        obj = ui_make_progress(parent, node.get("value").asInt(0));
        lv_bar_set_range(obj, 0, node.get("max").asInt(100));
    } else if (type == "arc") {
        obj = ui_make_arc(parent, node.get("value").asInt(0));
        lv_arc_set_range(obj, 0, node.get("max").asInt(100));
    } else if (type == "dropdown") {
        obj = lv_dropdown_create(parent);
        const JsonValue& opts = node.get("options");
        if (opts.type == JsonValue::Array && !opts.arr.empty()) {
            std::string joined;
            for (size_t i = 0; i < opts.arr.size(); i++) {
                if (i) joined += "\n";
                joined += opts.arr[i].str;
            }
            lv_dropdown_set_options(obj, joined.c_str());
        }
        lv_dropdown_set_selected(obj, node.get("selected").asInt(0));
        lv_obj_set_width(obj, LV_PCT(100));
        lv_obj_set_style_text_font(obj, ui_font(14, ui_dpi()), 0);
        lv_obj_set_style_text_color(obj, ui_palette().text, LV_PART_MAIN);
        lv_obj_set_style_bg_color(obj, ui_palette().card, LV_PART_MAIN);
        lv_obj_set_style_bg_opa(obj, LV_OPA_COVER, LV_PART_MAIN);
        lv_obj_set_style_border_width(obj, ui_dpx(1, ui_dpi()), LV_PART_MAIN);
        lv_obj_set_style_border_color(obj, ui_palette().divider, LV_PART_MAIN);
        lv_obj_set_style_radius(obj, ui_dpx(8, ui_dpi()), LV_PART_MAIN);
        lv_obj_add_event_cb(obj, ui_node_event, LV_EVENT_VALUE_CHANGED, nullptr);
    } else if (type == "divider") {
        obj = ui_make_divider(parent);
    } else if (type == "spacer") {
        obj = ui_make_spacer(parent, UI_SPACE_MD);
        lv_obj_set_size(obj, ui_dpx(node.get("size").asInt(16), ui_dpi()),
                        ui_dpx(node.get("size").asInt(16), ui_dpi()));
    }

    remember_id(obj, id);
    return obj;
}

/* -------------------------------------------------------------------------- */
/* Node updates from Kotlin                                                   */
/* -------------------------------------------------------------------------- */

static void apply_update(lv_obj_t* obj, const JsonValue& v) {
    if (!obj) return;

    const JsonValue& text = v.get("text");
    if (text.type == JsonValue::String) {
        if (lv_obj_check_type(obj, &lv_label_class)) {
            lv_label_set_text(obj, text.str.c_str());
        } else if (lv_obj_check_type(obj, &lv_button_class)) {
            lv_obj_t* lbl = lv_obj_get_child(obj, 0);
            if (lbl && lv_obj_check_type(lbl, &lv_label_class)) {
                lv_label_set_text(lbl, text.str.c_str());
            }
        } else if (lv_obj_check_type(obj, &lv_checkbox_class)) {
            lv_checkbox_set_text(obj, text.str.c_str());
        }
    }

    const JsonValue& value = v.get("value");
    if (value.type == JsonValue::Number) {
        if (lv_obj_check_type(obj, &lv_slider_class)) {
            lv_slider_set_value(obj, static_cast<int>(value.num), LV_ANIM_OFF);
        } else if (lv_obj_check_type(obj, &lv_bar_class)) {
            lv_bar_set_value(obj, static_cast<int>(value.num), LV_ANIM_OFF);
        } else if (lv_obj_check_type(obj, &lv_arc_class)) {
            lv_arc_set_value(obj, static_cast<int>(value.num));
        }
    }

    const JsonValue& checked = v.get("checked");
    if (checked.type == JsonValue::Bool &&
        (lv_obj_check_type(obj, &lv_switch_class) ||
         lv_obj_check_type(obj, &lv_checkbox_class))) {
        if (checked.b) lv_obj_add_state(obj, LV_STATE_CHECKED);
        else          lv_obj_clear_state(obj, LV_STATE_CHECKED);
    }

    const JsonValue& visible = v.get("visible");
    if (visible.type == JsonValue::Bool) {
        if (visible.b) lv_obj_clear_flag(obj, LV_OBJ_FLAG_HIDDEN);
        else           lv_obj_add_flag(obj, LV_OBJ_FLAG_HIDDEN);
    }
}

} /* namespace */

void ui_update_node(int screen_id, const char* node_id, const char* json) {
    if (screen_id != g_currentAppScreen || !node_id || !json) return;
    auto it = g_idToNode.find(node_id);
    if (it == g_idToNode.end()) return;
    JsonParser parser(json);
    apply_update(it->second, parser.parse());
}

/* -------------------------------------------------------------------------- */
/* Public entry                                                               */
/* -------------------------------------------------------------------------- */

bool ui_build_app_screen(int screen_id, int dpi) {
    auto it = g_appScreens.find(screen_id);
    if (it == g_appScreens.end()) return false;

    g_currentAppScreen = screen_id;
    g_nodeIds.clear();
    g_idToNode.clear();

    lv_obj_t* screen = ui_begin_screen(dpi);
    UiScaffold scaffold = ui_make_scaffold(screen, it->second.title.c_str(),
                                           it->second.show_back);

    JsonParser parser(it->second.json);
    const JsonValue root = parser.parse();
    if (root.type == JsonValue::Array) {
        build_children(root, scaffold.body);
    } else if (root.type == JsonValue::Object) {
        /* Also accept a single root node. */
        build_node(root, scaffold.body);
    }
    return true;
}

} /* namespace lvgl_android */

#if defined(__clang__) || defined(__GNUC__)
#pragma GCC diagnostic pop
#endif
