/*
 * Copyright (c) 2026 LVGL Android Framework Contributors
 * SPDX-License-Identifier: MIT
 *
 * This file is part of the LVGL Android Framework.
 * See LICENSE in the project root for full license information.
 *
 * Native LVGL port: owns the render thread, the ANativeWindow, the display and
 * the LVGL thread. All mutations of LVGL happen on the render thread through a
 * task queue, so Kotlin callers never race the render loop.
 *
 * Lifecycle:
 *   nativeCreate()        -> allocate engine, no LVGL yet.
 *   nativeInitDisplay()   -> lv_init, create display+indev, build the current
 *                            screen (consuming any pendingDemoId), start thread.
 *   nativeScreen*()       -> enqueue screen/status/theme changes.
 *   nativeDeinitDisplay() -> stop thread, tear down display, release window.
 *   nativeDestroy()       -> lv_deinit, free engine.
 *
 * Surface recreation: nativeDeinitDisplay/nativeInitDisplay pair recreates the
 * display without destroying the engine, and re-applies the last screen + theme.
 *
 * @breadcrumb BC-001
 * @breadcrumb BC-002
 * @breadcrumb BC-003
 * @breadcrumb BC-007
 * @breadcrumb BC-008
 * @breadcrumb BC-009
 * @breadcrumb BC-012
 * @breadcrumb BC-031
 * @decision ADR-008
 * @invariant engine->running == false implies the render thread is joined
 * @trap Never call scheduleTask() while holding engine->mutex (deadlock/ANR).
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
#include <deque>
#include <functional>
#include <memory>

#include "lvgl/lvgl.h"
#include "demo_ui.h"
#include "lvgl_anim.h"

#define LOG_TAG "LVGLPort"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* Forward LVGL log messages to logcat (stdout is discarded by Android). */
static void lv_log_to_logcat(lv_log_level_t level, const char* buf) {
    int prio = ANDROID_LOG_DEBUG;
    switch (level) {
        case LV_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case LV_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN; break;
        case LV_LOG_LEVEL_INFO:  prio = ANDROID_LOG_INFO; break;
        case LV_LOG_LEVEL_TRACE: prio = ANDROID_LOG_VERBOSE; break;
        default:                 prio = ANDROID_LOG_DEBUG; break;
    }
    __android_log_print(prio, "LVGL", "%s", buf);
}

/* Forward declarations for navigation helpers defined near the JNI exports.
 * The in-namespace drainNavRequests calls them on the LVGL thread. They have
 * C linkage (defined inside extern "C") so declare them accordingly here. */
extern "C" {
static bool nav_valid();
static void nav_to(int screenId);
static void nav_back();
static void sys_set_brightness(int percent);
static void sys_set_volume(int percent);
static void toggle_dark_mode();
}



namespace lvgl_android {

struct Engine {
    ANativeWindow* nativeWindow = nullptr;

    /* Surface rebinding. Android hands us a new surface for every activity
     * transition; instead of tearing the whole display down (which caused both
     * the "wrong screen" bug and the start-timeout ANR) the UI thread parks the
     * new window here and the LVGL thread installs it. */
    ANativeWindow* pendingWindow = nullptr;
    bool pendingWindowSet = false;
    std::atomic<bool> windowValid{false};

    std::thread lvglThread;
    std::mutex mutex;             /* protects display + taskQueue */
    std::mutex stateMutex;        /* protects dpi + screen ids + theme + last status */
    std::condition_variable cv;
    std::atomic<bool> running{false};
    std::atomic<bool> paused{false};
    std::atomic<bool> stopping{false};   /* set during teardown; flush bails out */
    std::atomic<bool> initialized{false};

    /* Real display density, captured from nativesurfaceChanged. */
    int dpi = 440;

    std::mutex touchMutex;
    lv_indev_state_t touchState = LV_INDEV_STATE_RELEASED;
    lv_point_t touchPoint = {0, 0};
    bool touchNew = false;
    /* LVGL polls the input device once per rendered frame. On a slow frame
     * (software GL can drop to a few fps) a short tap can fall entirely
     * between two polls, so the press is held until LVGL has observed it.
     * @breadcrumb BC-024 */
    bool touchPressSeen = false;

    lv_display_t* display = nullptr;
    lv_indev_t* indev = nullptr;

    std::unique_ptr<uint8_t[]> fb1;
    std::unique_ptr<uint8_t[]> fb2;
    uint32_t dispWidth = 0;
    uint32_t dispHeight = 0;
    uint32_t fbStride = 0;

    /* The screen requested via nativeSetScreen before the display existed. */
    int pendingScreenId = SCREEN_HOME;
    /* The screen currently shown — survives surface recreation. */
    int currentScreenId = SCREEN_HOME;

    /* Cached theme; applied after every screen build and on config change. */
    UiTheme theme;

    /* Last status text pushed from Kotlin + the screen it belongs to. Re-applied
     * by rebuild_screen() so an onResume rebuild never wipes live status. */
    std::string lastStatusText;
    int lastStatusScreen = -1;

    /* Battery level (0..100, -1 = unknown) for the dashboard status bar. */
    int  batteryLevel = -1;
    bool batteryCharging = false;

    /* Real device facts for the dashboard device map (manufacturer, model, SoC,
     * memory, storage, display, android version, cameras, sensors). Written
     * from the UI thread, copied onto the LVGL thread by rebuild_screen(). */
    std::mutex deviceInfoMutex;
    std::string devInfo[9];

    /* Safe-area insets (px) from Android; the UI kit pads against them. */
    uint32_t insetTop = 0;
    uint32_t insetBottom = 0;
    uint32_t insetLeft = 0;
    uint32_t insetRight = 0;

    /* Last error message, exposed via nativeGetLastError. */
    char lastError[128] = {0};

    /* Navigation request from the UI (menu card tapped). Set by the JNI
     * engine via setNavCallback(); read & cleared by drainNavRequests(). */
    std::mutex navMutex;
    int navRequest = -1;           // -1 = none, otherwise a target screen id

    /* Tasks run on the LVGL thread. */
    std::deque<std::function<void()>> taskQueue;

    void setError(const char* msg) {
        std::strncpy(lastError, msg, sizeof(lastError) - 1);
        lastError[sizeof(lastError) - 1] = '\0';
        LOGE("%s", msg);
    }
};

/* ---- Pixel helpers ------------------------------------------------------- */

/* 32-bit R/B swap. One word per pixel, no branches, vector-friendly. */
static void copy_pixels_rb_swap(uint32_t* __restrict__ dst,
                                const uint32_t* __restrict__ src,
                                uint32_t pixel_count) {
    for (uint32_t i = 0; i < pixel_count; i++) {
        uint32_t c = src[i];
        dst[i] = (c & 0xFF00FF00u) | ((c & 0x00FF0000u) >> 16) | ((c & 0x000000FFu) << 16);
    }
}

/* ---- Display / flush ----------------------------------------------------- */

static void display_flush_cb(lv_display_t* disp, const lv_area_t* area, uint8_t* px_map) {
    (void)area;
    auto* engine = static_cast<Engine*>(lv_display_get_user_data(disp));
    if (!engine || !engine->nativeWindow || !engine->windowValid.load()) {
        lv_display_flush_ready(disp);
        return;
    }
    /* Bail out during teardown so the UI thread never blocks on a lock held by
     * a dying render thread (fixes the System UI ANR). */
    if (engine->stopping.load()) {
        lv_display_flush_ready(disp);
        return;
    }
    if (engine->paused.load()) {
        lv_display_flush_ready(disp);
        return;
    }

    ANativeWindow_Buffer buffer;
    ARect bounds = {0, 0, (int32_t)engine->dispWidth, (int32_t)engine->dispHeight};
    if (ANativeWindow_lock(engine->nativeWindow, &buffer, &bounds) < 0) {
        engine->setError("ANativeWindow_lock failed");
        lv_display_flush_ready(disp);
        return;
    }

    const uint32_t copy_w = (engine->dispWidth  < (uint32_t)buffer.width)
                                ? engine->dispWidth  : (uint32_t)buffer.width;
    const uint32_t copy_h = (engine->dispHeight < (uint32_t)buffer.height)
                                ? engine->dispHeight : (uint32_t)buffer.height;
    const uint32_t dst_stride_px = buffer.stride;     /* stride is in pixels */
    const uint32_t src_stride_px = engine->dispWidth; /* tightly packed fb */

    uint32_t* dst = static_cast<uint32_t*>(buffer.bits);
    const uint32_t* src = reinterpret_cast<const uint32_t*>(px_map);

    if (dst_stride_px == src_stride_px) {
        /* Common case: contiguous copy + swap in one pass. */
        copy_pixels_rb_swap(dst, src, copy_w * copy_h);
    } else {
        for (uint32_t y = 0; y < copy_h; y++) {
            copy_pixels_rb_swap(dst + y * dst_stride_px,
                                src + y * src_stride_px, copy_w);
        }
    }

    if (ANativeWindow_unlockAndPost(engine->nativeWindow) < 0) {
        engine->setError("ANativeWindow_unlockAndPost failed");
    }
    lv_display_flush_ready(disp);
}

static void touch_read_cb(lv_indev_t* indev, lv_indev_data_t* data) {
    auto* engine = static_cast<Engine*>(lv_indev_get_user_data(indev));
    if (!engine) return;
    std::lock_guard<std::mutex> lock(engine->touchMutex);
    data->state = engine->touchState;
    data->point = engine->touchPoint;
    /* Hold a short press until LVGL has polled it once. Without this, a tap
     * whose DOWN/UP pair lands between two polls (easy at a few fps) is never
     * seen as pressed and produces no click. */
    if (data->state == LV_INDEV_STATE_RELEASED && !engine->touchPressSeen) {
        data->state = LV_INDEV_STATE_PRESSED;
    }
    if (data->state == LV_INDEV_STATE_PRESSED) {
        engine->touchPressSeen = true;
    }
    engine->touchNew = false;
}

static uint32_t tick_get_cb(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint32_t)(ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
}

/* ---- Navigation hooks (used by demo_ui.cpp event callbacks) ------------- */
/* These live in the LVGL thread's namespace so the thread loop can call them.
 * They forward native UI taps to the registered Kotlin navigator via JNI. */

extern Engine* g_activeEngine;   // defined just below

static void nav_to_hook(int screenId) {
    __android_log_print(ANDROID_LOG_INFO, "LVGLPort", "nav_to_hook id=%d engine=%p", screenId, (void*)g_activeEngine);
    if (!g_activeEngine) return;
    if (screenId < 0 || screenId >= SCREEN_COUNT) return;
    std::lock_guard<std::mutex> lock(g_activeEngine->navMutex);
    g_activeEngine->navRequest = screenId;
    g_activeEngine->cv.notify_all();
}

static void nav_back_hook() {
    if (!g_activeEngine) return;
    /* Request activity-level back. The drain routine recognises the sentinel
     * and calls the navigator's navigateBack() (Kotlin runs onBackPressed). */
    std::lock_guard<std::mutex> lock(g_activeEngine->navMutex);
    g_activeEngine->navRequest = -2;
    g_activeEngine->cv.notify_all();
}

Engine* g_activeEngine = nullptr;

/* Flushes pending navigation requests from native UI taps. Forwards menu taps
 * (screen id >= 0) or the back sentinel (-2) to the registered Kotlin
 * navigator via the global g_navigator. */
static void drainNavRequests(Engine* engine) {
    std::lock_guard<std::mutex> lock(engine->navMutex);
    if (engine->navRequest == -1) return;
    int id = engine->navRequest;
    engine->navRequest = -1;
    __android_log_print(ANDROID_LOG_INFO, "LVGLPort", "drainNav id=%d valid=%d", id, nav_valid());
    if (id == -2) {
        nav_back();
    } else if (id >= 0 && nav_valid()) {
        nav_to(id);
    }
}

/* ---- LVGL thread --------------------------------------------------------- */

static void rebuild_screen(Engine* engine, int screen_id) {
    if (!engine->display) return;
    UiTheme theme;
    int dpi;
    std::string status_text;
    int status_screen;
    {
        std::lock_guard<std::mutex> state_lock(engine->stateMutex);
        theme = engine->theme;
        dpi = engine->dpi;
        status_text = engine->lastStatusText;
        status_screen = engine->lastStatusScreen;
    }
    /* Push the real device facts onto the LVGL thread before the build. */
    {
        UiDeviceInfo info;
        {
            std::lock_guard<std::mutex> lk(engine->deviceInfoMutex);
            info.manufacturer    = engine->devInfo[0].c_str();
            info.model           = engine->devInfo[1].c_str();
            info.soc             = engine->devInfo[2].c_str();
            info.memory          = engine->devInfo[3].c_str();
            info.storage         = engine->devInfo[4].c_str();
            info.display         = engine->devInfo[5].c_str();
            info.android_version = engine->devInfo[6].c_str();
            info.cameras         = engine->devInfo[7].c_str();
            info.sensors         = engine->devInfo[8].c_str();
        }
        ui_set_device_info(info);
    }
    /* launch_screen() (demo_ui.cpp) cleans + builds the requested screen. */
    launch_screen(screen_id, dpi, theme);
    {
        std::lock_guard<std::mutex> state_lock(engine->stateMutex);
        engine->currentScreenId = screen_id;
        engine->pendingScreenId = -1;
    }
    /* Restore the live status text for this screen (camera/game/launcher). */
    if (status_screen == screen_id && !status_text.empty()) {
        lv_obj_t* status = ui_current_status_label();
        if (status) lv_label_set_text(status, status_text.c_str());
    }
    {
        std::lock_guard<std::mutex> lock(engine->navMutex);
        engine->navRequest = -1;
    }
    /* Trigger a full redraw so the new content composits immediately. */
    lv_obj_invalidate(lv_screen_active());
    lv_refr_now(engine->display);
}

static void lvgl_thread_func(Engine* engine) {
    LOGI("LVGL thread started");
    /* Small delay to let the surface settle before the first frame. */
    std::this_thread::sleep_for(std::chrono::milliseconds(20));
    uint32_t last_frame_tick = lv_tick_get();
    /* Reset the animation engine so stale animations from a prior session
     * (e.g. an activity that was destroyed without draining) never replay. */
    lvgl_anim::Engine::instance().reset();

    while (engine->running.load()) {
        {
            std::unique_lock<std::mutex> lock(engine->mutex);
            engine->cv.wait_for(lock, std::chrono::milliseconds(16),
                                [&] { return !engine->taskQueue.empty() ||
                                             engine->pendingWindowSet ||
                                             !engine->running.load(); });
        }
        if (!engine->running.load()) break;

        /* Install a newly bound surface (activity transition) before rendering.
         * This replaces the old per-activity display teardown/recreate, which
         * both showed the wrong screen and stalled the UI thread (ANR). */
        if (engine->pendingWindowSet) {
            std::lock_guard<std::mutex> lock(engine->mutex);
            if (engine->pendingWindowSet) {
                if (engine->nativeWindow && engine->nativeWindow != engine->pendingWindow) {
                    ANativeWindow_release(engine->nativeWindow);
                }
                engine->nativeWindow  = engine->pendingWindow;
                engine->pendingWindow = nullptr;
                engine->pendingWindowSet = false;
                engine->windowValid   = true;
                /* Full repaint into the freshly bound surface. */
                if (lv_screen_active()) lv_obj_invalidate(lv_screen_active());
            }
        }

        if (engine->paused.load()) continue;

        /* Drain all queued tasks for this tick. */
        {
            std::unique_lock<std::mutex> lock(engine->mutex);
            while (!engine->taskQueue.empty() && engine->running.load()) {
                auto task = engine->taskQueue.front();
                engine->taskQueue.pop_front();
                /* release lock while running to keep the thread responsive */
                lock.unlock();
                task();
                lock.lock();
            }
        }

        if (!engine->running.load() || engine->paused.load()) continue;

        /* Flush any pending native->Kotlin navigation requests. */
        drainNavRequests(engine);

        uint32_t since = lv_timer_handler();

        /* Advance the animation engine by the real frame delta so spring motion
         * is frame-rate independent. */
        {
            uint32_t now = lv_tick_get();
            float dt = (now - last_frame_tick) / 1000.0f;
            if (dt <= 0.0f) dt = 0.001f;
            if (dt > 0.1f) dt = 0.1f;   /* clamp after a long stall (e.g. ANR) */
            last_frame_tick = now;
            lvgl_anim::Engine::instance().tick(dt);
        }

        /* If LVGL asks for a shorter wait than our tick, honour it up to 50ms. */
        if (since > 0 && since < 50 && !engine->paused.load()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(since));
        }
    }
    LOGI("LVGL thread stopped");
}

/* ---- Engine construction / teardown -------------------------------------- */

static bool init_display(Engine* engine) {
    lv_init();
    lv_log_register_print_cb(lv_log_to_logcat);
    lv_tick_set_cb(tick_get_cb);

    engine->display = lv_display_create(engine->dispWidth, engine->dispHeight);
    if (!engine->display) {
        engine->setError("lv_display_create failed");
        return false;
    }
    lv_display_set_user_data(engine->display, engine);

    const uint32_t buf_size = engine->dispWidth * engine->dispHeight * 4;

    engine->fb1.reset(new (std::nothrow) uint8_t[buf_size]());
    engine->fb2.reset(new (std::nothrow) uint8_t[buf_size]());
    if (!engine->fb1 || !engine->fb2) {
        engine->setError("framebuffer allocation failed");
        lv_display_delete(engine->display);
        engine->display = nullptr;
        return false;
    }

    lv_display_set_buffers(engine->display, engine->fb1.get(), engine->fb2.get(),
                           buf_size, LV_DISPLAY_RENDER_MODE_DIRECT);
    lv_display_set_flush_cb(engine->display, display_flush_cb);
    lv_display_set_dpi(engine->display, engine->dpi);

    engine->indev = lv_indev_create();
    if (!engine->indev) {
        engine->setError("lv_indev_create failed");
        lv_display_delete(engine->display);
        engine->display = nullptr;
        engine->fb1.reset();
        engine->fb2.reset();
        return false;
    }
    lv_indev_set_type(engine->indev, LV_INDEV_TYPE_POINTER);
    lv_indev_set_read_cb(engine->indev, touch_read_cb);
    lv_indev_set_user_data(engine->indev, engine);

    engine->initialized = true;
    return true;
}

static void shutdown_display(Engine* engine) {
    if (engine->display) {
        lv_display_delete(engine->display);
        engine->display = nullptr;
    }
    if (engine->indev) {
        lv_indev_delete(engine->indev);
        engine->indev = nullptr;
    }
    engine->fb1.reset();
    engine->fb2.reset();
    if (engine->nativeWindow) {
        ANativeWindow_release(engine->nativeWindow);
        engine->nativeWindow = nullptr;
    }
    if (engine->pendingWindow) {
        ANativeWindow_release(engine->pendingWindow);
        engine->pendingWindow = nullptr;
    }
    engine->pendingWindowSet = false;
    engine->windowValid = false;
    engine->initialized = false;
    {
        std::lock_guard<std::mutex> state_lock(engine->stateMutex);
        engine->lastStatusText.clear();
        engine->lastStatusScreen = -1;
    }
    engine->taskQueue.clear();
}

} /* namespace lvgl_android */

using namespace lvgl_android;

/* ---- JNI plumbing --------------------------------------------------------- */

static JavaVM* g_vm = nullptr;
static jobject g_navRef = nullptr;          // global ref to Kotlin ScreenNavigator
static jmethodID g_midNavTo = nullptr;       // navigateTo(int)
static jmethodID g_midNavBack = nullptr;     // navigateBack()
/* Optional system-control methods (dashboard backlight / volume panels).
 * Resolved only when the registered navigator implements them. */
static jmethodID g_midSetBrightness = nullptr;   // setBrightness(float)
static jmethodID g_midSetVolume = nullptr;       // setVolumePercent(int)
static jmethodID g_midToggleDarkMode = nullptr;  // toggleDarkMode()

static JNIEnv* jniEnv() {
    JNIEnv* env = nullptr;
    if (!g_vm) return nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
        return env;
    }
    // Attach the current (LVGL) thread to the JVM if it isn't already.
    JavaVMAttachArgs args = { JNI_VERSION_1_6, "lvgl-port", nullptr };
    if (g_vm->AttachCurrentThread(&env, &args) != JNI_OK) {
        return nullptr;
    }
    return env;
}

static Engine* fromHandle(jlong handle) {
    return reinterpret_cast<Engine*>(static_cast<intptr_t>(handle));
}

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" {

/* ---- Engine lifetime ----------------------------------------------------- */

JNIEXPORT jlong JNICALL
Java_com_lvgl_android_engine_LvglEngine_nativeCreate(JNIEnv* /*env*/, jobject /*thiz*/) {
    auto* engine = new Engine();
    /* Wire navigation hooks so LVGL event callbacks can request screen
     * switches / back presses from native code. The system hooks drive the
     * dashboard's backlight / volume panels (see BC-023). */
    ui_set_nav_hooks(nav_to_hook, nav_back_hook);
    ui_set_system_hooks(sys_set_brightness, sys_set_volume);
    ui_set_theme_hook(toggle_dark_mode);
    LOGI("Engine created");
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_com_lvgl_android_engine_LvglEngine_nativeDestroy(JNIEnv* /*env*/, jobject /*thiz*/,
                                                          jlong handle) {
    auto* engine = fromHandle(handle);
    if (!engine) return;

    engine->stopping = true;
    engine->running = false;
    engine->cv.notify_all();
    if (engine->lvglThread.joinable()) {
        engine->lvglThread.join();
    }
    lvgl_anim::Engine::instance().reset();

    if (engine->initialized) {
        shutdown_display(engine);
    }
    if (g_activeEngine == engine) g_activeEngine = nullptr;
    /* Release the Kotlin navigator global ref so it does not outlive the engine. */
    if (g_navRef) {
        if (JNIEnv* env = jniEnv()) {
            env->DeleteGlobalRef(g_navRef);
        }
        g_navRef = nullptr;
    }
    g_midNavTo = nullptr;
    g_midNavBack = nullptr;
    g_midSetBrightness = nullptr;
    g_midSetVolume = nullptr;
    lv_deinit();
    delete engine;
    LOGI("Engine destroyed");
}

/* ---- Display init / deinit ---------------------------------------------- */

JNIEXPORT void JNICALL
Java_com_lvgl_android_engine_LvglEngine_nativeInitDisplay(JNIEnv* env, jobject /*thiz*/,
                                                         
                                                           jlong handle, jobject surface,
                                                           jint width, jint height, jint dpi) {
    auto* engine = fromHandle(handle);
    if (!engine) return;
    LOGI("nativeInitDisplay: %dx%d dpi=%d", width, height, dpi);

    if (width <= 0 || height <= 0) {
        engine->setError("invalid dimensions");
        return;
    }

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) {
        engine->setError("ANativeWindow_fromSurface failed");
        return;
    }
    if (ANativeWindow_setBuffersGeometry(window, width, height,
                                         WINDOW_FORMAT_RGBA_8888) < 0) {
        engine->setError("ANativeWindow_setBuffersGeometry failed");
        ANativeWindow_release(window);
        return;
    }

    const bool same_size = engine->initialized && engine->display &&
                           engine->dispWidth  == static_cast<uint32_t>(width) &&
                           engine->dispHeight == static_cast<uint32_t>(height);

    if (same_size) {
        /* Fast path (the common case when switching activities): keep the
         * display, screen and LVGL thread alive and only rebind the surface.
         * This is what makes each activity show its own screen immediately
         * instead of rebuilding/re-destroying the display. */
        {
            std::lock_guard<std::mutex> state_lock(engine->stateMutex);
            engine->dpi = dpi > 0 ? dpi : engine->dpi;
            if (engine->display) lv_display_set_dpi(engine->display, engine->dpi);
        }
        {
            std::lock_guard<std::mutex> lock(engine->mutex);
            if (engine->pendingWindow && engine->pendingWindow != window) {
                ANativeWindow_release(engine->pendingWindow);
            }
            engine->pendingWindow = window;
            engine->pendingWindowSet = true;
        }
        engine->paused = false;
        engine->cv.notify_all();
        LOGI("display surface rebound %dx%d", width, height);
        return;
    }

    /* First init, or a genuine size change: rebuild the display + framebuffers. */
    if (engine->initialized) {
        engine->stopping = true;
        engine->running  = false;
        engine->cv.notify_all();
        if (engine->lvglThread.joinable()) {
            engine->lvglThread.join();
        }
        shutdown_display(engine);
    }

    engine->dispWidth  = static_cast<uint32_t>(width);
    engine->dispHeight = static_cast<uint32_t>(height);
    engine->stopping   = false;
    {
        std::lock_guard<std::mutex> state_lock(engine->stateMutex);
        engine->dpi = dpi > 0 ? dpi : 440;
    }
    /* Release a stale pending window before overwriting it (leak fix). */
    if (engine->pendingWindow && engine->pendingWindow != window) {
        ANativeWindow_release(engine->pendingWindow);
    }
    engine->pendingWindow = window;
    engine->pendingWindowSet = true;

    int screen_to_build;
    {
        std::lock_guard<std::mutex> state_lock(engine->stateMutex);
        screen_to_build = engine->pendingScreenId < 0 ? engine->currentScreenId
                                                      : engine->pendingScreenId;
    }
    {
        std::lock_guard<std::mutex> lock(engine->mutex);
        if (!init_display(engine)) {
            shutdown_display(engine);
            return;
        }
        /* Build the requested screen (or the menu by default), applying theme. */
        rebuild_screen(engine, screen_to_build);
    }

    g_activeEngine = engine;
    engine->running = true;
    engine->paused = false;
    engine->lvglThread = std::thread(lvgl_thread_func, engine);
    LOGI("LVGL initialised, screen=%d", engine->currentScreenId);
}

static void scheduleTask(Engine* engine, std::function<void()> task) {
    {
        std::lock_guard<std::mutex> lock(engine->mutex);
        engine->taskQueue.push_back(std::move(task));
    }
    engine->cv.notify_all();
}

JNIEXPORT void JNICALL
Java_com_lvgl_android_engine_LvglEngine_nativeDeinitDisplay(JNIEnv* /*env*/, jobject /*thiz*/,
                                                          jlong handle) {
    auto* engine = fromHandle(handle);
    if (!engine) return;
    LOGI("nativeDeinitDisplay");

    /* The surface is going away (activity paused/stopped). Stop drawing to it,
     * but KEEP the display, current screen and LVGL thread alive so the next
     * activity can simply rebind its surface. Joining the render thread from
     * the UI thread here is what used to wedge the app (start-timeout ANR). */
    engine->windowValid = false;
    engine->paused = true;
    engine->cv.notify_all();
    LOGI("LVGL surface released");
}

/* ---- Pause / resume ------------------------------------------------------ */

JNIEXPORT void JNICALL
Java_com_lvgl_android_engine_LvglEngine_nativeResume(JNIEnv* /*env*/, jobject /*thiz*/,
                                                          jlong handle) {
    auto* engine = fromHandle(handle);
    if (!engine) return;
    engine->paused = false;
    engine->cv.notify_all();
    LOGI("LVGL resumed");
}

JNIEXPORT void JNICALL
Java_com_lvgl_android_engine_LvglEngine_nativePause(JNIEnv* /*env*/, jobject /*thiz*/,
                                                          jlong handle) {
    auto* engine = fromHandle(handle);
    if (!engine) return;
    engine->paused = true;
    LOGI("LVGL paused");
}

/* ---- Touch --------------------------------------------------------------- */

JNIEXPORT void JNICALL
Java_com_lvgl_android_engine_LvglEngine_nativeTouchEvent(JNIEnv* /*env*/, jobject /*thiz*/,
                                                         
                                                          jlong handle, jint action, jfloat x, jfloat y) {
    auto* engine = fromHandle(handle);
    if (!engine) return;
    std::lock_guard<std::mutex> lock(engine->touchMutex);
    engine->touchState = (action == 0 || action == 2)
                             ? LV_INDEV_STATE_PRESSED : LV_INDEV_STATE_RELEASED;
    engine->touchPoint.x = static_cast<lv_coord_t>(x);
    engine->touchPoint.y = static_cast<lv_coord_t>(y);
    engine->touchNew = true;
    if (action == 0) engine->touchPressSeen = false;
}

/* ---- Screen / status / theme -------------------------------------------- */

JNIEXPORT void JNICALL
Java_com_lvgl_android_engine_LvglEngine_nativeSetScreen(JNIEnv* /*env*/, jobject /*thiz*/,
                                                         
                                                         jlong handle, jint screenId) {
    auto* engine = fromHandle(handle);
    if (!engine) return;
    /* Built-in screens are 0..7; app-defined screens start at APP_SCREEN_BASE. */
    if (screenId < 0 || (screenId >= SCREEN_COUNT && screenId < APP_SCREEN_BASE)) return;
    LOGI("nativeSetScreen: %d", screenId);

    /* NOTE: never call scheduleTask() while holding engine->mutex — it locks
     * the same non-recursive mutex and deadlocks the UI thread (the original
     * start-timeout ANR). */
    bool has_display;
    {
        std::lock_guard<std::mutex> lock(engine->mutex);
        has_display = engine->display != nullptr;
    }
    {
        std::lock_guard<std::mutex> state_lock(engine->stateMutex);
        /* Always remember the request so a display (re)initialisation shows it. */
        engine->pendingScreenId = screenId;
    }
    if (!has_display) return;
    scheduleTask(engine, [engine, screenId]() { rebuild_screen(engine, screenId); });
}

JNIEXPORT void JNICALL
Java_com_lvgl_android_engine_LvglEngine_nativeSetStatusText(JNIEnv* env, jobject /*thiz*/,
                                                         
                                                             jlong handle, jstring text) {
    auto* engine = fromHandle(handle);
    if (!engine) return;
    if (!text) return;
    const char* raw = env->GetStringUTFChars(text, nullptr);
    if (!raw) return;

    std::string copy(raw);
    env->ReleaseStringUTFChars(text, raw);

    /* Updating a label must happen on the LVGL thread. */
    scheduleTask(engine, [engine, copy]() {
        if (!engine->display) return;
        {
            std::lock_guard<std::mutex> state_lock(engine->stateMutex);
            /* Remember the text against the screen it belongs to so a later screen
             * rebuild (onResume re-applies the screen) restores it instead of
             * wiping live status. */
            engine->lastStatusText = copy;
            engine->lastStatusScreen = engine->currentScreenId;
        }
        /* demo_ui stores the status label in the screen's user data. */
        lv_obj_t* screen = lv_screen_active();
        if (!screen) return;
        lv_obj_t* status = static_cast<lv_obj_t*>(lv_obj_get_user_data(screen));
        if (status) {
            lv_label_set_text(status, copy.c_str());
            lv_obj_invalidate(status);
        }
    });
}

JNIEXPORT void JNICALL
Java_com_lvgl_android_engine_LvglEngine_nativeSetBatteryLevel(JNIEnv* /*env*/, jobject /*thiz*/,
                                                              jlong handle, jint level,
                                                              jboolean charging) {
    auto* engine = fromHandle(handle);
    if (!engine) return;
    scheduleTask(engine, [engine, level, charging]() {
        if (!engine->display) return;
        ui_set_battery_level(static_cast<int>(level), charging);
    });
}

JNIEXPORT void JNICALL
Java_com_lvgl_android_engine_LvglEngine_nativeApplyTheme(JNIEnv* env, jobject /*thiz*/,
                                                         jlong handle, jboolean darkMode,
                                                          jintArray palette) {
    auto* engine = fromHandle(handle);
    if (!engine) return;

    UiTheme theme;
    theme.dark_mode = static_cast<bool>(darkMode);
    theme.applied   = true;

    /* Copy the Material 3 tonal roles; missing entries keep their defaults. */
    if (palette) {
        jsize n = env->GetArrayLength(palette);
        if (n > MATERIAL_ROLE_COUNT) n = MATERIAL_ROLE_COUNT;
        jint tmp[MATERIAL_ROLE_COUNT] = {0};
        env->GetIntArrayRegion(palette, 0, n, tmp);
        for (int i = 0; i < n; i++) {
            theme.roles[i] = static_cast<int>(tmp[i]);
        }
    }
    theme.primary   = theme.roles[MATERIAL_PRIMARY];
    theme.secondary = theme.roles[MATERIAL_SECONDARY];

    /* Never scheduleTask() under engine->mutex (deadlock — see nativeSetScreen). */
    bool changed;
    bool has_display;
    {
        std::lock_guard<std::mutex> lock(engine->mutex);
        has_display = engine->display != nullptr;
    }
    {
        std::lock_guard<std::mutex> state_lock(engine->stateMutex);
        changed = !engine->theme.applied ||
                  engine->theme.dark_mode != theme.dark_mode ||
                  engine->theme.primary   != theme.primary;
        engine->theme = theme;
    }
    /* Rebuilding is only needed when the palette actually changed; otherwise
     * this would re-run on every onResume for no reason. */
    if (!changed || !has_display) return;
    scheduleTask(engine, [engine]() {
        /* Rebuild the current screen so the full palette re-applies (not just
         * the background colour, which is all ui_apply_theme used to do). */
        rebuild_screen(engine, engine->currentScreenId);
    });
}

/* ---- Introspection (used by tests) -------------------------------------- */

JNIEXPORT jboolean JNICALL
Java_com_lvgl_android_engine_LvglEngine_nativeIsDisplayReady(JNIEnv* /*env*/, jobject /*thiz*/,
                                                          jlong handle) {
    auto* engine = fromHandle(handle);
    return engine && engine->initialized && engine->display;
}

JNIEXPORT jint JNICALL
Java_com_lvgl_android_engine_LvglEngine_nativeGetScreen(JNIEnv* /*env*/, jobject /*thiz*/,
                                                          jlong handle) {
    auto* engine = fromHandle(handle);
    if (!engine) return -1;
    std::lock_guard<std::mutex> lock(engine->stateMutex);
    return engine->currentScreenId;
}

JNIEXPORT jintArray JNICALL
Java_com_lvgl_android_engine_LvglEngine_nativeCaptureFrame(JNIEnv* env, jobject /*thiz*/,
                                                          jlong handle) {
    auto* engine = fromHandle(handle);
    if (!engine || !engine->initialized || !engine->fb1) return nullptr;
    std::lock_guard<std::mutex> lock(engine->mutex);
    const jsize n = static_cast<jsize>(engine->dispWidth * engine->dispHeight);
    jintArray out = env->NewIntArray(n);
    if (!out) return nullptr;
    /* Return the raw (BGRA) framebuffer as 0xRRGGBB ints for test analysis. */
    const uint32_t* fb = reinterpret_cast<const uint32_t*>(engine->fb1.get());
    std::unique_ptr<jint[]> buf(new (std::nothrow) jint[n]);
    if (!buf) return nullptr;
    for (jsize i = 0; i < n; i++) {
        uint32_t c = fb[i];
        /* Convert BGRA in memory to an 0xRRGGBB int. */
        buf[i] = static_cast<jint>(((c >> 16) & 0xFF) << 16 |
                                   ((c >> 8)  & 0xFF) << 8  |
                                   (c & 0xFF));
    }
    env->SetIntArrayRegion(out, 0, n, buf.get());
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_lvgl_android_engine_LvglEngine_nativeGetLastError(JNIEnv* env, jobject /*thiz*/,
                                                          jlong handle) {
    auto* engine = fromHandle(handle);
    if (!engine) return env->NewStringUTF("");
    return env->NewStringUTF(engine->lastError);
}

/* Dump the current screen's layout lint as JSON. The walk must happen on the
 * LVGL thread, so the request is queued and the caller waits for the result. */
JNIEXPORT jstring JNICALL
Java_com_lvgl_android_engine_LvglEngine_nativeDumpLayoutReport(JNIEnv* env, jobject /*thiz*/,
                                                               jlong handle) {
    auto* engine = fromHandle(handle);
    if (!engine || !engine->display) return env->NewStringUTF("{\"issues\":[]}");

    std::mutex m;
    std::condition_variable cv;
    bool done = false;
    std::string result;
    scheduleTask(engine, [&]() {
        char buf[2048];
        ui_layout_report_json(lv_screen_active(), buf, sizeof(buf));
        result = buf;
        {
            std::lock_guard<std::mutex> lk(m);
            done = true;
        }
        cv.notify_all();
    });
    std::unique_lock<std::mutex> lk(m);
    cv.wait_for(lk, std::chrono::seconds(2), [&] { return done; });
    return env->NewStringUTF(result.c_str());
}

/* Store the real device facts for the dashboard device map. Plain string copy:
 * no LVGL objects are touched here, so it is safe from the UI thread. */
JNIEXPORT void JNICALL
Java_com_lvgl_android_engine_LvglEngine_nativeSetDeviceInfo(JNIEnv* env, jobject /*thiz*/,
                                                            jlong handle,
                                                            jstring manufacturer,
                                                            jstring model,
                                                            jstring soc,
                                                            jstring memory,
                                                            jstring storage,
                                                            jstring display,
                                                            jstring androidVersion,
                                                            jstring cameras,
                                                            jstring sensors) {
    auto* engine = fromHandle(handle);
    if (!engine) return;

    jstring fields[9] = { manufacturer, model, soc, memory, storage, display,
                          androidVersion, cameras, sensors };
    std::lock_guard<std::mutex> lk(engine->deviceInfoMutex);
    for (int i = 0; i < 9; i++) {
        const char* raw = fields[i] ? env->GetStringUTFChars(fields[i], nullptr) : nullptr;
        engine->devInfo[i] = raw ? raw : "";
        if (raw) env->ReleaseStringUTFChars(fields[i], raw);
    }
}

/* ---- Navigation bridge (native -> Kotlin) --------------------------------- */
/*
 * The LVGL menu cards and back buttons live in native code but must drive
 * Android activity navigation. Kotlin registers a ScreenNavigator; the engine
 * calls into it from the LVGL thread whenever a card/button is tapped.
 */

JNIEXPORT void JNICALL
Java_com_lvgl_android_engine_LvglEngine_nativeSetNavCallback(JNIEnv* env, jobject /*thiz*/,
                                                          jlong /*handle*/, jobject navigator) {
    if (g_navRef) {
        env->DeleteGlobalRef(g_navRef);
        g_navRef = nullptr;
    }
    g_midNavTo = nullptr;
    g_midNavBack = nullptr;
    g_midSetBrightness = nullptr;
    g_midSetVolume = nullptr;
    g_midToggleDarkMode = nullptr;
    if (!navigator) return;
    g_navRef = env->NewGlobalRef(navigator);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        g_navRef = nullptr;
        return;
    }
    jclass cls = env->GetObjectClass(g_navRef);
    if (!cls || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return;
    }
    g_midNavTo   = env->GetMethodID(cls, "navigateTo", "(I)V");
    if (env->ExceptionCheck()) env->ExceptionClear();
    g_midNavBack = env->GetMethodID(cls, "navigateBack", "()V");
    if (env->ExceptionCheck()) env->ExceptionClear();
    /* Optional: not every navigator implements the system controls. */
    g_midSetBrightness = env->GetMethodID(cls, "setBrightness", "(F)V");
    if (env->ExceptionCheck()) env->ExceptionClear();
    g_midSetVolume = env->GetMethodID(cls, "setVolumePercent", "(I)V");
    if (env->ExceptionCheck()) env->ExceptionClear();
    /* Optional: not every navigator implements the theme toggle. */
    g_midToggleDarkMode = env->GetMethodID(cls, "toggleDarkMode", "()V");
    if (env->ExceptionCheck()) env->ExceptionClear();
    LOGI("navigation callback registered%s",
         (g_midSetBrightness && g_midSetVolume) ? " (+system controls)" : "");
}

/* ---- App-defined screen bridge (Kotlin -> native) ------------------------- */

static void set_ui_listener(JNIEnv* env, jobject listener);

static void set_launcher_listener(JNIEnv* env, jobject listener);

JNIEXPORT void JNICALL
Java_com_lvgl_android_engine_LvglEngine_nativeRegisterScreen(JNIEnv* env, jobject /*thiz*/,
                                                             jlong handle, jint id,
                                                             jstring title,
                                                             jboolean show_back,
                                                             jstring json) {
    auto* engine = fromHandle(handle);
    if (!engine) return;
    const char* t = env->GetStringUTFChars(title, nullptr);
    const char* j = env->GetStringUTFChars(json, nullptr);
    ui_register_app_screen(static_cast<int>(id), t ? t : "",
                           show_back != 0, j ? j : "");
    if (t) env->ReleaseStringUTFChars(title, t);
    if (j) env->ReleaseStringUTFChars(json, j);
}

JNIEXPORT void JNICALL
Java_com_lvgl_android_engine_LvglEngine_nativeUnregisterScreen(JNIEnv* /*env*/, jobject /*thiz*/,
                                                               jlong handle, jint id) {
    auto* engine = fromHandle(handle);
    if (!engine) return;
    ui_unregister_app_screen(static_cast<int>(id));
}

JNIEXPORT void JNICALL
Java_com_lvgl_android_engine_LvglEngine_nativeUpdateNode(JNIEnv* env, jobject /*thiz*/,
                                                         jlong handle, jint screen_id,
                                                         jstring node_id, jstring json) {
    auto* engine = fromHandle(handle);
    if (!engine) return;
    const char* n = env->GetStringUTFChars(node_id, nullptr);
    const char* j = env->GetStringUTFChars(json, nullptr);
    /* LVGL objects may only be touched on the LVGL thread. */
    scheduleTask(engine, [engine, screen_id = static_cast<int>(screen_id),
                          node = std::string(n ? n : ""),
                          spec = std::string(j ? j : "")]() {
        ui_update_node(screen_id, node.c_str(), spec.c_str());
    });
    if (n) env->ReleaseStringUTFChars(node_id, n);
    if (j) env->ReleaseStringUTFChars(json, j);
}

JNIEXPORT void JNICALL
Java_com_lvgl_android_engine_LvglEngine_nativeSetUiListener(JNIEnv* env, jobject /*thiz*/,
                                                            jlong /*handle*/,
                                                            jobject listener) {
    set_ui_listener(env, listener);
}

JNIEXPORT void JNICALL
Java_com_lvgl_android_engine_LvglEngine_nativeSetLauncherApps(JNIEnv* env, jobject /*thiz*/,
                                                              jlong handle,
                                                              jobjectArray labels,
                                                              jobjectArray packages) {
    auto* engine = fromHandle(handle);
    if (!engine) return;
    const jsize n = labels ? env->GetArrayLength(labels) : 0;
    std::vector<std::string> lbls;
    std::vector<std::string> pkgs;
    lbls.reserve(static_cast<size_t>(n));
    pkgs.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; i++) {
        jstring l = static_cast<jstring>(env->GetObjectArrayElement(labels, i));
        jstring p = static_cast<jstring>(env->GetObjectArrayElement(packages, i));
        const char* lc = l ? env->GetStringUTFChars(l, nullptr) : nullptr;
        const char* pc = p ? env->GetStringUTFChars(p, nullptr) : nullptr;
        lbls.emplace_back(lc ? lc : "");
        pkgs.emplace_back(pc ? pc : "");
        if (lc) env->ReleaseStringUTFChars(l, lc);
        if (pc) env->ReleaseStringUTFChars(p, pc);
        if (l) env->DeleteLocalRef(l);
        if (p) env->DeleteLocalRef(p);
    }
    std::vector<const char*> lbl_ptrs;
    std::vector<const char*> pkg_ptrs;
    lbl_ptrs.reserve(lbls.size());
    pkg_ptrs.reserve(pkgs.size());
    for (const auto& s : lbls) lbl_ptrs.push_back(s.c_str());
    for (const auto& s : pkgs) pkg_ptrs.push_back(s.c_str());
    ui_set_launcher_apps(lbl_ptrs.data(), pkg_ptrs.data(), static_cast<int>(n));
}

JNIEXPORT void JNICALL
Java_com_lvgl_android_engine_LvglEngine_nativeSetLauncherListener(JNIEnv* env, jobject /*thiz*/,
                                                                  jlong /*handle*/,
                                                                  jobject listener) {
    set_launcher_listener(env, listener);
}

static bool nav_valid() {
    return g_vm && g_navRef && g_midNavTo && g_midNavBack;
}

static void nav_to(int screenId) {
    JNIEnv* env = jniEnv();
    if (!env) return;
    env->CallVoidMethod(g_navRef, g_midNavTo, static_cast<jint>(screenId));
    if (env->ExceptionCheck()) env->ExceptionClear();
}

static void nav_back() {
    JNIEnv* env = jniEnv();
    if (!env) return;
    env->CallVoidMethod(g_navRef, g_midNavBack);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

/* System-control bridge used by the dashboard's backlight / volume panels.
 * The Kotlin side applies the real Android window / AudioManager change. */
static void sys_set_brightness(int percent) {
    JNIEnv* env = jniEnv();
    if (!env || !g_navRef || !g_midSetBrightness) return;
    env->CallVoidMethod(g_navRef, g_midSetBrightness,
                        static_cast<jfloat>(percent / 100.0f));
    if (env->ExceptionCheck()) {
        LOGE("setBrightness JNI call failed");
        env->ExceptionClear();
    }
}

static void sys_set_volume(int percent) {
    JNIEnv* env = jniEnv();
    if (!env || !g_navRef || !g_midSetVolume) return;
    env->CallVoidMethod(g_navRef, g_midSetVolume, static_cast<jint>(percent));
    if (env->ExceptionCheck()) env->ExceptionClear();
}

/* Theme bridge used by the dashboard's theme FAB. The Kotlin side flips the
 * app-local dark-mode override and pushes the new palette to LVGL. */
static void toggle_dark_mode() {
    JNIEnv* env = jniEnv();
    if (!env || !g_navRef || !g_midToggleDarkMode) return;
    env->CallVoidMethod(g_navRef, g_midToggleDarkMode);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

/* ---- App-defined screen bridge (native -> Kotlin) ------------------------- */
/*
 * App screens declared in Kotlin (LvglUi) emit their taps and value changes
 * through here. The listener is a LvglUiEventReceiver, whose onUiEvent method
 * is resolved once and cached.
 */
static jobject  g_uiListener = nullptr;
static jmethodID g_midUiEvent = nullptr;

static void set_ui_listener(JNIEnv* env, jobject listener) {
    if (g_uiListener) {
        env->DeleteGlobalRef(g_uiListener);
        g_uiListener = nullptr;
    }
    g_midUiEvent = nullptr;
    if (!listener) return;
    g_uiListener = env->NewGlobalRef(listener);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        g_uiListener = nullptr;
        return;
    }
    jclass cls = env->GetObjectClass(g_uiListener);
    if (!cls || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return;
    }
    g_midUiEvent = env->GetMethodID(cls, "onUiEvent",
                                    "(ILjava/lang/String;Ljava/lang/String;I)V");
    if (env->ExceptionCheck()) env->ExceptionClear();
}

} /* extern "C" */

namespace lvgl_android {

/* Defined outside extern "C" so it matches the C++ declaration in demo_ui.h;
 * the JNI listener state above is file-static and therefore reachable. */
void ui_emit_event(int screen_id, const char* node_id, const char* type, int value) {
    if (!g_vm || !g_uiListener || !g_midUiEvent) return;
    JNIEnv* env = jniEnv();
    if (!env) return;
    jstring jid   = env->NewStringUTF(node_id ? node_id : "");
    jstring jtype = env->NewStringUTF(type ? type : "");
    env->CallVoidMethod(g_uiListener, g_midUiEvent, static_cast<jint>(screen_id),
                        jid, jtype, static_cast<jint>(value));
    env->DeleteLocalRef(jid);
    env->DeleteLocalRef(jtype);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

} /* namespace lvgl_android */

/* ---- Launcher app selection (native -> Kotlin) ---------------------------- */
static jobject  g_launcherListener = nullptr;
static jmethodID g_midAppSelected = nullptr;

static void set_launcher_listener(JNIEnv* env, jobject listener) {
    if (g_launcherListener) {
        env->DeleteGlobalRef(g_launcherListener);
        g_launcherListener = nullptr;
    }
    g_midAppSelected = nullptr;
    if (!listener) return;
    g_launcherListener = env->NewGlobalRef(listener);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        g_launcherListener = nullptr;
        return;
    }
    jclass cls = env->GetObjectClass(g_launcherListener);
    if (!cls || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return;
    }
    g_midAppSelected = env->GetMethodID(cls, "onAppSelected", "(Ljava/lang/String;)V");
    if (env->ExceptionCheck()) env->ExceptionClear();
}

namespace lvgl_android {

void ui_emit_launcher_event(const char* package_name) {
    if (!g_vm || !g_launcherListener || !g_midAppSelected) return;
    JNIEnv* env = jniEnv();
    if (!env) return;
    jstring jpkg = env->NewStringUTF(package_name ? package_name : "");
    env->CallVoidMethod(g_launcherListener, g_midAppSelected, jpkg);
    env->DeleteLocalRef(jpkg);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

} /* namespace lvgl_android */
