package me.ghost.ffapi.api.controls

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.ghost.ffapi.PrinterConfig
import me.ghost.ffapi.PrinterModel
import me.ghost.ffapi.api.FlashForgeHttpApi
import me.ghost.ffapi.backend.AD5XBackend
import me.ghost.ffapi.backend.Creator5Backend
import me.ghost.ffapi.backend.DualApiBackend
import me.ghost.ffapi.models.AD5XMaterialMapping
import me.ghost.ffapi.tcpapi.FlashForgeClient
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer as OkBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Ported from the TS `JobControl.test.ts` (startCreator5Job, printLocalFile/new-firmware) plus
 * upload wire-format coverage (the TS lib had no upload tests — added here). Uses an OkHttp
 * application interceptor as a test seam: it records the outgoing request and short-circuits the
 * network with a success envelope, so the exact wire bodies/headers are asserted without sockets.
 */
class JobControlWireFormatTest {

    private val okBody = """{"code":0,"message":"ok"}""".toResponseBody("application/json".toMediaType())

    /** An api whose every call is captured and answered with a success envelope (no network). */
    private fun capturingApi(): Pair<FlashForgeHttpApi, MutableList<Request>> {
        val requests = mutableListOf<Request>()
        val api = FlashForgeHttpApi("printer", testInterceptor = Interceptor { chain ->
            requests += chain.request()
            okhttp3.Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("ok")
                .body(okBody)
                .build()
        })
        return api to requests
    }

    private fun bodyText(request: Request): String {
        val buffer = OkBuffer()
        request.body?.writeTo(buffer)
        return buffer.readUtf8()
    }

    private fun tcp() = FlashForgeClient("0.0.0.0", kotlinx.coroutines.test.TestScope())

    private fun printer(firmware: String? = "1.9.2") =
        PrinterConfig("0.0.0.0", "SN123456", "CC123456", firmwareVersion = firmware)

    // ---- startCreator5Job (/printGcode body) ----

    @Test
    fun `Creator 5 start-job posts the C5-native printGcode body`() = runTest {
        val (api, requests) = capturingApi()
        val backend = Creator5Backend(printer(), api, tcp(), PrinterModel.CREATOR_5)

        val result = backend.startCreator5Job(
            fileName = "multi.3mf",
            levelingBeforePrint = true,
            materialMappings = listOf(
                AD5XMaterialMapping(0, 2, "PLA", "#2E54DD", "#2E54DD"),
                AD5XMaterialMapping(1, 3, "PETG", "#FF0000", "#FF0000"),
            ),
        )
        assertTrue(result.isSuccess)

        val body = Json.parseToJsonElement(bodyText(requests.single())).jsonObject
        assertEquals("SN123456", body["serialNumber"]!!.jsonPrimitive.content)
        assertEquals("CC123456", body["checkCode"]!!.jsonPrimitive.content)
        assertEquals("multi.3mf", body["fileName"]!!.jsonPrimitive.content)
        assertEquals(true, body["levelingBeforePrint"]!!.jsonPrimitive.boolean)
        assertEquals(false, body["flowCalibration"]!!.jsonPrimitive.boolean)
        assertEquals(false, body["timeLapseVideo"]!!.jsonPrimitive.boolean)

        // Confirmed C5 capture: these fields do NOT belong on the /printGcode body.
        assertFalse("useMatlStation must be absent", body.containsKey("useMatlStation"))
        assertFalse("gcodeToolCnt must be absent", body.containsKey("gcodeToolCnt"))
        assertFalse("firstLayerInspection must be absent", body.containsKey("firstLayerInspection"))

        val mappings = body["materialMappings"]!!.jsonArray
        assertEquals(2, mappings.size)
        assertEquals(0, mappings[0].jsonObject["toolId"]!!.jsonPrimitive.int)
        assertEquals(2, mappings[0].jsonObject["slotId"]!!.jsonPrimitive.int)
        assertEquals("PLA", mappings[0].jsonObject["materialName"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Creator 5 single-tool start-job omits materialMappings`() = runTest {
        val (api, requests) = capturingApi()
        val backend = Creator5Backend(printer(), api, tcp(), PrinterModel.CREATOR_5)

        backend.startCreator5Job(fileName = "single.gcode", levelingBeforePrint = false)

        val body = Json.parseToJsonElement(bodyText(requests.single())).jsonObject
        assertFalse(body.containsKey("materialMappings"))
        // flowCalibration / timeLapseVideo are still always present.
        assertEquals(false, body["flowCalibration"]!!.jsonPrimitive.boolean)
        assertEquals(false, body["timeLapseVideo"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `Creator 5 start-job rejects an invalid slotId without calling the printer`() = runTest {
        val (api, requests) = capturingApi()
        val backend = Creator5Backend(printer(), api, tcp(), PrinterModel.CREATOR_5)

        val result = backend.startCreator5Job(
            fileName = "bad.3mf",
            levelingBeforePrint = true,
            materialMappings = listOf(AD5XMaterialMapping(0, 0, "PLA", "#2E54DD", "#2E54DD")), // slotId 0 invalid
        )
        assertTrue(result.isFailure)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `Creator 5 start-job rejects an empty file name`() = runTest {
        val (api, requests) = capturingApi()
        val backend = Creator5Backend(printer(), api, tcp(), PrinterModel.CREATOR_5)
        assertTrue(backend.startCreator5Job(fileName = "  ", levelingBeforePrint = true).isFailure)
        assertTrue(requests.isEmpty())
    }

    // ---- uploadFile (AD5X) headers ----

    @Test
    fun `AD5X upload sends the material-station headers with base64 materialMappings`() = runTest {
        val (api, requests) = capturingApi()
        val mappings = listOf(AD5XMaterialMapping(0, 1, "PLA", "#FF0000", "#FF0000"))

        api.uploadFile(
            serialNumber = "SN", checkCode = "CC",
            fileName = "f.gcode", fileBytes = byteArrayOf(1, 2, 3),
            startPrint = true, levelBeforePrint = true,
            gcodeToolCnt = 1, materialMappings = mappings,
        ).getOrThrow()

        val req = requests.single()
        assertEquals("SN", req.header("serialNumber"))
        assertEquals("CC", req.header("checkCode"))
        assertEquals("3", req.header("fileSize"))
        assertEquals("true", req.header("printNow"))
        assertEquals("true", req.header("levelingBeforePrint"))
        assertEquals("true", req.header("useMatlStation"))
        assertEquals("1", req.header("gcodeToolCnt"))
        assertEquals("false", req.header("firstLayerInspection"))
        assertEquals("false", req.header("flowCalibration"))
        assertEquals("false", req.header("timeLapseVideo"))
        assertTrue(req.body?.contentType()?.toString()?.startsWith("multipart/form-data") == true)

        // materialMappings header is the base64 of the JSON array.
        val b64 = req.header("materialMappings")
        assertNotNull(b64)
        val decoded = String(Base64.getDecoder().decode(b64!!), Charsets.UTF_8)
        assertTrue(decoded.contains("\"toolId\":0"))
        assertTrue(decoded.contains("\"materialName\":\"PLA\""))
    }

    // ---- uploadFileCreator5 headers ----

    @Test
    fun `Creator 5 upload omits firstLayerInspection and materialMappings headers`() = runTest {
        val (api, requests) = capturingApi()

        api.uploadFileCreator5(
            serialNumber = "SN", checkCode = "CC",
            fileName = "f.gcode", fileBytes = byteArrayOf(1, 2, 3),
            startPrint = true, levelBeforePrint = false,
            flowCalibration = true, useMatlStation = true, gcodeToolCnt = 2,
        ).getOrThrow()

        val req = requests.single()
        assertEquals("SN", req.header("serialNumber"))
        assertEquals("3", req.header("fileSize"))
        assertEquals("true", req.header("printNow"))
        assertEquals("false", req.header("levelingBeforePrint"))
        assertEquals("true", req.header("flowCalibration")) // booleans as the string "true"/"false"
        assertEquals("true", req.header("useMatlStation"))
        assertEquals("2", req.header("gcodeToolCnt"))
        // NO firstLayerInspection (absent on the C5); NO materialMappings (C5 maps at print-start).
        assertNull(req.header("firstLayerInspection"))
        assertNull(req.header("materialMappings"))
        assertTrue(req.body?.contentType()?.toString()?.startsWith("multipart/form-data") == true)
    }

    // ---- isNewFirmware short-circuit (probe exposes the protected check) ----

    private class FirmwareProbe(override val model: PrinterModel, firmware: String?) : DualApiBackend(
        printer = PrinterConfig("0.0.0.0", "SN", "CC", firmwareVersion = firmware),
        http = FlashForgeHttpApi("0.0.0.0"),
        tcp = FlashForgeClient("0.0.0.0", kotlinx.coroutines.test.TestScope()),
    ) {
        fun probe(): Boolean = isNewFirmware()
    }

    @Test
    fun `isNewFirmware short-circuits to true for AD5X despite a low version string`() {
        assertTrue(FirmwareProbe(PrinterModel.AD5X, "1.1.7").probe())
    }

    @Test
    fun `isNewFirmware short-circuits to true for Creator 5 despite reporting 1_9_2`() {
        assertTrue(FirmwareProbe(PrinterModel.CREATOR_5, "1.9.2").probe())
        assertTrue(FirmwareProbe(PrinterModel.CREATOR_5_PRO, "1.9.2").probe())
    }

    @Test
    fun `isNewFirmware keeps the 3_1_3 threshold for the 5M family`() {
        assertTrue(FirmwareProbe(PrinterModel.ADVENTURER_5M, "3.1.3").probe())
        assertTrue(FirmwareProbe(PrinterModel.ADVENTURER_5M, "3.2.0").probe())
        assertFalse(FirmwareProbe(PrinterModel.ADVENTURER_5M, "2.0.0").probe())
        assertFalse(FirmwareProbe(PrinterModel.ADVENTURER_5M, null).probe())
    }

    // ---- AD5X startPrint always uses the full payload (mirrors TS printLocalFile) ----

    @Test
    fun `AD5X startPrint sends the new payload regardless of firmware version`() = runTest {
        val (api, requests) = capturingApi()
        val backend = AD5XBackend(printer(firmware = "1.1.7"), api, tcp())

        backend.startPrint("test.gcode", true).getOrThrow()

        val body = bodyText(requests.single())
        assertTrue("new payload must carry useMatlStation", body.contains("\"useMatlStation\""))
        assertTrue(body.contains("\"gcodeToolCnt\""))
    }

    @Test
    fun `AD5X startPrint with mappings sets useMatlStation true and tool count`() = runTest {
        val (api, requests) = capturingApi()
        val backend = AD5XBackend(printer(), api, tcp())
        val mappings = listOf(
            AD5XMaterialMapping(0, 1, "PLA", "#FF0000", "#FF0000"),
            AD5XMaterialMapping(1, 2, "PLA", "#00FF00", "#00FF00"),
        )

        backend.startPrint("multicolor.3mf", true, mappings).getOrThrow()

        val body = bodyText(requests.single())
        assertTrue(body.contains("\"useMatlStation\":true"))
        assertTrue(body.contains("\"gcodeToolCnt\":2"))
        assertTrue(body.contains("\"materialMappings\""))
    }
}
