package me.ghost.ffapi.api.controls

import kotlinx.coroutines.test.runTest
import me.ghost.ffapi.PrinterCapabilities
import me.ghost.ffapi.PrinterConfig
import me.ghost.ffapi.PrinterModel
import me.ghost.ffapi.api.FlashForgeHttpApi
import me.ghost.ffapi.backend.DualApiBackend
import me.ghost.ffapi.backend.SlotAction
import me.ghost.ffapi.error.NotSupportedException
import me.ghost.ffapi.tcpapi.FlashForgeClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format tests for the model-gated `msConfig_cmd` color field. Mirrors the TS
 * `Control.configureSlot`: Creator 5 sends a snapped uppercase `#RRGGBB` (firmware renders an icon
 * only on a byte-for-byte palette match); AD5X strips the `#` for freeform hex. `slotAction`
 * (`ms_cmd`) stays AD5X-only — it is rejected on a Creator 5.
 */
class ConfigureSlotWireFormatTest {

    /** Backend double: captures the resolved (slot, mt, rgb) it would have POSTed. */
    private class CapturingBackend(
        override val model: PrinterModel,
        materialStation: Boolean = true,
    ) : DualApiBackend(
        printer = PrinterConfig("0.0.0.0", "SN", "CC"),
        http = FlashForgeHttpApi("0.0.0.0"),
        tcp = FlashForgeClient("0.0.0.0", kotlinx.coroutines.test.TestScope()),
    ) {
        data class SlotCfg(val slot: Int, val mt: String, val rgb: String)
        val captured = mutableListOf<SlotCfg>()

        init {
            capabilities = PrinterCapabilities(model = model, hasMaterialStation = materialStation)
        }

        override suspend fun sendConfigureSlot(slot: Int, materialName: String, rgb: String): Result<Unit> {
            captured += SlotCfg(slot, materialName, rgb)
            return Result.success(Unit)
        }
    }

    @Test
    fun `Creator 5 snaps the color to a palette hashRRGGBB with the hash`() = runTest {
        val b = CapturingBackend(PrinterModel.CREATOR_5)
        b.setSlotMaterial(slot = 1, materialName = "PLA", hexRgb = "#FF0000")
        val cfg = b.captured.single()
        assertEquals(1, cfg.slot)
        assertEquals("PLA", cfg.mt)
        // Pure red snaps to palette Red #F82D29, WITH the leading '#'.
        assertEquals("#F82D29", cfg.rgb)
    }

    @Test
    fun `AD5X strips the hash and sends freeform RRGGBB`() = runTest {
        val b = CapturingBackend(PrinterModel.AD5X)
        b.setSlotMaterial(slot = 2, materialName = "PETG", hexRgb = "#FF8800")
        val cfg = b.captured.single()
        assertEquals("FF8800", cfg.rgb)
    }

    @Test
    fun `AD5X leaves an already-bare hex unchanged`() = runTest {
        val b = CapturingBackend(PrinterModel.AD5X)
        b.setSlotMaterial(slot = 3, materialName = "PLA", hexRgb = "12ABCD")
        assertEquals("12ABCD", b.captured.single().rgb)
    }

    @Test
    fun `Creator 5 Pro uses the palette snap too`() = runTest {
        val b = CapturingBackend(PrinterModel.CREATOR_5_PRO)
        b.setSlotMaterial(slot = 4, materialName = "PLA", hexRgb = "4caaf8")
        // Case-insensitive input still snaps to the exact uppercase palette entry.
        assertEquals("#4CAAF8", b.captured.single().rgb)
    }

    @Test
    fun `setSlotMaterial is rejected when the model has no material station`() = runTest {
        val b = CapturingBackend(PrinterModel.ADVENTURER_5M, materialStation = false)
        val r = b.setSlotMaterial(slot = 1, materialName = "PLA", hexRgb = "#FF0000")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull() is NotSupportedException)
        assertTrue(b.captured.isEmpty())
    }

    @Test
    fun `slotAction ms_cmd is rejected on Creator 5 even with a material station`() = runTest {
        val b = CapturingBackend(PrinterModel.CREATOR_5)
        val r = b.slotAction(slot = 1, action = SlotAction.LOAD)
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull() is NotSupportedException)
    }
}
