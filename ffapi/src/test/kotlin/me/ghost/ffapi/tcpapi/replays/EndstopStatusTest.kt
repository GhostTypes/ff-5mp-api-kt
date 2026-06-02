package me.ghost.ffapi.tcpapi.replays

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported coverage for the M119 endstop/status parser (ff-5mp-api-ts `EndstopStatus`). */
class EndstopStatusTest {

    @Test fun `parses a ready M119 reply`() {
        val resp = "CMD M119 Received.\n" +
            "Endstop: X-max:0 Y-max:0 Z-min:1\n" +
            "MachineStatus: READY\n" +
            "MoveMode: READY\n" +
            "Status S:1 L:0 J:0 F:0\n" +
            "LED: 1\n" +
            "CurrentFile: \n" +
            "ok"
        val r = EndstopStatus().fromReplay(resp)!!
        assertEquals(0, r.endstop!!.xMax)
        assertEquals(1, r.endstop!!.zMin)
        assertEquals(MachineStatus.READY, r.machineStatus)
        assertEquals(MoveMode.READY, r.moveMode)
        assertTrue(r.isReady())
        assertFalse(r.isPrinting())
        assertTrue(r.ledEnabled)
        assertNull(r.currentFile)
        assertEquals(1, r.status!!.s)
    }

    @Test fun `maps PRINTING to building from sd and reads current file`() {
        val resp = "CMD M119 Received.\n" +
            "Endstop: X-max:0 Y-max:0 Z-min:0\n" +
            "MachineStatus: BUILDING_FROM_SD\n" +
            "MoveMode: MOVING\n" +
            "LED: 0\n" +
            "CurrentFile: Benchy.gcode\n" +
            "ok"
        val r = EndstopStatus().fromReplay(resp)!!
        assertTrue(r.isPrinting())
        assertEquals(MoveMode.MOVING, r.moveMode)
        assertFalse(r.ledEnabled)
        assertEquals("Benchy.gcode", r.currentFile)
    }

    @Test fun `reads Adventurer 3 LEDStatus and PrintFileName variants`() {
        val resp = "CMD M119 Received.\n" +
            "Endstop: X-max:0 Y-max:0 Z-min:0\n" +
            "MachineStatus: PAUSED\n" +
            "MoveMode: PAUSED\n" +
            "LEDStatus: on\n" +
            "PrintFileName: thing.gx\n" +
            "ok"
        val r = EndstopStatus().fromReplay(resp)!!
        assertTrue(r.isPaused())
        assertTrue(r.ledEnabled)
        assertEquals("thing.gx", r.currentFile)
    }

    @Test fun `null when required lines missing`() {
        assertNull(EndstopStatus().fromReplay("CMD M119 Received.\nok"))
        assertNull(EndstopStatus().fromReplay(""))
    }
}
