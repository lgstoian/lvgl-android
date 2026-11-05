// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.guards

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the crash detection in the dynamic test harness.
 *
 * The CI emulator runs without hardware acceleration, so system processes are
 * starved and noisy: the Bluetooth stack logs "Start Timeout Delay: 4000" and
 * then SIGABRTs in its own process. Broad markers ("FATAL", "start timeout")
 * matched that noise and failed an otherwise healthy walk, so the harness must
 * only fail on a crash or ANR in the sample app itself.
 *
 * @breadcrumb BC-036
 * @guard-rule rule=dynamic-harness-crash-detection test=DynamicHarnessGuardTest
 */
class DynamicHarnessGuardTest {

    private val repoRoot: File = locateRepoRoot()
    private val harness: File = File(repoRoot, "tools/dynamic-test.ps1")

    private fun locateRepoRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/java").isDirectory }
            ?: File("..").absoluteFile

    /** The harness's crash regex, extracted from its single-quoted assignment. */
    private fun crashPattern(): Regex {
        assertTrue("tools/dynamic-test.ps1 must exist", harness.isFile)
        val pattern = GuardRules.dynamicHarnessCrashPattern(harness.readText())
        assertNotNull("harness must define \$crashPattern", pattern)
        return Regex(pattern!!)
    }

    @Test
    fun harnessCrashPatternIsAppSpecific() {
        val hits = GuardRules.detectUnscopedCrashPattern(harness.readText())
        assertTrue(
            "harness crash pattern must be scoped to the sample app:\n" +
                hits.joinToString("\n") { it.reason },
            hits.isEmpty()
        )
    }

    @Test
    fun harnessCrashPatternIgnoresSystemProcessNoise() {
        val pattern = crashPattern()
        listOf(
            "D AdapterState: Start Timeout Delay: 4000",
            "I BluetoothBondStateMachine: StableState(): Entering Off State",
            "F libc    : Fatal signal 6 (SIGABRT), code -1 (SI_QUEUE) in tid 3636 " +
                "(bt_stack_manage), pid 3585 (droid.bluetooth)",
        ).forEach { line ->
            assertFalse(
                "system-process noise must not fail the walk: $line",
                pattern.containsMatchIn(line)
            )
        }
    }

    @Test
    fun harnessCrashPatternDetectsSampleAppCrashes() {
        val pattern = crashPattern()
        listOf(
            "E AndroidRuntime: Process: com.lvgl.sample, PID: 12345",
            "I ActivityManager: ANR in com.lvgl.sample (com.lvgl.sample/.MainActivity)",
            "F libc    : Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR) in tid 9, " +
                "pid 99 (com.lvgl.sample)",
            "F DEBUG   : pid: 99, tid: 99, name: com.lvgl.sample  >>> com.lvgl.sample <<<",
        ).forEach { line ->
            assertTrue("an app crash must fail the walk: $line", pattern.containsMatchIn(line))
        }
    }
}
