package com.lvgl.sample

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.lvgl.android.activity.LvglActivity
import com.lvgl.android.navigation.LvglNavigation

class MainActivity : LvglActivity() {

    override fun onCreateLvgl() {
        LvglNavigation.setMainActivity(this)

        setContentView(createMenuLayout())
    }

    private fun createMenuLayout(): FrameLayout {
        val root = FrameLayout(this)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val items = listOf(
            "Widget Showcase" to WidgetShowcaseActivity::class.java,
            "Layout Showcase" to LayoutShowcaseActivity::class.java,
            "Interactive Demo" to InteractiveDemoActivity::class.java
        )

        items.forEach { (title, target) ->
            val tv = TextView(this).apply {
                text = title
                textSize = 20f
                setPadding(40, 40, 40, 40)
                setOnClickListener {
                    LvglNavigation.navigateTo(this@MainActivity, target)
                }
            }
            layout.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        root.addView(layout, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return root
    }

    override fun onBackPressed() {
        if (LvglNavigation.getBackStack().isNotEmpty()) {
            LvglNavigation.popScreen()
        }
        super.onBackPressed()
    }

    companion object {
        var currentDemo: Int = 0
    }
}
