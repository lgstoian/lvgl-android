// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.ui

/**
 * Minimal JSON writer for the app-screen wire format.
 *
 * Hand-rolled so the library carries no JSON dependency and the unit tests run
 * on the JVM. Only what [LvglNode] needs is supported: objects, arrays,
 * strings, integers and booleans.
 *
 * @breadcrumb BC-033
 */
internal object LvglJson {

    fun escape(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '' -> sb.append("\\f")
                else -> if (ch < ' ') {
                    sb.append("\\u").append("%04x".format(ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        return sb.toString()
    }

    fun encode(nodes: List<LvglNode>): String = encodeWithIds(nodes).first

    /**
     * Encodes the tree and also returns the nodes with their final ids, so the
     * caller can build a node-id -> callback map for event dispatch.
     */
    internal fun encodeWithIds(nodes: List<LvglNode>): Pair<String, List<LvglNode>> {
        val assigned = assignNodeIds(nodes)
        val json = assigned.joinToString(",", "[", "]") { encodeNode(it) }
        return json to assigned
    }

    private fun encodeNode(node: LvglNode): String {
        val o = JsonObject()
        o.put("id", node.id)
        when (node) {
            is LvglNode.Column -> {
                o.put("t", "column")
                o.put("card", node.card)
                o.put("gap", node.gapDp)
                o.put("pad", node.padDp)
                o.putRaw("children", childArray(node.children))
            }
            is LvglNode.Row -> {
                o.put("t", "row")
                o.put("card", node.card)
                o.put("gap", node.gapDp)
                o.put("pad", node.padDp)
                o.putRaw("children", childArray(node.children))
            }
            is LvglNode.Label -> {
                o.put("t", "label")
                o.put("text", node.text)
                o.put("size", node.sizeDp)
            }
            is LvglNode.Button -> {
                o.put("t", "button")
                o.put("text", node.text)
                o.put("style", node.style.name.lowercase())
                o.put("danger", node.danger)
                o.put("fill", node.fillWidth)
            }
            is LvglNode.Slider -> {
                o.put("t", "slider")
                o.put("value", node.value)
                o.put("min", node.min)
                o.put("max", node.max)
            }
            is LvglNode.Switch -> {
                o.put("t", "switch")
                o.put("checked", node.checked)
            }
            is LvglNode.Checkbox -> {
                o.put("t", "checkbox")
                o.put("text", node.text)
                o.put("checked", node.checked)
            }
            is LvglNode.Progress -> {
                o.put("t", "progress")
                o.put("value", node.value)
                o.put("max", node.max)
            }
            is LvglNode.Arc -> {
                o.put("t", "arc")
                o.put("value", node.value)
                o.put("max", node.max)
            }
            is LvglNode.Dropdown -> {
                o.put("t", "dropdown")
                o.put("selected", node.selectedIndex)
                val opts = JsonArray()
                node.options.forEach { opts.add("\"${LvglJson.escape(it)}\"") }
                o.putRaw("options", opts.toString())
            }
            is LvglNode.Divider -> o.put("t", "divider")
            is LvglNode.Spacer -> {
                o.put("t", "spacer")
                o.put("size", node.sizeDp)
            }
        }
        return o.toString()
    }

    private fun childArray(children: List<LvglNode>): String {
        val arr = JsonArray()
        children.forEach { arr.add(encodeNode(it)) }
        return arr.toString()
    }
}

internal class JsonObject {
    private val parts = mutableListOf<String>()

    fun put(key: String, value: String) {
        parts.add("\"${LvglJson.escape(key)}\":\"${LvglJson.escape(value)}\"")
    }

    fun put(key: String, value: Int) {
        parts.add("\"${LvglJson.escape(key)}\":$value")
    }

    fun put(key: String, value: Boolean) {
        parts.add("\"${LvglJson.escape(key)}\":$value")
    }

    /** Insert an already-serialised JSON value (array/object) without quoting. */
    fun putRaw(key: String, raw: String) {
        parts.add("\"${LvglJson.escape(key)}\":$raw")
    }

    override fun toString(): String = parts.joinToString(",", "{", "}")
}

private class JsonArray {
    private val parts = mutableListOf<String>()

    fun add(value: String) {
        parts.add(value)
    }

    override fun toString(): String = parts.joinToString(",", "[", "]")
}
