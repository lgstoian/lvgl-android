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
 * Guards the size report's fail-closed ABI assertion: a missing arm64-v8a,
 * x86_64 or universal release APK must fail the script, not be silently
 * skipped by the size budget loop.
 *
 * @breadcrumb BC-031
 * @breadcrumb BC-037
 */
class ReleaseArtifactGuardTest {

    private val repoRoot: File = locateRepoRoot()
    private val script: File = File(repoRoot, "tools/size-report.ps1")

    private fun locateRepoRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/java").isDirectory }
            ?: File("..").absoluteFile

    @Test
    fun sizeReportAssertsEveryExpectedAbi() {
        assertTrue("tools/size-report.ps1 must exist", script.isFile)
        val violations = GuardRules.detectMissingAbiPresenceCheck(script.readText())
        assertTrue(
            "size report must assert all expected release APKs exist:\n" +
                violations.joinToString("\n") { it.reason },
            violations.isEmpty(),
        )
    }
}
