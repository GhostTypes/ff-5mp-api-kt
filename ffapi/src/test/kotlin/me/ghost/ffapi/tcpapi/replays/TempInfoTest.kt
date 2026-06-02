package me.ghost.ffapi.tcpapi.replays

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from ff-5mp-api-ts `TempInfo.test.ts`. */
class TempInfoTest {

    @Test fun `parses current over set`() {
        val t = TempData("210/210")
        assertEquals(210, t.getCurrent()); assertEquals(210, t.getSet()); assertEquals("210/210", t.getFull())
    }

    @Test fun `parses current only`() {
        val t = TempData("25")
        assertEquals(25, t.getCurrent()); assertEquals(0, t.getSet()); assertEquals("25", t.getFull())
    }

    @Test fun `truncates decimals before rounding`() {
        val t = TempData("210.5/210.8")
        assertEquals(210, t.getCurrent()); assertEquals(210, t.getSet())
    }

    @Test fun `removes trailing slash zero`() {
        val t = TempData("60/60/0.0")
        assertEquals(60, t.getCurrent()); assertEquals(60, t.getSet())
    }

    @Test fun `parses M105 with T0 and B`() {
        val r = TempInfo().fromReplay("CMD M105 Received.\nT0:210/210 B:60/60 @:127 B@:127")
        assertNotNull(r)
        assertEquals(210, r!!.getExtruderTemp()!!.getCurrent())
        assertEquals(210, r.getExtruderTemp()!!.getSet())
        assertEquals(60, r.getBedTemp()!!.getCurrent())
        assertEquals(60, r.getBedTemp()!!.getSet())
    }

    @Test fun `parses M105 with T not T0`() {
        val r = TempInfo().fromReplay("CMD M105 Received.\nT:200/200 B:50/50")
        assertNotNull(r)
        assertEquals(200, r!!.getExtruderTemp()!!.getCurrent())
        assertEquals(50, r.getBedTemp()!!.getCurrent())
    }

    @Test fun `defaults bed to zero when absent`() {
        val r = TempInfo().fromReplay("CMD M105 Received.\nT:200/200")
        assertNotNull(r)
        assertEquals(200, r!!.getExtruderTemp()!!.getCurrent())
        assertEquals(0, r.getBedTemp()!!.getCurrent())
        assertEquals(0, r.getBedTemp()!!.getSet())
    }

    @Test fun `null when no extruder temp`() {
        assertNull(TempInfo().fromReplay("CMD M105 Received.\nB:60/60"))
    }

    @Test fun `null for empty replay`() {
        assertNull(TempInfo().fromReplay(""))
    }

    @Test fun `null for replay with no temperature line`() {
        assertNull(TempInfo().fromReplay("CMD M105 Received."))
    }

    @Test fun `handles T paren format`() {
        val r = TempInfo().fromReplay("CMD M105 Received.\nT):210/210 B:60/60")
        assertNotNull(r)
        assertEquals(210, r!!.getExtruderTemp()!!.getCurrent())
    }

    @Test fun `parses single-line Adventurer 3 response`() {
        val r = TempInfo().fromReplay("ok T0:185/200 B:60/60\r\n")
        assertNotNull(r)
        assertEquals(185, r!!.getExtruderTemp()!!.getCurrent())
        assertEquals(200, r.getExtruderTemp()!!.getSet())
        assertEquals(60, r.getBedTemp()!!.getCurrent())
    }

    @Test fun `isCooled true when cool`() {
        val t = TempInfo().apply { fromReplay("CMD M105 Received.\nT0:30/0 B:25/0") }
        assertTrue(t.isCooled())
    }

    @Test fun `isCooled false when extruder hot`() {
        val t = TempInfo().apply { fromReplay("CMD M105 Received.\nT0:210/210 B:30/0") }
        assertFalse(t.isCooled())
    }

    @Test fun `areTempsSafe at threshold`() {
        val t = TempInfo().apply { fromReplay("CMD M105 Received.\nT0:249/249 B:99/99") }
        assertTrue(t.areTempsSafe())
    }

    @Test fun `areTempsSafe false when extruder exceeds`() {
        val t = TempInfo().apply { fromReplay("CMD M105 Received.\nT0:260/260 B:60/60") }
        assertFalse(t.areTempsSafe())
    }
}
