// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android

import com.lvgl.android.guards.LayoutLintRules
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the layout-lint report validator detects real problems and ignores a
 * clean report (no false green, no false positive). Mirrors GuardCanaryTest.
 *
 * @breadcrumb BC-027
 * @guard-rule rule=layout-lint test=LayoutLintGuardTest
 */
class LayoutLintGuardTest {

    private val clean = """{"issues":[]}"""

    private val overlap = """
        {"issues":[
          {"type":"overlap","rect":[44,0,266,143],"rect2":[300,0,779,143],
           "child":"label","parent":"obj"}
        ]}
    """.trimIndent()

    private val bounds = """
        {"issues":[
          {"type":"bounds","rect":[44,-168,523,311],"rect2":[],
           "child":"obj","parent":"obj"}
        ]}
    """.trimIndent()

    private val text = """
        {"issues":[
          {"type":"text","rect":[468,240,611,279],"rect2":[],
           "child":"label","parent":"obj"}
        ]}
    """.trimIndent()

    @Test
    fun parsesIssueFields() {
        val issues = LayoutLintRules.parseIssues(overlap)
        assertTrue("expected one issue, got ${issues.size}", issues.size == 1)
        val it = issues[0]
        assertTrue("type", it.type == "overlap")
        assertTrue("child", it.child == "label")
        assertTrue("parent", it.parent == "obj")
        assertTrue("rect", it.rect == listOf(44, 0, 266, 143))
        assertTrue("rect2", it.rect2 == listOf(300, 0, 779, 143))
    }

    @Test
    fun cleanReportHasNoViolations() {
        assertTrue(
            "clean report must not fail",
            LayoutLintRules.validateJson(clean).isEmpty()
        )
    }

    @Test
    fun detectsOverlap() {
        assertTrue(
            "overlap must be reported",
            LayoutLintRules.validateJson(overlap).isNotEmpty()
        )
    }

    @Test
    fun detectsOutOfBounds() {
        assertTrue(
            "bounds issue must be reported",
            LayoutLintRules.validateJson(bounds).isNotEmpty()
        )
    }

    @Test
    fun detectsClippedText() {
        assertTrue(
            "text overflow must be reported",
            LayoutLintRules.validateJson(text).isNotEmpty()
        )
    }

    @Test
    fun allowListSilencesAnIntentionalCase() {
        val violations = LayoutLintRules.validateJson(overlap, allowedTypes = setOf("overlap"))
        assertTrue("allow-listed type must not fail", violations.isEmpty())
    }

    @Test
    fun malformedReportYieldsNoIssuesRatherThanThrowing() {
        assertTrue(LayoutLintRules.parseIssues("not json").isEmpty())
        assertTrue(LayoutLintRules.parseIssues("{\"issues\":[{}]}").isEmpty())
        assertTrue(LayoutLintRules.validateJson("not json").isEmpty())
    }
}
