// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.sample

import android.util.Log
import com.lvgl.android.engine.LvglEngine
import com.lvgl.android.launcher.LauncherAppListener
import com.lvgl.android.launcher.LvglLauncherActivity
import com.lvgl.android.navigation.LvglNavigation

/**
 * Demonstrates [LvglLauncherActivity]: the installed apps are collected on a
 * background thread, pushed to the native launcher grid and launched when tapped.
 */
class LauncherSampleActivity : LvglLauncherActivity() {

    override fun onCreateLvgl() {
        LvglNavigation.pushScreen("LauncherSample")
        setLvglScreen(6)
        LvglEngine.instance.launcherAppListener = LauncherAppListener { pkg ->
            runOnUiThread { launchApp(pkg) }
        }
        loadAppList()
    }

    private fun loadAppList() {
        /* Querying packages + loading labels for every app is slow; do it off the
         * main thread so the UI never janks. */
        Thread {
            try {
                val apps = getAppList()
                    .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
                    .take(MAX_APPS)
                val labels = Array(apps.size) { apps[it].loadLabel(packageManager).toString() }
                val packages = Array(apps.size) { apps[it].packageName }
                runOnUiThread {
                    LvglEngine.instance.setLauncherApps(labels, packages)
                    setLvglScreen(6)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load apps", e)
            }
        }.start()
    }

    override fun onAllAppsChanged() {
        loadAppList()
    }

    override fun onDestroy() {
        LvglEngine.instance.launcherAppListener = null
        super.onDestroy()
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
        private const val MAX_APPS = 60
    }
}
