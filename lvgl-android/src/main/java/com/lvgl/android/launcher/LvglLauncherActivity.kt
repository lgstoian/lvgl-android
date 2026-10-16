// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.launcher

import android.content.Intent
import android.os.Bundle
import com.lvgl.android.activity.LvglActivity

abstract class LvglLauncherActivity : LvglActivity() {

    abstract fun onAllAppsChanged()
    abstract fun onPackageAdded(packageName: String)
    abstract fun onPackageRemoved(packageName: String)
    abstract fun onPackageChanged(packageName: String)

    fun getDefaultHomeIntent(): Intent {
        return Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    fun getAppList(): List<android.content.pm.ApplicationInfo> {
        val pm = packageManager
        return pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
    }

    fun launchApp(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
    }
}

