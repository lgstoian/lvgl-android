// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.guards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Machine-checkable Google Play readiness rules.
 *
 * Each test maps to a published Play requirement so a regression fails the build
 * instead of being caught at review time.
 *
 * @breadcrumb BC-035
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

    @Test
    fun targetsCurrentApiLevel() {
        val text = appBuild.readText()
        val target = Regex("""targetSdk\s*=\s*(\d+)""")
            .find(text)?.groupValues?.get(1)?.toIntOrNull()
        assertNotNull("targetSdk must be set in app/build.gradle.kts", target)
        assertTrue("Google Play requires targetSdk >= 36 (found $target)", target!! >= 36)
    }

    @Test
    fun ships64BitAbis() {
        val text = appBuild.readText()
        assertTrue("arm64-v8a is required", text.contains("arm64-v8a"))
    }

    @Test
    fun releaseBuildIsOptimised() {
        val text = appBuild.readText()
        assertTrue("release must enable R8", text.contains("isMinifyEnabled = true"))
        assertTrue("release must shrink resources", text.contains("isShrinkResources = true"))
    }

    @Test
    fun manifestDeclaresPlayAttributes() {
        val text = manifest.readText()
        assertTrue("dataExtractionRules", text.contains("android:dataExtractionRules"))
        assertTrue("fullBackupContent", text.contains("android:fullBackupContent"))
        assertTrue("enableOnBackInvokedCallback", text.contains("android:enableOnBackInvokedCallback"))
        assertTrue("resizeableActivity", text.contains("android:resizeableActivity"))
        assertTrue("localeConfig", text.contains("android:localeConfig"))
    }

    @Test
    fun declaresOnlyTheRequiredPermissions() {
        val permissions = Regex("""<uses-permission[^>]*android:name="([^"]+)\"""")
            .findAll(manifest.readText())
            .map { it.groupValues[1] }
            .toList()
        assertEquals(
            "the app must request only the permissions it needs",
            listOf("android.permission.CAMERA"),
            permissions,
        )
    }

    @Test
    fun declaresQueriesForPackageVisibility() {
        val text = manifest.readText()
        assertTrue("<queries> is required by the launcher sample", text.contains("<queries>"))
        assertTrue("LAUNCHER category is required", text.contains("android.intent.category.LAUNCHER"))
    }

    @Test
    fun backupAndLocaleResourcesExist() {
        listOf("backup_rules.xml", "data_extraction_rules.xml", "locales_config.xml").forEach { name ->
            assertTrue("$name must exist", File(repoRoot, "app/src/main/res/xml/$name").isFile)
        }
    }

    @Test
    fun privacyPolicyAndPlayChecklistExist() {
        assertTrue("PRIVACY.md must exist", File(repoRoot, "PRIVACY.md").isFile)
        assertTrue("docs/play-store.md must exist", File(repoRoot, "docs/play-store.md").isFile)
    }

    @Test
    fun disclosesOpenSourceLicenses() {
        assertTrue(
            "licenses_text must exist",
            strings.readText().contains("name=\"licenses_text\""),
        )
        assertTrue(
            "LicensesActivity must exist",
            File(repoRoot, "app/src/main/java/com/lvgl/sample/LicensesActivity.kt").isFile,
        )
    }
}
