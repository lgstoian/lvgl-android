/*
 * Copyright (c) 2026 LVGL Android Framework Contributors
 * SPDX-License-Identifier: MIT
 *
 * LVGL 9.6 uses a compile-time LV_ASSERT_HANDLER macro (see
 * include/lvgl/debugging/lv_assert.h). This header is referenced by the
 * LV_ASSERT_CUSTOM_INCLUDE compile definition so that LVGL assertions are
 * routed to logcat instead of halting the process.
 */
#pragma once

#include <android/log.h>

#define LV_ASSERT_HANDLER                                                       \
    do {                                                                        \
        __android_log_print(ANDROID_LOG_ERROR, "LVGL",                          \
                            "LVGL assertion failed");                           \
    } while (0)
