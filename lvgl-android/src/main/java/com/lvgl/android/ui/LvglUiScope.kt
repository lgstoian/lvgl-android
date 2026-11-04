// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.ui

/**
 * Kotlin DSL for declaring an LVGL screen.
 *
 * ```kotlin
 * LvglUi.registerScreen(1000, title = "Hello") {
 *     column {
 *         label("Hello, LVGL!", sizeDp = 20)
 *         button("Tap me") { ... }
 *     }
 * }
 * ```
 *
 * The scope is a tree of [LvglNode]s. It is converted to JSON and rendered by
 * the native UI kit, so the same Material 3 styling and layout lint apply as to
 * the built-in screens. Node ids are assigned automatically.
 *
 * @breadcrumb BC-033
 * @do-not Do not cache a scope between screens; build a fresh tree per screen.
 */
class LvglUiScope internal constructor() {

    private val children = mutableListOf<LvglNode>()

    internal fun <T : LvglNode> add(node: T): T = node.also { children.add(it) }

    internal fun snapshot(): List<LvglNode> = children.toList()

    /** Vertical stack; [card] renders it as a Material 3 filled card. */
    fun column(
        card: Boolean = false,
        gapDp: Int = 8,
        padDp: Int = 16,
        content: LvglUiScope.() -> Unit,
    ): LvglNode.Column {
        val scope = LvglUiScope()
        scope.content()
        return add(LvglNode.Column("", scope.snapshot(), card, gapDp, padDp))
    }

    /** Horizontal stack; [card] renders it as a Material 3 filled card. */
    fun row(
        card: Boolean = false,
        gapDp: Int = 8,
        padDp: Int = 16,
        content: LvglUiScope.() -> Unit,
    ): LvglNode.Row {
        val scope = LvglUiScope()
        scope.content()
        return add(LvglNode.Row("", scope.snapshot(), card, gapDp, padDp))
    }

    /** Single-line text. */
    fun label(text: String, sizeDp: Int = 14, id: String? = null): LvglNode.Label =
        add(LvglNode.Label(id ?: "", text, sizeDp))

    /** Material 3 button; the trailing lambda is the tap handler. */
    fun button(
        text: String,
        style: LvglButtonStyle = LvglButtonStyle.FILLED,
        danger: Boolean = false,
        fillWidth: Boolean = false,
        id: String? = null,
        onClick: (() -> Unit)? = null,
    ): LvglNode.Button = add(LvglNode.Button(id ?: "", text, style, danger, fillWidth, onClick))

    /** Material 3 slider; the trailing lambda receives the new value. */
    fun slider(
        value: Int,
        min: Int = 0,
        max: Int = 100,
        id: String? = null,
        onChange: ((Int) -> Unit)? = null,
    ): LvglNode.Slider = add(LvglNode.Slider(id ?: "", value, min, max, onChange))

    /** Material 3 switch; the trailing lambda receives the new state. */
    fun toggle(
        checked: Boolean,
        id: String? = null,
        onChange: ((Boolean) -> Unit)? = null,
    ): LvglNode.Switch = add(LvglNode.Switch(id ?: "", checked, onChange))

    /** Material 3 checkbox; the trailing lambda receives the new state. */
    fun checkbox(
        text: String,
        checked: Boolean,
        id: String? = null,
        onChange: ((Boolean) -> Unit)? = null,
    ): LvglNode.Checkbox = add(LvglNode.Checkbox(id ?: "", text, checked, onChange))

    /** Determinate progress bar. */
    fun progress(value: Int, max: Int = 100, id: String? = null): LvglNode.Progress =
        add(LvglNode.Progress(id ?: "", value, max))

    /** Radial gauge. */
    fun arc(value: Int, max: Int = 100, id: String? = null): LvglNode.Arc =
        add(LvglNode.Arc(id ?: "", value, max))

    /** Single-choice dropdown; the trailing lambda receives the selected index. */
    fun dropdown(
        options: List<String>,
        selectedIndex: Int = 0,
        id: String? = null,
        onSelect: ((Int) -> Unit)? = null,
    ): LvglNode.Dropdown = add(LvglNode.Dropdown(id ?: "", options, selectedIndex, onSelect))

    /** 1 dp divider. */
    fun divider(id: String? = null): LvglNode.Divider = add(LvglNode.Divider(id ?: ""))

    /** Fixed-size spacer. */
    fun spacer(sizeDp: Int = 16, id: String? = null): LvglNode.Spacer =
        add(LvglNode.Spacer(id ?: "", sizeDp))
}

/** Assigns stable `n0`, `n1`, ... ids to every node, in tree order. Explicit
 * ids supplied through the DSL are preserved. */
internal fun assignNodeIds(nodes: List<LvglNode>): List<LvglNode> {
    var counter = 0
    fun nextId(): String = "n${counter++}"
    fun walk(node: LvglNode): LvglNode = when (node) {
        is LvglNode.Column ->
            node.copy(id = node.id.ifEmpty { nextId() }, children = node.children.map(::walk))
        is LvglNode.Row ->
            node.copy(id = node.id.ifEmpty { nextId() }, children = node.children.map(::walk))
        is LvglNode.Label -> node.copy(id = node.id.ifEmpty { nextId() })
        is LvglNode.Button -> node.copy(id = node.id.ifEmpty { nextId() })
        is LvglNode.Slider -> node.copy(id = node.id.ifEmpty { nextId() })
        is LvglNode.Switch -> node.copy(id = node.id.ifEmpty { nextId() })
        is LvglNode.Checkbox -> node.copy(id = node.id.ifEmpty { nextId() })
        is LvglNode.Progress -> node.copy(id = node.id.ifEmpty { nextId() })
        is LvglNode.Arc -> node.copy(id = node.id.ifEmpty { nextId() })
        is LvglNode.Dropdown -> node.copy(id = node.id.ifEmpty { nextId() })
        is LvglNode.Divider -> node.copy(id = node.id.ifEmpty { nextId() })
        is LvglNode.Spacer -> node.copy(id = node.id.ifEmpty { nextId() })
    }
    return nodes.map(::walk)
}
