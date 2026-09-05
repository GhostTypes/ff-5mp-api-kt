package me.ghost.ffapi.models

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the lenient `pid` deserialization: the authoritative docs describe the wire value as a
 * hex-encoded string ("0023"), while some transports send a plain JSON number (35). Both must
 * arrive as the same `Int?` model id. Cross-checked against the firmware pid table: 35=5M,
 * 36=5M Pro, 38=AD5X, 40=Creator 5, 41=Creator 5 Pro.
 */
class FFPrinterDetailPidTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun pidFrom(detailJson: String): Int? =
        json.decodeFromString<FFPrinterDetail>(detailJson).pid

    @Test
    fun `parses a plain JSON number`() {
        assertEquals(35, pidFrom("""{"pid": 35}"""))
        assertEquals(36, pidFrom("""{"pid": 36}"""))
        assertEquals(38, pidFrom("""{"pid": 38}"""))
    }

    @Test
    fun `parses a decimal-number form`() {
        // The firmware serializes whole values inconsistently — sometimes with ".0".
        assertEquals(35, pidFrom("""{"pid": 35.0}"""))
        assertEquals(41, pidFrom("""{"pid": 41.0}"""))
    }

    @Test
    fun `parses the documented hex string form`() {
        // "0023" = 0x23 = 35 = Adventurer 5M; a decimal Int? mis-parses this as 23 (Guider 2).
        assertEquals(35, pidFrom("""{"pid": "0023"}"""))
        assertEquals(36, pidFrom("""{"pid": "0024"}"""))
        assertEquals(38, pidFrom("""{"pid": "0026"}"""))
        assertEquals(40, pidFrom("""{"pid": "0028"}"""))
        assertEquals(41, pidFrom("""{"pid": "0029"}"""))
    }

    @Test
    fun `an all-zero hex string is zero`() {
        assertEquals(0, pidFrom("""{"pid": "0000"}"""))
        assertEquals(0, pidFrom("""{"pid": "0"}"""))
    }

    @Test
    fun `missing, null and unparseable pids yield null`() {
        assertNull(pidFrom("""{"name": "X"}"""))
        assertNull(pidFrom("""{"pid": null}"""))
        assertNull(pidFrom("""{"pid": "not-hex"}"""))
    }

    @Test
    fun `pid survives a decode-encode round trip`() {
        val original = FFPrinterDetail(name = "AD5X", pid = 38)
        val encoded = json.encodeToString(FFPrinterDetail.serializer(), original)
        assertEquals(38, json.decodeFromString<FFPrinterDetail>(encoded).pid)
    }

    @Test
    fun `a hex-string pid drives model detection end to end`() {
        // What /detail actually delivers on a 5M: pid as "0023". Detection must read it as 35.
        val detail = json.decodeFromString<FFPrinterDetail>(
            """{"name": "MyPrinter", "pid": "0023", "status": "printing"}"""
        )
        val info = MachineInfo().fromDetail(detail)!!

        assertEquals(35, info.pid)
        assertFalse(info.isPro)
        assertFalse(info.isAD5X)
        assertTrue(info.isCreator5.not())

        // And the AD5X hex form ("0026" = 38) must detect as AD5X even when renamed.
        val ad5x = json.decodeFromString<FFPrinterDetail>(
            """{"name": "LegoTech82", "pid": "0026", "status": "ready"}"""
        )
        val ad5xInfo = MachineInfo().fromDetail(ad5x)!!
        assertEquals(38, ad5xInfo.pid)
        assertTrue(ad5xInfo.isAD5X)
    }
}
