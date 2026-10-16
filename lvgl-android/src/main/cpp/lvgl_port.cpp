/*
 * Copyright (c) 2026 LVGL Android Framework Contributors
 * SPDX-License-Identifier: MIT
 *
 * This file is part of the LVGL Android Framework.
 * See LICENSE in the project root for full license information.
 */
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
#include <memory>

#include "lvgl/lvgl.h"
#include "demo_ui.h"

#define LOG_TAG "LVGLPort"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace lvgl_android {

struct Engine {
    ANativeWindow* nativeWindow = nullptr;
    std::thread lvglThread;
    std::mutex mutex;
    std::condition_variable cv;
    std::atomic<bool> running{false};
    std::atomic<bool> paused{false};
    std::atomic<bool> initialized{false};

    std::mutex touchMutex;
    lv_indev_state_t touchState = LV_INDEV_STATE_RELEASED;
    lv_point_t touchPoint = {0, 0};
    bool touchNew = false;

    lv_display_t* display = nullptr;
    lv_indev_t* indev = nullptr;

    uint8_t* fb1 = nullptr;
    uint8_t* fb2 = nullptr;
    uint32_t dispWidth = 0;
    uint32_t dispHeight = 0;
    uint32_t fbStride = 0;
    bool bufferLogged = false;
};

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

    auto* engine = static_cast<Engine*>(lv_display_get_user_data(disp));
    if (!engine || !engine->nativeWindow) {
        lv_display_flush_ready(disp);
        return;
    }

    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(engine->nativeWindow, &buffer, nullptr) < 0) {
        lv_display_flush_ready(disp);
        return;
    }

    if (!engine->bufferLogged) {
        LOGI("ANativeWindow buffer: %dx%d stride=%d format=%d",
             buffer.width, buffer.height, buffer.stride, buffer.format);
        engine->bufferLogged = true;
    }

    int32_t copy_w = engine->dispWidth;
    int32_t copy_h = engine->dispHeight;
    if (copy_w > buffer.width) copy_w = buffer.width;
    if (copy_h > buffer.height) copy_h = buffer.height;

    uint8_t* dst = static_cast<uint8_t*>(buffer.bits);
    uint32_t dst_stride_bytes = buffer.stride * 4;

    for (int32_t y = 0; y < copy_h; y++) {
        copy_pixels_rb_swap(dst + y * dst_stride_bytes,
                            px_map + y * engine->fbStride, copy_w);
    }

    ANativeWindow_unlockAndPost(engine->nativeWindow);
    lv_display_flush_ready(disp);
}

static void touch_read_cb(lv_indev_t* indev, lv_indev_data_t* data) {
    (void)indev;
    auto* engine = static_cast<Engine*>(lv_indev_get_user_data(indev));
    if (!engine) return;

    std::lock_guard<std::mutex> lock(engine->touchMutex);
    data->state = engine->touchState;
    data->point.x = engine->touchPoint.x;
    data->point.y = engine->touchPoint.y;
    engine->touchNew = false;
}

static uint32_t tick_get_cb(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint32_t)(ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
}

static void lvgl_thread_func(Engine* engine) {
    LOGI("LVGL thread started");
    std::this_thread::sleep_for(std::chrono::milliseconds(50));

    while (engine->running) {
        {
            std::unique_lock<std::mutex> lock(engine->mutex);
            engine->cv.wait_for(lock, std::chrono::milliseconds(5));
        }

        if (!engine->running) break;
        if (engine->paused) continue;

        {
            std::lock_guard<std::mutex> lock(engine->mutex);
            lv_timer_handler();
        }
    }

    LOGI("LVGL thread stopped");
}

} /* namespace lvgl_android */

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

    lv_obj_t* btn_label = lv_label_create(btn);
    lv_label_set_text(btn_label, "Click Me!");
    lv_obj_center(btn_label);

    /* Slider */
    lv_obj_t* slider = lv_slider_create(screen);
    lv_obj_set_width(slider, 350);
    lv_obj_align(slider, LV_ALIGN_BOTTOM_MID, 0, -80);
    lv_slider_set_value(slider, 50, LV_ANIM_OFF);

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
}

static void onCreateLvgl(lvgl_android::Engine* engine) {
    create_default_ui();
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_lvgl_android_engine_LvglEngine_nativeCreate(JNIEnv* env, jobject thiz) {
    (void)env;
    (void)thiz;
    auto* engine = new lvgl_android::Engine();
    LOGI("Engine created");
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_com_lvgl_android_engine_LvglEngine_nativeDestroy(JNIEnv* env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    auto* engine = reinterpret_cast<lvgl_android::Engine*>(handle);
    if (!engine) return;

    engine->running = false;
    engine->cv.notify_all();

    if (engine->lvglThread.joinable()) {
        engine->lvglThread.join();
    }

    if (engine->display) {
        lv_display_delete(engine->display);
    }

    delete[] engine->fb1;
    delete[] engine->fb2;

    if (engine->nativeWindow) {
        ANativeWindow_release(engine->nativeWindow);
    }

    lv_deinit();
    delete engine;
    LOGI("Engine destroyed");
}

JNIEXPORT void JNICALL
Java_com_lvgl_android_engine_LvglEngine_nativeInitDisplay(JNIEnv* env, jobject thiz, jlong handle,
                                                           jobject surface, jint width, jint height, jint dpi) {
    (void)thiz;
    auto* engine = reinterpret_cast<lvgl_android::Engine*>(handle);
    if (!engine) return;

    LOGI("nativeInitDisplay: width=%d height=%d dpi=%d", width, height, dpi);

    if (width <= 0 || height <= 0) {
        LOGE("Invalid dimensions: %dx%d", width, height);
        return;
    }

    engine->nativeWindow = ANativeWindow_fromSurface(env, surface);
    if (!engine->nativeWindow) {
        LOGE("Failed to get native window from surface");
        return;
    }

    engine->dispWidth = width;
    engine->dispHeight = height;
    engine->bufferLogged = false;

    lv_init();
    lv_tick_set_cb(lvgl_android::tick_get_cb);

    engine->display = lv_display_create(width, height);
    if (!engine->display) {
        LOGE("Failed to create LVGL display");
        ANativeWindow_release(engine->nativeWindow);
        engine->nativeWindow = nullptr;
        return;
    }

    lv_display_set_user_data(engine->display, engine);

    engine->fbStride = width * 4;
    uint32_t buf_size = width * height * 4;

    engine->fb1 = new uint8_t[buf_size];
    engine->fb2 = new uint8_t[buf_size];

    if (!engine->fb1 || !engine->fb2) {
        LOGE("Failed to allocate framebuffers");
        return;
    }

    memset(engine->fb1, 0xFF, buf_size);
    memset(engine->fb2, 0xFF, buf_size);

    lv_display_set_buffers(engine->display, engine->fb1, engine->fb2, buf_size, LV_DISPLAY_RENDER_MODE_DIRECT);
    lv_display_set_flush_cb(engine->display, lvgl_android::display_flush_cb);
    lv_display_set_dpi(engine->display, dpi);

    engine->indev = lv_indev_create();
    lv_indev_set_type(engine->indev, LV_INDEV_TYPE_POINTER);
    lv_indev_set_read_cb(engine->indev, lvgl_android::touch_read_cb);
    lv_indev_set_user_data(engine->indev, engine);

    lv_theme_default_init(engine->display, lv_palette_main(LV_PALETTE_BLUE),
                          lv_palette_main(LV_PALETTE_RED),
                          LV_THEME_DEFAULT_DARK, LV_FONT_DEFAULT);

    onCreateLvgl(engine);

    engine->running = true;
    engine->paused = false;
    engine->initialized = true;
    engine->lvglThread = std::thread(lvgl_android::lvgl_thread_func, engine);

    LOGI("LVGL initialized successfully");
}

JNIEXPORT void JNICALL
Java_com_lvgl_android_engine_LvglEngine_nativeDeinitDisplay(JNIEnv* env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    auto* engine = reinterpret_cast<lvgl_android::Engine*>(handle);
    if (!engine) return;

    LOGI("nativeDeinitDisplay");

    engine->running = false;
    engine->cv.notify_all();

    if (engine->lvglThread.joinable()) {
        engine->lvglThread.join();
    }

    if (engine->display) {
        lv_display_delete(engine->display);
        engine->display = nullptr;
    }

    delete[] engine->fb1;
    delete[] engine->fb2;
    engine->fb1 = nullptr;
    engine->fb2 = nullptr;

    if (engine->nativeWindow) {
        ANativeWindow_release(engine->nativeWindow);
        engine->nativeWindow = nullptr;
    }

    lv_deinit();
    engine->initialized = false;
    LOGI("LVGL deinitialized");
}

JNIEXPORT void JNICALL
Java_com_lvgl_android_engine_LvglEngine_nativeResume(JNIEnv* env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    auto* engine = reinterpret_cast<lvgl_android::Engine*>(handle);
    if (!engine) return;
    LOGI("nativeResume");
    engine->paused = false;
}

JNIEXPORT void JNICALL
Java_com_lvgl_android_engine_LvglEngine_nativePause(JNIEnv* env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    auto* engine = reinterpret_cast<lvgl_android::Engine*>(handle);
    if (!engine) return;
    LOGI("nativePause");
    engine->paused = true;
}

JNIEXPORT void JNICALL
Java_com_lvgl_android_engine_LvglEngine_nativeTouchEvent(JNIEnv* env, jobject thiz, jlong handle,
                                                          jint action, jfloat x, jfloat y) {
    (void)env;
    (void)thiz;
    auto* engine = reinterpret_cast<lvgl_android::Engine*>(handle);
    if (!engine) return;

    std::lock_guard<std::mutex> lock(engine->touchMutex);
    engine->touchPoint.x = (lv_coord_t)x;
    engine->touchPoint.y = (lv_coord_t)y;
    engine->touchNew = true;

    if (action == 0) {
        engine->touchState = LV_INDEV_STATE_PRESSED;
    } else if (action == 1) {
        engine->touchState = LV_INDEV_STATE_RELEASED;
    }
}

JNIEXPORT void JNICALL
Java_com_lvgl_android_engine_LvglEngine_nativeApplyTheme(JNIEnv* env, jobject thiz, jlong handle,
                                                          jboolean darkMode, jint primaryColor,
                                                          jint secondaryColor) {
    (void)env;
    (void)thiz;
    auto* engine = reinterpret_cast<lvgl_android::Engine*>(handle);
    if (!engine || !engine->display) return;

    lv_theme_default_init(engine->display,
                          lv_palette_main(LV_PALETTE_BLUE),
                          lv_palette_main(LV_PALETTE_RED),
                          darkMode ? LV_THEME_DEFAULT_DARK : 0,
                          LV_FONT_DEFAULT);

    LOGI("Theme applied: dark=%d primary=0x%06X secondary=0x%06X",
         darkMode, primaryColor, secondaryColor);
}

JNIEXPORT void JNICALL
Java_com_lvgl_android_engine_LvglEngine_nativeLoadDemo(JNIEnv* env, jobject thiz, jlong handle, jint demoId) {
    (void)env;
    (void)thiz;
    auto* engine = reinterpret_cast<lvgl_android::Engine*>(handle);
    if (!engine) return;

    LOGI("Loading demo: %d", demoId);
    lvgl_android::launch_demo_ui(demoId);
}

} /* extern "C" */

