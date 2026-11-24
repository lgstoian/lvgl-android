// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.guards

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the CI and release workflow configuration: API-36 emulator coverage,
 * the release smoke that blocks publishing, AAB bundling without ABI splits,
 * lintVitalRelease and the size report must all survive edits to the YAML.
 *
 * Detection lives in GuardRules; GuardCanaryTest proves the detector catches
 * broken fixtures and accepts a clean one (no false green, no false positive).
 *
 * @breadcrumb BC-037
 * @guard-rule rule=release-workflow test=ReleaseWorkflowGuardTest
 */
class ReleaseWorkflowGuardTest {

    private val repoRoot: File = locateRepoRoot()
    private val workflowDir: File = File(repoRoot, ".github/workflows")
    private val ciWorkflow: File = File(workflowDir, "ci.yml")
    private val releaseWorkflow: File = File(workflowDir, "release.yml")

    private fun locateRepoRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/java").isDirectory }
            ?: File("..").absoluteFile

    @Test
    fun guardTestCanSeeWorkflows() {
        assertTrue(
            "Guard test cannot locate workflows (looked under $workflowDir)",
            workflowDir.isDirectory
        )
        assertTrue("CI workflow missing: ${ciWorkflow.path}", ciWorkflow.isFile)
        assertTrue("Release workflow missing: ${releaseWorkflow.path}", releaseWorkflow.isFile)
    }

    @Test
    fun workflowsConfigureEveryRequiredReleaseGate() {
        assertTrue("CI workflow missing: ${ciWorkflow.path}", ciWorkflow.isFile)
        assertTrue("Release workflow missing: ${releaseWorkflow.path}", releaseWorkflow.isFile)
        val violations = GuardRules.detectReleaseWorkflowGaps(
            ciWorkflow.readText(), releaseWorkflow.readText()
        )
        assertTrue(
            "CI and release workflows must configure all release gates:\n" +
                violations.joinToString("\n") { "${it.file}:${it.line} ${it.reason}" },
            violations.isEmpty()
        )
    }
}
