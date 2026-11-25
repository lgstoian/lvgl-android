// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.accessibility

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import com.lvgl.android.engine.LvglEngine
import com.lvgl.android.ui.LvglNode
import com.lvgl.android.ui.LvglUi
import com.lvgl.android.ui.LvglUiObserver

/**
 * Virtual accessibility tree for Kotlin UI-builder app screens.
 *
 * The framework renders app screens natively in LVGL, so the view hierarchy is
 * just one [SurfaceView]. TalkBack nevertheless needs accessible children; this
 * provider mirrors the registered [LvglUi] tree as virtual views with stable
 * ids (`1..N` in tree order). Only semantic nodes are exposed: labels, buttons,
 * checkboxes, switches, sliders, progress bars, arcs and dropdowns. Layout
 * containers (`Column`/`Row`) and decoration (`Divider`/`Spacer`) are flattened
 * away, so every virtual child has the host view as its parent.
 *
 * LVGL has no JNI API for object bounds or focus, so bounds are approximate
 * horizontal bands inside the host and focus is Android-side only. Native
 * events and [LvglUi.updateNode] calls are observed and merged into the tree;
 * all observer work is marshalled to the main thread, and no LVGL callback is
 * ever blocked by an accessibility query.
 *
 * @breadcrumb BC-033
 * @do-not Do not call native LVGL methods while answering an accessibility
 *   query; node state is mirrored in Kotlin on purpose.
 */
internal class LvglNodeAccessibilityProvider(
    private val context: Context,
    private val host: View,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) : AccessibilityNodeProvider(), LvglUiObserver {

    private data class Entry(val virtualId: Int, val node: LvglNode)

    /** Node id -> runtime overrides; declaration values stay in the tree. */
    private val texts = mutableMapOf<String, String>()
    private val values = mutableMapOf<String, Int>()
    private val checkedStates = mutableMapOf<String, Boolean>()
    private val selections = mutableMapOf<String, Int>()
    private val visibility = mutableMapOf<String, Boolean>()

    private var currentScreen = NO_SCREEN
    private var focusedVirtualId = NO_FOCUS
    private var attached = false

    /** Host-context service used to decide whether any service can receive events. */
    private val accessibilityManager =
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager

    /** Set at the end of construction so no send can race a partial provider. */
    private var initialized = false

    init {
        initialized = true
    }

    /** Registers the state observer; safe to call more than once. */
    internal fun attach() {
        if (attached) return
        attached = true
        LvglUi.addObserver(this)
        refresh()
    }

    /** Removes the state observer and clears mirrored state. */
    internal fun detach() {
        if (!attached) return
        attached = false
        LvglUi.removeObserver(this)
        mainHandler.post {
            currentScreen = NO_SCREEN
            focusedVirtualId = NO_FOCUS
            clearState()
        }
    }

    /** Re-reads the engine's current screen and announces a content change. */
    internal fun refresh() {
        runOnMain {
            resolveScreen()
            sendHostEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
    }

    override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
        val screenId = resolveScreen()
        val entries = entries(screenId)
        if (virtualViewId == HOST_VIEW_ID) {
            val info = AccessibilityNodeInfo.obtain(host)
            info.packageName = context.packageName
            info.className = host.javaClass.name
            info.isFocusable = true
            info.isFocused = host.isFocused
            entries.forEach { info.addChild(host, it.virtualId) }
            return info
        }
        val entry = entries.firstOrNull { it.virtualId == virtualViewId } ?: return null
        return buildInfo(entry, entries.size)
    }

    override fun findAccessibilityNodeInfosByText(
        text: String,
        virtualViewId: Int,
    ): List<AccessibilityNodeInfo> {
        val entries = entries(resolveScreen())
        val candidates = if (virtualViewId == HOST_VIEW_ID) {
            entries
        } else {
            entries.filter { it.virtualId == virtualViewId }
        }
        return candidates
            .filter { descriptionOf(it.node).contains(text, ignoreCase = true) }
            .map { buildInfo(it, entries.size) }
    }

    override fun findFocus(focus: Int): AccessibilityNodeInfo? {
        if (focus != AccessibilityNodeInfo.FOCUS_ACCESSIBILITY &&
            focus != AccessibilityNodeInfo.FOCUS_INPUT
        ) return null
        if (focusedVirtualId == NO_FOCUS) return null
        val entries = entries(resolveScreen())
        val entry = entries.firstOrNull { it.virtualId == focusedVirtualId } ?: return null
        return buildInfo(entry, entries.size)
    }

    override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
        runOnMain {
            val screenId = resolveScreen()
            val entries = entries(screenId)
            val entry = entries.firstOrNull { it.virtualId == virtualViewId } ?: return@runOnMain
            when (action) {
                AccessibilityNodeInfo.ACTION_FOCUS -> {
                    focusedVirtualId = virtualViewId
                    sendNodeEvent(entry, AccessibilityEvent.TYPE_VIEW_FOCUSED)
                }
                AccessibilityNodeInfo.ACTION_CLEAR_FOCUS -> {
                    if (focusedVirtualId == virtualViewId) focusedVirtualId = NO_FOCUS
                    sendNodeEvent(entry, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                }
                AccessibilityNodeInfo.ACTION_CLICK -> if (isInteractive(entry.node)) {
                    click(entry, screenId)
                }
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id -> {
                    val node = entry.node as? LvglNode.Slider ?: return@runOnMain
                    val current = valueOf(node)
                    val requested = arguments?.getInt(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, current,
                    ) ?: current
                    val next = requested.coerceIn(node.min, node.max)
                    values[node.id] = next
                    LvglUi.updateNode(screenId, node.id, value = next)
                    dispatch(screenId, node.id, "value", next)
                    sendNodeEvent(entry, AccessibilityEvent.TYPE_VIEW_SELECTED)
                }
                else -> Unit
            }
        }
        return true
    }

    override fun onEvent(screenId: Int, nodeId: String, type: String, value: Int) {
        mainHandler.post {
            when (type) {
                "value" -> values[nodeId] = value
                "toggle" -> checkedStates[nodeId] = value != 0
                "select" -> selections[nodeId] = value
            }
        }
    }

    override fun onNodeUpdated(
        screenId: Int,
        nodeId: String,
        text: String?,
        value: Int?,
        checked: Boolean?,
        visible: Boolean?,
    ) {
        mainHandler.post {
            text?.let { texts[nodeId] = it }
            value?.let { values[nodeId] = it }
            checked?.let { checkedStates[nodeId] = it }
            visible?.let { visibility[nodeId] = it }
        }
    }

    private fun click(entry: Entry, screenId: Int) {
        val node = entry.node
        when (node) {
            is LvglNode.Button -> {
                dispatch(screenId, node.id, "click", 0)
                sendNodeEvent(entry, AccessibilityEvent.TYPE_VIEW_CLICKED)
            }
            is LvglNode.Switch, is LvglNode.Checkbox -> {
                val next = !checkedOf(node)
                checkedStates[node.id] = next
                LvglUi.updateNode(screenId, node.id, checked = next)
                dispatch(screenId, node.id, "toggle", if (next) 1 else 0)
                sendNodeEvent(entry, AccessibilityEvent.TYPE_VIEW_CLICKED)
            }
            is LvglNode.Dropdown -> {
                if (node.options.isEmpty()) return
                val current = selections[node.id] ?: node.selectedIndex
                val next = (current + 1) % node.options.size
                selections[node.id] = next
                LvglUi.updateNode(screenId, node.id, value = next)
                dispatch(screenId, node.id, "select", next)
                sendNodeEvent(entry, AccessibilityEvent.TYPE_VIEW_SELECTED)
            }
            else -> Unit
        }
    }

    /** Synthesizes the same typed event the native UI kit would deliver. */
    private fun dispatch(screenId: Int, nodeId: String, type: String, value: Int) {
        LvglUi.receiver.onUiEvent(screenId, nodeId, type, value)
    }

    private fun buildInfo(entry: Entry, count: Int): AccessibilityNodeInfo {
        val node = entry.node
        val info = AccessibilityNodeInfo.obtain(host, entry.virtualId)
        info.packageName = context.packageName
        info.className = classNameOf(node)
        val description = descriptionOf(node)
        info.text = description
        info.contentDescription = description
        info.isEnabled = true
        info.isVisibleToUser = visibility[node.id] != false
        info.isFocusable = true
        info.isFocused = focusedVirtualId == entry.virtualId
        info.isClickable = isInteractive(node)
        if (node is LvglNode.Switch || node is LvglNode.Checkbox) {
            info.isCheckable = true
            info.isChecked = checkedOf(node)
        }
        when (node) {
            is LvglNode.Slider -> info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,
                node.min.toFloat(), node.max.toFloat(), valueOf(node).toFloat(),
            )
            is LvglNode.Progress, is LvglNode.Arc -> {
                val max = if (node is LvglNode.Progress) node.max else (node as LvglNode.Arc).max
                info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
                    AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,
                    0f, max.toFloat(), valueOf(node).toFloat(),
                )
            }
            else -> Unit
        }
        info.addAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (info.isFocused) info.addAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
        if (info.isClickable) info.addAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (node is LvglNode.Slider) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
        }

        val bounds = Rect()
        val location = IntArray(2)
        host.getLocationOnScreen(location)
        bounds.set(
            location[0], location[1],
            location[0] + host.width.coerceAtLeast(1),
            location[1] + host.height.coerceAtLeast(1),
        )
        if (bounds.isEmpty) {
            bounds.set(0, 0, host.width.coerceAtLeast(1), host.height.coerceAtLeast(1))
        }
        val height = (bounds.height() / count.coerceAtLeast(1)).coerceAtLeast(1)
        val top = bounds.top + (entry.virtualId - 1) * height
        val row = Rect(bounds.left, top, bounds.right, (top + height).coerceAtMost(bounds.bottom))
        info.setBoundsInParent(row)
        info.setBoundsInScreen(row)
        return info
    }

    private fun sendNodeEvent(entry: Entry, type: Int) {
        val event = AccessibilityEvent.obtain(type)
        event.packageName = context.packageName
        event.className = classNameOf(entry.node)
        event.text.add(descriptionOf(entry.node))
        if (entry.node is LvglNode.Switch || entry.node is LvglNode.Checkbox) {
            event.isChecked = checkedOf(entry.node)
        }
        sendHostEvent(event)
    }

    private fun sendHostEvent(type: Int) {
        sendHostEvent(AccessibilityEvent.obtain(type).apply { packageName = context.packageName })
    }

    /**
     * Best-effort delivery: announcements are optional, but the caller is often
     * a SurfaceView callback, so a disabled service or a vendor quirk must never
     * throw into it. Tree mirroring still happens when no service is listening.
     */
    private fun sendHostEvent(event: AccessibilityEvent) {
        if (!initialized || !attached || !host.isAttachedToWindow ||
            accessibilityManager?.isEnabled != true
        ) {
            return
        }
        try {
            val parent = host.parent
            if (parent != null) parent.requestSendAccessibilityEvent(host, event)
            else host.sendAccessibilityEventUnchecked(event)
        } catch (t: Throwable) {
            // Accessibility may have turned off between the check and the send,
            // or a vendor ROM may reject the event; stay silent either way.
        }
    }

    /** Returns the engine's current screen and resets state when it changes. */
    private fun resolveScreen(): Int {
        val screenId = try {
            LvglEngine.instance.currentScreen()
        } catch (t: Throwable) {
            NO_SCREEN
        }
        if (screenId != currentScreen) {
            currentScreen = screenId
            focusedVirtualId = NO_FOCUS
            clearState()
        }
        return screenId
    }

    private fun clearState() {
        texts.clear()
        values.clear()
        checkedStates.clear()
        selections.clear()
        visibility.clear()
    }

    private fun entries(screenId: Int): List<Entry> {
        val tree = LvglUi.snapshot(screenId) ?: return emptyList()
        val result = mutableListOf<Entry>()
        fun walk(nodes: List<LvglNode>) {
            nodes.forEach { node ->
                when (node) {
                    is LvglNode.Column -> walk(node.children)
                    is LvglNode.Row -> walk(node.children)
                    is LvglNode.Label,
                    is LvglNode.Button,
                    is LvglNode.Slider,
                    is LvglNode.Switch,
                    is LvglNode.Checkbox,
                    is LvglNode.Progress,
                    is LvglNode.Arc,
                    is LvglNode.Dropdown,
                    -> result.add(Entry(result.size + 1, node))
                    else -> Unit
                }
            }
        }
        walk(tree)
        return result
    }

    private fun descriptionOf(node: LvglNode): String = texts[node.id] ?: when (node) {
        is LvglNode.Label -> node.text
        is LvglNode.Button -> node.text
        is LvglNode.Checkbox -> node.text
        is LvglNode.Dropdown -> {
            val selected = selections[node.id] ?: node.selectedIndex
            val option = node.options.getOrNull(selected)
            if (option != null) "Dropdown, $option" else "Dropdown"
        }
        is LvglNode.Switch -> "Switch"
        is LvglNode.Slider -> "Slider"
        is LvglNode.Progress -> "Progress"
        is LvglNode.Arc -> "Gauge"
        else -> node.id
    }

    private fun classNameOf(node: LvglNode): String = when (node) {
        is LvglNode.Label -> "android.widget.TextView"
        is LvglNode.Button -> "android.widget.Button"
        is LvglNode.Checkbox -> "android.widget.CheckBox"
        is LvglNode.Switch -> "android.widget.Switch"
        is LvglNode.Slider, is LvglNode.Progress, is LvglNode.Arc -> "android.widget.SeekBar"
        is LvglNode.Dropdown -> "android.widget.Spinner"
        else -> "android.view.View"
    }

    private fun isInteractive(node: LvglNode): Boolean = when (node) {
        is LvglNode.Button, is LvglNode.Switch, is LvglNode.Checkbox, is LvglNode.Dropdown -> true
        else -> false
    }

    private fun checkedOf(node: LvglNode): Boolean = checkedStates[node.id] ?: when (node) {
        is LvglNode.Switch -> node.checked
        is LvglNode.Checkbox -> node.checked
        else -> false
    }

    private fun valueOf(node: LvglNode): Int = values[node.id] ?: when (node) {
        is LvglNode.Slider -> node.value
        is LvglNode.Progress -> node.value
        is LvglNode.Arc -> node.value
        else -> 0
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else mainHandler.post(action)
    }

    private companion object {
        const val HOST_VIEW_ID = AccessibilityNodeProvider.HOST_VIEW_ID
        const val NO_SCREEN = -1
        /** Virtual ids start at 1, so 0 means "nothing focused". */
        const val NO_FOCUS = 0
    }
}
