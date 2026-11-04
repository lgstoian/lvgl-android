// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.sample

import android.content.Context
import com.lvgl.android.ui.LvglUi

/**
 * The App Demo screen, declared entirely in Kotlin with the [LvglUi] DSL.
 *
 * Keeping the tree out of the activity also keeps user-visible strings in
 * `strings.xml` (the hard rule) and node ids in one place.
 */
object AppDemoScreen {

    /** App-defined screen id; built-in screens occupy `0..7`. */
    const val SCREEN_ID = 1000

    private const val ID_COUNTER = "counter"
    private const val ID_TAP = "tap"
    private const val ID_SLIDER = "slider"
    private const val ID_SLIDER_VALUE = "slider_value"
    private const val ID_WIFI = "wifi"
    private const val ID_NOTIFICATIONS = "notif"
    private const val ID_DROPDOWN = "dropdown"
    private const val ID_DROPDOWN_VALUE = "dropdown_value"

    /** Declares the screen; safe to call more than once (it replaces the tree). */
    fun register(context: Context) {
        var taps = 0

        LvglUi.registerScreen(SCREEN_ID, title = context.getString(R.string.app_demo_title)) {
            column {
                label(context.getString(R.string.app_demo_heading), sizeDp = 20)
                label(context.getString(R.string.app_demo_body), sizeDp = 13)
                spacer(8)

                column(card = true) {
                    label(context.getString(R.string.app_demo_button_section), sizeDp = 12)
                    label(context.getString(R.string.app_demo_taps, 0), sizeDp = 18, id = ID_COUNTER)
                    spacer(4)
                    button(context.getString(R.string.app_demo_tap), fillWidth = true, id = ID_TAP) {
                        taps++
                        LvglUi.updateNode(
                            SCREEN_ID, ID_COUNTER,
                            text = context.getString(R.string.app_demo_taps, taps)
                        )
                    }
                }
                spacer(8)

                column(card = true) {
                    label(context.getString(R.string.app_demo_slider_section), sizeDp = 12)
                    label(
                        context.getString(R.string.app_demo_value, 40),
                        sizeDp = 18,
                        id = ID_SLIDER_VALUE
                    )
                    spacer(4)
                    slider(40, 0, 100, id = ID_SLIDER) { value ->
                        LvglUi.updateNode(
                            SCREEN_ID, ID_SLIDER_VALUE,
                            text = context.getString(R.string.app_demo_value, value)
                        )
                    }
                }
                spacer(8)

                column(card = true) {
                    label(context.getString(R.string.app_demo_toggles_section), sizeDp = 12)
                    spacer(4)
                    toggle(true, id = ID_WIFI) { }
                    checkbox(
                        context.getString(R.string.app_demo_notifications),
                        false,
                        id = ID_NOTIFICATIONS
                    ) { }
                }
                spacer(8)

                column(card = true) {
                    label(context.getString(R.string.app_demo_dropdown_section), sizeDp = 12)
                    spacer(4)
                    dropdown(listOf("Option A", "Option B", "Option C"), 0, id = ID_DROPDOWN) { index ->
                        LvglUi.updateNode(
                            SCREEN_ID, ID_DROPDOWN_VALUE,
                            text = context.getString(R.string.app_demo_option, index + 1)
                        )
                    }
                    spacer(4)
                    label(context.getString(R.string.app_demo_option, 1), sizeDp = 14,
                          id = ID_DROPDOWN_VALUE)
                }
                spacer(8)

                column(card = true) {
                    label(context.getString(R.string.app_demo_gauges_section), sizeDp = 12)
                    spacer(8)
                    progress(65, 100)
                    spacer(8)
                    arc(80, 100)
                }
                spacer(8)

                button(context.getString(R.string.app_demo_licenses), fillWidth = true) {
                    context.startActivity(
                        android.content.Intent(context, LicensesActivity::class.java)
                    )
                }
            }
        }
    }
}
