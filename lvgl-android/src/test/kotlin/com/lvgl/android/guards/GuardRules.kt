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
        "wired-multiwindow-pip",
        "wired-gesture-results",
        "wired-performance-caller",
        "wired-launcher-receiver",
        "wired-accessibility-announcements",
        "wired-camera-permission",
        "wired-ui-event-bridge",
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
                // Annotation arguments (@SuppressLint("MissingPermission")) are
                // framework keys, not prose. Exclude only the literal that is
                // inside the annotation, never the rest of the line: a
                // user-visible literal following the annotation must still fail.
                if (ANNOTATION_ARGUMENT.containsMatchIn(line.substring(0, m.range.first))) {
                    return@forEach
                }
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

    // ---- feature wiring guards ---------------------------------------------
    //
    // A file existing is not evidence that the feature works. These detectors
    // read the CALL SITES, not just the class declarations, so an unwired
    // feature fails the build instead of leaving a green guard (BC-037).

    const val FEATURE_ACTIVITY = "LvglActivity.kt"
    const val FEATURE_INPUT_PROCESSOR = "LvglInputProcessor.kt"
    const val FEATURE_GAME_SAMPLE = "GameSampleActivity.kt"
    const val FEATURE_LAUNCHER = "LvglLauncherActivity.kt"
    const val FEATURE_ACCESSIBILITY_HELPER = "LvglAccessibilityHelper.kt"
    const val FEATURE_SCREEN_READER = "LvglScreenReader.kt"
    const val FEATURE_CAMERA_SAMPLE = "CameraSampleActivity.kt"
    const val FEATURE_UI_CPP = "lvgl_ui.cpp"
    const val FEATURE_PORT_CPP = "lvgl_port.cpp"

    /** Logical source key -> repository-relative path. Shared by the production
     *  guard and its canaries so the two cannot drift apart. */
    val featureSourcePaths: Map<String, String> = mapOf(
        FEATURE_ACTIVITY to "lvgl-android/src/main/java/com/lvgl/android/activity/LvglActivity.kt",
        FEATURE_INPUT_PROCESSOR to "lvgl-android/src/main/java/com/lvgl/android/input/LvglInputProcessor.kt",
        FEATURE_GAME_SAMPLE to "app/src/main/java/com/lvgl/sample/GameSampleActivity.kt",
        FEATURE_LAUNCHER to "lvgl-android/src/main/java/com/lvgl/android/launcher/LvglLauncherActivity.kt",
        // The helper lives in this file; the logical key names the component.
        FEATURE_ACCESSIBILITY_HELPER to
            "lvgl-android/src/main/java/com/lvgl/android/accessibility/LvglAccessibilityDelegate.kt",
        FEATURE_SCREEN_READER to
            "lvgl-android/src/main/java/com/lvgl/android/accessibility/LvglScreenReader.kt",
        FEATURE_CAMERA_SAMPLE to "app/src/main/java/com/lvgl/sample/CameraSampleActivity.kt",
        FEATURE_UI_CPP to "lvgl-android/src/main/cpp/lvgl_ui.cpp",
        FEATURE_PORT_CPP to "lvgl-android/src/main/cpp/lvgl_port.cpp",
    )

    /** Run every feature-wiring detector over one source map. Missing sources
     *  are violations, never silent skips, so a broken checkout fails closed. */
    fun detectUnwiredFeatures(sources: Map<String, String>): List<Violation> =
        detectMultiWindowWiring(sources) +
            detectGestureResultWiring(sources) +
            detectPerformanceSinkCaller(sources) +
            detectLauncherReceiverWiring(sources) +
            detectAccessibilityAnnouncementWiring(sources) +
            detectCameraPermissionWiring(sources) +
            detectUiEventBridgeWiring(sources)

    /** The activity must override both lifecycle callbacks and forward them to
     *  a MultiWindowManager it actually constructs. */
    fun detectMultiWindowWiring(sources: Map<String, String>): List<Violation> {
        val src = featureCode(sources, FEATURE_ACTIVITY) ?: return listOf(
            missingFeatureSource(FEATURE_ACTIVITY, "multi-window / PiP wiring")
        )
        val out = mutableListOf<Violation>()
        if (!MULTI_WINDOW_OVERRIDE.containsMatchIn(src)) {
            out.add(violation(FEATURE_ACTIVITY, "LvglActivity must override onMultiWindowModeChanged"))
        }
        if (!PIP_OVERRIDE.containsMatchIn(src)) {
            out.add(violation(FEATURE_ACTIVITY, "LvglActivity must override onPictureInPictureModeChanged"))
        }
        if (!MULTI_WINDOW_CONSTRUCT.containsMatchIn(src)) {
            out.add(violation(FEATURE_ACTIVITY, "LvglActivity must construct a MultiWindowManager"))
        }
        if (!MULTI_WINDOW_FORWARD.containsMatchIn(src)) {
            out.add(violation(FEATURE_ACTIVITY, "onMultiWindowModeChanged must forward to MultiWindowManager"))
        }
        if (!PIP_FORWARD.containsMatchIn(src)) {
            out.add(violation(FEATURE_ACTIVITY, "onPictureInPictureModeChanged must forward to MultiWindowManager"))
        }
        return out
    }

    /** Tap and long press are returned directly by GestureRecognizer.handle();
     *  only the queued gestures may come from consumeLastGesture(). Dropping the
     *  direct result silently swallowed every tap (BC-033). */
    fun detectGestureResultWiring(sources: Map<String, String>): List<Violation> {
        val src = featureCode(sources, FEATURE_INPUT_PROCESSOR) ?: return listOf(
            missingFeatureSource(FEATURE_INPUT_PROCESSOR, "gesture result wiring")
        )
        val out = mutableListOf<Violation>()
        if (!GESTURE_RECOGNIZER_CREATED.containsMatchIn(src)) {
            out.add(violation(FEATURE_INPUT_PROCESSOR, "input processor must create a GestureRecognizer"))
        }
        val direct = GESTURE_DIRECT_RESULT.find(src)
        if (direct == null) {
            out.add(
                violation(
                    FEATURE_INPUT_PROCESSOR,
                    "must capture GestureRecognizer.handle(...)'s direct result"
                )
            )
        } else if (!Regex("""return\s+${Regex.escape(direct.groupValues[1])}\s*\?:\s*""" +
                """recognizer\.consumeLastGesture\s*\(""").containsMatchIn(src)
        ) {
            out.add(
                violation(
                    FEATURE_INPUT_PROCESSOR,
                    "must return the direct handle() result (tap/long press) before " +
                        "falling back to consumeLastGesture()"
                )
            )
        }
        return out
    }

    /** The performance sink is only useful when something calls it; the game
     *  sample's frame loop is that caller. */
    fun detectPerformanceSinkCaller(sources: Map<String, String>): List<Violation> {
        val src = featureCode(sources, FEATURE_GAME_SAMPLE) ?: return listOf(
            missingFeatureSource(FEATURE_GAME_SAMPLE, "performance monitor caller")
        )
        if (PERFORMANCE_FRAME_CALL.containsMatchIn(src)) return emptyList()
        return listOf(
            violation(
                FEATURE_GAME_SAMPLE,
                "GameSampleActivity must call LvglPerformanceMonitor.onFrameRendered()"
            )
        )
    }

    /** The launcher must register its package receiver on start, unregister it
     *  on stop, listen for install/remove/change and dispatch all three app
     *  callbacks from onReceive(). */
    fun detectLauncherReceiverWiring(sources: Map<String, String>): List<Violation> {
        val src = featureCode(sources, FEATURE_LAUNCHER) ?: return listOf(
            missingFeatureSource(FEATURE_LAUNCHER, "package receiver wiring")
        )
        val out = mutableListOf<Violation>()
        if (!LAUNCHER_REGISTER.containsMatchIn(src)) {
            out.add(violation(FEATURE_LAUNCHER, "onStart must register the package receiver"))
        }
        if (!LAUNCHER_UNREGISTER.containsMatchIn(src)) {
            out.add(violation(FEATURE_LAUNCHER, "onStop must unregister the package receiver"))
        }
        listOf("ACTION_PACKAGE_ADDED", "ACTION_PACKAGE_REMOVED", "ACTION_PACKAGE_CHANGED").forEach { action ->
            if (!Regex("""Intent\.$action\b""").containsMatchIn(src)) {
                out.add(violation(FEATURE_LAUNCHER, "package receiver must listen for Intent.$action"))
            }
        }
        listOf("onPackageAdded", "onPackageRemoved", "onPackageChanged", "onAllAppsChanged").forEach { cb ->
            if (!Regex("""\b$cb\s*\(""").containsMatchIn(src)) {
                out.add(violation(FEATURE_LAUNCHER, "package receiver must dispatch the $cb callback"))
            }
        }
        return out
    }

    /** Announcements must reach a real AccessibilityEvent through
     *  LvglScreenReader; a Log.d() stub is not an announcement. */
    fun detectAccessibilityAnnouncementWiring(sources: Map<String, String>): List<Violation> {
        val helper = featureCode(sources, FEATURE_ACCESSIBILITY_HELPER) ?: return listOf(
            missingFeatureSource(FEATURE_ACCESSIBILITY_HELPER, "accessibility announcements")
        )
        val reader = featureCode(sources, FEATURE_SCREEN_READER) ?: return listOf(
            missingFeatureSource(FEATURE_SCREEN_READER, "accessibility announcements")
        )
        val out = mutableListOf<Violation>()
        if (!Regex("""object\s+LvglAccessibilityHelper\b""").containsMatchIn(helper)) {
            out.add(violation(FEATURE_ACCESSIBILITY_HELPER, "LvglAccessibilityHelper object is missing"))
        }
        val announce = ACCESSIBILITY_ANNOUNCE.find(helper)
        if (announce == null) {
            out.add(
                violation(
                    FEATURE_ACCESSIBILITY_HELPER,
                    "helper must expose announce(context, text)"
                )
            )
        } else if (!SCREEN_READER_DELEGATE.containsMatchIn(announce.value)) {
            out.add(
                violation(
                    FEATURE_ACCESSIBILITY_HELPER,
                    "announcements must delegate to LvglScreenReader, not just log"
                )
            )
        }
        if (!REAL_ACCESSIBILITY_EVENT.containsMatchIn(reader)) {
            out.add(
                violation(
                    FEATURE_SCREEN_READER,
                    "LvglScreenReader must post a real AccessibilityEvent, not just log"
                )
            )
        }
        return out
    }

    /** Opening a camera before the CAMERA grant is a SecurityException waiting
     *  to happen. The guard must be in the same function and before preview.open. */
    fun detectCameraPermissionWiring(sources: Map<String, String>): List<Violation> {
        val src = featureCode(sources, FEATURE_CAMERA_SAMPLE) ?: return listOf(
            missingFeatureSource(FEATURE_CAMERA_SAMPLE, "camera permission check")
        )
        val out = mutableListOf<Violation>()
        val open = CAMERA_OPEN.find(src)
        if (open == null) {
            out.add(violation(FEATURE_CAMERA_SAMPLE, "camera sample must open the preview"))
            return out
        }
        val functionStart = src.lastIndexOf("fun ", open.range.first)
        val beforeOpen = if (functionStart >= 0) src.substring(functionStart, open.range.first) else src
        val guard = CAMERA_GRANT_CHECK.find(beforeOpen)
        if (guard == null) {
            out.add(
                violation(
                    FEATURE_CAMERA_SAMPLE,
                    "camera sample must grant-check before preview.open()"
                )
            )
        } else {
            val afterGuard = beforeOpen.substring(guard.range.first)
            if (!Regex("""\bif\b""").containsMatchIn(beforeOpen) ||
                !Regex("""\breturn\b""").containsMatchIn(afterGuard)
            ) {
                out.add(
                    violation(
                        FEATURE_CAMERA_SAMPLE,
                        "the grant check must be an if (...) return guard before preview.open()"
                    )
                )
            }
        }
        if (!CAMERA_REQUEST_PERMISSION.containsMatchIn(src)) {
            out.add(violation(FEATURE_CAMERA_SAMPLE, "camera sample must request CAMERA when not granted"))
        }
        return out
    }

    /** Native UI node callbacks need a real JNI bridge: the builder emits via
     *  ui_emit_event() and the port forwards it to the Kotlin listener. */
    fun detectUiEventBridgeWiring(sources: Map<String, String>): List<Violation> {
        val ui = featureCode(sources, FEATURE_UI_CPP) ?: return listOf(
            missingFeatureSource(FEATURE_UI_CPP, "UI event bridge")
        )
        val port = featureCode(sources, FEATURE_PORT_CPP) ?: return listOf(
            missingFeatureSource(FEATURE_PORT_CPP, "UI event bridge")
        )
        val out = mutableListOf<Violation>()
        if (!UI_EMIT_CALL.containsMatchIn(ui)) {
            out.add(
                violation(
                    FEATURE_UI_CPP,
                    "lvgl_ui.cpp must emit node events through ui_emit_event()"
                )
            )
        }
        val definition = UI_EMIT_DEFINITION.find(port)
        if (definition == null) {
            out.add(violation(FEATURE_PORT_CPP, "lvgl_port.cpp must define ui_emit_event(...)"))
        } else {
            val body = port.substring(
                definition.range.last,
                minOf(port.length, definition.range.last + UI_EMIT_BODY_CHARS)
            )
            if (!JNI_EVENT_CALL.containsMatchIn(body) || !UI_EVENT_LISTENER.containsMatchIn(body)) {
                out.add(
                    violation(
                        FEATURE_PORT_CPP,
                        "ui_emit_event must forward events to the Kotlin listener, not be a no-op"
                    )
                )
            }
        }
        return out
    }

    private fun featureCode(sources: Map<String, String>, key: String): String? =
        sources[key]?.takeIf { it.isNotBlank() }?.let { SourceComments.stripComments(it) }

    private fun missingFeatureSource(key: String, feature: String): Violation =
        violation(key, "$feature source is missing or blank; cannot verify wiring")

    private fun violation(file: String, reason: String): Violation =
        Violation(file = file, line = 0, text = "", reason = reason)

    private val MULTI_WINDOW_OVERRIDE =
        Regex("""override\s+fun\s+onMultiWindowModeChanged\s*\(""")
    private val PIP_OVERRIDE =
        Regex("""override\s+fun\s+onPictureInPictureModeChanged\s*\(""")
    private val MULTI_WINDOW_CONSTRUCT = Regex("""MultiWindowManager\s*\(""")
    private val MULTI_WINDOW_FORWARD =
        Regex("""multiWindowManager\.onMultiWindowModeChanged\s*\(""")
    private val PIP_FORWARD =
        Regex("""multiWindowManager\.onPictureInPictureModeChanged\s*\(""")
    private val GESTURE_RECOGNIZER_CREATED = Regex("""GestureRecognizer\s*\(""")
    private val GESTURE_DIRECT_RESULT =
        Regex("""val\s+(\w+)\s*=\s*recognizer\.handle\s*\(""")
    private val PERFORMANCE_FRAME_CALL =
        Regex("""LvglPerformanceMonitor\.onFrameRendered\s*\(\s*\)""")
    private val LAUNCHER_REGISTER = Regex(
        """override\s+fun\s+onStart\s*\([^)]*\)[\s\S]{0,500}?registerReceiver\s*\(\s*packageReceiver"""
    )
    private val LAUNCHER_UNREGISTER = Regex(
        """override\s+fun\s+onStop\s*\([^)]*\)[\s\S]{0,500}?unregisterReceiver\s*\(\s*packageReceiver"""
    )
    private val ACCESSIBILITY_ANNOUNCE = Regex(
        """fun\s+announce\s*\([^)]*\)[\s\S]{0,300}?\}"""
    )
    private val SCREEN_READER_DELEGATE =
        Regex("""LvglScreenReader\.announce\s*\(\s*context\s*,\s*text\s*\)""")
    private val REAL_ACCESSIBILITY_EVENT = Regex(
        """AccessibilityEvent\.obtain\s*\([\s\S]{0,400}?sendAccessibilityEventUnchecked\s*\("""
    )
    private val CAMERA_OPEN = Regex("""preview\.open\s*\(""")
    private val CAMERA_GRANT_CHECK =
        Regex("""permissionGranted\s*\(\s*\)|checkSelfPermission\s*\(""")
    private val CAMERA_REQUEST_PERMISSION = Regex("""requestPermissions\s*\(""")
    private val UI_EMIT_CALL = Regex("""\bui_emit_event\s*\(""")
    private val UI_EMIT_DEFINITION = Regex("""void\s+ui_emit_event\s*\([^)]*\)\s*\{""")
    private const val UI_EMIT_BODY_CHARS = 800
    private val JNI_EVENT_CALL = Regex("""CallVoidMethod\s*\(""")
    private val UI_EVENT_LISTENER = Regex("""cb->event\b""")

    private val SET_CONTENT_VIEW = Regex("""\bsetContentView\s*\(""")
    private val STRING_LITERAL = Regex(""""([^"]*?)"""")
    private val ANNOTATION_ARGUMENT = Regex("""@\w+\s*\([^()]*$""")
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
