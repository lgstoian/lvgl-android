// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.navigation

import android.content.Intent

/**
 * Process-wide screen back stack and activity navigation helper.
 *
 * Keeps a simple stack of screen names so an LVGL UI can navigate between
 * screens without holding strong references to destroyed activities (the
 * current activity is held through a weak reference). All methods are intended
 * for the UI thread.
 */
object LvglNavigation {
    private var currentActivityRef: java.lang.ref.WeakReference<android.app.Activity>? = null
    private val screenStack = mutableListOf<String>()

    /**
     * Pushes [screen] onto the back stack.
     *
     * @param screen the screen name to push.
     */
    fun pushScreen(screen: String) {
        screenStack.add(screen)
    }

    /** A snapshot of the current back stack, bottom first. */
    fun getBackStack(): List<String> = screenStack.toList()

    /**
     * Removes and returns the topmost screen.
     *
     * @return the popped screen, or `null` when the stack is empty.
     */
    fun popScreen(): String? {
        return if (screenStack.isNotEmpty()) screenStack.removeAt(screenStack.lastIndex) else null
    }

    /** Clears the back stack. */
    fun clearStack() {
        screenStack.clear()
    }

    /**
     * Remembers the main activity used by [getMainActivity] and [navigateTo].
     *
     * The reference is weak, so destroying the activity does not leak it.
     *
     * @param activity the current activity, or `null` to clear the reference.
     */
    fun setMainActivity(activity: android.app.Activity?) {
        currentActivityRef = activity?.let { java.lang.ref.WeakReference(it) }
    }

    /**
     * The most recently registered main activity.
     *
     * @return the activity, or `null` if none was set or it has been destroyed.
     */
    fun getMainActivity(): android.app.Activity? = currentActivityRef?.get()

    /**
     * Starts [target] from [activity] with `FLAG_ACTIVITY_SINGLE_TOP`.
     *
     * @param activity the calling activity.
     * @param target the activity class to start.
     */
    fun navigateTo(activity: android.app.Activity, target: Class<*>) {
        val intent = Intent(activity, target)
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        activity.startActivity(intent)
    }

    /**
     * Triggers the back action through the activity's on-back dispatcher.
     *
     * Does nothing unless [activity] is a [androidx.activity.ComponentActivity],
     * which provides the dispatcher.
     *
     * @param activity the calling activity.
     */
    fun navigateBack(activity: android.app.Activity) {
        if (activity is androidx.activity.ComponentActivity) {
            activity.onBackPressedDispatcher.onBackPressed()
        }
    }
}

