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
 * @breadcrumb BC-037
 * @guard-rule rule=layout-lint test=LayoutLintGuardTest
 */
object LayoutLintRules {

    /** Sentinel type for a broken report: never allow-listable. */
    const val INVALID_REPORT_TYPE = "invalid-report"

    /** A single layout problem reported by the native lint. */
    data class Issue(
        val type: String,        // "overlap" | "bounds" | "text" | "invalid-report"
        val rect: List<Int>,     // [x1, y1, x2, y2]
        val rect2: List<Int>,    // second rect for overlaps
        val child: String,       // LVGL class of the offending child, or parse detail
        val parent: String       // LVGL class of its parent
    )

    /** Parse a report. Malformed input yields an [INVALID_REPORT_TYPE] issue so a
     *  broken lint run fails closed instead of looking like a clean report. */
    fun parseIssues(json: String): List<Issue> {
        val root = MiniJson.parse(json)
        if (root !is Map<*, *>) return listOf(invalid("report is not a JSON object"))
        val rawIssues = root["issues"]
        if (rawIssues !is List<*>) return listOf(invalid("report has no issues array"))
        return rawIssues.flatMap { raw ->
            if (raw !is Map<*, *>) return@flatMap listOf(invalid("issue is not an object"))
            val type = raw["type"] as? String
            if (type.isNullOrBlank()) return@flatMap listOf(invalid("issue has no type: $raw"))
            val rect = intList(raw["rect"])
            if (rect.size < 4) return@flatMap listOf(invalid("$type issue has no valid rect"))
            listOf(
                Issue(
                    type = type,
                    rect = rect,
                    rect2 = intList(raw["rect2"]),
                    child = raw["child"] as? String ?: "",
                    parent = raw["parent"] as? String ?: ""
                )
            )
        }
    }

    /**
     * Returns a human-readable violation for every issue whose type is not in
     * [allowedTypes] (an allow-list for intentional cases). Invalid reports are
     * always violations, even if an allow-list mentions the sentinel type.
     */
    fun validate(issues: List<Issue>, allowedTypes: Set<String> = emptySet()): List<String> =
        issues.filter { it.type == INVALID_REPORT_TYPE || it.type !in allowedTypes }.map { issue ->
            if (issue.type == INVALID_REPORT_TYPE) return@map "$INVALID_REPORT_TYPE: ${issue.child}"
            val where = if (issue.rect.size >= 4) {
                " at (${issue.rect[0]},${issue.rect[1]} ${issue.rect[2]},${issue.rect[3]})"
            } else {
                ""
            }
            "${issue.type}: ${issue.child} in ${issue.parent}$where"
        }

    fun validateJson(json: String, allowedTypes: Set<String> = emptySet()): List<String> =
        validate(parseIssues(json), allowedTypes)

    private fun invalid(detail: String): Issue =
        Issue(INVALID_REPORT_TYPE, emptyList(), emptyList(), detail, "report")

    private fun intList(raw: Any?): List<Int> =
        (raw as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()
}
