package com.lvgl.sample

import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.lvgl.android.activity.LvglActivity
import com.lvgl.android.navigation.LvglNavigation

/**
 * Demonstrates the LVGL Launcher Activity with:
 * - App list retrieval
 * - Package change callbacks
 * - App launching
 *
 * Subclasses LvglLauncherActivity which provides:
 * - onAllAppsChanged() callback
 * - onPackageAdded/Removed/Changed() callbacks
 * - getAppList() to retrieve installed apps
 * - launchApp(packageName) to start apps
 */
class LauncherSampleActivity : com.lvgl.android.launcher.LvglLauncherActivity() {

    private lateinit var appListText: TextView

    override fun onCreateLvgl() {
        LvglNavigation.pushScreen("LauncherSample")

        val root = FrameLayout(this)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val title = TextView(this).apply {
            text = "Launcher Sample"
            textSize = 24f
            setPadding(20, 20, 20, 20)
        }

        appListText = TextView(this).apply {
            text = "Loading apps..."
            textSize = 14f
            setPadding(20, 10, 20, 10)
        }

        layout.addView(title)
        layout.addView(appListText)
        root.addView(layout, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(root)

        loadAppList()
    }

    private fun loadAppList() {
        try {
            val apps = getAppList()
            val sb = StringBuilder()
            sb.append("Installed apps (${apps.size}):\n\n")
            apps.take(20).forEach { app ->
                sb.append("• ${app.loadLabel(packageManager)}\n")
            }
            if (apps.size > 20) {
                sb.append("\n... and ${apps.size - 20} more")
            }
            appListText.text = sb.toString()
        } catch (e: Exception) {
            appListText.text = "Error loading apps: ${e.message}"
        }
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
