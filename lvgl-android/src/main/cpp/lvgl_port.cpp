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
#include <cstdint>
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
    /* Target frame interval from LvglMotionConfig.refreshHz, in microseconds.
     * The render loop paces to one deadline from this value; 16667 us = 60 Hz.
     * @invariant 1000us <= frameIntervalUs <= 33334us (240..30 Hz) */
    std::atomic<uint32_t> frameIntervalUs{16667};
    /* Monotonic surface-bind generation. Every nativeInitDisplay bumps it. A
     * surfaceDestroyed delivered late can then be recognised as stale (its
     * bind has been superseded) and must not blank the newer surface. */
    std::atomic<uint64_t> bindGeneration{0};

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
    /* Kotlin forwards ACTION_POINTER_DOWN/UP through the same (action,x,y)
     * entry point. Track the FIRST down as the primary pointer so a second
     * finger cannot hijack the single-point indev mid-gesture. */
    bool touchPrimaryActive = false;

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

    /* ROM-derived motion config (guarded by stateMutex). Applied to the indev
     * on display init and on a runtime config push. @see LvglMotionConfig */
    lvgl_android::lvgl_anim::MotionConfig motion;
    bool motionSet = false;

    /* Last status text pushed from Kotlin + the screen it belongs to. Re-applied
     * by rebuild_screen() so an onResume rebuild never wipes live status. */
    std::string lastStatusText;
    int lastStatusScreen = -1;

    /* Battery level (0..100, -1 = unknown) for the dashboard status bar. */
    int  batteryLevel = -1;
    bool batteryCharging = false;

    /* Real device facts for the dashboard device map. Written from the UI
     * thread, copied onto the LVGL thread by rebuild_screen() / the refresh
     * task. Index order is the nativeSetDeviceInfo JNI argument order. */
    std::mutex deviceInfoMutex;
    std::string devInfo[15];

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

/* A snapshot owns its strings so ui_set_device_info() never receives pointers
 * into engine->devInfo after deviceInfoMutex has been released (the UI thread
 * can overwrite those strings at any time). */
struct DeviceInfoSnapshot {
    std::string value[15];

    UiDeviceInfo as_ui() const {
        UiDeviceInfo info = {};
        info.manufacturer  = value[0].c_str();
        info.model         = value[1].c_str();
        info.soc           = value[2].c_str();
        info.memory        = value[3].c_str();
        info.storage       = value[4].c_str();
        info.display       = value[5].c_str();
        info.android_version = value[6].c_str();
        info.cameras       = value[7].c_str();
        info.sensors       = value[8].c_str();
        info.modem         = value[9].c_str();
        info.network       = value[10].c_str();
        info.battery_tech  = value[11].c_str();
        info.audio         = value[12].c_str();
        info.usb           = value[13].c_str();
        info.bluetooth     = value[14].c_str();
        return info;
    }
};

static DeviceInfoSnapshot snapshot_device_info(Engine* engine) {
    DeviceInfoSnapshot out;
    std::lock_guard<std::mutex> lk(engine->deviceInfoMutex);
    for (int i = 0; i < 15; i++) out.value[i] = engine->devInfo[i];
    return out;
}

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
     * a dying render thread (fixes the System UI ANR). `paused` is deliberately
     * NOT checked here: a stale onPause from a previous activity must never
     * blank a freshly bound surface. `windowValid` (cleared by
     * nativeDeinitDisplay) is the authority on whether a surface is drawable. */
    if (engine->stopping.load()) {
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
    /* Re-check after the lock: teardown may have begun while we were blocked
     * in ANativeWindow_lock. Always hand the buffer back so the render thread
     * can exit and the UI thread's join cannot wedge (BC-002). */
    if (engine->stopping.load() || !engine->windowValid.load()) {
        ANativeWindow_unlockAndPost(engine->nativeWindow);
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

    if (dst_stride_px == src_stride_px && copy_w == engine->dispWidth) {
        /* Common case: the window is at least as wide as the display and both
         * buffers are tightly packed. A narrower window has a shorter row but
         * the same stride, so it must take the row-by-row path below. */
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
    if (screenId < 0 || (screenId >= SCREEN_COUNT && screenId < APP_SCREEN_BASE)) return;
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
    int id;
    {
        std::lock_guard<std::mutex> lock(engine->navMutex);
        id = engine->navRequest;
        engine->navRequest = -1;
    }
    if (id == -1) return;
    __android_log_print(ANDROID_LOG_INFO, "LVGLPort", "drainNav id=%d valid=%d", id, nav_valid());
    /* Call Kotlin without holding navMutex: a JNI callback that re-enters
     * native (e.g. a screen rebuild) would deadlock on the same mutex. */
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
    /* Push the real device facts onto the LVGL thread before the build. Snapshot
     * first: without this, a concurrent push from Kotlin could reallocate a
     * string while ui_set_device_info() was reading its c_str(). */
    DeviceInfoSnapshot device_info = snapshot_device_info(engine);
    ui_set_device_info(device_info.as_ui());
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

/* Apply the ROM-sampled animation path to framework-driven LVGL transitions.
 * LVGL creates scroll and style-transition animations with its own built-in
 * paths; rebind only those generic easing paths to fast_out_slow_in and leave
 * deliberate shape paths (step/overshoot/bounce/custom) untouched. Walking the
 * live screen each frame keeps this automatic for every consumer app — no
 * demo-code dependency — and catches animations started after config changes. */
static void apply_motion_path_to_transitions(lv_obj_t* obj) {
    if (!obj) return;
    lv_anim_t* anim = lv_anim_get(obj, nullptr);
    if (anim) {
        const lv_anim_path_cb_t cb = anim->path_cb;
        if (cb == lv_anim_path_linear || cb == lv_anim_path_ease_in ||
            cb == lv_anim_path_ease_out || cb == lv_anim_path_ease_in_out) {
            lvgl_anim::apply_motion_path(anim);
        }
    }
    const uint32_t count = lv_obj_get_child_count(obj);
    for (uint32_t i = 0; i < count; i++) {
        apply_motion_path_to_transitions(lv_obj_get_child(obj, i));
    }
}

static void lvgl_thread_func(Engine* engine) {
    LOGI("LVGL thread started");
    /* Small delay to let the surface settle before the first frame. */
    std::this_thread::sleep_for(std::chrono::milliseconds(20));

    /* Single monotonic deadline. The old loop did cv.wait_for(16ms) followed by
     * an extra sleep_for(since) when an LVGL timer was due — two independent
     * sleeps per iteration produced irregular frame intervals and jerky
     * scroll/fling. One deadline now drives exactly one lv_timer_handler()
     * call; work arriving early is drained without rendering an extra frame. */
    using clock = std::chrono::steady_clock;
    auto deadline = clock::now();
    auto last_frame = deadline;

    /* Reset the animation engine so stale animations from a prior session
     * (e.g. an activity that was destroyed without draining) never replay.
     * The showcase screen is built before the thread starts and its frame
     * callback is still live, so keep that callback in this one case. */
    bool showcase_built;
    {
        std::lock_guard<std::mutex> state_lock(engine->stateMutex);
        showcase_built = engine->currentScreenId == SCREEN_SHOWCASE;
    }
    lvgl_anim::Engine::instance().reset(showcase_built);

    while (engine->running.load()) {
        {
            std::unique_lock<std::mutex> lock(engine->mutex);
            engine->cv.wait_until(lock, deadline, [&] {
                return !engine->taskQueue.empty() ||
                       (engine->pendingWindowSet && !engine->stopping.load()) ||
                       !engine->running.load();
            });
        }
        if (!engine->running.load()) break;

        /* Install a newly bound surface (activity transition) before rendering.
         * This replaces the old per-activity display teardown/recreate, which
         * both showed the wrong screen and stalled the UI thread (ANR). */
        if (engine->pendingWindowSet) {
            std::lock_guard<std::mutex> lock(engine->mutex);
            if (engine->pendingWindowSet && !engine->stopping.load() &&
                engine->running.load()) {
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

        /* A stale onPause from the outgoing activity can arrive after the new
         * surface was bound. `paused` must not defer tasks, timers, navigation
         * or input polling: the flush callback already gates drawing on
         * windowValid, so the engine stays alive and responsive (S4). */

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

        if (!engine->running.load()) break;

        /* Flush any pending native->Kotlin navigation requests. */
        drainNavRequests(engine);

        const auto frame_start = clock::now();
        if (frame_start < deadline) {
            /* Woken by a task/window before the frame deadline: everything is
             * already drained, so just go back to the SAME wait. No second
             * sleep and no busy loop — wait_until blocks until the deadline. */
            continue;
        }

        /* Framework transitions use the ROM interpolator shape. @why LVGL
         * hardcodes ease_out for scroll/throw and style transitions; the sampled
         * curve is what makes the whole UI feel like the device's own motion. */
        if (lv_screen_active()) apply_motion_path_to_transitions(lv_screen_active());

        /* One LVGL tick per deadline. The returned "time to next timer" is no
         * longer used as an extra sleep; that was the pacing bug. */
        (void)lv_timer_handler();

        /* Advance the animation engine by the real frame delta so spring motion
         * is frame-rate independent. Use the same monotonic clock that paces
         * the frame, not the 32-bit LVGL tick. */
        float dt = std::chrono::duration<float>(frame_start - last_frame).count();
        last_frame = frame_start;
        if (dt <= 0.0f) dt = 0.001f;
        if (dt > 0.1f) dt = 0.1f;   /* clamp after a long stall (e.g. ANR) */
        lvgl_anim::Engine::instance().tick(dt);

        /* Advance by exactly one interval. If a frame overran, drop the missed
         * deadlines instead of catching up with a burst (capped catch-up). */
        const auto interval = std::chrono::microseconds(engine->frameIntervalUs.load());
        deadline += interval;
        const auto now = clock::now();
        if (deadline <= now) deadline = now + interval;
    }
    LOGI("LVGL thread stopped");
}

/* ---- Engine construction / teardown -------------------------------------- */

static bool init_display(Engine* engine) {
    /* LVGL is process-wide: a genuine size change tears down the display but
     * NOT the library. Calling lv_init() again logs "already initialized" and
     * can leave stale global state (the black-surface race). */
    static bool s_lvInitialized = false;
    if (!s_lvInitialized) {
        lv_init();
        lv_log_register_print_cb(lv_log_to_logcat);
        lv_tick_set_cb(tick_get_cb);
        s_lvInitialized = true;
    }

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

    /* ROM-adaptive fling: Android friction -> LVGL scroll_throw (percent of
     * velocity lost per frame), touch slop -> scroll limit, min fling velocity
     * -> gesture minimum velocity. The defaults apply when Kotlin has not
     * pushed a snapshot yet. */
    {
        std::lock_guard<std::mutex> state_lock(engine->stateMutex);
        lv_indev_set_scroll_throw(engine->indev, engine->motion.scroll_throw);
        lv_indev_set_scroll_limit(engine->indev, engine->motion.scroll_limit);
        lv_indev_set_gesture_min_velocity(engine->indev, engine->motion.gesture_min_velocity);
        uint32_t interval_us = (uint32_t)std::lround(engine->motion.frame_interval_ms * 1000.0f);
        if (interval_us < 1000) interval_us = 1000;
        if (interval_us > 33334) interval_us = 33334;
        engine->frameIntervalUs.store(interval_us);
        LOGI("LVGL motion applied: scrollThrow=%u%% scrollLimit=%u gestureMinVelocity=%u "
             "decay=%.3f frameInterval=%.2fms refresh=%.1fHz",
             (unsigned)engine->motion.scroll_throw, (unsigned)engine->motion.scroll_limit,
             (unsigned)engine->motion.gesture_min_velocity, engine->motion.decay,
             engine->motion.frame_interval_ms, engine->motion.refresh_hz);
    }

    engine->initialized = true;
    return true;
}

static void shutdown_display(Engine* engine) {
    /* Free showcase-owned LVGL objects and their frame callback BEFORE LVGL
     * deletes the display/screens. After lv_display_delete() they would be
     * dangling pointers and the next animation tick would use freed memory. */
    ui_reset_showcase();
    lvgl_anim::Engine::instance().reset();
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
/* Cached callbacks live in g_navCallback / g_uiCallback / g_launcherCallback
 * above; every access is guarded by g_jniMutex. */

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

/* All cached JNI global references and method IDs are written from the UI
 * thread and read from the LVGL thread. Guard them with one mutex and hand out
 * shared_ptr snapshots: a listener replaced mid-dispatch stays alive until the
 * LVGL thread has finished its JNI call (C2). */
static std::mutex g_jniMutex;

struct NavCallback {
    jobject ref = nullptr;
    jmethodID to = nullptr;
    jmethodID back = nullptr;
    jmethodID brightness = nullptr;
    jmethodID volume = nullptr;
    jmethodID theme = nullptr;
    ~NavCallback() {
        if (ref) {
            if (JNIEnv* e = jniEnv()) e->DeleteGlobalRef(ref);
        }
    }
};

struct UiCallback {
    jobject ref = nullptr;
    jmethodID event = nullptr;
    ~UiCallback() {
        if (ref) {
            if (JNIEnv* e = jniEnv()) e->DeleteGlobalRef(ref);
        }
    }
};

struct LauncherCallback {
    jobject ref = nullptr;
    jmethodID selected = nullptr;
    ~LauncherCallback() {
        if (ref) {
            if (JNIEnv* e = jniEnv()) e->DeleteGlobalRef(ref);
        }
    }
};

static std::shared_ptr<NavCallback> g_navCallback;
static std::shared_ptr<UiCallback> g_uiCallback;
static std::shared_ptr<LauncherCallback> g_launcherCallback;

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
    /* No render thread can tick the callback now. Drop it before lv_deinit(),
     * even when no display was ever created. */
    ui_reset_showcase();
    lvgl_anim::Engine::instance().reset();

    if (engine->initialized) {
        shutdown_display(engine);
    }
    if (g_activeEngine == engine) g_activeEngine = nullptr;
    /* Drop cached global refs once no render thread can dispatch them. */
    {
        std::lock_guard<std::mutex> lock(g_jniMutex);
        g_navCallback.reset();
        g_uiCallback.reset();
        g_launcherCallback.reset();
    }
    lv_deinit();
    delete engine;
    LOGI("Engine destroyed");
}

/* ---- Display init / deinit ---------------------------------------------- */

static void scheduleTask(Engine* engine, std::function<void()> task);

JNIEXPORT void JNICALL
Java_com_lvgl_android_engine_LvglEngine_nativeInitDisplay(JNIEnv* env, jobject /*thiz*/,
                                                         
                                                           jlong handle, jobject surface,
                                                           jint width, jint height, jint dpi) {
    auto* engine = fromHandle(handle);
    if (!engine) return;
    LOGI("nativeInitDisplay: %dx%d dpi=%d", width, height, dpi);
    /* A live bind wins over any queued stale deinit/pause. The generation lets
     * the deinit path below detect that it no longer owns the surface. */
    const uint64_t generation = engine->bindGeneration.fetch_add(1) + 1;
    LOGI("surface bind generation=%llu", (unsigned long long)generation);

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
        bool dpi_changed = false;
        {
            std::lock_guard<std::mutex> state_lock(engine->stateMutex);
            const int new_dpi = dpi > 0 ? dpi : engine->dpi;
            dpi_changed = engine->dpi != new_dpi;
            engine->dpi = new_dpi;
            if (engine->display) lv_display_set_dpi(engine->display, engine->dpi);
        }
        {
            std::lock_guard<std::mutex> lock(engine->mutex);
            /* C7: fromSurface returns a NEW reference. When the very same
             * ANativeWindow pointer is reacquired we already own a reference —
             * drop the extra one instead of leaking it. */
            if (window == engine->pendingWindow || window == engine->nativeWindow) {
                ANativeWindow_release(window);
            } else if (engine->pendingWindow) {
                ANativeWindow_release(engine->pendingWindow);
            }
            engine->pendingWindow = window;
            engine->pendingWindowSet = true;
        }
        /* A stale deinit may have set stopping/paused and cleared windowValid.
         * Clear all three here so flush resumes immediately on the very next
         * tick instead of waiting for a further lifecycle callback (BC-002). */
        engine->stopping    = false;
        engine->paused      = false;
        engine->windowValid = true;
        g_activeEngine      = engine;
        engine->cv.notify_all();
        /* DPI drives spacing and fonts, so a density change needs a rebuild
         * even when the pixel size is unchanged (S5). */
        if (dpi_changed) {
            int screen_id;
            {
                std::lock_guard<std::mutex> state_lock(engine->stateMutex);
                screen_id = engine->pendingScreenId < 0 ? engine->currentScreenId
                                                        : engine->pendingScreenId;
            }
            scheduleTask(engine, [engine, screen_id]() {
                rebuild_screen(engine, screen_id);
            });
        }
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
        shutdown_display(engine);   /* releases the previous window reference */
    }

    engine->dispWidth  = static_cast<uint32_t>(width);
    engine->dispHeight = static_cast<uint32_t>(height);
    engine->stopping   = false;
    {
        std::lock_guard<std::mutex> state_lock(engine->stateMutex);
        engine->dpi = dpi > 0 ? dpi : 440;
    }
    /* shutdown_display cleared any stale pending window; install the freshly
     * acquired reference (do not drop it even if the pointer matches the old
     * nativeWindow — that reference was just released above). */
    {
        std::lock_guard<std::mutex> lock(engine->mutex);
        engine->pendingWindow = window;
        engine->pendingWindowSet = true;
    }

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

    /* A newer surface can already be bound before Android delivers
     * surfaceDestroyed for the outgoing activity. Never release that pending
     * window or blank its flush: the Kotlin owner check normally prevents this
     * call, but the native guard keeps it a no-op even if a stale callback
     * gets through. @breadcrumb BC-001 */
    bool newerSurfaceBound;
    {
        std::lock_guard<std::mutex> lock(engine->mutex);
        newerSurfaceBound = engine->pendingWindowSet && engine->running.load() &&
                            engine->initialized.load();
    }
    if (newerSurfaceBound) {
        LOGI("nativeDeinitDisplay ignored: newer surface already bound (gen=%llu)",
             (unsigned long long)engine->bindGeneration.load());
        return;
    }
    LOGI("nativeDeinitDisplay");

    /* The surface is going away (activity paused/stopped). Stop drawing to it,
     * but KEEP the display, current screen and LVGL thread alive so the next
     * activity can simply rebind its surface. Joining the render thread from
     * the UI thread here is what used to wedge the app (start-timeout ANR). */
    /* A surface that is being destroyed may have left a pending window behind
     * (initDisplay queued it but the render thread never installed it). Release
     * it here, otherwise it leaks and can be installed onto the next surface. */
    {
        std::lock_guard<std::mutex> lock(engine->mutex);
        if (engine->pendingWindow) {
            ANativeWindow_release(engine->pendingWindow);
            engine->pendingWindow = nullptr;
        }
        engine->pendingWindowSet = false;
    }
    engine->windowValid = false;
    engine->paused = true;
    /* Do NOT set `stopping` here. It is only needed by the full-rebuild path
     * (which sets it before joining the render thread). Setting it on a plain
     * surface release defers the pending-window install on the render thread
     * and makes display_flush_cb bail, which is exactly how a stale deinit
     * used to blank a freshly bound surface (BC-002). nativeInitDisplay also
     * clears it on every bind. */
    /* Only forget the engine when no new surface has taken over; a stale
     * deinit must not clear the bridge used by the live activity (BC-001). */
    bool has_surface;
    {
        std::lock_guard<std::mutex> lock(engine->mutex);
        has_surface = engine->pendingWindowSet;
    }
    if (!has_surface && g_activeEngine == engine) g_activeEngine = nullptr;
    engine->cv.notify_all();
    LOGI("LVGL surface released");
}

/* ---- ROM-adaptive motion -------------------------------------------------- */

JNIEXPORT void JNICALL
Java_com_lvgl_android_engine_LvglEngine_nativeSetMotionConfig(JNIEnv* env, jobject /*thiz*/,
                                                             jlong handle, jfloatArray values) {
    auto* engine = fromHandle(handle);
    if (!engine) return;

    constexpr int kMotionWireSize = 14 + lvgl_android::lvgl_anim::INTERPOLATOR_SAMPLES;
    float wire[kMotionWireSize] = {0};
    jsize n = values ? env->GetArrayLength(values) : 0;
    if (n > kMotionWireSize) n = kMotionWireSize;
    if (n > 0) env->GetFloatArrayRegion(values, 0, n, wire);
    const auto cfg = lvgl_android::lvgl_anim::MotionConfig::from_wire(wire, n);

    {
        std::lock_guard<std::mutex> lock(engine->stateMutex);
        engine->motion     = cfg;
        engine->motionSet  = true;
    }
    uint32_t interval_us = (uint32_t)std::lround(cfg.frame_interval_ms * 1000.0f);
    if (interval_us < 1000) interval_us = 1000;
    if (interval_us > 33334) interval_us = 33334;
    engine->frameIntervalUs.store(interval_us);
    LOGI("ROM motion received: animatorScale=%.3f mid=%.3f refresh=%.1fHz interval=%.2fms "
         "springs short=%.1f/%.1f medium=%.1f/%.1f long=%.1f/%.1f",
         cfg.animator_scale, cfg.fast_out_slow_in_mid, cfg.refresh_hz,
         cfg.frame_interval_ms,
         cfg.short_spring.stiffness, cfg.short_spring.damping,
         cfg.medium_spring.stiffness, cfg.medium_spring.damping,
         cfg.long_spring.stiffness, cfg.long_spring.damping);

    /* Spring re-rolling and indev updates touch LVGL objects, so run them on
     * the render thread. The cached struct above is used by init_display(). */
    scheduleTask(engine, [engine, cfg]() {
        ui_apply_motion_config(cfg);
        if (engine->indev) {
            lv_indev_set_scroll_throw(engine->indev, cfg.scroll_throw);
            lv_indev_set_scroll_limit(engine->indev, cfg.scroll_limit);
            lv_indev_set_gesture_min_velocity(engine->indev, cfg.gesture_min_velocity);
        }
    });
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
    /* A stale onPause from an outgoing activity can arrive after the new
     * surface was bound. There is nothing drawable to pause in that window;
     * leaving `paused` untouched keeps the flag from being set by a writer
     * that no longer owns the display (BC-001). */
    if (!engine->windowValid.load() || engine->stopping.load()) {
        LOGI("LVGL pause ignored: no active surface");
        return;
    }
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
    if (action == 0) {
        /* ACTION_DOWN starts a gesture; a secondary ACTION_POINTER_DOWN while
         * the primary is still down must not move the point (C13). */
        if (engine->touchPrimaryActive) return;
        engine->touchPrimaryActive = true;
        engine->touchPressSeen = false;
        engine->touchPoint.x = static_cast<lv_coord_t>(x);
        engine->touchPoint.y = static_cast<lv_coord_t>(y);
        engine->touchState = LV_INDEV_STATE_PRESSED;
    } else if (action == 2) {
        /* ACTION_MOVE is delivered with pointerIndex 0 (the primary pointer). */
        if (!engine->touchPrimaryActive) return;
        engine->touchPoint.x = static_cast<lv_coord_t>(x);
        engine->touchPoint.y = static_cast<lv_coord_t>(y);
        engine->touchState = LV_INDEV_STATE_PRESSED;
    } else {
        /* ACTION_UP / POINTER_UP / CANCEL releases the primary pointer. Kotlin
         * maps POINTER_UP to the same action, so ignore an UP that lands far
         * from the tracked point: that is a secondary finger lifting. */
        const int tolerance = engine->dpi > 0 ? engine->dpi / 10 : 16;
        const int dx = static_cast<int>(x) - static_cast<int>(engine->touchPoint.x);
        const int dy = static_cast<int>(y) - static_cast<int>(engine->touchPoint.y);
        if (engine->touchPrimaryActive &&
            (dx < -tolerance || dx > tolerance || dy < -tolerance || dy > tolerance)) {
            return;
        }
        engine->touchPrimaryActive = false;
        engine->touchState = LV_INDEV_STATE_RELEASED;
    }
    engine->touchNew = true;
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
        /* demo_ui clears this pointer in launch_screen() before every build,
         * so it never refers to a label from a previously built screen. */
        lv_obj_t* status = ui_current_status_label();
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
        /* Compare every tonal role, not just primary: a Material You update
         * can change surfaces/text/container roles while primary stays equal
         * (S10). */
        changed = !engine->theme.applied ||
                  engine->theme.dark_mode != theme.dark_mode;
        for (int i = 0; !changed && i < MATERIAL_ROLE_COUNT; i++) {
            changed = engine->theme.roles[i] != theme.roles[i];
        }
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

    /* Own the result in a shared_ptr: a timed-out caller must not leave the
     * queued lambda holding dangling references to stack locals (C3). */
    struct ReportSlot {
        std::mutex m;
        std::condition_variable cv;
        bool done = false;
        std::string result = "{\"issues\":[]}";
    };
    auto slot = std::make_shared<ReportSlot>();
    scheduleTask(engine, [slot]() {
        char buf[2048];
        ui_layout_report_json(lv_screen_active(), buf, sizeof(buf));
        {
            std::lock_guard<std::mutex> lk(slot->m);
            slot->result = buf;
            slot->done = true;
        }
        slot->cv.notify_all();
    });
    std::unique_lock<std::mutex> lk(slot->m);
    slot->cv.wait_for(lk, std::chrono::seconds(2), [slot] { return slot->done; });
    return env->NewStringUTF(slot->result.c_str());
}

/* Store the real device facts for the dashboard device map. Plain string copy:
 * no LVGL objects are touched here, so it is safe from the UI thread. Exactly
 * 15 fields, in the order declared by the Kotlin bridge. */
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
                                                            jstring sensors,
                                                             jstring modem,
                                                             jstring network,
                                                             jstring batteryTech,
                                                             jstring audio,
                                                             jstring usb,
                                                             jstring bluetooth) {
    auto* engine = fromHandle(handle);
    if (!engine) return;

    jstring fields[15] = {
        manufacturer, model, soc, memory, storage, display, androidVersion,
        cameras, sensors, modem, network, batteryTech, audio, usb, bluetooth
    };
    std::lock_guard<std::mutex> lk(engine->deviceInfoMutex);
    for (int i = 0; i < 15; i++) {
        const char* raw = fields[i] ? env->GetStringUTFChars(fields[i], nullptr) : nullptr;
        engine->devInfo[i] = raw ? raw : "";
        if (raw) env->ReleaseStringUTFChars(fields[i], raw);
    }
}

/* Hardware detection can complete after the dashboard was built (permissions,
 * async callbacks). Re-push the cached strings and rebuild the active screen
 * on the LVGL thread so the device map shows the real values. */
JNIEXPORT void JNICALL
Java_com_lvgl_android_engine_LvglEngine_nativeRefreshDeviceInfo(JNIEnv* /*env*/,
                                                                jobject /*thiz*/,
                                                                jlong handle) {
    auto* engine = fromHandle(handle);
    if (!engine) return;
    bool has_display;
    {
        std::lock_guard<std::mutex> lock(engine->mutex);
        has_display = engine->display != nullptr;
    }
    if (!has_display) return;
    scheduleTask(engine, [engine]() {
        if (!engine->display) return;
        DeviceInfoSnapshot device_info = snapshot_device_info(engine);
        ui_set_device_info(device_info.as_ui());
        int screen_id;
        {
            std::lock_guard<std::mutex> state_lock(engine->stateMutex);
            screen_id = engine->currentScreenId;
        }
        rebuild_screen(engine, screen_id);
    });
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
    auto next = std::make_shared<NavCallback>();
    if (navigator) {
        next->ref = env->NewGlobalRef(navigator);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            next->ref = nullptr;
        }
    }
    if (next->ref) {
        jclass cls = env->GetObjectClass(next->ref);
        if (!cls || env->ExceptionCheck()) {
            if (env->ExceptionCheck()) env->ExceptionClear();
        } else {
            next->to = env->GetMethodID(cls, "navigateTo", "(I)V");
            if (env->ExceptionCheck()) env->ExceptionClear();
            next->back = env->GetMethodID(cls, "navigateBack", "()V");
            if (env->ExceptionCheck()) env->ExceptionClear();
            /* Optional: not every navigator implements the system controls. */
            next->brightness = env->GetMethodID(cls, "setBrightness", "(F)V");
            if (env->ExceptionCheck()) env->ExceptionClear();
            next->volume = env->GetMethodID(cls, "setVolumePercent", "(I)V");
            if (env->ExceptionCheck()) env->ExceptionClear();
            next->theme = env->GetMethodID(cls, "toggleDarkMode", "()V");
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    }
    const bool has_system = next->brightness && next->volume;
    {
        std::lock_guard<std::mutex> lock(g_jniMutex);
        g_navCallback = std::move(next);
    }
    LOGI("navigation callback registered%s", has_system ? " (+system controls)" : "");
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
    const jsize label_count = labels ? env->GetArrayLength(labels) : 0;
    const jsize package_count = packages ? env->GetArrayLength(packages) : 0;
    /* The two arrays come from separate Kotlin collections; never assume they
     * have the same length (C14). */
    const jsize n = label_count < package_count ? label_count : package_count;
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
        if (env->ExceptionCheck()) env->ExceptionClear();
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

static std::shared_ptr<NavCallback> nav_snapshot() {
    std::lock_guard<std::mutex> lock(g_jniMutex);
    return g_navCallback;
}

static bool nav_valid() {
    if (!g_vm) return false;
    auto cb = nav_snapshot();
    return cb && cb->ref && cb->to && cb->back;
}

static void nav_to(int screenId) {
    auto cb = nav_snapshot();
    if (!cb || !cb->ref || !cb->to) return;
    JNIEnv* env = jniEnv();
    if (!env) return;
    /* Copy the reference and method id locally: the shared_ptr keeps the
     * global ref alive even if Kotlin re-registers during this call (C2). */
    jobject ref = cb->ref;
    jmethodID mid = cb->to;
    env->CallVoidMethod(ref, mid, static_cast<jint>(screenId));
    if (env->ExceptionCheck()) env->ExceptionClear();
}

static void nav_back() {
    auto cb = nav_snapshot();
    if (!cb || !cb->ref || !cb->back) return;
    JNIEnv* env = jniEnv();
    if (!env) return;
    jobject ref = cb->ref;
    jmethodID mid = cb->back;
    env->CallVoidMethod(ref, mid);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

/* System-control bridge used by the dashboard's backlight / volume panels.
 * The Kotlin side applies the real Android window / AudioManager change. */
static void sys_set_brightness(int percent) {
    auto cb = nav_snapshot();
    if (!cb || !cb->ref || !cb->brightness) return;
    JNIEnv* env = jniEnv();
    if (!env) return;
    jobject ref = cb->ref;
    jmethodID mid = cb->brightness;
    env->CallVoidMethod(ref, mid, static_cast<jfloat>(percent / 100.0f));
    if (env->ExceptionCheck()) {
        LOGE("setBrightness JNI call failed");
        env->ExceptionClear();
    }
}

static void sys_set_volume(int percent) {
    auto cb = nav_snapshot();
    if (!cb || !cb->ref || !cb->volume) return;
    JNIEnv* env = jniEnv();
    if (!env) return;
    jobject ref = cb->ref;
    jmethodID mid = cb->volume;
    env->CallVoidMethod(ref, mid, static_cast<jint>(percent));
    if (env->ExceptionCheck()) env->ExceptionClear();
}

/* Theme bridge used by the dashboard's theme FAB. The Kotlin side flips the
 * app-local dark-mode override and pushes the new palette to LVGL. */
static void toggle_dark_mode() {
    auto cb = nav_snapshot();
    if (!cb || !cb->ref || !cb->theme) return;
    JNIEnv* env = jniEnv();
    if (!env) return;
    jobject ref = cb->ref;
    jmethodID mid = cb->theme;
    env->CallVoidMethod(ref, mid);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

/* ---- App-defined screen bridge (native -> Kotlin) ------------------------- */
/*
 * App screens declared in Kotlin (LvglUi) emit their taps and value changes
 * through here. The listener is a LvglUiEventReceiver, whose onUiEvent method
 * is resolved once and cached.
 */
static void set_ui_listener(JNIEnv* env, jobject listener) {
    auto next = std::make_shared<UiCallback>();
    if (listener) {
        next->ref = env->NewGlobalRef(listener);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            next->ref = nullptr;
        }
    }
    if (next->ref) {
        jclass cls = env->GetObjectClass(next->ref);
        if (!cls || env->ExceptionCheck()) {
            if (env->ExceptionCheck()) env->ExceptionClear();
        } else {
            next->event = env->GetMethodID(cls, "onUiEvent",
                                           "(ILjava/lang/String;Ljava/lang/String;I)V");
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    }
    {
        std::lock_guard<std::mutex> lock(g_jniMutex);
        g_uiCallback = std::move(next);
    }
}

} /* extern "C" */

namespace lvgl_android {

/* Defined outside extern "C" so it matches the C++ declaration in demo_ui.h;
 * the JNI listener state above is file-static and therefore reachable. */
void ui_emit_event(int screen_id, const char* node_id, const char* type, int value) {
    if (!g_vm) return;
    std::shared_ptr<UiCallback> cb;
    {
        std::lock_guard<std::mutex> lock(g_jniMutex);
        cb = g_uiCallback;
    }
    if (!cb || !cb->ref || !cb->event) return;
    JNIEnv* env = jniEnv();
    if (!env) return;
    jobject ref = cb->ref;
    jmethodID mid = cb->event;
    jstring jid   = env->NewStringUTF(node_id ? node_id : "");
    jstring jtype = env->NewStringUTF(type ? type : "");
    env->CallVoidMethod(ref, mid, static_cast<jint>(screen_id),
                        jid, jtype, static_cast<jint>(value));
    env->DeleteLocalRef(jid);
    env->DeleteLocalRef(jtype);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

} /* namespace lvgl_android */

/* ---- Launcher app selection (native -> Kotlin) ---------------------------- */
static void set_launcher_listener(JNIEnv* env, jobject listener) {
    auto next = std::make_shared<LauncherCallback>();
    if (listener) {
        next->ref = env->NewGlobalRef(listener);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            next->ref = nullptr;
        }
    }
    if (next->ref) {
        jclass cls = env->GetObjectClass(next->ref);
        if (!cls || env->ExceptionCheck()) {
            if (env->ExceptionCheck()) env->ExceptionClear();
        } else {
            next->selected = env->GetMethodID(cls, "onAppSelected", "(Ljava/lang/String;)V");
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    }
    {
        std::lock_guard<std::mutex> lock(g_jniMutex);
        g_launcherCallback = std::move(next);
    }
}

namespace lvgl_android {

void ui_emit_launcher_event(const char* package_name) {
    if (!g_vm) return;
    std::shared_ptr<LauncherCallback> cb;
    {
        std::lock_guard<std::mutex> lock(g_jniMutex);
        cb = g_launcherCallback;
    }
    if (!cb || !cb->ref || !cb->selected) return;
    JNIEnv* env = jniEnv();
    if (!env) return;
    jobject ref = cb->ref;
    jmethodID mid = cb->selected;
    jstring jpkg = env->NewStringUTF(package_name ? package_name : "");
    env->CallVoidMethod(ref, mid, jpkg);
    env->DeleteLocalRef(jpkg);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

} /* namespace lvgl_android */
