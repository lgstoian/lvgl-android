// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.sample

import android.os.Bundle
import android.util.Log
import com.lvgl.android.launcher.LvglLauncherActivity
import com.lvgl.android.navigation.LvglNavigation

/**
 * Demonstrates the LVGL Launcher Activity with app-list retrieval.
 *
 * The installed-app list is rendered inside LVGL via the engine's status text
 * API (see ai-context.md - LvglActivity subclasses must not setContentView()).
 */
class LauncherSampleActivity : LvglLauncherActivity() {

    override fun onCreateLvgl() {
        LvglNavigation.pushScreen("LauncherSample")
        setLvglScreen(6)
        com.lvgl.android.engine.LvglEngine.instance.setStatusText(getString(R.string.launcher_status_loading))
        loadAppList()
    }

    private fun loadAppList() {
        /* Querying packages + loading labels for up to 20 apps is slow; do it off
         * the main thread so the UI never janks. */
        Thread {
            try {
                val apps = getAppList()
                val sb = StringBuilder()
                sb.append(getString(R.string.launcher_count, apps.size))
                apps.take(20).forEach { app ->
                    sb.append(getString(R.string.launcher_item, app.loadLabel(packageManager)))
                }
                if (apps.size > 20) {
                    sb.append(getString(R.string.launcher_more, apps.size - 20))
                }
                com.lvgl.android.engine.LvglEngine.instance.setStatusText(sb.toString())
            } catch (e: Exception) {
                com.lvgl.android.engine.LvglEngine.instance.setStatusText(
                    getString(R.string.launcher_error, e.message)
                )
            }
        }.start()
    }

    override fun onAllAppsChanged() {
        Log.d(TAG, "App list changed")
        loadAppList()
    }

    override fun onPackageAdded(packageName: String) {
        Log.d(TAG, "Package added: $packageName")
    }

    override fun onPackageRemoved(packageName: String) {
        Log.d(TAG, "Package removed: $packageName")
    }

    override fun onPackageChanged(packageName: String) {
        Log.d(TAG, "Package changed: $packageName")
    }

    companion object {
        private const val TAG = "LauncherSampleActivity"
    }
}
