package me.ghost.ffapi.tcpapi.replays

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Ported from ff-5mp-api-ts `PrintStatus.test.ts`. Divergence: the TS `getPrintPercent()` returns
 * `NaN` when layer total is 0; the Kotlin port returns `null` (idiomatic), so that case asserts null.
 */
class PrintStatusTest {

    private val validM27 = "CMD M27 Received.\nSD printing byte 12345/67890\nLayer: 10/250"

    @Test fun `parses a valid M27 response`() {
        val r = PrintStatus().fromReplay(validM27)!!
        assertEquals("12345", r.sdCurrent)
        assertEquals("67890", r.sdTotal)
        assertEquals("10", r.layerCurrent)
        assertEquals("250", r.layerTotal)
    }

    @Test fun `calculates percent`() {
        assertEquals(4, PrintStatus().apply { fromReplay(validM27) }.getPrintPercent())
    }

    @Test fun `handles 50 percent`() {
        val r = PrintStatus().apply { fromReplay("CMD M27 Received.\nSD printing byte 50000/100000\nLayer: 125/250") }
        assertEquals(50, r.getPrintPercent())
    }

    @Test fun `handles 100 percent`() {
        val r = PrintStatus().apply { fromReplay("CMD M27 Received.\nSD printing byte 67890/67890\nLayer: 250/250") }
        assertEquals(100, r.getPrintPercent())
    }

    @Test fun `clamps above 100`() {
        val r = PrintStatus().apply { fromReplay("CMD M27 Received.\nSD printing byte 100000/67890\nLayer: 300/250") }
        assertEquals(100, r.getPrintPercent())
    }

    @Test fun `null percent when total layers zero`() {
        val r = PrintStatus().apply { fromReplay("CMD M27 Received.\nSD printing byte 0/0\nLayer: 0/0") }
        assertNull(r.getPrintPercent())
    }

    @Test fun `progress strings`() {
        val r = PrintStatus().apply { fromReplay(validM27) }
        assertEquals("10/250", r.getLayerProgress())
        assertEquals("12345/67890", r.getSdProgress())
    }

    @Test fun `falls back to sd progress when no layer line`() {
        val r = PrintStatus().fromReplay("CMD M27 Received.\nSD printing byte 12345/67890")!!
        assertEquals("12345/67890", r.getSdProgress())
        assertEquals("12345/67890", r.getLayerProgress())
        assertEquals(18, r.getPrintPercent())
    }

    @Test fun `null for malformed layer data`() {
        assertNull(PrintStatus().fromReplay("CMD M27 Received.\nSD printing byte 12345/67890\nLayer: invalid"))
    }

    @Test fun `handles extra whitespace`() {
        val r = PrintStatus().fromReplay("CMD M27 Received.\nSD printing byte   12345  /  67890\nLayer:   10  /  250  ")!!
        assertEquals("12345", r.sdCurrent)
        assertEquals("67890", r.sdTotal)
        assertEquals("10", r.layerCurrent)
        assertEquals("250", r.layerTotal)
    }

    @Test fun `parses Adventurer 3 percentage style`() {
        val r = PrintStatus().fromReplay("CMD M27 Received.\nSD printing byte 45/100\nok")!!
        assertEquals("45", r.sdCurrent)
        assertEquals("100", r.sdTotal)
        assertEquals(45, r.getPrintPercent())
    }
}
