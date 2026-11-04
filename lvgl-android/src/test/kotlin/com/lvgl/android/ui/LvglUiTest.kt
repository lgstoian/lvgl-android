// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the app-defined screen DSL and its JSON wire format.
 *
 * @breadcrumb BC-033
 */
class LvglUiTest {

    @Test
    fun columnWithChildrenSerialisesToJson() {
        val scope = LvglUiScope()
        scope.column {
            label("Hello")
            button("Tap me")
        }

        val json = LvglJson.encode(scope.snapshot())

        assertTrue(json.contains("\"t\":\"column\""))
        assertTrue(json.contains("\"t\":\"label\""))
        assertTrue(json.contains("\"t\":\"button\""))
        assertTrue(json.contains("\"text\":\"Hello\""))
        assertTrue(json.contains("\"children\":["))
    }

    @Test
    fun idsAreAssignedInTreeOrder() {
        val scope = LvglUiScope()
        scope.column {
            label("A")
            row {
                label("B")
            }
        }

        val json = LvglJson.encode(scope.snapshot())

        assertTrue(json.contains("\"id\":\"n0\""))
        assertTrue(json.contains("\"id\":\"n1\""))
        assertTrue(json.contains("\"id\":\"n2\""))
        assertTrue(json.contains("\"id\":\"n3\""))
    }

    @Test
    fun stringsAreEscaped() {
        val scope = LvglUiScope()
        scope.label("He said \"hi\"\nand left")

        val json = LvglJson.encode(scope.snapshot())

        assertTrue(json.contains("\\\"hi\\\""))
        assertTrue(json.contains("\\n"))
        assertFalse("JSON must be single-line", json.contains("\n"))
    }

    @Test
    fun registerScreenRejectsBuiltInIds() {
        var thrown = false
        try {
            LvglUi.registerScreen(0) { label("nope") }
        } catch (expected: IllegalArgumentException) {
            thrown = true
        }
        assertTrue("ids below APP_SCREEN_BASE must be rejected", thrown)
    }

    @Test
    fun eventsAreMappedFromTheWireFormat() {
        val received = mutableListOf<LvglUiEvent>()
        LvglUi.listener = LvglUiListener { _, event -> received.add(event) }

        LvglUi.receiver.onUiEvent(1000, "n1", "click", 0)
        LvglUi.receiver.onUiEvent(1000, "n2", "value", 42)
        LvglUi.receiver.onUiEvent(1000, "n3", "toggle", 1)
        LvglUi.receiver.onUiEvent(1000, "n4", "unknown", 0)

        assertEquals(3, received.size)
        assertEquals(LvglUiEvent.Click("n1"), received[0])
        assertEquals(LvglUiEvent.ValueChanged("n2", 42), received[1])
        assertEquals(LvglUiEvent.Toggled("n3", true), received[2])

        LvglUi.listener = null
    }

    @Test
    fun nodeCallbacksAreInvoked() {
        var clicks = 0
        var sliderValue = -1
        var toggleValue: Boolean? = null

        LvglUi.registerScreen(1000) {
            column {
                button("Tap", id = "btn") { clicks++ }
                slider(0, 0, 100, id = "sld", onChange = { sliderValue = it })
                toggle(false, id = "sw", onChange = { toggleValue = it })
            }
        }

        LvglUi.receiver.onUiEvent(1000, "btn", "click", 0)
        LvglUi.receiver.onUiEvent(1000, "sld", "value", 33)
        LvglUi.receiver.onUiEvent(1000, "sw", "toggle", 1)

        assertEquals(1, clicks)
        assertEquals(33, sliderValue)
        assertEquals(true, toggleValue)

        LvglUi.unregisterScreen(1000)
    }

    @Test
    fun allNodeTypesCarryTheirTypeTag() {
        val scope = LvglUiScope()
        scope.column {
            label("l")
            button("b")
            slider(5)
            toggle(true)
            checkbox("c", true)
            progress(10)
            arc(20)
            dropdown(listOf("A", "B"), 0)
            divider()
            spacer(8)
        }

        val json = LvglJson.encode(scope.snapshot())

        listOf(
            "column", "label", "button", "slider", "switch",
            "checkbox", "progress", "arc", "dropdown", "divider", "spacer",
        ).forEach { tag ->
            assertTrue("missing node type $tag", json.contains("\"t\":\"$tag\""))
        }
        assertTrue(json.contains("\"options\":[\"A\",\"B\"]"))
    }

    @Test
    fun dropdownCallbacksAreInvoked() {
        var selected = -1
        LvglUi.registerScreen(1001) {
            column {
                dropdown(listOf("A", "B", "C"), 0, id = "dd") { selected = it }
            }
        }

        LvglUi.receiver.onUiEvent(1001, "dd", "select", 2)

        assertEquals(2, selected)
        LvglUi.unregisterScreen(1001)
    }
}
