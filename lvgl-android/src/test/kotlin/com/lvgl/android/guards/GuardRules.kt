// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.guards

import com.lvgl.android.annotations.SourceComments

/**
 * Pure detection logic for the source-level guard rules. Extracted from the
 * guard tests so the canary suite (GuardCanaryTest) can prove each detector
 * both catches a violation and ignores a clean fixture, and so the canaries
 * exercise the exact public functions the production tests call.
 *
 * @breadcrumb BC-014
 * @breadcrumb BC-031
 * @breadcrumb BC-035
 * @breadcrumb BC-036
 * @breadcrumb BC-037
 * @guard-rule rule=no-set-content-view test=HardRuleGuardTest
 * @guard-rule rule=all-app-types test=HardRuleGuardTest
 * @guard-rule rule=no-hardcoded-strings test=HardRuleGuardTest
 * @guard-rule rule=demo-ui-single-source test=HardRuleGuardTest
 * @guard-rule rule=dynamic-harness-crash-detection test=DynamicHarnessGuardTest
 * @guard-rule rule=play-compliance test=PlayComplianceGuardTest
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
        "layout-lint",
        "play-compliance",
        "dynamic-harness-crash-detection",
        "release-workflow",
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

    /** Apply every source-level hard rule to a set of named sources. Production
     *  tests and wiring canaries must call this same entry point. */
    fun detectHardRuleViolations(sources: List<Pair<String, String>>): List<Violation> =
        sources.flatMap { (name, content) ->
            detectSetContentView(name, content) + detectHardcodedUserStrings(name, content)
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

    /**
     * Extract the crash regex from the dynamic harness script, or null when the
     * script does not define `$crashPattern`.
     */
    fun dynamicHarnessCrashPattern(harness: String): String? =
        Regex("""\bcrashPattern\s*=\s*'([^']+)'""").find(harness)?.groupValues?.get(1)

    /**
     * Detect a crash pattern that is not scoped to the sample app.
     *
     * Broad markers ("FATAL", "start timeout", bare "SIGSEGV") also match
     * system-process noise on an unaccelerated CI emulator: the Bluetooth stack
     * logs "AdapterState: Start Timeout Delay: 4000" and then SIGABRTs in its own
     * process, which failed an otherwise healthy walk (BC-036).
     */
    fun detectUnscopedCrashPattern(harness: String): List<Violation> {
        val pattern = dynamicHarnessCrashPattern(harness)
            ?: return listOf(
                Violation(
                    file = "tools/dynamic-test.ps1",
                    line = 0,
                    text = "\$crashPattern",
                    reason = "harness must define the crash pattern"
                )
            )
        val broad = BROAD_CRASH_MARKERS.filter { it in pattern }
        if (broad.isEmpty()) return emptyList()
        return listOf(
            Violation(
                file = "tools/dynamic-test.ps1",
                line = 0,
                text = pattern,
                reason = "crash pattern must be scoped to com.lvgl.sample; " +
                    "broad markers match system-process noise: ${broad.joinToString(", ")}"
            )
        )
    }

    /**
     * Pure Google Play compliance detection. Missing files are represented by
     * absent keys (or blank values) in [repoFiles], so a broken checkout cannot
     * read as compliant. Attribute values, permission sets and XML elements are
     * parsed; a substring mention is never enough.
     */
    fun detectPlayComplianceViolations(
        appBuildText: String,
        manifestText: String,
        stringsText: String,
        repoFiles: Map<String, String>
    ): List<Violation> {
        val out = mutableListOf<Violation>()
        val manifestFile = "app/src/main/AndroidManifest.xml"
        val buildFile = "app/build.gradle.kts"

        val target = TARGET_SDK.find(appBuildText)?.groupValues?.get(1)?.toIntOrNull()
        when {
            target == null -> out.add(playViolation(buildFile, "targetSdk must be numeric"))
            target < 36 -> out.add(playViolation(buildFile, "Google Play requires targetSdk >= 36 (found $target)"))
        }

        val quotedValues = QUOTED_VALUE.findAll(appBuildText).map { it.groupValues[1] }.toSet()
        listOf("arm64-v8a", "x86_64").forEach { abi ->
            if (abi !in quotedValues) {
                out.add(playViolation(buildFile, "64-bit ABI $abi is required", abi))
            }
        }
        val minify = MINIFY.find(appBuildText)?.groupValues?.get(1)
        if (minify != "true") {
            out.add(playViolation(buildFile, "release must enable R8 (isMinifyEnabled = true)", minify ?: ""))
        }
        val shrink = SHRINK.find(appBuildText)?.groupValues?.get(1)
        if (shrink != "true") {
            out.add(playViolation(buildFile, "release must shrink resources (isShrinkResources = true)", shrink ?: ""))
        }

        listOf("enableOnBackInvokedCallback", "resizeableActivity").forEach { name ->
            val value = androidAttribute(manifestText, name)
            if (value != "true") {
                out.add(playViolation(manifestFile, "$name must be \"true\" (found ${value ?: "missing"})", name))
            }
        }
        listOf("dataExtractionRules", "fullBackupContent", "localeConfig").forEach { name ->
            val value = androidAttribute(manifestText, name)
            if (value.isNullOrBlank()) {
                out.add(playViolation(manifestFile, "$name must reference a resource", name))
            }
        }

        val permissions = USES_PERMISSION.findAll(manifestText).map { it.groupValues[1] }.toList()
        if (permissions != listOf("android.permission.CAMERA")) {
            out.add(
                playViolation(
                    manifestFile,
                    "the app must request only android.permission.CAMERA (found ${permissions.joinToString()})"
                )
            )
        }

        val queries = QUERIES_BLOCK.find(manifestText)?.value
        if (queries == null) {
            out.add(playViolation(manifestFile, "<queries> is required by the launcher sample"))
        } else {
            listOf("android.intent.action.MAIN", "android.intent.category.LAUNCHER").forEach { name ->
                if (name !in queries) out.add(playViolation(manifestFile, "<queries> must declare $name", name))
            }
        }

        val activities = ACTIVITY_BLOCK.findAll(manifestText).map { it.value }.toList()
        if (activities.isEmpty()) out.add(playViolation(manifestFile, "manifest declares no activities"))
        val mainActivity = activities.firstOrNull { "MainActivity" in it }
        if (mainActivity == null) {
            out.add(playViolation(manifestFile, "MainActivity must be declared"))
        } else {
            listOf("android.intent.action.MAIN", "android.intent.category.LAUNCHER").forEach { name ->
                if (name !in mainActivity) {
                    out.add(playViolation(manifestFile, "MainActivity must declare $name", name))
                }
            }
        }
        activities.forEach { block ->
            val name = androidAttribute(block, "name") ?: "<unnamed>"
            val exported = androidAttribute(block, "exported")
            val hasFilter = INTENT_FILTER.containsMatchIn(block)
            when {
                hasFilter && exported != "true" -> out.add(
                    playViolation(manifestFile, "$name has an intent filter and must be exported=\"true\" (found ${exported ?: "missing"})", name)
                )
                !hasFilter && exported != "false" -> out.add(
                    playViolation(manifestFile, "$name has no intent filter and must be exported=\"false\" (found ${exported ?: "missing"})", name)
                )
            }
        }

        mapOf(
            "app/src/main/res/xml/backup_rules.xml" to listOf("<full-backup-content", "<exclude"),
            "app/src/main/res/xml/data_extraction_rules.xml" to
                listOf("<data-extraction-rules", "<cloud-backup", "<device-transfer", "<exclude"),
            "app/src/main/res/xml/locales_config.xml" to
                listOf("<locale-config", "<locale ", "android:name=\"en\""),
        ).forEach { (path, elements) ->
            val xml = repoFiles[path] ?: ""
            if (xml.isBlank()) {
                out.add(playViolation(path, "$path must exist and be non-empty"))
            } else {
                elements.forEach { element ->
                    if (element !in xml) out.add(playViolation(path, "$path must contain $element", element))
                }
            }
        }
        listOf("PRIVACY.md", "docs/play-store.md").forEach { path ->
            if (repoFiles[path].isNullOrBlank()) {
                out.add(playViolation(path, "$path must exist and be non-empty"))
            }
        }
        if (!LICENSES_TEXT.containsMatchIn(stringsText)) {
            out.add(playViolation("app/src/main/res/values/strings.xml", "licenses_text must exist"))
        }
        val licensesActivity = "app/src/main/java/com/lvgl/sample/LicensesActivity.kt"
        if (repoFiles[licensesActivity].isNullOrBlank()) {
            out.add(playViolation(licensesActivity, "LicensesActivity must exist"))
        }
        return out
    }

    /**
     * Fail-closed check of the two release workflows. A workflow that silently
     * drops its emulator API level, its release smoke, its AAB bundling flag,
     * its lint gate or its size gate must fail the guard suite instead of
     * shipping an unverified release. The detection is purely textual: no
     * workflow file is read here, so the same code powers the canary fixtures
     * and the production test (BC-037).
     *
     * An explicitly named diagnostic `fallback` step may use an older API image
     * when no API 36 image is available; it never counts as target-level
     * coverage and must not mask the primary job's failure.
     */
    fun detectReleaseWorkflowGaps(ciText: String, releaseText: String): List<Violation> {
        val out = mutableListOf<Violation>()
        out.addAll(detectWorkflowApiLevel(ciText, CI_WORKFLOW))
        out.addAll(detectWorkflowApiLevel(releaseText, RELEASE_WORKFLOW))

        if (!SMOKE_ONLY_MARKER.containsMatchIn(releaseText)) {
            out.add(workflowViolation(RELEASE_WORKFLOW, "release smoke must run with SMOKE_ONLY=1"))
        }
        if (!CI_DYNAMIC_MARKER.containsMatchIn(releaseText)) {
            out.add(workflowViolation(RELEASE_WORKFLOW, "release smoke must invoke tools/ci-dynamic.sh"))
        }
        if (!releaseText.contains("-Plvgl.bundleNoSplits=true")) {
            out.add(workflowViolation(RELEASE_WORKFLOW, "AAB bundling must pass -Plvgl.bundleNoSplits=true"))
        }
        if (!ciText.contains("lintVitalRelease")) {
            out.add(workflowViolation(CI_WORKFLOW, "CI must run lintVitalRelease"))
        }
        if (!ciText.contains("size-report.ps1")) {
            out.add(workflowViolation(CI_WORKFLOW, "CI must run tools/size-report.ps1"))
        }
        out.addAll(detectContinueOnErrorOnSmoke(releaseText))
        return out
    }

    /** A primary emulator step must target API 36; only a clearly named
     *  diagnostic fallback step may mention an older image. */
    private fun detectWorkflowApiLevel(text: String, file: String): List<Violation> {
        val out = mutableListOf<Violation>()
        val lines = text.lines()
        val blocks = workflowStepBlocks(lines)
        val levels = mutableListOf<Pair<Int, String>>()
        lines.forEachIndexed { idx, line ->
            API_LEVEL.findAll(line).forEach { levels.add(idx to it.groupValues[1]) }
        }
        if (levels.none { it.second == "36" }) {
            out.add(workflowViolation(file, "$file must configure api-level: 36 (found: " +
                levels.joinToString { it.second } + ")"))
        }
        levels.filter { it.second != "36" }.forEach { (idx, level) ->
            val block = blocks.firstOrNull { idx in it.startLine until it.endLine }
            val diagnostic = block?.text?.contains("fallback", ignoreCase = true) == true
            if (!diagnostic) {
                out.add(
                    Violation(
                        file = file,
                        line = idx + 1,
                        text = lines[idx].trim(),
                        reason = "primary emulator step must use api-level: 36 (found $level)"
                    )
                )
            }
        }
        return out
    }

    /** `continue-on-error` in or immediately around the release smoke step
     *  would let a crashing release publish, so it is always a violation. */
    private fun detectContinueOnErrorOnSmoke(text: String): List<Violation> {
        val lines = text.lines()
        val out = mutableListOf<Violation>()
        workflowStepBlocks(lines).forEach { block ->
            val inStep = lines.subList(block.startLine, block.endLine)
            val isSmoke = inStep.any {
                SMOKE_ONLY_MARKER.containsMatchIn(it) || CI_DYNAMIC_MARKER.containsMatchIn(it)
            }
            if (!isSmoke) return@forEach
            val from = maxOf(0, block.startLine - 8)
            val until = minOf(lines.size, block.endLine + 1)
            for (idx in from until until) {
                if (CONTINUE_ON_ERROR.containsMatchIn(lines[idx])) {
                    out.add(
                        Violation(
                            file = RELEASE_WORKFLOW,
                            line = idx + 1,
                            text = lines[idx].trim(),
                            reason = "the release smoke step must not set continue-on-error"
                        )
                    )
                }
            }
        }
        return out
    }

    private data class WorkflowStep(val startLine: Int, val endLine: Int, val text: String)

    /** Split a workflow into YAML step blocks, including each step's leading
     *  comment lines so a `fallback` marker written above `- name:` is visible. */
    private fun workflowStepBlocks(lines: List<String>): List<WorkflowStep> {
        val starts = mutableListOf<Int>()
        lines.forEachIndexed { idx, line ->
            if (STEP_START.containsMatchIn(line)) starts.add(idx)
        }
        return starts.mapIndexed { i, start ->
            val end = starts.getOrElse(i + 1) { lines.size }
            val header = generateSequence(start - 1) { it - 1 }
                .takeWhile { it >= 0 && lines[it].trimStart().startsWith("#") }
                .lastOrNull() ?: start
            WorkflowStep(header, end, lines.subList(header, end).joinToString("\n"))
        }
    }

    private fun workflowViolation(file: String, reason: String): Violation =
        Violation(file = file, line = 0, text = "", reason = reason)

    /** Fail-closed check for tools/size-report.ps1: the ABI presence assertion
     *  must exist in the script itself, not just in CI configuration. */
    fun detectMissingAbiPresenceCheck(script: String): List<Violation> {
        val out = mutableListOf<Violation>()
        val file = "tools/size-report.ps1"
        val quoted = QUOTED_VALUE.findAll(script).map { it.groupValues[1] }.toSet()
        listOf("arm64-v8a", "x86_64", "universal").forEach { abi ->
            if (abi !in quoted) {
                out.add(Violation(file, 0, abi, "size report must assert the $abi release APK exists"))
            }
        }
        if (!MISSING_ABI_FAILURE.containsMatchIn(script)) {
            out.add(Violation(file, 0, "Write-Error", "size report must fail clearly when an ABI APK is missing"))
        }
        return out
    }

    private fun playViolation(file: String, reason: String, text: String = ""): Violation =
        Violation(file = file, line = 0, text = text, reason = reason)

    private fun androidAttribute(block: String, name: String): String? =
        Regex("""android:$name\s*=\s*"([^"]*)"""").find(block)?.groupValues?.get(1)

    /** Markers that also match system-process noise; never scope a crash check with these. */
    private val BROAD_CRASH_MARKERS = listOf("start timeout", "FATAL|", "|FATAL", "SIGSEGV")

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
        """(TAG =|Log\.|LvglNavigation\.pushScreen|\.java|::class|R\.string|""" +
            """\b(?:const\s+)?val\s+(?:ID|KEY|PREF)_[A-Z0-9_]*\s*=)"""
    )
    private val BUILDER_DECL = Regex("""void\s+(build_\w+_screen)\s*\([^)]*\)\s*;""")
    private val BUILDER_DEF = Regex("""void\s+(build_\w+_screen)\s*\([^)]*\)\s*\{""")
    private val TARGET_SDK = Regex("""targetSdk\s*=\s*(\d+)""")
    private val QUOTED_VALUE = Regex(""""([^"\r\n]+)"""")
    private val MINIFY = Regex("""isMinifyEnabled\s*=\s*(\w+)""")
    private val SHRINK = Regex("""isShrinkResources\s*=\s*(\w+)""")
    private val USES_PERMISSION = Regex("""<uses-permission[^>]*android:name="([^"]+)\"""")
    private val QUERIES_BLOCK = Regex("""<queries\b[^>]*>[\s\S]*?</queries>""")
    private val ACTIVITY_BLOCK = Regex("""<activity\b[^>]*/>|<activity\b[^>]*>[\s\S]*?</activity>""")
    private val INTENT_FILTER = Regex("""<intent-filter\b""")
    private val LICENSES_TEXT = Regex("""<string\s+name="licenses_text"\s*>""")
    private val MISSING_ABI_FAILURE =
        Regex("""(?is)missing[\s\S]{0,200}Write-Error|Write-Error[\s\S]{0,200}missing""")
    private const val CI_WORKFLOW = "ci.yml"
    private const val RELEASE_WORKFLOW = "release.yml"
    private val API_LEVEL = Regex("""api-level\s*:\s*(\d+)""")
    private val SMOKE_ONLY_MARKER = Regex("""SMOKE_ONLY\s*=\s*1""")
    private val CI_DYNAMIC_MARKER = Regex("""ci-dynamic\.sh""")
    private val CONTINUE_ON_ERROR = Regex("""continue-on-error""")
    private val STEP_START = Regex("""^\s*-\s+(?:name|uses)\s*:""")
    /* UI object creation in the port; lv_display_create/lv_indev_create are the
     * engine's own objects and are allowed there. */
    private val PORT_UI = Regex("""lv_(?!display_create|indev_create)\w+_create\s*\(""")
}
