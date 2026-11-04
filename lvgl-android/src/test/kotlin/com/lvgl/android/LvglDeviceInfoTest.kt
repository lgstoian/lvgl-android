// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android

import com.lvgl.android.system.LvglDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The device map is only useful if the platform ids and sizes are mapped
 * correctly, so the mapping logic is unit-tested directly.
 *
 * @breadcrumb BC-028
 */
class LvglDeviceInfoTest {

    private val gb = 1024L * 1024L * 1024L
    private val mb = 1024L * 1024L

    @Test
    fun mapsKnownPlatforms() {
        assertEquals("Snapdragon 8 Elite", LvglDeviceInfo.socMarketingName("sm8750"))
        assertEquals("Snapdragon 8 Gen 3", LvglDeviceInfo.socMarketingName("sm8650"))
        assertEquals("Snapdragon 8 Gen 2", LvglDeviceInfo.socMarketingName("sm8550"))
        assertEquals("Snapdragon 888", LvglDeviceInfo.socMarketingName("sm8350"))
        assertEquals("Google Tensor", LvglDeviceInfo.socMarketingName("gs101"))
        assertEquals("Google Tensor", LvglDeviceInfo.socMarketingName("gs201"))
        assertEquals("Exynos 2100", LvglDeviceInfo.socMarketingName("exynos2100"))
        assertEquals("Kirin 9000", LvglDeviceInfo.socMarketingName("kirin9000"))
        assertEquals("MediaTek Dimensity", LvglDeviceInfo.socMarketingName("mt6989"))
        assertEquals("Unisoc", LvglDeviceInfo.socMarketingName("t618"))
    }

    @Test
    fun mapsEmulatorHardwareToVirtualSoc() {
        assertEquals("Virtual SoC", LvglDeviceInfo.socMarketingName("ranchu"))
        assertEquals("Virtual SoC", LvglDeviceInfo.socMarketingName("goldfish"))
        assertEquals("Virtual SoC", LvglDeviceInfo.socMarketingName("virtual"))
    }

    @Test
    fun fallsBackToReadableHardwareId() {
        assertEquals("Unknown888", LvglDeviceInfo.socMarketingName("unknown888"))
        assertEquals("", LvglDeviceInfo.socMarketingName(""))
    }

    @Test
    fun formatsBinarySizesLikeMarketingLabels() {
        assertEquals("8 GB", LvglDeviceInfo.formatSize(8 * gb))
        assertEquals("12 GB", LvglDeviceInfo.formatSize(12 * gb))
        assertEquals("64 GB", LvglDeviceInfo.formatSize(64 * gb))
        assertEquals("512 MB", LvglDeviceInfo.formatSize(512 * mb))
        assertEquals("", LvglDeviceInfo.formatSize(0))
    }
}
