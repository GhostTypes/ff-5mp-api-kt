package me.ghost.ffapi.api.controls.ad5x

import me.ghost.ffapi.api.controls.PaletteSnap.PaletteColor
import me.ghost.ffapi.api.controls.creator5.Creator5Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the AD5X palette + nearest-color snapping. Ported from the TS
 * `ad5xPalette.test.ts`, which it mirrors 1:1.
 *
 * The `msConfig_cmd` wire format needs a byte-for-byte firmware palette match, sent as
 * uppercase `#RRGGBB` with the leading `#`. The palette-difference test guards against the
 * two models' lists drifting into each other, which would silently send a Creator 5 color to
 * an AD5X.
 */
class Ad5xPaletteTest {

    private val paletteHexes = Ad5xPalette.AD5X_PALETTE.map { it.hex }

    @Test
    fun `palette has 24 entries, all uppercase hashRRGGBB, index 0 is White`() {
        assertEquals(24, Ad5xPalette.AD5X_PALETTE.size)
        for (c in Ad5xPalette.AD5X_PALETTE) {
            assertTrue(c.hex.matches(Regex("^#[0-9A-F]{6}$")))
            assertEquals(c.hex, c.hex.uppercase())
        }
        assertEquals(PaletteColor(0, "White", "#FFFFFF"), Ad5xPalette.AD5X_PALETTE[0])
    }

    @Test
    fun `indices are sequential 0-23`() {
        Ad5xPalette.AD5X_PALETTE.forEachIndexed { position, color ->
            assertEquals(position, color.index)
        }
    }

    @Test
    fun `every palette entry snaps to itself`() {
        for (c in Ad5xPalette.AD5X_PALETTE) {
            assertEquals(c.hex, Ad5xPalette.snapToAd5xPalette(c.hex).hex)
        }
    }

    @Test
    fun `never returns an off-palette value`() {
        for (input in listOf("#FF0000", "#123456", "#00FF00", "#ABCDEF", "#112233", "#FEDCBA", "#8080FF")) {
            assertTrue(paletteHexes.contains(Ad5xPalette.snapToAd5xPalette(input).hex))
        }
    }

    @Test
    fun `always returns uppercase hashRRGGBB with the leading hash`() {
        for (input in listOf("#ff0000", "ff0000", "#45a8f9", "45A8f9", "#abc")) {
            assertTrue(Ad5xPalette.snapToAd5xPalette(input).hex.matches(Regex("^#[0-9A-F]{6}$")))
        }
    }

    @Test
    fun `accepts a bare hex with no leading hash and returns it prefixed`() {
        // Values read back from an AD5X slot can arrive without the "#".
        assertEquals("#161616", Ad5xPalette.snapToAd5xPalette("161616").hex)
        assertEquals("#7C4B00", Ad5xPalette.snapToAd5xPalette("7C4B00").hex)
    }

    @Test
    fun `snaps an exact palette entry to itself regardless of input case or shape`() {
        assertEquals("#45A8F9", Ad5xPalette.snapToAd5xPalette("#45A8F9").hex)
        assertEquals("#45A8F9", Ad5xPalette.snapToAd5xPalette("#45a8f9").hex)
        assertEquals("#45A8F9", Ad5xPalette.snapToAd5xPalette("45a8F9").hex)
    }

    @Test
    fun `snaps pure red FF0000 to palette Red F72224`() {
        assertEquals("#F72224", Ad5xPalette.snapToAd5xPalette("#FF0000").hex)
    }

    @Test
    fun `snaps white to FFFFFF, 3-digit shorthand too`() {
        assertEquals("#FFFFFF", Ad5xPalette.snapToAd5xPalette("#FFFFFF").hex)
        assertEquals("#FFFFFF", Ad5xPalette.snapToAd5xPalette("#FFF").hex)
    }

    @Test
    fun `falls back to White index 0 on unparseable input`() {
        assertEquals(Ad5xPalette.AD5X_PALETTE[0], Ad5xPalette.snapToAd5xPalette("not-a-color"))
        assertEquals(Ad5xPalette.AD5X_PALETTE[0], Ad5xPalette.snapToAd5xPalette(""))
    }

    @Test
    fun `uses AD5X values, not Creator 5 values, where the two palettes differ`() {
        // Blue and Black differ between the models; snapping must not cross over.
        assertEquals("#45A8F9", Ad5xPalette.snapToAd5xPalette("#45A8F9").hex)
        assertEquals("#161616", Ad5xPalette.snapToAd5xPalette("#161616").hex)

        val creator5Hexes = Creator5Palette.CREATOR5_PALETTE.map { it.hex }
        assertTrue(!creator5Hexes.contains("#45A8F9"))
        assertTrue(!creator5Hexes.contains("#161616"))
    }

    @Test
    fun `lists the 14 materials the AD5X UI renders, starting with PLA`() {
        assertEquals(14, Ad5xPalette.AD5X_MATERIALS.size)
        assertEquals("PLA", Ad5xPalette.AD5X_MATERIALS[0])
        assertTrue(Ad5xPalette.AD5X_MATERIALS.contains("PETG"))
        assertTrue(Ad5xPalette.AD5X_MATERIALS.contains("PPS-CF"))
    }
}
