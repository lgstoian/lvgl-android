// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.launcher

import android.content.Intent
import android.os.Bundle
import com.lvgl.android.activity.LvglActivity

/**
 * Receives app-selection events from the native launcher grid.
 *
 * Set it on `LvglEngine.instance.launcherAppListener`; the callback runs on the
 * LVGL thread, so switch to the UI thread before starting an activity.
 */
fun interface LauncherAppListener {
    fun onAppSelected(packageName: String)
}

/**
 * Base activity for an LVGL launcher/home screen.
 *
 * Provides the building blocks a launcher needs: the list of installed
 * applications, launching by package, and the intent that resolves the current
 * default home. Subclasses implement the package-change callbacks to refresh
 * their app grid.
 *
 * Declare the activity as a home candidate in the manifest with
 * `android.intent.category.HOME` and `DEFAULT` categories.
 */
abstract class LvglLauncherActivity : LvglActivity() {

    /**
     * Called when the set of applications may have changed.
     *
     * Rebuild the app grid here; [getAppList] is safe to call.
     */
    abstract fun onAllAppsChanged()

    /**
     * Called when a package was installed.
     *
     * @param packageName the installed package.
     */
    abstract fun onPackageAdded(packageName: String)

    /**
     * Called when a package was removed.
     *
     * @param packageName the removed package.
     */
    abstract fun onPackageRemoved(packageName: String)

    /**
     * Called when a package was replaced or changed.
     *
     * @param packageName the changed package.
     */
    abstract fun onPackageChanged(packageName: String)

    /**
     * An intent that opens the user's current home screen.
     *
     * @return an `ACTION_MAIN` / `CATEGORY_HOME` intent flagged with
     *   `FLAG_ACTIVITY_NEW_TASK`.
     */
    fun getDefaultHomeIntent(): Intent {
        return Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    /**
     * Lists the installed applications.
     *
     * PackageManager queries can be slow, so call from a background thread and
     * post the result back to the UI thread before updating LVGL.
     *
     * @return the installed [android.content.pm.ApplicationInfo] list.
     */
    fun getAppList(): List<android.content.pm.ApplicationInfo> {
        val pm = packageManager
        return pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
    }

    /**
     * Launches the default activity for [packageName].
     *
     * Does nothing when the package has no launch intent.
     *
     * @param packageName the package to launch; call from the UI thread.
     */
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

