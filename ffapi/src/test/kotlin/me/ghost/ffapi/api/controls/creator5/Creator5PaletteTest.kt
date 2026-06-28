package me.ghost.ffapi.api.controls.creator5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from the TS `creator5Palette.test.ts`. Verifies the CIEDE2000 perceptual snap produces
 * byte-for-byte firmware palette matches (exact, case-sensitive "#RRGGBB").
 */
class Creator5PaletteTest {

    private val paletteHexes = Creator5Palette.CREATOR5_PALETTE.map { it.hex }

    @Test
    fun `palette has 24 entries, all uppercase hashRRGGBB, index 0 = White`() {
        assertEquals(24, Creator5Palette.CREATOR5_PALETTE.size)
        for (c in Creator5Palette.CREATOR5_PALETTE) {
            assertTrue("hex ${c.hex} must be #RRGGBB", c.hex.matches(Regex("^#[0-9A-F]{6}$")))
            assertEquals(c.hex, c.hex.uppercase())
        }
        assertEquals(
            Creator5Palette.Color(0, "White", "#FFFFFF"),
            Creator5Palette.CREATOR5_PALETTE[0],
        )
    }

    @Test
    fun `every palette entry snaps to itself`() {
        for (c in Creator5Palette.CREATOR5_PALETTE) {
            assertEquals(c.hex, Creator5Palette.snapToCreator5Palette(c.hex).hex)
        }
    }

    @Test
    fun `never returns an off-palette value`() {
        val inputs = listOf("#FF0000", "#123456", "#00FF00", "#ABCDEF", "#112233", "#FEDCBA", "#8080FF")
        for (input in inputs) {
            val snapped = Creator5Palette.snapToCreator5Palette(input).hex
            assertTrue("$snapped must be a palette entry for input $input", snapped in paletteHexes)
        }
    }

    @Test
    fun `always returns uppercase hashRRGGBB with the leading hash`() {
        for (input in listOf("#ff0000", "ff0000", "#4caaf8", "4CAAf8", "#abc")) {
            val snapped = Creator5Palette.snapToCreator5Palette(input).hex
            assertTrue(snapped.matches(Regex("^#[0-9A-F]{6}$")))
        }
    }

    @Test
    fun `snaps pure red FF0000 to palette Red F82D29`() {
        assertEquals("#F82D29", Creator5Palette.snapToCreator5Palette("#FF0000").hex)
    }

    @Test
    fun `snaps an exact palette entry to itself regardless of input case or shape`() {
        assertEquals("#4CAAF8", Creator5Palette.snapToCreator5Palette("#4CAAF8").hex)
        assertEquals("#4CAAF8", Creator5Palette.snapToCreator5Palette("#4caaf8").hex)
        assertEquals("#4CAAF8", Creator5Palette.snapToCreator5Palette("4caaF8").hex)
    }

    @Test
    fun `snaps white to FFFFFF (3-digit shorthand too)`() {
        assertEquals("#FFFFFF", Creator5Palette.snapToCreator5Palette("#FFFFFF").hex)
        assertEquals("#FFFFFF", Creator5Palette.snapToCreator5Palette("#FFF").hex)
    }

    @Test
    fun `falls back to White index 0 on unparseable input`() {
        assertEquals(Creator5Palette.CREATOR5_PALETTE[0], Creator5Palette.snapToCreator5Palette("not-a-color"))
        assertEquals(Creator5Palette.CREATOR5_PALETTE[0], Creator5Palette.snapToCreator5Palette(""))
    }
}
