// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android

import com.lvgl.android.guards.GuardRules
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level guard tests. These fail the build when the documented hard
 * rules are violated, so problems are caught in CI before they reach a device.
 *
 * Detection lives in [GuardRules] so GuardCanaryTest can prove each rule both
 * catches a violation and ignores a clean fixture (no false green, no false
 * positive).
 *
 * Rules enforced (from AGENTS.md / ai-context.md):
 *  - LvglActivity subclasses must NOT call setContentView() (replaces the
 *    LVGL SurfaceView and causes the greyed-out "System UI isn't responding"
 *    screen). Switch content via LvglEngine.setScreen() instead.
 *  - No feature removals: all app types (IME, launcher, game, camera) must
 *    still exist as classes.
 *  - No hardcoded user-visible strings in activity sources.
 *
 * @breadcrumb BC-014
 * @guard-rule rule=no-set-content-view test=HardRuleGuardTest
 * @guard-rule rule=all-app-types test=HardRuleGuardTest
 * @guard-rule rule=no-hardcoded-strings test=HardRuleGuardTest
 */
class HardRuleGuardTest {

    /**
     * Locate the repository root by walking up from the test's working
     * directory. Hardcoding a relative path silently broke this suite: the
     * path resolved outside the repository, `File.exists()` was false, and two
     * of the three rules below became no-ops.
     */
    private val repoRoot: File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/java").isDirectory }
            ?: File("..").absoluteFile

    private val appSrc = File(repoRoot, "app/src/main/java")

    @Test
    fun guardTestCanSeeAppSources() {
        assertTrue(
            "Guard test cannot locate app sources (looked for app/src/main/java under $repoRoot)",
            appSrc.isDirectory
        )
    }

    @Test
    fun noSetContentViewInLvglActivitySubclasses() {
        val activities = appSrc.walkTopDown().filter { it.isFile && it.name.endsWith("Activity.kt") }
        val found = activities.toList()
        assertTrue("No Activity.kt files found under $appSrc", found.isNotEmpty())

        val violations = found.flatMap { f ->
            GuardRules.detectSetContentView(f.name, f.readText())
        }
        assertTrue(
            "LvglActivity subclasses must NOT call setContentView(): " +
                violations.joinToString("; ") { "${it.file}:${it.line} ${it.text}" },
            violations.isEmpty()
        )
    }

    @Test
    fun allAppTypesPresent() {
        // These classes must exist (no feature removals, AGENTS.md hard rule).
        GuardRules.requiredFeatures.forEach { (label, path) ->
            val f = File(repoRoot, "lvgl-android/src/main/java/com/lvgl/android/$path")
            assertTrue("Feature removed: $label ($path)", f.exists())
        }
    }

    @Test
    fun demoUiIsSingleSourceOfTruth() {
        // No duplication hard rule: screens live in demo_ui.*, never in the port.
        val header = File(repoRoot, "lvgl-android/src/main/cpp/demo_ui.h").readText()
        val impl = File(repoRoot, "lvgl-android/src/main/cpp/demo_ui.cpp").readText()
        val showcase = File(repoRoot, "lvgl-android/src/main/cpp/demo_showcase.cpp").readText()
        val port = File(repoRoot, "lvgl-android/src/main/cpp/lvgl_port.cpp").readText()
        val violations = GuardRules.detectDuplicateScreenBuilders(header, impl, showcase, port)
        assertTrue(
            "Screen builders must be declared in demo_ui.h, implemented in demo_ui.cpp " +
                "or demo_showcase.cpp, and never built in lvgl_port.cpp: " +
                violations.joinToString("; ") { "${it.file} ${it.text}" },
            violations.isEmpty()
        )
    }

    @Test
    fun noHardcodedUserStringsInActivities() {
        // User-visible text must come from resources, not string literals in
        // activity Kotlin files (except log tags, class refs, LVGL API keys).
        val activityFiles = appSrc.walkTopDown().filter {
            it.isFile && it.name.endsWith("Activity.kt")
        }.toList()
        assertTrue("No Activity.kt files found under $appSrc", activityFiles.isNotEmpty())

        val violations = activityFiles.flatMap { f ->
            GuardRules.detectHardcodedUserStrings(f.name, f.readText())
        }
        assertTrue(
            "Hardcoded user strings found (move to strings.xml): " +
                violations.joinToString("; ") { "${it.file}:${it.line} ${it.text}" },
            violations.isEmpty()
        )
    }
}
