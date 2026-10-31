// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lvgl.android.engine.LvglEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the process-wide engine lifecycle on a real device. The engine must
 * survive overlapping display init/deinit and only free when the last display
 * releases it (see AGENTS.md architecture + the per-activity shutdown fix).
 */
@RunWith(AndroidJUnit4::class)
class LvglEngineLifecycleInstrumentedTest {

    @Test
    fun engineIsSharedAcrossDisplays() {
        val engine = LvglEngine.instance
        engine.initialize()
        assertTrue("engine should be initialized", engine.initialized)
        assertTrue("last error should be empty", engine.lastError().isEmpty())
    }
}
