// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.ui

import com.lvgl.android.engine.LvglEngine

/**
 * Registry of app-defined LVGL screens.
 *
 * The framework ships eight built-in screens (ids `0..7`); an app registers its
 * own from [APP_SCREEN_BASE] up with [registerScreen] and then shows them with
 * `setLvglScreen(id)` exactly like a built-in screen:
 *
 * ```kotlin
 * LvglUi.registerScreen(1000, title = "Hello") {
 *     column {
 *         label("Hello from Kotlin!", sizeDp = 20)
 *         button("Tap me") { ... }
 *     }
 * }
 *
 * class HelloActivity : LvglActivity() {
 *     override fun onCreateLvgl() = setLvglScreen(1000)
 * }
 * ```
 *
 * The tree is rendered by the same native UI kit as the built-in screens, so it
 * follows the Material 3 palette and is covered by the layout lint.
 *
 * @breadcrumb BC-033
 * @do-not Do not use ids below [APP_SCREEN_BASE]; they are reserved for the
 *   built-in screens.
 */
object LvglUi {

    /** First screen id an app may register. Built-in screens occupy `0..7`. */
    const val APP_SCREEN_BASE = 1000

    /**
     * Node callbacks keyed by screen id then node id. Populated when a screen is
     * registered, because the callbacks live in Kotlin while the native side
     * only sees the serialised tree.
     */
    private val callbacks = mutableMapOf<Int, Map<String, (LvglUiEvent) -> Unit>>()

    internal val receiver = LvglUiEventReceiver { screenId, nodeId, type, value ->
        val event = when (type) {
            "click" -> LvglUiEvent.Click(nodeId)
            "value" -> LvglUiEvent.ValueChanged(nodeId, value)
            "toggle" -> LvglUiEvent.Toggled(nodeId, value != 0)
            "select" -> LvglUiEvent.Selected(nodeId, value)
            else -> null
        } ?: return@LvglUiEventReceiver
        callbacks[screenId]?.get(nodeId)?.invoke(event)
        currentListener?.onUiEvent(screenId, event)
    }

    private var currentListener: LvglUiListener? = null

    /**
     * Receives taps and value changes from app-defined screens. Node callbacks
     * supplied in the DSL are always invoked; this listener is an additional
     * screen-level hook. Events are dispatched on the LVGL thread, so switch to
     * the UI thread before touching Android views.
     */
    var listener: LvglUiListener?
        get() = currentListener
        set(value) {
            currentListener = value
        }

    /**
     * Declares a screen. Re-registering the same id replaces the previous tree.
     *
     * @param id stable screen id; must be at least [APP_SCREEN_BASE].
     * @param title optional app-bar title.
     * @param showBack whether the app bar shows a back button.
     * @param content the screen tree.
     */
    fun registerScreen(
        id: Int,
        title: String = "",
        showBack: Boolean = true,
        content: LvglUiScope.() -> Unit,
    ) {
        require(id >= APP_SCREEN_BASE) { "App screen ids must be >= $APP_SCREEN_BASE (got $id)" }
        val scope = LvglUiScope()
        scope.content()
        val (json, assigned) = LvglJson.encodeWithIds(scope.snapshot())
        callbacks[id] = collectCallbacks(assigned)
        /* The receiver is always installed so node callbacks work even when no
         * global listener is set. */
        LvglEngine.instance.uiEventListener = receiver
        LvglEngine.instance.registerScreen(id, title, showBack, json)
    }

    /** Removes a previously registered screen. */
    fun unregisterScreen(id: Int) {
        callbacks.remove(id)
        LvglEngine.instance.unregisterScreen(id)
    }

    /**
     * Updates a node of an already-built app screen.
     *
     * Only the supplied properties change; everything else keeps its value.
     * The update is marshalled to the LVGL thread, so it is safe to call from
     * any thread.
     *
     * @param screenId the screen that owns the node.
     * @param nodeId the node's explicit id (see the `id` parameter of the
     *   [LvglUiScope] node builders).
     */
    fun updateNode(
        screenId: Int,
        nodeId: String,
        text: String? = null,
        value: Int? = null,
        checked: Boolean? = null,
        visible: Boolean? = null,
    ) {
        val json = JsonObject().apply {
            text?.let { put("text", it) }
            value?.let { put("value", it) }
            checked?.let { put("checked", it) }
            visible?.let { put("visible", it) }
        }.toString()
        LvglEngine.instance.updateNode(screenId, nodeId, json)
    }

    /** Flattens the node callbacks of a tree into a node-id -> handler map. */
    private fun collectCallbacks(nodes: List<LvglNode>): Map<String, (LvglUiEvent) -> Unit> {
        val map = mutableMapOf<String, (LvglUiEvent) -> Unit>()

        fun walk(list: List<LvglNode>) {
            list.forEach { node ->
                val handler: ((LvglUiEvent) -> Unit)? = when (node) {
                    is LvglNode.Button ->
                        node.onClick?.let { onClick -> { _: LvglUiEvent -> onClick() } }
                    is LvglNode.Slider ->
                        node.onChange?.let { onChange ->
                            { event: LvglUiEvent ->
                                if (event is LvglUiEvent.ValueChanged) onChange(event.value)
                            }
                        }
                    is LvglNode.Switch ->
                        node.onChange?.let { onChange ->
                            { event: LvglUiEvent ->
                                if (event is LvglUiEvent.Toggled) onChange(event.checked)
                            }
                        }
                    is LvglNode.Checkbox ->
                        node.onChange?.let { onChange ->
                            { event: LvglUiEvent ->
                                if (event is LvglUiEvent.Toggled) onChange(event.checked)
                            }
                        }
                    is LvglNode.Dropdown ->
                        node.onSelect?.let { onSelect ->
                            { event: LvglUiEvent ->
                                if (event is LvglUiEvent.Selected) onSelect(event.index)
                            }
                        }
                    else -> null
                }
                if (handler != null && node.id.isNotEmpty()) map[node.id] = handler

                when (node) {
                    is LvglNode.Column -> walk(node.children)
                    is LvglNode.Row -> walk(node.children)
                    else -> {}
                }
            }
        }

        walk(nodes)
        return map
    }
}
