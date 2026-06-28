package me.ghost.ffapi.api.controls

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.ghost.ffapi.PrinterCapabilities
import me.ghost.ffapi.PrinterConfig
import me.ghost.ffapi.PrinterModel
import me.ghost.ffapi.api.FlashForgeHttpApi
import me.ghost.ffapi.backend.DualApiBackend
import me.ghost.ffapi.error.NotSupportedException
import me.ghost.ffapi.models.TempCtlArgs
import me.ghost.ffapi.tcpapi.FlashForgeClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from the TS `TempControl.test.ts`. Verifies the HTTP `temperatureCtl_cmd` wire format for
 * the HTTP-only Creator 5 transport, including the v1.6.1 nozzle-off = `0` bugfix (the firmware
 * ignores -100 inside the `nozzles[]` array) and the C5's always-4-entry `nozzles[]` array.
 *
 * A [CapturingBackend] subclass overrides [PrinterBackend.sendTempControl] to record the built
 * [TempCtlArgs] without touching the network.
 */
class TempControlTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** Backend double: records every [TempCtlArgs] it would have POSTed, model/httpOnly configurable. */
    private class CapturingBackend(
        override val model: PrinterModel,
        chamberCapable: Boolean = model.isCreator5,
        override val httpOnly: Boolean = model.isCreator5,
    ) : DualApiBackend(
        printer = PrinterConfig("0.0.0.0", "SN", "CC"),
        http = FlashForgeHttpApi("0.0.0.0"),
        tcp = FlashForgeClient("0.0.0.0", kotlinx.coroutines.test.TestScope()),
    ) {
        val captured = mutableListOf<TempCtlArgs>()

        init {
            capabilities = PrinterCapabilities(model = model, chamberTempControl = chamberCapable)
        }

        override suspend fun sendTempControl(args: TempCtlArgs): Result<Unit> {
            captured += args
            return Result.success(Unit)
        }
    }

    // ---- TempCtlArgs wire serialization ----

    @Test
    fun `omits the nozzles array when null`() {
        val s = json.encodeToString(TempCtlArgs.serializer(), TempCtlArgs(-200, -200, 60, -200, null))
        assertEquals("""{"rightNozzle":-200,"leftNozzle":-200,"platform":60,"chamber":-200}""", s)
    }

    @Test
    fun `includes the nozzles array when present`() {
        val s = json.encodeToString(
            TempCtlArgs.serializer(),
            TempCtlArgs(-200, -200, -200, -200, listOf(-200, -200, 230, -200))
        )
        assertEquals(
            """{"rightNozzle":-200,"leftNozzle":-200,"platform":-200,"chamber":-200,"nozzles":[-200,-200,230,-200]}""",
            s,
        )
    }

    // ---- buildNozzleArray ----

    @Test
    fun `buildNozzleArray sets one tool and leaves others at -200`() {
        assertEquals(listOf(-200, -200, 230, -200), TempControl.buildNozzleArray(2, 230))
    }

    @Test
    fun `buildNozzleArray returns null for an out-of-range index`() {
        assertNull(TempControl.buildNozzleArray(-1, 200))
        assertNull(TempControl.buildNozzleArray(4, 200))
    }

    // ---- HTTP-only single-tool path (httpOnly && !isCreator5 -> rightNozzle scalar) ----

    @Test
    fun `setNozzleTemp on httpOnly single-tool sends rightNozzle, others unchanged`() = runTest {
        val b = CapturingBackend(PrinterModel.ADVENTURER_5M, httpOnly = true)
        val r = b.setNozzleTemp(215)
        assertTrue(r.isSuccess)
        assertEquals(TempCtlArgs(215, -200, -200, -200, null), b.captured.single())
    }

    @Test
    fun `cancelNozzleTemp on httpOnly single-tool turns rightNozzle off via -100`() = runTest {
        val b = CapturingBackend(PrinterModel.ADVENTURER_5M, httpOnly = true)
        b.cancelNozzleTemp()
        assertEquals(TempCtlArgs(-100, -200, -200, -200, null), b.captured.single())
    }

    // ---- Creator 5 per-tool nozzles[] path (the v1.6.1 nozzle-off = 0 bugfix) ----

    @Test
    fun `C5 setNozzleTemp drives T0 via nozzles and does NOT set rightNozzle`() = runTest {
        val b = CapturingBackend(PrinterModel.CREATOR_5)
        val r = b.setNozzleTemp(215)
        assertTrue(r.isSuccess)
        assertEquals(
            TempCtlArgs(-200, -200, -200, -200, listOf(215, -200, -200, -200)),
            b.captured.single(),
        )
    }

    @Test
    fun `C5 cancelNozzleTemp turns T0 off via 0 in nozzles (firmware ignores -100)`() = runTest {
        val b = CapturingBackend(PrinterModel.CREATOR_5)
        b.cancelNozzleTemp()
        assertEquals(
            TempCtlArgs(-200, -200, -200, -200, listOf(0, -200, -200, -200)),
            b.captured.single(),
        )
    }

    @Test
    fun `setToolTemp sets one tool and leaves the others at -200`() = runTest {
        val b = CapturingBackend(PrinterModel.CREATOR_5)
        val r = b.setToolTemp(2, 230)
        assertTrue(r.isSuccess)
        val args = b.captured.single()
        assertEquals(listOf(-200, -200, 230, -200), args.nozzles)
    }

    @Test
    fun `setToolTemp rejects an out-of-range index without sending`() = runTest {
        val b = CapturingBackend(PrinterModel.CREATOR_5)
        val r = b.setToolTemp(5, 200)
        assertTrue(r.isFailure)
        assertTrue(b.captured.isEmpty())
    }

    @Test
    fun `setToolTemps sends all four per-tool targets`() = runTest {
        val b = CapturingBackend(PrinterModel.CREATOR_5)
        val r = b.setToolTemps(listOf(200, 210, 220, 230))
        assertTrue(r.isSuccess)
        assertEquals(listOf(200, 210, 220, 230), b.captured.single().nozzles)
    }

    @Test
    fun `setToolTemps rejects a wrong-length array without sending`() = runTest {
        val b = CapturingBackend(PrinterModel.CREATOR_5)
        val r = b.setToolTemps(listOf(200, 210))
        assertTrue(r.isFailure)
        assertTrue(b.captured.isEmpty())
    }

    @Test
    fun `cancelToolTemp turns a single tool off via 0`() = runTest {
        val b = CapturingBackend(PrinterModel.CREATOR_5)
        b.cancelToolTemp(0)
        assertEquals(listOf(0, -200, -200, -200), b.captured.single().nozzles)
    }

    // ---- Bed temp ----

    @Test
    fun `httpOnly single-tool setBedTemp sends platform set, no nozzles`() = runTest {
        val b = CapturingBackend(PrinterModel.ADVENTURER_5M, httpOnly = true)
        b.setBedTemp(60)
        assertEquals(TempCtlArgs(-200, -200, 60, -200, null), b.captured.single())
    }

    @Test
    fun `httpOnly single-tool cancelBedTemp turns platform off via -100`() = runTest {
        val b = CapturingBackend(PrinterModel.ADVENTURER_5M, httpOnly = true)
        b.cancelBedTemp()
        assertEquals(TempCtlArgs(-200, -200, -100, -200, null), b.captured.single())
    }

    @Test
    fun `C5 setBedTemp still includes a 4-entry nozzles array (all unchanged)`() = runTest {
        val b = CapturingBackend(PrinterModel.CREATOR_5)
        val r = b.setBedTemp(60)
        assertTrue(r.isSuccess)
        assertEquals(
            TempCtlArgs(-200, -200, 60, -200, listOf(-200, -200, -200, -200)),
            b.captured.single(),
        )
    }

    // ---- Chamber temp (Creator 5 series, capability-gated) ----

    @Test
    fun `C5 setChamberTemp sends chamber set and a 4-entry nozzles array`() = runTest {
        val b = CapturingBackend(PrinterModel.CREATOR_5)
        val r = b.setChamberTemp(50)
        assertTrue(r.isSuccess)
        assertEquals(
            TempCtlArgs(-200, -200, -200, 50, listOf(-200, -200, -200, -200)),
            b.captured.single(),
        )
    }

    @Test
    fun `C5 cancelChamberTemp turns the chamber off via -100`() = runTest {
        val b = CapturingBackend(PrinterModel.CREATOR_5)
        b.cancelChamberTemp()
        assertEquals(
            TempCtlArgs(-200, -200, -200, -100, listOf(-200, -200, -200, -200)),
            b.captured.single(),
        )
    }

    @Test
    fun `setChamberTemp is rejected when the model lacks the chamber capability`() = runTest {
        val b = CapturingBackend(PrinterModel.ADVENTURER_5M, chamberCapable = false, httpOnly = false)
        val r = b.setChamberTemp(50)
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull() is NotSupportedException)
        assertTrue(b.captured.isEmpty())
    }

    @Test
    fun `sentinel constants match the TS values`() {
        assertEquals(-200, TempControl.TEMP_NO_CHANGE)
        assertEquals(-100, TempControl.TEMP_OFF)
        assertEquals(0, TempControl.NOZZLE_OFF)
        assertEquals(4, TempControl.NOZZLE_COUNT)
        // Regression guard: NOZZLE_OFF must NOT equal TEMP_OFF.
        assertFalse(TempControl.NOZZLE_OFF == TempControl.TEMP_OFF)
    }
}
