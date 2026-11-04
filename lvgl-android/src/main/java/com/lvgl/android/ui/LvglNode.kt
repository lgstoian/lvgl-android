// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.ui

/**
 * A node in an app-defined LVGL screen.
 *
 * The tree is declarative: [LvglUiScope] builds one, [LvglUi] serialises it to
 * JSON and the native UI kit renders it with the shared Material 3 palette.
 * Node ids are assigned automatically and are what [LvglUiEvent.nodeId]
 * refers to.
 *
 * @breadcrumb BC-033
 */
sealed class LvglNode {
    /** Stable id within the screen; assigned by [LvglUiScope]. */
    abstract val id: String

    /**
     * Vertical stack.
     *
     * @property id stable node id assigned when the tree is encoded.
     * @property children the stacked nodes, in order.
     * @property card whether the stack is rendered as a Material 3 filled card.
     * @property gapDp vertical gap between children in dp.
     * @property padDp inner padding in dp.
     */
    data class Column(
        override val id: String,
        val children: List<LvglNode>,
        val card: Boolean = false,
        val gapDp: Int = 8,
        val padDp: Int = 16,
    ) : LvglNode()

    /**
     * Horizontal stack.
     *
     * @property id stable node id assigned when the tree is encoded.
     * @property children the stacked nodes, in order.
     * @property card whether the stack is rendered as a Material 3 filled card.
     * @property gapDp horizontal gap between children in dp.
     * @property padDp inner padding in dp.
     */
    data class Row(
        override val id: String,
        val children: List<LvglNode>,
        val card: Boolean = false,
        val gapDp: Int = 8,
        val padDp: Int = 16,
    ) : LvglNode()

    /**
     * Single-line text.
     *
     * @property id stable node id assigned when the tree is encoded.
     * @property text the text to display.
     * @property sizeDp font size in dp.
     */
    data class Label(
        override val id: String,
        val text: String,
        val sizeDp: Int = 14,
    ) : LvglNode()

    /**
     * Material 3 button.
     *
     * @property id stable node id assigned when the tree is encoded.
     * @property text the button label.
     * @property style the Material 3 button style.
     * @property danger whether the button uses the error colour.
     * @property fillWidth whether the button expands to the available width.
     * @property onClick invoked when the button is tapped.
     */
    data class Button(
        override val id: String,
        val text: String,
        val style: LvglButtonStyle = LvglButtonStyle.FILLED,
        val danger: Boolean = false,
        val fillWidth: Boolean = false,
        val onClick: (() -> Unit)? = null,
    ) : LvglNode()

    /**
     * Material 3 slider.
     *
     * @property id stable node id assigned when the tree is encoded.
     * @property value the current value.
     * @property min the minimum value.
     * @property max the maximum value.
     * @property onChange invoked with the new value.
     */
    data class Slider(
        override val id: String,
        val value: Int,
        val min: Int = 0,
        val max: Int = 100,
        val onChange: ((Int) -> Unit)? = null,
    ) : LvglNode()

    /**
     * Material 3 switch.
     *
     * @property id stable node id assigned when the tree is encoded.
     * @property checked whether the switch is on.
     * @property onChange invoked with the new state.
     */
    data class Switch(
        override val id: String,
        val checked: Boolean,
        val onChange: ((Boolean) -> Unit)? = null,
    ) : LvglNode()

    /**
     * Material 3 checkbox.
     *
     * @property id stable node id assigned when the tree is encoded.
     * @property text the label next to the checkbox.
     * @property checked whether the checkbox is checked.
     * @property onChange invoked with the new state.
     */
    data class Checkbox(
        override val id: String,
        val text: String,
        val checked: Boolean,
        val onChange: ((Boolean) -> Unit)? = null,
    ) : LvglNode()

    /**
     * Determinate progress bar.
     *
     * @property id stable node id assigned when the tree is encoded.
     * @property value the current value.
     * @property max the maximum value.
     */
    data class Progress(
        override val id: String,
        val value: Int,
        val max: Int = 100,
    ) : LvglNode()

    /**
     * Radial gauge.
     *
     * @property id stable node id assigned when the tree is encoded.
     * @property value the current value.
     * @property max the maximum value.
     */
    data class Arc(
        override val id: String,
        val value: Int,
        val max: Int = 100,
    ) : LvglNode()

    /**
     * Single-choice dropdown.
     *
     * @property options the visible options.
     * @property selectedIndex the initially selected option.
     * @property onSelect invoked with the new index when the user picks one.
     */
    data class Dropdown(
        override val id: String,
        val options: List<String>,
        val selectedIndex: Int = 0,
        val onSelect: ((Int) -> Unit)? = null,
    ) : LvglNode()

    /**
     * 1 dp divider.
     *
     * @property id stable node id assigned when the tree is encoded.
     */
    data class Divider(override val id: String) : LvglNode()

    /**
     * Fixed-size spacer.
     *
     * @property id stable node id assigned when the tree is encoded.
     * @property sizeDp the spacer size in dp.
     */
    data class Spacer(override val id: String, val sizeDp: Int = 16) : LvglNode()
}

/** Material 3 button styles understood by [LvglNode.Button]. */
enum class LvglButtonStyle {
    /** Filled with the primary container colour. */
    FILLED,

    /** Tonal container variant. */
    TONAL,

    /** Bordered outline style. */
    OUTLINED,

    /** Borderless text-only style. */
    TEXT,
}
