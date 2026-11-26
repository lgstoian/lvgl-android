// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.guards

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fail-closed wiring guards for the v1.3.0 camera-aspect and ROM-motion
 * features. A class or JNI export existing is not evidence that it is used:
 * these tests read the call sites — LvglActivity installing LvglMotionConfig,
 * LvglEngine calling its native setter, native code applying LVGL scroll
 * throw, Camera2 reading the active-array/crop region from a CaptureResult,
 * and the sample consuming the public aspect listener.
 *
 * Detection lives in [GuardRules] so [GuardCanaryTest] can prove each detector
 * catches a broken fixture and accepts a clean one. The repo root is located by
 * walking up and every source is asserted present and non-blank, so a broken
 * path fails loudly instead of silently checking nothing (BC-014, BC-037).
 *
 * @breadcrumb BC-014
 * @breadcrumb BC-037
 * @guard-rule rule=wired-motion-config test=V130GuardTest
 * @guard-rule rule=wired-native-motion-setter test=V130GuardTest
 * @guard-rule rule=wired-scroll-throw test=V130GuardTest
 * @guard-rule rule=camera-aspect-active-array test=V130GuardTest
 * @guard-rule rule=wired-camera-aspect-listener test=V130GuardTest
 */
class V130GuardTest {

    private val repoRoot: File = locateRepoRoot()

    private fun locateRepoRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/java").isDirectory }
            ?: File("..").absoluteFile

    private fun readRequired(relativePath: String): String {
        val file = File(repoRoot, relativePath)
        assertTrue("v1.3.0 source missing: ${file.path}", file.isFile)
        val text = file.readText()
        assertTrue("v1.3.0 source blank: ${file.path}", text.isNotBlank())
        return text
    }

    /** Load every source the v1.3.0 detectors need. Missing or blank sources
     *  fail here; the detectors themselves also fail closed so the canaries can
     *  prove that behaviour. */
    private fun v130Sources(): Map<String, String> {
        assertTrue(
            "Cannot locate repo sources (looked for app/src/main/java under $repoRoot)",
            File(repoRoot, "app/src/main/java").isDirectory,
        )
        val sources = GuardRules.v130SourcePaths.mapValues { (_, relativePath) ->
            readRequired(relativePath)
        }.toMutableMap()

        val nativeRoot = File(repoRoot, "lvgl-android/src/main/cpp")
        assertTrue("Native source root missing: ${nativeRoot.path}", nativeRoot.isDirectory)
        val nativeFiles = nativeRoot.walkTopDown()
            .filter { it.isFile && it.extension in setOf("cpp", "h") }
            .toList()
        assertTrue("No native sources found under ${nativeRoot.path}", nativeFiles.isNotEmpty())
        sources[GuardRules.V130_NATIVE] = nativeFiles.joinToString("\n") { it.readText() }

        val motionDir = File(repoRoot, "lvgl-android/src/main/java/com/lvgl/android/motion")
        assertTrue("Motion package missing: ${motionDir.path}", motionDir.isDirectory)
        val configFile = motionDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .firstOrNull {
                val text = it.readText()
                "class LvglMotionConfig" in text || "object LvglMotionConfig" in text
            }
        assertNotNull("LvglMotionConfig declaration missing under ${motionDir.path}", configFile)
        return sources
    }

    private fun assertClean(hits: List<GuardRules.Violation>) {
        assertTrue(
            "Unwired v1.3.0 feature found:\n" +
                hits.joinToString("\n") { "${it.file}: ${it.reason}" },
            hits.isEmpty(),
        )
    }

    @Test
    fun guardCanSeeEveryV130Source() {
        val sources = v130Sources()
        assertTrue(
            "Not all v1.3.0 wiring sources were loaded",
            sources.keys.containsAll(GuardRules.v130SourcePaths.keys + GuardRules.V130_NATIVE),
        )
    }

    @Test
    fun motionConfigIsInstalledFromTheActivity() {
        assertClean(GuardRules.detectMotionConfigCall(v130Sources()))
    }

    @Test
    fun nativeMotionSetterIsDeclaredCalledAndExported() {
        assertClean(GuardRules.detectNativeMotionSetter(v130Sources()))
    }

    @Test
    fun scrollThrowIsAppliedNatively() {
        assertClean(GuardRules.detectScrollThrowApplication(v130Sources()))
    }

    @Test
    fun cameraAspectDerivesFromActiveArrayAndCaptureResult() {
        assertClean(GuardRules.detectCameraAspectSource(v130Sources()))
    }

    @Test
    fun sampleSubscribesToTheCameraAspectListener() {
        assertClean(GuardRules.detectAspectListenerSubscription(v130Sources()))
    }

    @Test
    fun allV130DetectorsAgreeTheFeaturesAreWired() {
        assertClean(GuardRules.detectV130Violations(v130Sources()))
    }
}
