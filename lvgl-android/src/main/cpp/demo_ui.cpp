#include "demo_ui.h"

void lvgl_android::launch_demo_ui(int demo_id) {
    lv_obj_t* screen = lv_screen_active();
    lv_obj_clean(screen);

    if (demo_id == 0) create_widget_showcase_ui();
    else if (demo_id == 1) create_layout_showcase_ui();
    else if (demo_id == 2) create_interactive_ui();
}
