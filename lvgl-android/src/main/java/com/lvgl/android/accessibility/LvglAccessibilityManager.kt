// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.accessibility

import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import com.lvgl.android.engine.LvglEngine
import java.util.UUID

/**
 * Manages accessibility features for the LVGL Android framework.
 * Provides TalkBack support, content descriptions, and focus navigation.
 */
class LvglAccessibilityManager(private val context: Context) {

    private val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    private val listeners = mutableListOf<OnAccessibilityChangedListener>()

    interface OnAccessibilityChangedListener {
        fun onAccessibilityEnabled(enabled: Boolean)
        fun onAccessibilityDisabled()
    }

    fun isEnabled(): Boolean = manager.isEnabled

    fun isTouchExplorationEnabled(): Boolean = manager.isTouchExplorationEnabled

    fun addListener(listener: OnAccessibilityChangedListener) {
        listeners.add(listener)
        manager.addAccessibilityStateChangeListener { enabled ->
            if (enabled) listeners.forEach { it.onAccessibilityEnabled(true) }
            else listeners.forEach { it.onAccessibilityDisabled() }
        }
    }

    fun removeListener(listener: OnAccessibilityChangedListener) {
        listeners.remove(listener)
    }

    fun announce(text: String) {
        if (isTouchExplorationEnabled()) {
            LvglAccessibilityHelper.announce(context, text)
        }
    }

    fun setFocusable(viewId: String, focusable: Boolean) {
        LvglAccessibilityHelper.setFocusable(viewId, focusable)
    }

    fun setContentDescription(viewId: String, description: String) {
        LvglAccessibilityHelper.setContentDescription(viewId, description)
    }

    companion object {
        private const val TAG = "LvglAccessibilityManager"
    }
}

