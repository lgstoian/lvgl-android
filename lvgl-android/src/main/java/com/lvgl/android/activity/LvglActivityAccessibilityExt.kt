// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.activity

import android.view.KeyEvent
import com.lvgl.android.accessibility.LvglAccessibilityManager
import com.lvgl.android.accessibility.LvglFocusNavigation
import com.lvgl.android.accessibility.LvglScreenReader

/**
 * Installs the accessibility bridges on this activity.
 *
 * Creates a [LvglAccessibilityManager] and a [LvglFocusNavigation] and, when
 * accessibility is enabled, initialises [LvglScreenReader] and routes
 * D-pad/keyboard focus from the decor view. Returns the helpers so the caller
 * can keep using them (for example to announce focus changes).
 *
 * Call from `onCreate` on the UI thread, after the content view is set up.
 *
 * @return the helpers bound to this activity.
 */
fun LvglActivity.setupAccessibility(): AccessibilitySetup {
    val accManager = LvglAccessibilityManager(this)
    val focusNavigation = LvglFocusNavigation(this)

    if (accManager.isEnabled()) {
        LvglScreenReader.initialize(this)
        /* Drive D-pad/keyboard focus navigation from the decor view. */
        window.decorView.setOnKeyListener { _, keyCode, event ->
            event.action == KeyEvent.ACTION_DOWN && focusNavigation.handleKeyEvent(keyCode)
        }
    }
    return AccessibilitySetup(accManager, focusNavigation)
}

/**
 * Holds the accessibility helpers so callers can keep using them.
 *
 * @property manager the accessibility service wrapper.
 * @property focusNavigation the D-pad/keyboard focus navigator.
 */
data class AccessibilitySetup(
    val manager: LvglAccessibilityManager,
    val focusNavigation: LvglFocusNavigation
)

