// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.navigation

import android.content.Intent

object LvglNavigation {
    private var currentActivity: android.app.Activity? = null
    private val screenStack = mutableListOf<String>()

    fun pushScreen(screen: String) {
        screenStack.add(screen)
    }

    fun getBackStack(): List<String> = screenStack.toList()

    fun popScreen(): String? {
        return if (screenStack.isNotEmpty()) screenStack.removeAt(screenStack.lastIndex) else null
    }

    fun clearStack() {
        screenStack.clear()
    }

    fun setMainActivity(activity: android.app.Activity?) {
        currentActivity = activity
    }

    fun getMainActivity(): android.app.Activity? = currentActivity

    fun navigateTo(activity: android.app.Activity, target: Class<*>) {
        val intent = Intent(activity, target)
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        activity.startActivity(intent)
    }

    fun navigateBack(activity: android.app.Activity) {
        if (activity is android.app.Activity) {
            activity.onBackPressed()
        }
    }
}

