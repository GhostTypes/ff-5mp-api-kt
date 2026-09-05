package me.ghost.ffapi.api.controls.ad5x

import me.ghost.ffapi.api.controls.PaletteSnap
import me.ghost.ffapi.api.controls.PaletteSnap.PaletteColor

/**
 * AD5X material-station slot color palette and nearest-color snapping. Ported 1:1 from the TS
 * `ad5xPalette.ts` (same constants, same shared CIEDE2000 machinery).
 *
 * The AD5X material-station UI renders a color icon only for one of the firmware's 24 built-in
 * palette colors; any other value leaves the slot without an icon (hardware-verified — the
 * printer also *stores* whatever it is given, so a bare stripped hex poisons
 * `slotInfos[].materialColor` on every later read). The `rgb` field is sent as uppercase
 * `#RRGGBB`, exactly as the printer's own UI stores it — the `#` is part of the wire value and
 * must not be stripped.
 *
 * These values DIFFER from the Creator 5 palette (Blue is `#45A8F9` here vs `#4CAAF8` there),
 * so callers must snap against THIS list for the AD5X — see [PaletteSnap] for the shared
 * distance math.
 */
object Ad5xPalette {

    /**
     * The firmware's 24-entry UI palette. Index 0 (White) is also the value the firmware falls
     * back to when a color does not match.
     */
    val AD5X_PALETTE: List<PaletteColor> = listOf(
        PaletteColor(0, "White", "#FFFFFF"),
        PaletteColor(1, "Yellow", "#FEF043"),
        PaletteColor(2, "Light Green", "#DCF478"),
        PaletteColor(3, "Green", "#0ACC38"),
        PaletteColor(4, "Dark Green", "#067749"),
        PaletteColor(5, "Teal", "#0C6283"),
        PaletteColor(6, "Cyan", "#0DE2A0"),
        PaletteColor(7, "Light Blue", "#75D9F3"),
        PaletteColor(8, "Blue", "#45A8F9"),
        PaletteColor(9, "Dark Blue", "#2750E0"),
        PaletteColor(10, "Purple", "#46328E"),
        PaletteColor(11, "Violet", "#A03CF7"),
        PaletteColor(12, "Magenta", "#F330F9"),
        PaletteColor(13, "Pink", "#D4B0DC"),
        PaletteColor(14, "Coral", "#F95D73"),
        PaletteColor(15, "Red", "#F72224"),
        PaletteColor(16, "Brown", "#7C4B00"),
        PaletteColor(17, "Orange", "#F98D33"),
        PaletteColor(18, "Cream", "#FDEBD5"),
        PaletteColor(19, "Tan", "#D3C4A3"),
        PaletteColor(20, "Dark Brown", "#AF7836"),
        PaletteColor(21, "Gray", "#898989"),
        PaletteColor(22, "Light Gray", "#BCBCBC"),
        PaletteColor(23, "Black", "#161616"),
    )

    /** The 14 material names the AD5X material-station UI renders. */
    val AD5X_MATERIALS: List<String> = listOf(
        "PLA",
        "PLA-CF",
        "PETG",
        "PETG-CF",
        "ABS",
        "TPU",
        "SILK",
        "PA",
        "PA-CF",
        "PAHT-CF",
        "PC",
        "PC-ABS",
        "PET-CF",
        "PPS-CF",
    )

    /** Palette entries with their L*a*b* values precomputed once at init (mirrors the TS module load). */
    private val PALETTE_LAB: List<PaletteSnap.PaletteLabEntry> = PaletteSnap.buildPaletteLab(AD5X_PALETTE)

    /**
     * Snaps an arbitrary hex color to the nearest entry in the AD5X firmware palette using the
     * CIEDE2000 perceptual distance in CIE L*a*b* space. The returned [PaletteColor.hex] is
     * always uppercase `#RRGGBB` — with the leading `#`, which the wire format requires.
     * Unparseable input falls back to White (index 0, the firmware's own no-match fallback).
     * @param hex The caller's color as a hex string (leading `#` optional, any case).
     * @return The nearest AD5X palette entry.
     */
    fun snapToAd5xPalette(hex: String): PaletteColor =
        PaletteSnap.snapToPalette(hex, PALETTE_LAB)
}
