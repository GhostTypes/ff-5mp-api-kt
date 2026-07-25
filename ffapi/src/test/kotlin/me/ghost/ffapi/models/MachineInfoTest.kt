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
        autoShutdownTime = 30f,
        cameraStreamUrl = "",
        coolingFanLeftSpeed = 0f,
        coolingFanSpeed = 0f,
        cumulativeFilament = 0f,
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
        platTargetTemp = 0f,
        platTemp = 27.75f,
    )

    private fun genericDetail(name: String = "FlashForge 5M", pid: Int? = null) = FFPrinterDetail(
        name = name,
        pid = pid,
        firmwareVersion = "1.0.0",
        ipAddr = "192.168.1.100",
        macAddr = "AA:BB:CC:DD:EE:FF",
        coolingFanSpeed = 100f,
        platTemp = 60.5f,
        platTargetTemp = 60.0f,
        rightTemp = 210.3f,
        rightTargetTemp = 210.0f,
        status = "ready",
        cumulativePrintTime = 1200f,
        cumulativeFilament = 500.75f,
    )

    /**
     * A Creator 5 as the hardware reports it. Note the absent `hasMatlStation`: that field is
     * AD5X-only and this family never sends it, station attached or not — pass
     * [matlStationInfo] to model one that is.
     */
    private fun creator5Detail(
        name: String = "Creator 5",
        pid: Int? = 40,
        model: String? = "Creator 5",
        matlStationInfo: MatlStationInfo? = null,
    ) = FFPrinterDetail(
        name = name,
        model = model,
        pid = pid,
        matlStationInfo = matlStationInfo,
        firmwareVersion = "1.0.0",
        status = "ready",
        nozzleCnt = 4f,
        nozzleTemps = listOf(200f, 0f, 210f, 0f),
        nozzleTargetTemps = listOf(210f, 0f, 210f, 0f),
        camera = 1,
        lidar = 1,
        chamberTemp = 45f,
        chamberTargetTemp = 50f,
        platTemp = 60f,
        platTargetTemp = 60f,
        rightTemp = 200f,
        rightTargetTemp = 210f,
        ipAddr = "192.168.1.70",
        macAddr = "AA:BB:CC:DD:EE:01",
    )

    @Test
    fun `parses AD5X details`() {
        val r = converter.fromDetail(ad5xDetail())!!
        assertEquals("AD5X", r.name)
        assertTrue(r.isAD5X)
        assertFalse(r.isPro)
        assertEquals("1.1.3-1.0.8", r.firmwareVersion)
        assertEquals(true, r.hasMatlStation)
        assertEquals(0f, r.coolingFanLeftSpeed!!, 1e-6f)
        assertEquals(4, r.matlStationInfo!!.slotCnt)
        assertEquals(4, r.matlStationInfo!!.slotInfos.size)
        assertEquals("PLA", r.matlStationInfo!!.slotInfos[0].materialName)
        assertEquals(1, r.matlStationInfo!!.slotInfos[0].slotId)
        assertEquals("#2750E0", r.matlStationInfo!!.slotInfos[1].materialColor)
        assertEquals("?", r.indepMatlInfo!!.materialName)
        assertEquals("192.168.0.204", r.ipAddress)
        assertEquals(27.75f, r.printBed.current, 1e-6f)
        assertEquals(0f, r.extruder.current, 1e-6f)
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
        // False, not null: the capability is answered, not passed through.
        assertFalse(r.hasMatlStation)
        assertNull(r.matlStationInfo)
        assertNull(r.indepMatlInfo)
        assertNull(r.coolingFanLeftSpeed)
        assertEquals(100f, r.coolingFanSpeed, 1e-6f)
        assertEquals(60.5f, r.printBed.current, 1e-6f)
        assertEquals(210.3f, r.extruder.current, 1e-6f)
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
        assertEquals(0f, r.coolingFanSpeed, 1e-6f)
        assertEquals(0f, r.printBed.current, 1e-6f)
        assertEquals(0f, r.extruder.set, 1e-6f)
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

    @Test
    fun `detects Creator 5 from pid 40`() {
        val r = converter.fromDetail(creator5Detail(pid = 40))!!
        assertTrue(r.isCreator5)
        assertFalse(r.isCreator5Pro)
        assertFalse(r.isAD5X)
        assertFalse(r.isPro)
        assertEquals(40, r.pid)
        assertEquals("Creator 5", r.model)
    }

    @Test
    fun `detects Creator 5 Pro from pid 41`() {
        val r = converter.fromDetail(creator5Detail(pid = 41, model = "Creator 5 Pro"))!!
        assertTrue(r.isCreator5)
        assertTrue(r.isCreator5Pro)
        assertTrue(r.hasDoorSensor)
        assertEquals("Creator 5 Pro", r.model)
    }

    @Test
    fun `reports the material station on a Creator 5 Pro, which never sends the flag`() {
        // Regression test: hasMatlStation used to be a copy of detail.hasMatlStation, which the
        // Creator 5 series does not send at all. The flag arrived null and every consumer gating
        // on it concluded there was no station, while matlStationInfo listed four loaded slots.
        // Verified against real hardware (pid 41, firmware 1.9.4).
        val station = MatlStationInfo(
            slotCnt = 4,
            slotInfos = listOf(
                SlotInfo(hasFilament = true, materialColor = "#1B1B1B", materialName = "PLA", slotId = 1),
                SlotInfo(hasFilament = true, materialColor = "#1B1B1B", materialName = "PETG", slotId = 2),
                SlotInfo(hasFilament = true, materialColor = "#FFFFFF", materialName = "PLA", slotId = 3),
                SlotInfo(hasFilament = true, materialColor = "#805003", materialName = "PLA", slotId = 4),
            ),
        )
        val detail = creator5Detail(pid = 41, model = "Creator 5 Pro", matlStationInfo = station)
        assertNull(detail.hasMatlStation) // exactly what the printer sends

        val r = converter.fromDetail(detail)!!

        assertTrue(r.hasMatlStation)
        assertEquals(4, r.matlStationInfo!!.slotCnt)
        assertEquals("PETG", r.matlStationInfo!!.slotInfos[1].materialName)
        // Deriving the station must not drag a Creator 5 into AD5X detection.
        assertFalse(r.isAD5X)
        assertTrue(r.isCreator5Pro)
    }

    @Test
    fun `parses Creator 5 multi-nozzle tool temps and capabilities`() {
        val r = converter.fromDetail(creator5Detail())!!
        assertEquals(4, r.nozzleCount)
        assertEquals(4, r.toolTemps.size)
        assertEquals(200f, r.toolTemps[0].current, 1e-6f)
        assertEquals(210f, r.toolTemps[0].set, 1e-6f)
        assertEquals(0f, r.toolTemps[1].current, 1e-6f)
        assertEquals(210f, r.toolTemps[2].current, 1e-6f)
        assertEquals(45f, r.chamber.current, 1e-6f)
        assertEquals(50f, r.chamber.set, 1e-6f)
        assertTrue(r.hasCamera)
        assertTrue(r.hasLidar)
    }

    @Test
    fun `single-nozzle models report one tool temp mirroring the extruder`() {
        val r = converter.fromDetail(genericDetail())!!
        assertEquals(1, r.toolTemps.size)
        assertEquals(r.extruder.current, r.toolTemps[0].current, 1e-6f)
        assertEquals(r.extruder.set, r.toolTemps[0].set, 1e-6f)
        assertFalse(r.hasCamera)
        assertFalse(r.hasLidar)
        assertFalse(r.hasDoorSensor)
    }

    @Test
    fun `hasCamera is true when cameraStreamUrl is present`() {
        val r = converter.fromDetail(genericDetail().copy(cameraStreamUrl = "http://x/stream"))!!
        assertTrue(r.hasCamera)
        assertFalse(r.hasLidar)
    }

    @Test
    fun `model falls back to pid-derived name when detail model is absent`() {
        val r = converter.fromDetail(creator5Detail(name = "MyC5", model = null))!!
        assertEquals("Creator 5", r.model)
    }
}
