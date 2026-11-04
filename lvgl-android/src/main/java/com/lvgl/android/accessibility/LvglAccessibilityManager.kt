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
 *
 * Wraps the platform [AccessibilityManager], exposes the current state, and
 * forwards state changes to registered [OnAccessibilityChangedListener]s.
 * Only one system state-change listener is registered at a time and it is
 * removed once the last wrapper listener is gone, so there is no leak when
 * callers come and go.
 *
 * The manager holds a [Context]; keep the lifetime of the manager no longer
 * than the owning activity when a non-application context is passed.
 */
class LvglAccessibilityManager(private val context: Context) {

    private val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    private val listeners = mutableListOf<OnAccessibilityChangedListener>()
    private var systemListener: AccessibilityManager.AccessibilityStateChangeListener? = null
    private var systemListenerCount = 0

    /** Receives accessibility service enable/disable notifications. */
    interface OnAccessibilityChangedListener {
        /** Called when the accessibility service is enabled; [enabled] is `true`. */
        fun onAccessibilityEnabled(enabled: Boolean)

        /** Called when the accessibility service is disabled. */
        fun onAccessibilityDisabled()
    }

    /** Whether any accessibility service is currently enabled. */
    fun isEnabled(): Boolean = manager.isEnabled

    /** Whether touch exploration (TalkBack-style interaction) is enabled. */
    fun isTouchExplorationEnabled(): Boolean = manager.isTouchExplorationEnabled

    /**
     * Registers [listener] for accessibility state changes.
     *
     * The system listener is installed on the first registration and removed
     * when the last listener is removed.
     */
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

    /** Unregisters a previously added [listener]; safe if it was never added. */
    fun removeListener(listener: OnAccessibilityChangedListener) {
        listeners.remove(listener)
        if (systemListenerCount > 0) systemListenerCount--
        if (systemListenerCount <= 0) {
            systemListener?.let { manager.removeAccessibilityStateChangeListener(it) }
            systemListener = null
            systemListenerCount = 0
        }
    }

    /**
     * Announces [text] to the user when touch exploration is enabled.
     *
     * Announcements are dropped when touch exploration is off, because there
     * is no screen reader to receive them.
     */
    fun announce(text: String) {
        if (isTouchExplorationEnabled()) {
            LvglAccessibilityHelper.announce(context, text)
        }
    }

    /**
     * Marks the element identified by [viewId] as focusable or not.
     *
     * The id is opaque to this layer; it is the same id the app registered
     * with its focus model.
     */
    fun setFocusable(viewId: String, focusable: Boolean) {
        LvglAccessibilityHelper.setFocusable(viewId, focusable)
    }

    /** Sets the content [description] announced for the element [viewId]. */
    fun setContentDescription(viewId: String, description: String) {
        LvglAccessibilityHelper.setContentDescription(viewId, description)
    }

    /** Holds the private log tag. */
    companion object {
        private const val TAG = "LvglAccessibilityManager"
    }
}

