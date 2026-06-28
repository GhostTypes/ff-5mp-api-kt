package me.ghost.ffapi.api.controls.creator5

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.cbrt
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Creator 5 / Creator 5 Pro material-station slot color palette and perceptual nearest-color
 * snapping. Ported 1:1 from the TS `creator5Palette.ts` (same constants, same CIEDE2000 math, same
 * precomputed-Lab behavior) so the snapped output is byte-for-byte identical.
 *
 * The Creator 5 `msConfig_cmd` only renders a color icon when the `rgb` field is an EXACT,
 * case-sensitive match against one of the firmware's 24 built-in palette strings (compared via
 * `std::operator==` @0x0042c5e0 in `firmwareExe` 1.9.2). A non-match leaves the slot's color index
 * at 0 (White). These values differ from the AD5X palette (e.g. Blue is `#4CAAF8` here vs `#45A8F9`
 * on the AD5X), so callers must snap against THIS list specifically. The AD5X accepts freeform hex
 * (with the `#` stripped), so the two wire formats are mutually exclusive.
 */
object Creator5Palette {

    /** A single entry in the Creator 5 firmware color palette. */
    data class Color(val index: Int, val name: String, val hex: String)

    /** CIE L*a*b* color. */
    private data class Lab(val l: Double, val a: Double, val b: Double)

    /**
     * The firmware's 24-entry UI palette (firmwareExe 1.9.2, Ghidra-confirmed). Index 0 (White) is
     * also what the firmware falls back to on a no-match.
     */
    val CREATOR5_PALETTE: List<Color> = listOf(
        Color(0, "White", "#FFFFFF"),
        Color(1, "Yellow", "#FFF245"),
        Color(2, "Light Green", "#DEF578"),
        Color(3, "Green", "#21CC3D"),
        Color(4, "Dark Green", "#167A4B"),
        Color(5, "Teal", "#156682"),
        Color(6, "Cyan", "#24E4A0"),
        Color(7, "Light Blue", "#7BD9F0"),
        Color(8, "Blue", "#4CAAF8"),
        Color(9, "Dark Blue", "#2E54DD"),
        Color(10, "Purple", "#48358C"),
        Color(11, "Violet", "#A341F7"),
        Color(12, "Magenta", "#F435F6"),
        Color(13, "Pink", "#D5B4DE"),
        Color(14, "Coral", "#FA6173"),
        Color(15, "Red", "#F82D29"),
        Color(16, "Brown", "#805003"),
        Color(17, "Orange", "#F9903B"),
        Color(18, "Cream", "#FCEBD7"),
        Color(19, "Tan", "#D5C5A1"),
        Color(20, "Dark Brown", "#B17C38"),
        Color(21, "Gray", "#8C8C89"),
        Color(22, "Light Gray", "#BEBEBE"),
        Color(23, "Black", "#1B1B1B"),
    )

    /** D65 reference white point used by the sRGB -> XYZ transform. */
    private const val XN = 0.95047
    private const val YN = 1.0
    private const val ZN = 1.08883
    private const val POW25_7 = 6103515625.0 // 25^7

    private val HEX_REGEX = Regex("^[0-9a-fA-F]{3}([0-9a-fA-F]{3})?$")

    /** Palette entries with their L*a*b* values precomputed once at init (mirrors the TS module load). */
    private val PALETTE_LAB: List<Pair<Color, Lab>> = CREATOR5_PALETTE.map { c ->
        val (r, g, b) = hexToRgb(c.hex) ?: Triple(0, 0, 0)
        c to rgbToLab(r, g, b)
    }

    /** sRGB component (0-255) channel transfer function -> linear value (0-1). */
    private fun srgbToLinear(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }

    /** Converts an sRGB color (0-255 channels) to CIE L*a*b* under a D65 illuminant. */
    private fun rgbToLab(r: Int, g: Int, b: Int): Lab {
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

    /** CIEDE2000 color difference between two L*a*b* colors (kL=kC=kH=1). */
    private fun deltaE2000(c1: Lab, c2: Lab): Double {
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
    private fun hexToRgb(hex: String): Triple<Int, Int, Int>? {
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

    /**
     * Snaps an arbitrary hex color to the nearest entry in the Creator 5 firmware palette using the
     * CIEDE2000 perceptual distance in CIE L*a*b* space. The returned [Color.hex] is always
     * uppercase `#RRGGBB` and is guaranteed to be a byte-for-byte firmware match. Unparseable input
     * falls back to White (index 0, the firmware's own no-match fallback).
     * @param hex The caller's color as a hex string (leading `#` optional, any case).
     * @return The nearest Creator 5 palette entry.
     */
    fun snapToCreator5Palette(hex: String): Color {
        val rgb = hexToRgb(hex) ?: return CREATOR5_PALETTE[0]
        val target = rgbToLab(rgb.first, rgb.second, rgb.third)
        var best = PALETTE_LAB[0].first
        var bestDelta = Double.POSITIVE_INFINITY
        for ((color, lab) in PALETTE_LAB) {
            val delta = deltaE2000(target, lab)
            if (delta < bestDelta) {
                bestDelta = delta
                best = color
            }
        }
        return best
    }
}
