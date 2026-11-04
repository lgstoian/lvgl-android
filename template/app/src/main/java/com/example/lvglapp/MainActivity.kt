// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.

package com.example.lvglapp

import com.lvgl.android.activity.LvglActivity
import com.lvgl.android.engine.LvglEngine

/**
 * Minimal LVGL activity.
 *
 * The public Kotlin API is screen-level: onCreateLvgl() selects one of the
 * built-in LVGL screens. Screen 1 (Widget Showcase) shows an LVGL label and a
 * "Tap me" button; see docs/quickstart.md and docs/public-api.md. Do not call
 * setContentView() from an LvglActivity subclass.
 */
class MainActivity : LvglActivity() {

    override fun onCreateLvgl() {
        setLvglScreen(1)
        LvglEngine.instance.setStatusText("Hello, LVGL!")
    }
}
