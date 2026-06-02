package me.ghost.ffapi.tcpapi.replays

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Coverage for the M114 position parser (ff-5mp-api-ts `LocationInfo`). */
class LocationInfoTest {

    @Test fun `parses XYZ from M114 reply`() {
        val r = LocationInfo().fromReplay("CMD M114 Received.\nX:10.00 Y:20.50 Z:5.25 A:0 B:0\nok")!!
        assertEquals("10.00", r.x)
        assertEquals("20.50", r.y)
        assertEquals("5.25", r.z)
    }

    @Test fun `parses negative coordinates`() {
        val r = LocationInfo().fromReplay("X:-1.5 Y:-2 Z:0")!!
        assertEquals("-1.5", r.x)
        assertEquals("-2", r.y)
        assertEquals("0", r.z)
    }

    @Test fun `null when no position line`() {
        assertNull(LocationInfo().fromReplay("CMD M114 Received.\nok"))
        assertNull(LocationInfo().fromReplay(null))
    }
}
