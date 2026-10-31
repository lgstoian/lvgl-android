// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.sample.showcase

import android.os.Bundle
import com.lvgl.android.activity.LvglActivity

/**
 * Animated showcase — the M5Tab5-inspired research vehicle.
 *
 * This is the home for experimenting with spring-physics UI: animated launcher
 * grids, NumberFlow counters, spring-based controls. Everything is rendered in
 * LVGL through the lvgl_anim engine; see lvgl-android/src/main/cpp/lvgl_anim.h.
 *
 * Rules (see ai-context.md): LvglActivity subclasses must NOT call setContentView().
 */
class DemoShowcaseActivity : LvglActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateLvgl() {
        setLvglScreen(SCREEN_SHOWCASE)
    }

    companion object {
        private const val SCREEN_SHOWCASE = 7
    }
}
