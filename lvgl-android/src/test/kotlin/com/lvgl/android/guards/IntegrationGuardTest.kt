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
 * Fail-closed integration guards: a feature's source file existing is not
 * evidence that the feature is wired. Every check below reads the CALL SITES —
 * the forwarded lifecycle callback, the returned gesture result, the caller of
 * the perf sink, the registered receiver, the real accessibility event, the
 * grant check before camera open, and the native JNI event bridge.
 *
 * Detection lives in [GuardRules] so [GuardCanaryTest] can feed each detector a
 * broken fixture it must catch and a clean fixture it must accept. This test
 * locates the repo root by walking up and asserts every source exists, so a
 * broken path fails loudly instead of silently checking nothing (BC-037).
 *
 * @breadcrumb BC-037
 * @guard-rule rule=wired-multiwindow-pip test=IntegrationGuardTest
 * @guard-rule rule=wired-gesture-results test=IntegrationGuardTest
 * @guard-rule rule=wired-performance-caller test=IntegrationGuardTest
 * @guard-rule rule=wired-launcher-receiver test=IntegrationGuardTest
 * @guard-rule rule=wired-accessibility-announcements test=IntegrationGuardTest
 * @guard-rule rule=wired-camera-permission test=IntegrationGuardTest
 * @guard-rule rule=wired-ui-event-bridge test=IntegrationGuardTest
 */
class IntegrationGuardTest {

    private val repoRoot: File = locateRepoRoot()

    private fun locateRepoRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/java").isDirectory }
            ?: File("..").absoluteFile

    /** Read every source the wiring detectors need. Missing or blank sources
     *  fail the test here; the detectors also fail closed on their own so the
     *  canaries can prove it. */
    private fun featureSources(): Map<String, String> {
        assertTrue(
            "Cannot locate repo sources (looked for app/src/main/java under $repoRoot)",
            File(repoRoot, "app/src/main/java").isDirectory
        )
        return GuardRules.featureSourcePaths.mapValues { (key, relativePath) ->
            val file = File(repoRoot, relativePath)
            assertTrue("Feature source missing for $key: ${file.path}", file.isFile)
            val text = file.readText()
            assertTrue("Feature source blank for $key: ${file.path}", text.isNotBlank())
            text
        }
    }

    private fun assertClean(hits: List<GuardRules.Violation>) {
        assertTrue(
            "Unwired features found:\n" +
                hits.joinToString("\n") { "${it.file}: ${it.reason}" },
            hits.isEmpty()
        )
    }

    @Test
    fun guardCanSeeEveryFeatureSource() {
        val sources = featureSources()
        assertTrue(
            "Not all feature-wiring sources were loaded",
            sources.keys.containsAll(GuardRules.featureSourcePaths.keys)
        )
    }

    @Test
    fun multiWindowAndPipCallbacksAreForwarded() {
        assertClean(GuardRules.detectMultiWindowWiring(featureSources()))
    }

    @Test
    fun gestureRecognizerDirectResultIsReturned() {
        assertClean(GuardRules.detectGestureResultWiring(featureSources()))
    }

    @Test
    fun performanceMonitorSinkHasACaller() {
        assertClean(GuardRules.detectPerformanceSinkCaller(featureSources()))
    }

    @Test
    fun launcherPackageReceiverIsRegisteredAndDispatches() {
        assertClean(GuardRules.detectLauncherReceiverWiring(featureSources()))
    }

    @Test
    fun accessibilityAnnouncementsReachTheScreenReader() {
        assertClean(GuardRules.detectAccessibilityAnnouncementWiring(featureSources()))
    }

    @Test
    fun cameraPreviewIsOpenedOnlyAfterPermissionGrant() {
        assertClean(GuardRules.detectCameraPermissionWiring(featureSources()))
    }

    @Test
    fun nativeUiCallbacksHaveAJniEventBridge() {
        assertClean(GuardRules.detectUiEventBridgeWiring(featureSources()))
    }

    @Test
    fun allFeatureWiringDetectorsAgreeTheFeaturesAreWired() {
        assertClean(GuardRules.detectUnwiredFeatures(featureSources()))
    }
}
