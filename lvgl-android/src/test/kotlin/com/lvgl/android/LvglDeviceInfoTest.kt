// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android

import android.media.AudioDeviceInfo
import com.lvgl.android.system.LvglDeviceInfo
import com.lvgl.android.system.LvglDeviceInfo.ProbeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    @Test
    fun probeExceptionIsDistinguishableFromZero() {
        val failure = LvglDeviceInfo.probeCount(
            { throw IllegalStateException("camera service unavailable") }
        ) { ProbeStatus.ERROR }
        assertNull(failure.count)
        assertEquals(ProbeStatus.ERROR, failure.status)
        assertEquals(LvglDeviceInfo.UNKNOWN, failure.label)
        assertNotEquals("0", failure.label)
        assertNotEquals("", failure.label)
    }

    @Test
    fun deniedProbeIsDistinguishableFromGenuineAbsence() {
        val denied = LvglDeviceInfo.probeCount({ throw SecurityException() }) {
            if (it is SecurityException) ProbeStatus.DENIED else ProbeStatus.ERROR
        }
        assertEquals(ProbeStatus.DENIED, denied.status)
        assertEquals(LvglDeviceInfo.UNKNOWN, denied.label)

        val absent = LvglDeviceInfo.probeCount({ 0 }) { ProbeStatus.ERROR }
        assertEquals(ProbeStatus.ABSENT, absent.status)
        assertEquals("0", absent.label)

        val present = LvglDeviceInfo.probeCount({ 3 }) { ProbeStatus.ERROR }
        assertEquals(ProbeStatus.AVAILABLE, present.status)
        assertEquals("3", present.label)
    }

    @Test
    fun cameraUnionIncludesPhysicalCamerasAndIgnoresDuplicates() {
        val public = listOf("0", "1")
        val physical = mapOf("0" to listOf("2"), "1" to listOf("2", " "))
        val ids = LvglDeviceInfo.cameraIdUnion(public) { physical[it] }

        assertEquals(setOf("0", "1", "2"), ids)
        assertEquals(3, ids.size)
    }

    @Test
    fun cameraUnionOfPublicOnlyDevicesDoesNotInflateTheCount() {
        val ids = LvglDeviceInfo.cameraIdUnion(listOf("0", "1", "2")) { null }
        assertEquals(3, ids.size)
    }

    @Test
    fun audioLabelDropsUnknownsAndDuplicates() {
        val types = listOf(
            -1,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_TELEPHONY,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        )
        assertEquals("Speaker+Phone", LvglDeviceInfo.audioLabel(types))
        assertEquals(LvglDeviceInfo.UNKNOWN, LvglDeviceInfo.audioLabel(listOf(-1, -2)))
    }

    @Test
    fun usbLabelNeverClaimsNone() {
        assertEquals(
            LvglDeviceInfo.UNKNOWN,
            LvglDeviceInfo.usbCapabilityLabel(false, false, false)
        )
        assertEquals("USB", LvglDeviceInfo.usbCapabilityLabel(true, false, false))
        assertEquals("USB", LvglDeviceInfo.usbCapabilityLabel(false, true, false))
        assertEquals("USB", LvglDeviceInfo.usbCapabilityLabel(false, false, true))
    }

    @Test
    fun modemLabelUsesCapabilityWhenNoGenerationWasObserved() {
        assertEquals("Cellular", LvglDeviceInfo.modemLabel(true, null))
        assertEquals("5G", LvglDeviceInfo.modemLabel(true, "5G"))
        assertEquals(LvglDeviceInfo.UNKNOWN, LvglDeviceInfo.modemLabel(false, "5G"))
    }

    @Test
    fun wifiLabelDistinguishesOffFromUnknown() {
        assertEquals("on", LvglDeviceInfo.wifiStateLabel(true, 1))
        assertEquals("off", LvglDeviceInfo.wifiStateLabel(true, 0))
        assertEquals(LvglDeviceInfo.UNKNOWN, LvglDeviceInfo.wifiStateLabel(true, null))
        assertEquals(LvglDeviceInfo.UNKNOWN, LvglDeviceInfo.wifiStateLabel(false, 1))
    }
}
