// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.guards

import com.lvgl.android.annotations.MiniJson

/**
 * Pure validation logic for the native layout lint report
 * (`LvglEngine.dumpLayoutReport()`). The native side walks the built UI tree
 * and reports overlapping siblings, children outside their parent and clipped
 * text; this object turns that JSON into violations CI can assert on.
 *
 * @breadcrumb BC-027
 * @guard-rule rule=layout-lint test=LayoutLintGuardTest
 */
object LayoutLintRules {

    /** A single layout problem reported by the native lint. */
    data class Issue(
        val type: String,        // "overlap" | "bounds" | "text"
        val rect: List<Int>,     // [x1, y1, x2, y2]
        val rect2: List<Int>,    // second rect for overlaps
        val child: String,       // LVGL class of the offending child
        val parent: String       // LVGL class of its parent
    )

    /** Parse a report; malformed entries are skipped rather than fatal. */
    fun parseIssues(json: String): List<Issue> {
        val root = MiniJson.parse(json) as? Map<*, *> ?: return emptyList()
        val issues = root["issues"] as? List<*> ?: return emptyList()
        return issues.mapNotNull { raw ->
            val e = raw as? Map<*, *> ?: return@mapNotNull null
            val type = e["type"] as? String ?: return@mapNotNull null
            Issue(
                type = type,
                rect = intList(e["rect"]),
                rect2 = intList(e["rect2"]),
                child = e["child"] as? String ?: "",
                parent = e["parent"] as? String ?: ""
            )
        }
    }

    /**
     * Returns a human-readable violation for every issue whose type is not in
     * [allowedTypes] (an allow-list for intentional cases).
     */
    fun validate(issues: List<Issue>, allowedTypes: Set<String> = emptySet()): List<String> =
        issues.filter { it.type !in allowedTypes }.map { issue ->
            val where = if (issue.rect.size >= 4) {
                " at (${issue.rect[0]},${issue.rect[1]} ${issue.rect[2]},${issue.rect[3]})"
            } else {
                ""
            }
            "${issue.type}: ${issue.child} in ${issue.parent}$where"
        }

    fun validateJson(json: String, allowedTypes: Set<String> = emptySet()): List<String> =
        validate(parseIssues(json), allowedTypes)

    private fun intList(raw: Any?): List<Int> =
        (raw as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()
}
