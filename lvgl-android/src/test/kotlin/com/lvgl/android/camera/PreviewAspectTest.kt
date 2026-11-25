// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A portrait sensor reports YUV sizes in sensor coordinates. Unless the
 * relative rotation is applied, a preview surface sized from the raw width and
 * height stretches or squashes the live image.
 */
class PreviewAspectTest {

    private val tolerance = 1e-4f

    @Test
    fun portraitSensorAtZeroRotationSwapsDisplayDimensions() {
        assertEquals(90, PreviewOrientation.relativeRotation(90, 0))
        val display = PreviewOrientation.displaySizeForOrientation(CameraSize(1920, 1080), 90)
        assertEquals(CameraSize(1080, 1920), display)
        assertEquals(1080f / 1920f, display.aspect, tolerance)
        assertEquals(
            1080f / 1920f,
            PreviewOrientation.displayAspect(CameraSize(1920, 1080), 90),
            tolerance
        )
    }

    @Test
    fun portraitSensorRotatedWithTheDeviceDoesNotSwap() {
        assertEquals(0, PreviewOrientation.relativeRotation(90, 90))
        val display = PreviewOrientation.displaySizeForOrientation(CameraSize(1920, 1080), 0)
        assertEquals(CameraSize(1920, 1080), display)
        assertEquals(1920f / 1080f, display.aspect, tolerance)
    }

    @Test
    fun zeroSensorOrientationNeverSwaps() {
        assertEquals(0, PreviewOrientation.relativeRotation(0, 0))
        val display = PreviewOrientation.displaySizeForOrientation(CameraSize(1280, 720), 0)
        assertEquals(CameraSize(1280, 720), display)
        assertEquals(1280f / 720f, display.aspect, tolerance)
    }

    @Test
    fun selectsFourThreeOrSixteenNineByTargetAspect() {
        val candidates = listOf(CameraSize(640, 480), CameraSize(1280, 720))
        assertEquals(0, PreviewSizeSelector.bestFitIndex(candidates, 4f / 3f, 0))
        assertEquals(1, PreviewSizeSelector.bestFitIndex(candidates, 16f / 9f, 0))
    }

    @Test
    fun selectionUsesEffectiveAspectForPortraitSensor() {
        val candidates = listOf(CameraSize(640, 480), CameraSize(1280, 720))
        val portraitTarget = 1080f / 1920f

        /* Raw 1280x720 looks farthest from a portrait target, but after the
         * 90-degree sensor rotation it displays as 720x1280 and wins. */
        assertEquals(1, PreviewSizeSelector.bestFitIndex(candidates, portraitTarget, 90))
        val chosen = PreviewOrientation.displaySizeForOrientation(candidates[1], 90)
        assertEquals(CameraSize(720, 1280), chosen)
        assertEquals(portraitTarget, chosen.aspect, tolerance)
    }

    @Test
    fun relativeRotationNormalizesNegativeAndOverflowingValues() {
        assertEquals(270, PreviewOrientation.relativeRotation(90, 180))
        assertEquals(90, PreviewOrientation.relativeRotation(0, 270))
        assertEquals(180, PreviewOrientation.relativeRotation(270, 90))
        assertEquals(90, PreviewOrientation.relativeRotation(90, 360))
        assertEquals(
            CameraSize(1080, 1920),
            PreviewOrientation.displaySizeForOrientation(CameraSize(1920, 1080), 450)
        )
        assertEquals(
            CameraSize(1080, 1920),
            PreviewOrientation.displaySizeForOrientation(CameraSize(1920, 1080), -90)
        )
    }
}
