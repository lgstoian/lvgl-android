// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.sample

import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.lvgl.android.navigation.LvglNavigation

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LvglNavigation.setMainActivity(this)
        setContentView(createMenuLayout())
    }

    private fun createMenuLayout(): FrameLayout {
        val root = FrameLayout(this)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val title = TextView(this).apply {
            text = "LVGL Android Framework"
            textSize = 24f
            setPadding(40, 40, 40, 20)
        }
        layout.addView(title)

        val items = listOf(
            "Widget Showcase" to WidgetShowcaseActivity::class.java,
            "Layout Showcase" to LayoutShowcaseActivity::class.java,
            "Interactive Demo" to InteractiveDemoActivity::class.java
        )

        items.forEach { (titleText, target) ->
            val tv = TextView(this).apply {
                text = titleText
                textSize = 18f
                setPadding(40, 30, 40, 30)
                setOnClickListener {
                    startActivity(android.content.Intent(this@MainActivity, target))
                }
            }
            layout.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        root.addView(layout, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return root
    }
}

