package me.ghost.ffapi.api.controls

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.cbrt
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Shared perceptual color machinery for firmware palette snapping. Ported 1:1 from the TS
 * `paletteSnap.ts` (same constants, same CIEDE2000 math) so snapped output is byte-for-byte
 * identical to the reference.
 *
 * The AD5X and the Creator 5 series each render a material-station slot icon only when the
 * `rgb` value matches an entry in that model's own 24-color firmware palette. The palettes
 * differ (Blue is `#45A8F9` on the AD5X and `#4CAAF8` on the Creator 5), but the snapping
 * algorithm is identical, so the sRGB -> CIE L*a*b* conversion and the CIEDE2000 distance live
 * here and are shared by both — see [Ad5xPalette][me.ghost.ffapi.api.controls.ad5x.Ad5xPalette]
 * and [Creator5Palette][me.ghost.ffapi.api.controls.creator5.Creator5Palette].
 *
 * CIEDE2000 is used rather than a naive RGB distance because a palette miss shows the wrong
 * color on the printer; perceptual nearness is what a user expects.
 */
object PaletteSnap {

    /** A single entry in a printer's firmware color palette. */
    data class PaletteColor(
        /** Firmware palette index (0 is the no-match fallback on both models). */
        val index: Int,
        /** Color name as shown on the printer UI. */
        val name: String,
        /** Wire value sent to the printer, always uppercase `#RRGGBB`. */
        val hex: String,
    )

    /** CIE L*a*b* color. */
    data class Lab(val l: Double, val a: Double, val b: Double)

    /** A palette entry with its L*a*b* value precomputed. */
    data class PaletteLabEntry(val color: PaletteColor, val lab: Lab)

    /** D65 reference white point used by the sRGB -> XYZ transform. */
    private const val XN = 0.95047
    private const val YN = 1.0
    private const val ZN = 1.08883
    private const val POW25_7 = 6103515625.0 // 25^7

    private val HEX_REGEX = Regex("^[0-9a-fA-F]{3}([0-9a-fA-F]{3})?$")

    /** sRGB component (0-255) channel transfer function -> linear value (0-1). */
    private fun srgbToLinear(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }

    /** Converts an sRGB color (0-255 channels) to CIE L*a*b* under a D65 illuminant. */
    fun rgbToLab(r: Int, g: Int, b: Int): Lab {
        val rLin = srgbToLinear(r)
        val gLin = srgbToLinear(g)
        val bLin = srgbToLinear(b)

        var x = rLin * 0.4124564 + gLin * 0.3575761 + bLin * 0.1804375
        var y = rLin * 0.2126729 + gLin * 0.7151522 + bLin * 0.072175
        var z = rLin * 0.0193339 + gLin * 0.119192 + bLin * 0.9503041

        x /= XN
        y /= YN
        z /= ZN

        fun f(t: Double): Double = if (t > 0.008856) cbrt(t) else 7.787 * t + 16.0 / 116.0
        val fx = f(x)
        val fy = f(y)
        val fz = f(z)

        return Lab(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
    }

    /** atan2 -> hue in degrees, normalized to [0, 360). */
    private fun atan2deg(ordinate: Double, abscissa: Double): Double {
        var h = atan2(ordinate, abscissa) * 180.0 / PI
        if (h < 0) h += 360.0
        return h
    }

    /**
     * CIEDE2000 color difference between two L*a*b* colors (kL=kC=kH=1). The most accurate
     * standard delta-E metric, preferred here because the firmware renders only an exact palette
     * match — snapping to the wrong perceptual neighbor would display the wrong color on the
     * printer.
     */
    fun deltaE2000(c1: Lab, c2: Lab): Double {
        val (l1, a1, b1) = c1
        val (l2, a2, b2) = c2

        val c1Chroma = sqrt(a1 * a1 + b1 * b1)
        val c2Chroma = sqrt(a2 * a2 + b2 * b2)
        val cBar = (c1Chroma + c2Chroma) / 2.0
        val cBar7 = Math.pow(cBar, 7.0)
        val g = 0.5 * (1.0 - sqrt(cBar7 / (cBar7 + POW25_7)))

        val a1p = (1.0 + g) * a1
        val a2p = (1.0 + g) * a2
        val c1p = sqrt(a1p * a1p + b1 * b1)
        val c2p = sqrt(a2p * a2p + b2 * b2)
        val h1p = atan2deg(b1, a1p)
        val h2p = atan2deg(b2, a2p)

        val dLp = l2 - l1
        val dCp = c2p - c1p
        val dhp: Double = if (c1p * c2p == 0.0) {
            0.0
        } else {
            val diff = h2p - h1p
            when {
                kotlin.math.abs(diff) <= 180.0 -> diff
                diff > 180.0 -> diff - 360.0
                else -> diff + 360.0
            }
        }
        val dHp = 2.0 * sqrt(c1p * c2p) * sin(dhp * PI / 360.0)

        val lBarp = (l1 + l2) / 2.0
        val cBarp = (c1p + c2p) / 2.0
        val hBarp: Double = if (c1p * c2p == 0.0) {
            h1p + h2p
        } else {
            val diff = kotlin.math.abs(h1p - h2p)
            when {
                diff <= 180.0 -> (h1p + h2p) / 2.0
                h1p + h2p < 360.0 -> (h1p + h2p + 360.0) / 2.0
                else -> (h1p + h2p - 360.0) / 2.0
            }
        }

        val t = 1.0 -
            0.17 * cos((hBarp - 30.0) * PI / 180.0) +
            0.24 * cos(2.0 * hBarp * PI / 180.0) +
            0.32 * cos((3.0 * hBarp + 6.0) * PI / 180.0) -
            0.20 * cos((4.0 * hBarp - 63.0) * PI / 180.0)

        val dTheta = 30.0 * exp(-Math.pow((hBarp - 275.0) / 25.0, 2.0))
        val cBarp7 = Math.pow(cBarp, 7.0)
        val rC = 2.0 * sqrt(cBarp7 / (cBarp7 + POW25_7))
        val sL = 1.0 + (0.015 * Math.pow(lBarp - 50.0, 2.0)) / sqrt(20.0 + Math.pow(lBarp - 50.0, 2.0))
        val sC = 1.0 + 0.045 * cBarp
        val sH = 1.0 + 0.015 * cBarp * t
        val rT = -sin(2.0 * dTheta * PI / 180.0) * rC

        val termL = dLp / sL
        val termC = dCp / sC
        val termH = dHp / sH

        return sqrt(termL * termL + termC * termC + termH * termH + rT * termC * termH)
    }

    /**
     * Parses a hex color string (`#RRGGBB`, `RRGGBB`, 3-digit shorthand, any case) into its RGB
     * channels. Returns `null` for unparseable input.
     */
    fun hexToRgb(hex: String): Triple<Int, Int, Int>? {
        val clean = hex.trim().removePrefix("#")
        if (!HEX_REGEX.matches(clean)) return null
        val full = if (clean.length == 3) {
            clean.toCharArray().joinToString("") { ch -> "$ch$ch" }
        } else {
            clean
        }
        return Triple(
            full.substring(0, 2).toInt(16),
            full.substring(2, 4).toInt(16),
            full.substring(4, 6).toInt(16),
        )
    }

    /** Precomputes a palette's L*a*b* values once (mirrors the TS module-load precompute). */
    fun buildPaletteLab(palette: List<PaletteColor>): List<PaletteLabEntry> = palette.map { color ->
        val (r, g, b) = hexToRgb(color.hex) ?: Triple(0, 0, 0)
        PaletteLabEntry(color, rgbToLab(r, g, b))
    }

    /**
     * Snaps an arbitrary hex color to the nearest palette entry by CIEDE2000 distance. The
     * returned [PaletteColor.hex] is always uppercase `#RRGGBB`.
     *
     * Unparseable input falls back to `paletteLab[0]`, which on both firmware palettes is White —
     * the same value the firmware itself falls back to on a no-match.
     *
     * @param hex Caller's color (leading `#` optional, any case).
     * @param paletteLab Precomputed palette, from [buildPaletteLab].
     */
    fun snapToPalette(hex: String, paletteLab: List<PaletteLabEntry>): PaletteColor {
        val rgb = hexToRgb(hex) ?: return paletteLab[0].color
        val target = rgbToLab(rgb.first, rgb.second, rgb.third)
        var best = paletteLab[0]
        var bestDelta = Double.POSITIVE_INFINITY
        for (entry in paletteLab) {
            val delta = deltaE2000(target, entry.lab)
            if (delta < bestDelta) {
                bestDelta = delta
                best = entry
            }
        }
        return best.color
    }
}
