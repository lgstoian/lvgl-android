// Copyright (c) 2026 LVGL Android Framework Contributors
// SPDX-License-Identifier: MIT
//
// This file is part of the LVGL Android Framework.
// See LICENSE in the project root for full license information.
package com.lvgl.android.theme

import android.content.Context
import android.os.Build

/**
 * Material 3 tonal palette roles, in the fixed index order that the native UI
 * kit expects (see `UiTheme` in demo_ui.h).
 *
 * The palette is the single contract between Android and LVGL: Kotlin resolves
 * the colours (baseline scheme or Material You dynamic colours) and native
 * renders with them. Nothing else needs to be passed per screen.
 *
 * @breadcrumb BC-029
 * @do-not Do not reorder the constants; native indexes into this array.
 */
object MaterialPalette {

    /** Background colour for the overall canvas. */
    const val BACKGROUND = 0

    /** Content colour drawn on [BACKGROUND]. */
    const val ON_BACKGROUND = 1

    /** Surface colour for cards and sheets. */
    const val SURFACE = 2

    /** Content colour drawn on [SURFACE]. */
    const val ON_SURFACE = 3

    /** Slightly tinted surface used for outlines and secondary fills. */
    const val SURFACE_VARIANT = 4

    /** Content colour drawn on [SURFACE_VARIANT]. */
    const val ON_SURFACE_VARIANT = 5

    /** Primary brand colour. */
    const val PRIMARY = 6

    /** Content colour drawn on [PRIMARY]. */
    const val ON_PRIMARY = 7

    /** Container colour for filled primary components. */
    const val PRIMARY_CONTAINER = 8

    /** Content colour drawn on [PRIMARY_CONTAINER]. */
    const val ON_PRIMARY_CONTAINER = 9

    /** Secondary brand colour. */
    const val SECONDARY = 10

    /** Content colour drawn on [SECONDARY]. */
    const val ON_SECONDARY = 11

    /** Container colour for filled secondary components. */
    const val SECONDARY_CONTAINER = 12

    /** Content colour drawn on [SECONDARY_CONTAINER]. */
    const val ON_SECONDARY_CONTAINER = 13

    /** Outline colour for borders and dividers. */
    const val OUTLINE = 14

    /** Subtle outline colour for separators. */
    const val OUTLINE_VARIANT = 15

    /** Error colour for destructive states. */
    const val ERROR = 16

    /** Content colour drawn on [ERROR]. */
    const val ON_ERROR = 17

    /** Container colour for error messages. */
    const val ERROR_CONTAINER = 18

    /** Content colour drawn on [ERROR_CONTAINER]. */
    const val ON_ERROR_CONTAINER = 19

    /** Number of roles; the native array is exactly this long. */
    const val SIZE = 20

    /**
     * M3 baseline light scheme.
     *
     * @return a new palette array of [SIZE] entries.
     */
    fun light(): IntArray = intArrayOf(
        0xFFFFFBFEu.toInt(), 0xFF1C1B1Fu.toInt(),   // background / on background
        0xFFFFFBFEu.toInt(), 0xFF1C1B1Fu.toInt(),   // surface / on surface
        0xFFE7E0ECu.toInt(), 0xFF49454Fu.toInt(),   // surface variant / on surface variant
        0xFF6750A4u.toInt(), 0xFFFFFFFFu.toInt(),   // primary / on primary
        0xFFEADDFFu.toInt(), 0xFF21005Du.toInt(),   // primary container / on primary container
        0xFF625B71u.toInt(), 0xFFFFFFFFu.toInt(),   // secondary / on secondary
        0xFFE8DEF8u.toInt(), 0xFF1D192Bu.toInt(),   // secondary container / on secondary container
        0xFF79747Eu.toInt(), 0xFFCAC4D0u.toInt(),   // outline / outline variant
        0xFFB3261Eu.toInt(), 0xFFFFFFFFu.toInt(),   // error / on error
        0xFFF9DEDCu.toInt(), 0xFF410E0Bu.toInt(),   // error container / on error container
    )

    /**
     * M3 baseline dark scheme.
     *
     * @return a new palette array of [SIZE] entries.
     */
    fun dark(): IntArray = intArrayOf(
        0xFF1C1B1Fu.toInt(), 0xFFE6E1E5u.toInt(),
        0xFF1C1B1Fu.toInt(), 0xFFE6E1E5u.toInt(),
        0xFF49454Fu.toInt(), 0xFFCAC4D0u.toInt(),
        0xFFD0BCFFu.toInt(), 0xFF381E72u.toInt(),
        0xFF4F378Bu.toInt(), 0xFFEADDFFu.toInt(),
        0xFFCCC2DCu.toInt(), 0xFF332D41u.toInt(),
        0xFF4A4458u.toInt(), 0xFFE8DEF8u.toInt(),
        0xFF938F99u.toInt(), 0xFF49454Fu.toInt(),
        0xFFF2B8B5u.toInt(), 0xFF601410u.toInt(),
        0xFF8C1D18u.toInt(), 0xFFF9DEDCu.toInt(),
    )

    /**
     * Material You scheme derived from the Android system accents.
     *
     * The four accent slots (primary / secondary / tertiary / neutral) are
     * read straight from the framework on API 31+. The neutral ramp is kept
     * from the baseline scheme so contrast stays predictable when the system
     * accent is unusual. Falls back to the baseline scheme below API 31.
     *
     * @param context used to read the system accent colours.
     * @param dark `true` for the dark variant, `false` for light.
     * @return a new palette array of [SIZE] entries.
     */
    fun dynamic(context: Context, dark: Boolean): IntArray {
        val base = if (dark) dark() else light()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return base
        val accentPrimary = systemAccent(context, 1, base[PRIMARY])
        if (accentPrimary == base[PRIMARY]) return base

        val accentSecondary = systemAccent(context, 2, base[SECONDARY])
        val accentTertiary = systemAccent(context, 3, base[SURFACE_VARIANT])
        // Container tones come from a lighter/darker neutral step of the accent.
        base[PRIMARY] = accentPrimary
        base[PRIMARY_CONTAINER] = if (dark) darken(accentPrimary) else lighten(accentPrimary)
        base[ON_PRIMARY] = if (dark) 0xFF381E72.toInt() else 0xFFFFFFFF.toInt()
        base[ON_PRIMARY_CONTAINER] = if (dark) 0xFFEADDFF.toInt() else 0xFF21005D.toInt()
        base[SECONDARY] = accentSecondary
        base[SECONDARY_CONTAINER] = if (dark) darken(accentSecondary) else lighten(accentSecondary)
        base[ON_SECONDARY_CONTAINER] = if (dark) 0xFFE8DEF8.toInt() else 0xFF1D192B.toInt()
        base[SURFACE_VARIANT] = accentTertiary
        return base
    }

    private fun systemAccent(context: Context, slot: Int, fallback: Int): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return fallback
        val id = when (slot) {
            1 -> android.R.color.system_accent1_500
            2 -> android.R.color.system_accent2_500
            else -> android.R.color.system_accent3_500
        }
        return try {
            context.getColor(id)
        } catch (t: Throwable) {
            fallback
        }
    }

    /** Lighten a colour toward white by [amount] (0..1). */
    private fun lighten(color: Int, amount: Float = 0.65f): Int {
        val r = (color shr 16 and 0xFF)
        val g = (color shr 8 and 0xFF)
        val b = (color and 0xFF)
        fun mix(c: Int) = (c + (255 - c) * amount).toInt().coerceIn(0, 255)
        return 0xFF000000.toInt() or (mix(r) shl 16) or (mix(g) shl 8) or mix(b)
    }

    /** Darken a colour toward black by [amount] (0..1). */
    private fun darken(color: Int, amount: Float = 0.45f): Int {
        val r = (color shr 16 and 0xFF)
        val g = (color shr 8 and 0xFF)
        val b = (color and 0xFF)
        fun mix(c: Int) = (c * (1f - amount)).toInt().coerceIn(0, 255)
        return 0xFF000000.toInt() or (mix(r) shl 16) or (mix(g) shl 8) or mix(b)
    }
}
