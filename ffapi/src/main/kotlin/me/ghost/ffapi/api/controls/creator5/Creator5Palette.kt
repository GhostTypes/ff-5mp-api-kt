package me.ghost.ffapi.api.controls.creator5

import me.ghost.ffapi.api.controls.PaletteSnap
import me.ghost.ffapi.api.controls.PaletteSnap.PaletteColor

/**
 * Creator 5 / Creator 5 Pro material-station slot color palette and perceptual nearest-color
 * snapping. Ported 1:1 from the TS `creator5Palette.ts` (same constants, same shared CIEDE2000
 * machinery) so the snapped output is byte-for-byte identical.
 *
 * The Creator 5 `msConfig_cmd` only renders a color icon when the `rgb` field is an EXACT,
 * case-sensitive match against one of the firmware's 24 built-in palette strings (verified in
 * firmware 1.9.2). A non-match leaves the slot's color index at 0 (White). These values differ
 * from the AD5X palette (e.g. Blue is `#4CAAF8` here vs `#45A8F9` on the AD5X), so callers must
 * snap against THIS list specifically — see [PaletteSnap] for the shared distance math.
 */
object Creator5Palette {

    /**
     * The firmware's 24-entry UI palette (verified in firmware 1.9.2). Index 0 (White) is
     * also what the firmware falls back to on a no-match.
     */
    val CREATOR5_PALETTE: List<PaletteColor> = listOf(
        PaletteColor(0, "White", "#FFFFFF"),
        PaletteColor(1, "Yellow", "#FFF245"),
        PaletteColor(2, "Light Green", "#DEF578"),
        PaletteColor(3, "Green", "#21CC3D"),
        PaletteColor(4, "Dark Green", "#167A4B"),
        PaletteColor(5, "Teal", "#156682"),
        PaletteColor(6, "Cyan", "#24E4A0"),
        PaletteColor(7, "Light Blue", "#7BD9F0"),
        PaletteColor(8, "Blue", "#4CAAF8"),
        PaletteColor(9, "Dark Blue", "#2E54DD"),
        PaletteColor(10, "Purple", "#48358C"),
        PaletteColor(11, "Violet", "#A341F7"),
        PaletteColor(12, "Magenta", "#F435F6"),
        PaletteColor(13, "Pink", "#D5B4DE"),
        PaletteColor(14, "Coral", "#FA6173"),
        PaletteColor(15, "Red", "#F82D29"),
        PaletteColor(16, "Brown", "#805003"),
        PaletteColor(17, "Orange", "#F9903B"),
        PaletteColor(18, "Cream", "#FCEBD7"),
        PaletteColor(19, "Tan", "#D5C5A1"),
        PaletteColor(20, "Dark Brown", "#B17C38"),
        PaletteColor(21, "Gray", "#8C8C89"),
        PaletteColor(22, "Light Gray", "#BEBEBE"),
        PaletteColor(23, "Black", "#1B1B1B"),
    )

    /** Palette entries with their L*a*b* values precomputed once at init (mirrors the TS module load). */
    private val PALETTE_LAB: List<PaletteSnap.PaletteLabEntry> = PaletteSnap.buildPaletteLab(CREATOR5_PALETTE)

    /**
     * Snaps an arbitrary hex color to the nearest entry in the Creator 5 firmware palette using the
     * CIEDE2000 perceptual distance in CIE L*a*b* space. The returned [PaletteColor.hex] is always
     * uppercase `#RRGGBB` and is guaranteed to be a byte-for-byte firmware match. Unparseable input
     * falls back to White (index 0, the firmware's own no-match fallback).
     * @param hex The caller's color as a hex string (leading `#` optional, any case).
     * @return The nearest Creator 5 palette entry.
     */
    fun snapToCreator5Palette(hex: String): PaletteColor =
        PaletteSnap.snapToPalette(hex, PALETTE_LAB)
}
