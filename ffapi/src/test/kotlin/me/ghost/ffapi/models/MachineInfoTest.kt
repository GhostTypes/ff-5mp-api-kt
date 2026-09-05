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
        // Verified against real hardware (firmware 1.9.4).
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

    // ---- Status-string mapping (ported from the TS `getMachineState` suite) ----

    private fun expectState(status: String, expected: MachineState) {
        val r = converter.fromDetail(genericDetail().copy(status = status))
        assertEquals(expected, r!!.machineState)
    }

    @Test
    fun `maps the documented status strings`() {
        expectState("ready", MachineState.Ready)
        expectState("busy", MachineState.Busy)
        expectState("calibrate_doing", MachineState.Calibrating)
        expectState("error", MachineState.Error)
        expectState("heating", MachineState.Heating)
        expectState("printing", MachineState.Printing)
        expectState("pausing", MachineState.Pausing)
        expectState("paused", MachineState.Paused)
        expectState("cancel", MachineState.Cancelled)
        expectState("completed", MachineState.Completed)
    }

    // The Creator 5 Pro sends "pause", not the documented "paused", exactly when it pauses
    // itself on a detected clog — so the state read Unknown at the moment the user most needed
    // to know why the print stopped. Observed on pid 41, firmware 1.9.4. Ported from the
    // TS/py fix.
    @Test
    fun `maps the undocumented pause the Creator 5 Pro actually sends`() {
        expectState("pause", MachineState.Paused)
    }

    // Sent while a file is transferring to the printer. Mapped onto the existing Busy rather
    // than a new enum member: consumers may pin this enum to a fixed list, so adding a member
    // is breaking for them while reusing one is not.
    @Test
    fun `maps downloading onto Busy`() {
        expectState("downloading", MachineState.Busy)
    }

    @Test
    fun `falls back to Unknown for genuinely unrecognized statuses`() {
        expectState("flurbling", MachineState.Unknown)
        expectState("", MachineState.Unknown)
    }

    @Test
    fun `status mapping is case-insensitive`() {
        expectState("Pause", MachineState.Paused)
        expectState("PRINTING", MachineState.Printing)
    }

    // ---- CompletionTime gate (ported from the TS `CompletionTime` suite) ----

    // Printing is the only state the firmware counts `estimatedTime` down in, so it is the
    // only state where `now + estimatedTime` stays put across polls.
    @Test
    fun `derives a completion timestamp while the print is advancing`() {
        val before = System.currentTimeMillis()
        val r = converter.fromDetail(
            genericDetail().copy(status = "printing", estimatedTime = 3600f)
        )!!
        val after = System.currentTimeMillis()

        assertNotNull(r.completionTimeMillis)
        assertTrue(r.completionTimeMillis!! >= before + 3_590_000L)
        assertTrue(r.completionTimeMillis!! <= after + 3_610_000L)
        assertEquals("01:00", r.printEta)
    }

    // The firmware stops counting `estimatedTime` down whenever the print is not progressing.
    // Deriving `now + estimatedTime` on every poll would then walk the completion time forward
    // one minute per minute — a paused print would appear to recede forever. `printEta` stays
    // populated because the remaining *duration* is still correct; only the absolute
    // timestamp is not.
    //
    // 'heating' belongs here, not above: the pre-print warmup does not advance the job either,
    // so it drifts the same way, just for minutes not hours.
    @Test
    fun `returns null completion time when the print is not advancing`() {
        for (status in listOf("paused", "pause", "pausing", "heating", "ready", "error", "completed")) {
            val r = converter.fromDetail(
                genericDetail().copy(status = status, estimatedTime = 3600f)
            )
            assertNull(status, r!!.completionTimeMillis)
            assertEquals("01:00", r.printEta)
        }
    }

    // ---- Temperature-sentinel normalization + hasChamberSensor (ported from the py suite) ----

    // The firmware reports "this sensor does not exist" with an out-of-band negative sentinel
    // (-108 on a chamber-less Creator 5) instead of omitting the field. It must read as absent,
    // never as a -108 °C temperature.
    @Test
    fun `normalizes a chamberless Creator 5 sentinel to absent`() {
        val detail = creator5Detail().copy(chamberTemp = -108f, chamberTargetTemp = -108f)

        val r = converter.fromDetail(detail)!!

        assertFalse(r.hasChamberSensor)
        assertEquals(0f, r.chamber.current, 1e-6f)
        assertEquals(0f, r.chamber.set, 1e-6f)
    }

    @Test
    fun `a real chamber reading is untouched and the sensor flag follows it`() {
        val r = converter.fromDetail(creator5Detail())!! // chamberTemp = 45, target = 50

        assertTrue(r.hasChamberSensor)
        assertEquals(45f, r.chamber.current, 1e-6f)
        assertEquals(50f, r.chamber.set, 1e-6f)
    }

    @Test
    fun `treats every value at or below the sentinel floor as absent`() {
        for (t in listOf(-100f, -108f, -273f, MachineInfo.TEMP_SENTINEL_FLOOR)) {
            val r = converter.fromDetail(creator5Detail().copy(chamberTemp = t))!!
            assertFalse("chamberTemp $t must read as absent", r.hasChamberSensor)
            assertEquals(0f, r.chamber.current, 1e-6f)
        }
    }

    @Test
    fun `a value just above the floor is still a reading`() {
        val r = converter.fromDetail(creator5Detail().copy(chamberTemp = -49f))!!
        assertTrue(r.hasChamberSensor)
        assertEquals(-49f, r.chamber.current, 1e-6f)
    }

    // Only the chamber sentinel is confirmed in the wild, but the same normalization is
    // applied defensively to every temperature the structured model exposes.
    @Test
    fun `normalizes sentinels on the bed, extruder and per-tool temps too`() {
        val r = converter.fromDetail(
            genericDetail().copy(
                platTemp = -50f,
                platTargetTemp = -60f,
                rightTemp = -100f,
                rightTargetTemp = -100f,
            )
        )!!

        assertEquals(0f, r.printBed.current, 1e-6f)
        assertEquals(0f, r.printBed.set, 1e-6f)
        assertEquals(0f, r.extruder.current, 1e-6f)
        assertEquals(0f, r.extruder.set, 1e-6f)
        assertEquals(0f, r.toolTemps[0].current, 1e-6f)

        val tools = converter.fromDetail(
            creator5Detail().copy(nozzleTemps = listOf(200f, -108f), nozzleTargetTemps = listOf(210f, -108f))
        )!!
        assertEquals(200f, tools.toolTemps[0].current, 1e-6f)
        assertEquals(0f, tools.toolTemps[1].current, 1e-6f)
        assertEquals(0f, tools.toolTemps[1].set, 1e-6f)
    }

    @Test
    fun `hasChamberSensor is false when the printer omits the field entirely`() {
        val r = converter.fromDetail(genericDetail())!! // no chamberTemp at all
        assertFalse(r.hasChamberSensor)
        assertEquals(0f, r.chamber.current, 1e-6f)
    }
}
