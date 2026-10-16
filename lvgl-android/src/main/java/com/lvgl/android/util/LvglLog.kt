// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.util

import android.util.Log

object LvglLog {
    var level: Int = Log.DEBUG

    fun d(tag: String, message: String) {
        if (level <= Log.DEBUG) Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        if (level <= Log.INFO) Log.i(tag, message)
    }

    fun w(tag: String, message: String) {
        if (level <= Log.WARN) Log.w(tag, message)
    }

    fun e(tag: String, message: String) {
        if (level <= Log.ERROR) Log.e(tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable) {
        if (level <= Log.ERROR) Log.e(tag, message, throwable)
    }
}

