#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <thread>
#include <mutex>
#include <atomic>
#include <condition_variable>
#include <cstring>
#include <ctime>

#include "lvgl/lvgl.h"

#define LOG_TAG "LVGLPort"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static ANativeWindow* g_nativeWindow = nullptr;
static std::thread g_lvglThread;
static std::mutex g_mutex;
static std::condition_variable g_cv;
static std::atomic<bool> g_running{false};
static std::atomic<bool> g_paused{false};
static std::atomic<bool> g_initialized{false};

static std::mutex g_touchMutex;
static lv_indev_state_t g_touchState = LV_INDEV_STATE_RELEASED;
static lv_point_t g_touchPoint = {0, 0};
static bool g_touchNew = false;

static lv_display_t* g_display = nullptr;
static lv_indev_t* g_indev = nullptr;

static uint8_t* g_fb1 = nullptr;
static uint8_t* g_fb2 = nullptr;
static uint32_t g_dispWidth = 0;
static uint32_t g_dispHeight = 0;
static uint32_t g_fbStride = 0;
static bool g_bufferLogged = false;

static void copy_pixels_rb_swap(uint8_t* dst, const uint8_t* src, uint32_t pixel_count) {
    for (uint32_t i = 0; i < pixel_count; i++) {
        uint32_t offset = i * 4;
        dst[offset + 0] = src[offset + 2];
        dst[offset + 1] = src[offset + 1];
        dst[offset + 2] = src[offset + 0];
        dst[offset + 3] = src[offset + 3];
    }
}

static void display_flush_cb(lv_display_t* disp, const lv_area_t* area, uint8_t* px_map) {
    (void)area;
    (void)disp;

    if (!g_nativeWindow) {
        lv_display_flush_ready(disp);
        return;
    }

    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(g_nativeWindow, &buffer, nullptr) < 0) {
        lv_display_flush_ready(disp);
        return;
    }

    if (!g_bufferLogged) {
        LOGI("ANativeWindow buffer: %dx%d stride=%d format=%d",
             buffer.width, buffer.height, buffer.stride, buffer.format);
        g_bufferLogged = true;
    }

    int32_t copy_w = g_dispWidth;
    int32_t copy_h = g_dispHeight;
    if (copy_w > buffer.width) copy_w = buffer.width;
    if (copy_h > buffer.height) copy_h = buffer.height;

    uint8_t* dst = static_cast<uint8_t*>(buffer.bits);
    const uint8_t* src = px_map;
    uint32_t src_stride_bytes = g_fbStride;
    uint32_t dst_stride_bytes = buffer.stride * 4;

    for (int32_t y = 0; y < copy_h; y++) {
        copy_pixels_rb_swap(dst + y * dst_stride_bytes,
                            src + y * src_stride_bytes, copy_w);
    }

    ANativeWindow_unlockAndPost(g_nativeWindow);
    lv_display_flush_ready(disp);
}

static void touch_read_cb(lv_indev_t* indev, lv_indev_data_t* data) {
    (void)indev;

    std::lock_guard<std::mutex> lock(g_touchMutex);
    data->state = g_touchState;
    data->point.x = g_touchPoint.x;
    data->point.y = g_touchPoint.y;
    g_touchNew = false;
}

static uint32_t tick_get_cb(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint32_t)(ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
}

static void lvgl_thread_func() {
    LOGI("LVGL thread started");

    std::this_thread::sleep_for(std::chrono::milliseconds(50));

    while (g_running) {
        {
            std::unique_lock<std::mutex> lock(g_mutex);
            g_cv.wait_for(lock, std::chrono::milliseconds(5));
        }

        if (!g_running) break;
        if (g_paused) continue;

        {
            std::lock_guard<std::mutex> lock(g_mutex);
            lv_timer_handler();
        }
    }

    LOGI("LVGL thread stopped");
}

static int32_t g_btn_click_count = 0;

static void button_clicked_cb(lv_event_t* e) {
    (void)e;
    g_btn_click_count++;
}

static void slider_value_changed_cb(lv_event_t* e) {
    lv_obj_t* slider = lv_event_get_target(e);
    int32_t value = lv_slider_get_value(slider);
    LV_UNUSED(value);
}

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

    /* Button with click counter */
    lv_obj_t* btn = lv_button_create(screen);
    lv_obj_set_size(btn, 220, 60);
    lv_obj_align(btn, LV_ALIGN_CENTER, 0, -60);
    lv_obj_add_event_cb(btn, button_clicked_cb, LV_EVENT_CLICKED, NULL);

    lv_obj_t* btn_label = lv_label_create(btn);
    lv_label_set_text(btn_label, "Click Me!");
    lv_obj_center(btn_label);

    /* Click counter label */
    lv_obj_t* counter_label = lv_label_create(screen);
    lv_label_set_text(counter_label, "Clicks: 0");
    lv_obj_set_style_text_font(counter_label, &lv_font_montserrat_14, 0);
    lv_obj_align(counter_label, LV_ALIGN_CENTER, 0, 10);

    /* Slider */
    lv_obj_t* slider = lv_slider_create(screen);
    lv_obj_set_width(slider, 350);
    lv_obj_align(slider, LV_ALIGN_BOTTOM_MID, 0, -80);
    lv_slider_set_value(slider, 50, LV_ANIM_OFF);
    lv_obj_add_event_cb(slider, slider_value_changed_cb, LV_EVENT_VALUE_CHANGED, NULL);

    /* Slider value label */
    lv_obj_t* slider_label = lv_label_create(screen);
    lv_label_set_text(slider_label, "Slider: 50");
    lv_obj_set_style_text_font(slider_label, &lv_font_montserrat_14, 0);
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
    lv_obj_set_style_text_font(sw_label, &lv_font_montserrat_14, 0);
    lv_obj_align_to(sw_label, sw, LV_ALIGN_OUT_BOTTOM_MID, 0, 8);

    /* Bar indicator */
    lv_obj_t* bar = lv_bar_create(screen);
    lv_obj_set_size(bar, 200, 20);
    lv_obj_align(bar, LV_ALIGN_CENTER, 0, 70);
    lv_bar_set_value(bar, 60, LV_ANIM_ON);

    /* Footer */
    lv_obj_t* footer = lv_label_create(screen);
    lv_label_set_text(footer, "Build: Debug | ABI: arm64-v8a");
    lv_obj_set_style_text_font(footer, &lv_font_montserrat_14, 0);
    lv_obj_align(footer, LV_ALIGN_BOTTOM_MID, 0, -15);

    LOGI("Default UI created");
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_lvgl_sample_LvglView_nativeInit(JNIEnv* env, jobject thiz, jobject surface,
                                           jint width, jint height, jint dpi) {
    LOGI("nativeInit: width=%d height=%d dpi=%d", width, height, dpi);

    if (width <= 0 || height <= 0) {
        LOGE("Invalid dimensions: %dx%d", width, height);
        return;
    }

    g_nativeWindow = ANativeWindow_fromSurface(env, surface);
    if (!g_nativeWindow) {
        LOGE("Failed to get native window from surface");
        return;
    }

    g_dispWidth = width;
    g_dispHeight = height;
    g_bufferLogged = false;

    lv_init();
    lv_tick_set_cb(tick_get_cb);

    g_display = lv_display_create(width, height);
    if (!g_display) {
        LOGE("Failed to create LVGL display");
        ANativeWindow_release(g_nativeWindow);
        g_nativeWindow = nullptr;
        return;
    }

    g_fbStride = width * 4;
    uint32_t buf_size = width * height * 4;

    g_fb1 = new uint8_t[buf_size];
    g_fb2 = new uint8_t[buf_size];

    if (!g_fb1 || !g_fb2) {
        LOGE("Failed to allocate framebuffers");
        return;
    }

    memset(g_fb1, 0xFF, buf_size);
    memset(g_fb2, 0xFF, buf_size);

    lv_display_set_buffers(g_display, g_fb1, g_fb2, buf_size, LV_DISPLAY_RENDER_MODE_DIRECT);
    lv_display_set_flush_cb(g_display, display_flush_cb);

    lv_display_set_dpi(g_display, dpi);

    g_indev = lv_indev_create();
    lv_indev_set_type(g_indev, LV_INDEV_TYPE_POINTER);
    lv_indev_set_read_cb(g_indev, touch_read_cb);

    lv_theme_default_init(g_display, lv_palette_main(LV_PALETTE_BLUE),
                          lv_palette_main(LV_PALETTE_RED),
                          LV_THEME_DEFAULT_DARK, LV_FONT_DEFAULT);

    create_default_ui();

    g_running = true;
    g_paused = false;
    g_initialized = true;
    g_lvglThread = std::thread(lvgl_thread_func);

    LOGI("LVGL initialized successfully");
}

JNIEXPORT void JNICALL
Java_com_lvgl_sample_LvglView_nativeDeinit(JNIEnv* env, jobject thiz) {
    LOGI("nativeDeinit");

    g_running = false;
    g_cv.notify_all();

    if (g_lvglThread.joinable()) {
        g_lvglThread.join();
    }

    if (g_display) {
        lv_display_delete(g_display);
        g_display = nullptr;
    }

    delete[] g_fb1;
    delete[] g_fb2;
    g_fb1 = nullptr;
    g_fb2 = nullptr;

    if (g_nativeWindow) {
        ANativeWindow_release(g_nativeWindow);
        g_nativeWindow = nullptr;
    }

    lv_deinit();

    g_initialized = false;
    LOGI("LVGL deinitialized");
}

JNIEXPORT void JNICALL
Java_com_lvgl_sample_LvglView_nativeResume(JNIEnv* env, jobject thiz) {
    LOGI("nativeResume");
    g_paused = false;
}

JNIEXPORT void JNICALL
Java_com_lvgl_sample_LvglView_nativePause(JNIEnv* env, jobject thiz) {
    LOGI("nativePause");
    g_paused = true;
}

JNIEXPORT void JNICALL
Java_com_lvgl_sample_LvglView_nativeTouchEvent(JNIEnv* env, jobject thiz,
                                                 jint action, jfloat x, jfloat y) {
    std::lock_guard<std::mutex> lock(g_touchMutex);

    g_touchPoint.x = (lv_coord_t)x;
    g_touchPoint.y = (lv_coord_t)y;
    g_touchNew = true;

    if (action == 0) {
        g_touchState = LV_INDEV_STATE_PRESSED;
    } else if (action == 1) {
        g_touchState = LV_INDEV_STATE_RELEASED;
    }
}

} /* extern "C" */
