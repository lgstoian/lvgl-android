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
 * Machine-checkable Google Play readiness rules.
 *
 * Every rule is detected by [GuardRules.detectPlayComplianceViolations], the
 * same pure function the canary suite exercises with broken and clean fixtures.
 *
 * @breadcrumb BC-035
 * @breadcrumb BC-037
 * @guard-rule rule=play-compliance test=PlayComplianceGuardTest
 */
class PlayComplianceGuardTest {

    private val repoRoot: File = locateRepoRoot()
    private val appBuild: File = File(repoRoot, "app/build.gradle.kts")
    private val manifest: File = File(repoRoot, "app/src/main/AndroidManifest.xml")
    private val strings: File = File(repoRoot, "app/src/main/res/values/strings.xml")

    private fun locateRepoRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/java").isDirectory }
            ?: File("..").absoluteFile

    private fun violations(): List<GuardRules.Violation> {
        assertTrue("app/build.gradle.kts must exist", appBuild.isFile)
        assertTrue("AndroidManifest.xml must exist", manifest.isFile)
        assertTrue("strings.xml must exist", strings.isFile)
        val paths = listOf(
            "app/src/main/res/xml/backup_rules.xml",
            "app/src/main/res/xml/data_extraction_rules.xml",
            "app/src/main/res/xml/locales_config.xml",
            "PRIVACY.md",
            "docs/play-store.md",
            "app/src/main/java/com/lvgl/sample/LicensesActivity.kt",
        )
        val repoFiles = paths.associateWith { path ->
            val file = File(repoRoot, path)
            if (file.isFile) file.readText() else ""
        }
        return GuardRules.detectPlayComplianceViolations(
            appBuildText = appBuild.readText(),
            manifestText = manifest.readText(),
            stringsText = strings.readText(),
            repoFiles = repoFiles,
        )
    }

    private fun assertNoViolationMatching(part: String) {
        val hits = violations().filter { part in it.reason }
        assertTrue(
            "Play compliance violation '$part':\n" + hits.joinToString("\n") { it.reason },
            hits.isEmpty(),
        )
    }

    @Test
    fun playComplianceDetectorPassesTheRepo() {
        val hits = violations()
        assertTrue(
            "Play compliance violations:\n" + hits.joinToString("\n") { "${it.file}: ${it.reason}" },
            hits.isEmpty(),
        )
    }

    @Test
    fun targetsCurrentApiLevel() {
        assertNoViolationMatching("targetSdk")
    }

    @Test
    fun ships64BitAbis() {
        assertNoViolationMatching("64-bit ABI")
    }

    @Test
    fun releaseBuildIsOptimised() {
        assertNoViolationMatching("isMinifyEnabled")
        assertNoViolationMatching("isShrinkResources")
    }

    @Test
    fun manifestDeclaresPlayAttributes() {
        listOf(
            "dataExtractionRules", "fullBackupContent", "enableOnBackInvokedCallback",
            "resizeableActivity", "localeConfig",
        ).forEach { assertNoViolationMatching(it) }
    }

    @Test
    fun declaresOnlyTheRequiredPermissions() {
        assertNoViolationMatching("permission.CAMERA")
    }

    @Test
    fun declaresQueriesForPackageVisibility() {
        assertNoViolationMatching("<queries>")
    }

    @Test
    fun mainActivityIsLaunchable() {
        assertNoViolationMatching("MainActivity")
        assertNoViolationMatching("no activities")
    }

    @Test
    fun activityExportedFlagsMatchIntentFilters() {
        assertNoViolationMatching("must be exported")
    }

    @Test
    fun backupAndLocaleResourcesExist() {
        assertNoViolationMatching("app/src/main/res/xml/")
    }

    @Test
    fun privacyPolicyAndPlayChecklistExist() {
        assertNoViolationMatching("PRIVACY.md")
        assertNoViolationMatching("docs/play-store.md")
    }

    @Test
    fun disclosesOpenSourceLicenses() {
        assertNoViolationMatching("licenses_text")
        assertNoViolationMatching("LicensesActivity")
    }
}
