// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.util

import android.util.Log

/**
 * Process-wide logging facade in front of [android.util.Log].
 *
 * All framework classes log through this object so the verbosity can be
 * changed in one place. [level] follows the [android.util.Log] constants: a
 * message is emitted when [level] is at least as verbose as the message's
 * level, so the default `Log.DEBUG` emits everything.
 */
object LvglLog {
    /** Minimum severity that is logged; defaults to [android.util.Log.DEBUG]. */
    var level: Int = Log.DEBUG

    /** Logs [message] at debug level when [level] allows it. */
    fun d(tag: String, message: String) {
        if (level <= Log.DEBUG) Log.d(tag, message)
    }

    /** Logs [message] at info level when [level] allows it. */
    fun i(tag: String, message: String) {
        if (level <= Log.INFO) Log.i(tag, message)
    }

    /** Logs [message] at warning level when [level] allows it. */
    fun w(tag: String, message: String) {
        if (level <= Log.WARN) Log.w(tag, message)
    }

    /** Logs [message] at error level when [level] allows it. */
    fun e(tag: String, message: String) {
        if (level <= Log.ERROR) Log.e(tag, message)
    }

    /** Logs [message] with [throwable] at error level when [level] allows it. */
    fun e(tag: String, message: String, throwable: Throwable) {
        if (level <= Log.ERROR) Log.e(tag, message, throwable)
    }
}

