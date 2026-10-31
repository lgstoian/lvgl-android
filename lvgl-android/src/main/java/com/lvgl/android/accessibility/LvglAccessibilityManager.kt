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
    private var systemListener: AccessibilityManager.AccessibilityStateChangeListener? = null
    private var systemListenerCount = 0

    interface OnAccessibilityChangedListener {
        fun onAccessibilityEnabled(enabled: Boolean)
        fun onAccessibilityDisabled()
    }

    fun isEnabled(): Boolean = manager.isEnabled

    fun isTouchExplorationEnabled(): Boolean = manager.isTouchExplorationEnabled

    fun addListener(listener: OnAccessibilityChangedListener) {
        listeners.add(listener)
        /* Register the system listener once and keep it for as long as at least
         * one wrapper is registered; registering per-call leaks system listeners. */
        if (systemListener == null) {
            val l = AccessibilityManager.AccessibilityStateChangeListener { enabled ->
                if (enabled) listeners.forEach { it.onAccessibilityEnabled(true) }
                else listeners.forEach { it.onAccessibilityDisabled() }
            }
            systemListener = l
            manager.addAccessibilityStateChangeListener(l)
        }
        systemListenerCount++
    }

    fun removeListener(listener: OnAccessibilityChangedListener) {
        listeners.remove(listener)
        if (systemListenerCount > 0) systemListenerCount--
        if (systemListenerCount <= 0) {
            systemListener?.let { manager.removeAccessibilityStateChangeListener(it) }
            systemListener = null
            systemListenerCount = 0
        }
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

