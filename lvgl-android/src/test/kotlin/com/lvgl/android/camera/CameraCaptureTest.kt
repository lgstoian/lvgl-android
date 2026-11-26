// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * Pure helpers behind still capture: EXIF orientation, JPEG output selection
 * and the timestamped filename used by host apps that save the JPEG bytes.
 * No Android framework types are involved, so these run on the plain JVM.
 */
class CameraCaptureTest {

    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun backCameraJpegOrientationIsSensorMinusDeviceRotation() {
        assertEquals(90, CaptureOrientation.jpegOrientation(90, 0, false))
        assertEquals(0, CaptureOrientation.jpegOrientation(90, 90, false))
        assertEquals(270, CaptureOrientation.jpegOrientation(90, 180, false))
        assertEquals(180, CaptureOrientation.jpegOrientation(90, 270, false))
    }

    @Test
    fun frontCameraMirrorsTheBackCameraOrientation() {
        assertEquals(270, CaptureOrientation.jpegOrientation(90, 0, true))
        assertEquals(180, CaptureOrientation.jpegOrientation(90, 90, true))
        assertEquals(90, CaptureOrientation.jpegOrientation(90, 180, true))
        assertEquals(0, CaptureOrientation.jpegOrientation(90, 270, true))
    }

    @Test
    fun orientationNormalizesOverflowAndNegativeRotation() {
        assertEquals(90, CaptureOrientation.jpegOrientation(450, 360, false))
        assertEquals(270, CaptureOrientation.jpegOrientation(90, -180, false))
        assertEquals(0, CaptureOrientation.jpegOrientation(0, 0, false))
        assertEquals(0, CaptureOrientation.jpegOrientation(360, 720, true))
    }

    @Test
    fun filenameEncodesTimestampAndMillisecondsInUtc() {
        /* 2026-11-26T14:30:12.045Z. */
        val timestamp = 1795703412045L
        assertEquals(
            "IMG_20261126_143012_045.jpg",
            CaptureNaming.timestampedJpegName(timestamp, utc)
        )
    }

    @Test
    fun filenameHonoursTheSuppliedTimeZone() {
        val timestamp = 1795703412045L
        val plusTwo = TimeZone.getTimeZone("GMT+02:00")
        assertEquals(
            "IMG_20261126_163012_045.jpg",
            CaptureNaming.timestampedJpegName(timestamp, plusTwo)
        )
    }

    @Test
    fun adjacentMillisProduceDistinctFilenames() {
        val first = CaptureNaming.timestampedJpegName(1795703412045L, utc)
        val second = CaptureNaming.timestampedJpegName(1795703412046L, utc)
        assertTrue("Filenames must not collide between captures", first != second)
        assertTrue(first.endsWith(CaptureNaming.JPEG_EXTENSION))
        assertTrue(second.endsWith(CaptureNaming.JPEG_EXTENSION))
    }

    @Test
    fun largestJpegOutputWinsAndEmptyListHasNoChoice() {
        val candidates = listOf(
            CameraSize(640, 480), CameraSize(4000, 3000), CameraSize(1920, 1080)
        )
        assertEquals(CameraSize(4000, 3000), CaptureSizing.largestByArea(candidates))
        assertNull(CaptureSizing.largestByArea(emptyList()))
    }
}
