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

/**
 * The real preview aspect comes from the sensor active array or the streaming
 * crop region, never from YUV output sizes. On the Nubia Z2465N every reported
 * YUV size is square although the sensor is not, so deriving the aspect from
 * [PreviewSizeSelector] output alone produces a 1:1 preview.
 */
class CameraAspectSourceTest {

    private val tolerance = 1e-5f

    @Test
    fun squareYuvOutputWithNonSquareActiveArrayReproducesTheBug() {
        /* Regression case: the camera reports 3072x3072 and 2448x2448 YUV,
         * while the active array is a normal 4:3 sensor. */
        val real = DisplayAspectMath.fromRegion(4000, 3000, 0, 0)!!
        assertEquals(4f / 3f, real.aspect, tolerance)
        assertEquals(4000, real.width)
        assertEquals(3000, real.height)
        assertEquals(0, real.relativeRotation)

        val outputs = listOf(CameraSize(3072, 3072), CameraSize(2448, 2448))
        val chosen = outputs[PreviewSizeSelector.bestFitIndex(outputs, real.aspect, 0)]
        assertEquals(0, outputs.indexOf(chosen))
        assertEquals(1f, chosen.aspect, tolerance)
        assertTrue(
            "A square output size cannot describe the 4:3 sensor",
            chosen.aspect != real.aspect
        )
    }

    @Test
    fun aspectFromActiveArrayIsNotNullWhenSensorOrientationIsPortrait() {
        val real = DisplayAspectMath.fromRegion(4000, 3000, 90, 0)!!
        assertEquals(90, real.relativeRotation)
        assertEquals(3000, real.width)
        assertEquals(4000, real.height)
        assertEquals(3000f / 4000f, real.aspect, tolerance)
    }

    @Test
    fun deviceRotationCancelsPortraitSensorOrientation() {
        val real = DisplayAspectMath.fromRegion(4000, 3000, 90, 90)!!
        assertEquals(0, real.relativeRotation)
        assertEquals(4000, real.width)
        assertEquals(3000, real.height)
        assertEquals(4f / 3f, real.aspect, tolerance)
    }

    @Test
    fun hundredEightyDegreeRelativeRotationKeepsDimensions() {
        val real = DisplayAspectMath.fromRegion(4000, 3000, 270, 90)!!
        assertEquals(180, real.relativeRotation)
        assertEquals(4000, real.width)
        assertEquals(3000, real.height)
        assertEquals(4f / 3f, real.aspect, tolerance)
    }

    @Test
    fun streamingCropRegionOverridesTheActiveArray() {
        /* A square crop (digital zoom on a 4:3 sensor) really is square, unlike
         * a square YUV output size: the crop is the visible sensor area. */
        val real = DisplayAspectMath.fromRegion(3000, 3000, 0, 0)!!
        assertEquals(1f, real.aspect, tolerance)
        assertEquals(3000, real.width)
        assertEquals(3000, real.height)
    }

    @Test
    fun nonSquareCropRegionRotatesWithTheSensor() {
        val real = DisplayAspectMath.fromRegion(2000, 1000, 90, 0)!!
        assertEquals(1000, real.width)
        assertEquals(2000, real.height)
        assertEquals(0.5f, real.aspect, tolerance)
    }

    @Test
    fun realAspectIsUsedAsSelectionTargetInsteadOfViewAspect() {
        val outputs = listOf(CameraSize(640, 480), CameraSize(3072, 3072))
        val real = DisplayAspectMath.fromRegion(4000, 3000, 0, 0)!!
        val squareView = CameraSize(1000, 1000)

        assertEquals(0, PreviewSizeSelector.bestFitIndex(outputs, real.aspect, real.relativeRotation))
        assertEquals(1, PreviewSizeSelector.bestFitIndex(outputs, squareView.aspect, 0))
    }

    @Test
    fun nonPositiveRegionIsRejected() {
        assertNull(DisplayAspectMath.fromRegion(0, 3000, 90, 0))
        assertNull(DisplayAspectMath.fromRegion(4000, 0, 90, 0))
        assertNull(DisplayAspectMath.fromRegion(-1, 3000, 0, 0))
    }
}
