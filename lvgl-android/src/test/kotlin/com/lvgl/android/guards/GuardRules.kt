// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.guards

import com.lvgl.android.annotations.SourceComments

/**
 * Pure detection logic for the source-level guard rules. Extracted from
 * HardRuleGuardTest so the canary suite (GuardCanaryTest) can prove each
 * detector both catches a violation and ignores a clean fixture.
 *
 * @breadcrumb BC-014
 * @guard-rule rule=no-set-content-view test=HardRuleGuardTest
 * @guard-rule rule=all-app-types test=HardRuleGuardTest
 * @guard-rule rule=no-hardcoded-strings test=HardRuleGuardTest
 * @guard-rule rule=demo-ui-single-source test=HardRuleGuardTest
 */
object GuardRules {

    /** Registry of implemented guard rules; MachineCommentValidationTest asserts
     *  that every guard-rule annotation names one of these. */
    val RULE_NAMES: Set<String> = setOf(
        "no-set-content-view",
        "all-app-types",
        "no-hardcoded-strings",
        "demo-ui-single-source",
        "machine-comments",
        "guard-canary",
    )

    data class Violation(
        val file: String,
        val line: Int,
        val text: String,
        val reason: String
    )

    /**
     * Detect `setContentView(` calls in a LvglActivity subclass. Comments are
     * stripped first so a mention in a doc comment is not a false positive.
     */
    fun detectSetContentView(fileName: String, content: String): List<Violation> {
        val stripped = SourceComments.stripComments(content)
        val out = mutableListOf<Violation>()
        stripped.lines().forEachIndexed { idx, line ->
            if (SET_CONTENT_VIEW.containsMatchIn(line)) {
                out.add(
                    Violation(
                        file = fileName,
                        line = idx + 1,
                        text = line.trim(),
                        reason = "LvglActivity subclasses must NOT call setContentView()"
                    )
                )
            }
        }
        return out
    }

    /**
     * Detect user-visible string literals in an activity source. Log tags,
     * class refs, resource lookups and identifier-like keys are ignored.
     */
    fun detectHardcodedUserStrings(fileName: String, content: String): List<Violation> {
        val out = mutableListOf<Violation>()
        content.lines().forEachIndexed { idx, line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                return@forEachIndexed
            }
            if (IGNORED_LINE.containsMatchIn(line)) return@forEachIndexed
            STRING_LITERAL.findAll(line).forEach { m ->
                val text = m.groupValues[1]
                if (isUserVisible(text)) {
                    out.add(
                        Violation(
                            file = fileName,
                            line = idx + 1,
                            text = "\"$text\"",
                            reason = "user-visible string must come from strings.xml"
                        )
                    )
                }
            }
        }
        return out
    }

    /** True for literals that look like user-visible prose rather than keys. */
    fun isUserVisible(text: String): Boolean {
        if (text.length <= 3) return false
        if (!text[0].isLetter()) return false
        if (text.startsWith("LVGL") || text.startsWith("com.") || text.startsWith("android")) return false
        if (text.contains("R.string") || text.contains("::class") || text.contains(".java")) return false
        if (text.all { it.isUpperCase() || it == '_' || it.isDigit() }) return false
        return true
    }

    /**
     * Detect duplicated screen builders: every builder declared in demo_ui.h
     * must be implemented in demo_ui.cpp or demo_showcase.cpp, and the port
     * must not create LVGL objects itself (no duplication hard rule, BC-016).
     */
    fun detectDuplicateScreenBuilders(
        header: String,
        impl: String,
        showcase: String,
        port: String
    ): List<Violation> {
        val out = mutableListOf<Violation>()
        val declared = BUILDER_DECL.findAll(header).map { it.groupValues[1] }.toSet()
        val defined = BUILDER_DEF.findAll(impl).map { it.groupValues[1] }.toSet()
        val definedShowcase = BUILDER_DEF.findAll(showcase).map { it.groupValues[1] }.toSet()
        declared.sorted().forEach { name ->
            if (name !in defined && name !in definedShowcase) {
                out.add(
                    Violation(
                        file = "demo_ui.h",
                        line = 0,
                        text = name,
                        reason = "declared but not implemented in demo_ui.cpp/demo_showcase.cpp"
                    )
                )
            }
        }
        PORT_UI.findAll(port).forEach { m ->
            out.add(
                Violation(
                    file = "lvgl_port.cpp",
                    line = 0,
                    text = m.value,
                    reason = "port must not build LVGL UI; use demo_ui.cpp/demo_showcase.cpp"
                )
            )
        }
        return out
    }

    /** Framework components that must never be removed (no feature removals). */
    val requiredFeatures: Map<String, String> = mapOf(
        "IME service" to "input/LvglInputMethodService.kt",
        "Launcher activity" to "launcher/LvglLauncherActivity.kt",
        "Game activity" to "game/LvglGameActivity.kt",
        "Camera preview" to "camera/LvglCameraPreview.kt",
        "Engine" to "engine/LvglEngine.kt",
        "Activity base" to "activity/LvglActivity.kt",
        "Surface view" to "view/LvglSurfaceView.kt",
        "Material theme" to "theme/MaterialTheme.kt",
        "Theme bridge" to "theme/LvglThemeBridge.kt",
        "DPI manager" to "config/DpiManager.kt",
        "Display metrics" to "config/DisplayMetricsManager.kt",
        "Configuration" to "config/ConfigurationManager.kt",
        "Multiwindow" to "window/MultiWindowManager.kt",
        "Navigation" to "navigation/LvglNavigation.kt",
        "Input processor" to "input/LvglInputProcessor.kt",
        "Gesture recognizer" to "input/LvglGestureRecognizer.kt",
        "Hardware keyboard" to "input/HardwareKeyboardHandler.kt",
        "Performance monitor" to "perf/LvglPerformanceMonitor.kt",
        "Accessibility manager" to "accessibility/LvglAccessibilityManager.kt",
        "Focus navigation" to "accessibility/LvglFocusNavigation.kt",
        "Screen reader" to "accessibility/LvglScreenReader.kt",
    )

    private val SET_CONTENT_VIEW = Regex("""\bsetContentView\s*\(""")
    private val STRING_LITERAL = Regex(""""([^"]*?)"""")
    private val IGNORED_LINE = Regex(
        """(TAG =|Log\.|LvglNavigation\.pushScreen|\.java|::class|R\.string)"""
    )
    private val BUILDER_DECL = Regex("""void\s+(build_\w+_screen)\s*\([^)]*\)\s*;""")
    private val BUILDER_DEF = Regex("""void\s+(build_\w+_screen)\s*\([^)]*\)\s*\{""")
    /* UI object creation in the port; lv_display_create/lv_indev_create are the
     * engine's own objects and are allowed there. */
    private val PORT_UI = Regex("""lv_(?!display_create|indev_create)\w+_create\s*\(""")
}
