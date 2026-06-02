package me.ghost.ffapi.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from ff-5mp-api-ts `MachineInfo.test.ts`. Covers pid-first detection + fallbacks. */
class MachineInfoTest {

    private val converter = MachineInfo()

    private fun ad5xDetail(name: String = "AD5X", pid: Int? = 38) = FFPrinterDetail(
        autoShutdown = "close",
        autoShutdownTime = 30.0,
        cameraStreamUrl = "",
        coolingFanLeftSpeed = 0.0,
        coolingFanSpeed = 0.0,
        cumulativeFilament = 0.0,
        firmwareVersion = "1.1.3-1.0.8",
        hasMatlStation = true,
        indepMatlInfo = IndepMatlInfo(materialName = "?"),
        ipAddr = "192.168.0.204",
        lightStatus = "open",
        macAddr = "88:A9:A7:9D:2A:70",
        matlStationInfo = MatlStationInfo(
            slotCnt = 4,
            slotInfos = listOf(
                SlotInfo(hasFilament = true, materialColor = "#FFFFFF", materialName = "PLA", slotId = 1),
                SlotInfo(hasFilament = true, materialColor = "#2750E0", materialName = "PLA", slotId = 2),
                SlotInfo(hasFilament = true, materialColor = "#FEF043", materialName = "PLA", slotId = 3),
                SlotInfo(hasFilament = true, materialColor = "#F95D73", materialName = "PLA", slotId = 4),
            ),
        ),
        name = name,
        nozzleModel = "0.4mm",
        pid = pid,
        platTargetTemp = 0.0,
        platTemp = 27.75,
    )

    private fun genericDetail(name: String = "FlashForge 5M", pid: Int? = null) = FFPrinterDetail(
        name = name,
        pid = pid,
        firmwareVersion = "1.0.0",
        ipAddr = "192.168.1.100",
        macAddr = "AA:BB:CC:DD:EE:FF",
        coolingFanSpeed = 100.0,
        platTemp = 60.5,
        platTargetTemp = 60.0,
        rightTemp = 210.3,
        rightTargetTemp = 210.0,
        status = "ready",
        cumulativePrintTime = 1200.0,
        cumulativeFilament = 500.75,
    )

    @Test
    fun `parses AD5X details`() {
        val r = converter.fromDetail(ad5xDetail())!!
        assertEquals("AD5X", r.name)
        assertTrue(r.isAD5X)
        assertFalse(r.isPro)
        assertEquals("1.1.3-1.0.8", r.firmwareVersion)
        assertEquals(true, r.hasMatlStation)
        assertEquals(0.0, r.coolingFanLeftSpeed!!, 1e-9)
        assertEquals(4, r.matlStationInfo!!.slotCnt)
        assertEquals(4, r.matlStationInfo!!.slotInfos.size)
        assertEquals("PLA", r.matlStationInfo!!.slotInfos[0].materialName)
        assertEquals(1, r.matlStationInfo!!.slotInfos[0].slotId)
        assertEquals("#2750E0", r.matlStationInfo!!.slotInfos[1].materialColor)
        assertEquals("?", r.indepMatlInfo!!.materialName)
        assertEquals("192.168.0.204", r.ipAddress)
        assertEquals(27.75, r.printBed.current, 1e-9)
        assertEquals(0.0, r.extruder.current, 1e-9)
        assertEquals(MachineState.Unknown, r.machineState)
    }

    @Test
    fun `detects AD5X from material station with a custom name`() {
        val r = converter.fromDetail(ad5xDetail(name = "E2E-AD5X", pid = null))!!
        assertEquals("E2E-AD5X", r.name)
        assertTrue(r.isAD5X)
        assertFalse(r.isPro)
    }

    @Test
    fun `parses generic non-AD5X details`() {
        val r = converter.fromDetail(genericDetail())!!
        assertEquals("FlashForge 5M", r.name)
        assertFalse(r.isAD5X)
        assertFalse(r.isPro)
        assertNull(r.hasMatlStation)
        assertNull(r.matlStationInfo)
        assertNull(r.indepMatlInfo)
        assertNull(r.coolingFanLeftSpeed)
        assertEquals(100.0, r.coolingFanSpeed, 1e-9)
        assertEquals(60.5, r.printBed.current, 1e-9)
        assertEquals(210.3, r.extruder.current, 1e-9)
        assertEquals(MachineState.Ready, r.machineState)
        assertEquals("20h:0m", r.formattedTotalRunTime)
    }

    @Test
    fun `identifies a non-AD5X Pro by name`() {
        val r = converter.fromDetail(genericDetail(name = "FlashForge 5M Pro"))!!
        assertTrue(r.isPro)
        assertFalse(r.isAD5X)
    }

    @Test
    fun `detects AD5X from pid when renamed`() {
        val r = converter.fromDetail(
            FFPrinterDetail(name = "LegoTech82", pid = 38, firmwareVersion = "1.1.7-1.0.2", status = "ready")
        )!!
        assertEquals(38, r.pid)
        assertTrue(r.isAD5X)
        assertFalse(r.isPro)
    }

    @Test
    fun `detects 5M Pro from pid even when renamed`() {
        val r = converter.fromDetail(genericDetail(name = "MyPrinter", pid = 36))!!
        assertEquals(36, r.pid)
        assertTrue(r.isPro)
        assertFalse(r.isAD5X)
    }

    @Test
    fun `treats pid 35 as plain 5M even if name suggests Pro`() {
        val r = converter.fromDetail(genericDetail(name = "FlashForge 5M Pro", pid = 35))!!
        assertEquals(35, r.pid)
        assertFalse(r.isPro)
        assertFalse(r.isAD5X)
    }

    @Test
    fun `falls back to capability when pid absent`() {
        val r = converter.fromDetail(ad5xDetail(pid = null))!!
        assertNull(r.pid)
        assertTrue(r.isAD5X)
        assertFalse(r.isPro)
    }

    @Test
    fun `returns null when detail is null`() {
        assertNull(converter.fromDetail(null))
    }

    @Test
    fun `handles missing optional fields with defaults`() {
        val r = converter.fromDetail(FFPrinterDetail(name = "Minimal"))!!
        assertEquals("Minimal", r.name)
        assertFalse(r.isAD5X)
        assertFalse(r.isPro)
        assertEquals("", r.firmwareVersion)
        assertEquals(0.0, r.coolingFanSpeed, 1e-9)
        assertEquals(0.0, r.printBed.current, 1e-9)
        assertEquals(0.0, r.extruder.set, 1e-9)
        assertEquals(MachineState.Unknown, r.machineState)
    }

    @Test
    fun `preserves a populated camera stream url`() {
        val r = converter.fromDetail(
            genericDetail().copy(cameraStreamUrl = "http://192.168.1.100:8080/?action=stream")
        )!!
        assertNotNull(r)
        assertEquals("http://192.168.1.100:8080/?action=stream", r.cameraStreamUrl)
    }
}
