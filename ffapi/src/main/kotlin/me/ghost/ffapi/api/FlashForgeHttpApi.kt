package me.ghost.ffapi.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import me.ghost.ffapi.api.server.Commands
import me.ghost.ffapi.api.server.Endpoints
import me.ghost.ffapi.error.ApiErrorException
import me.ghost.ffapi.error.AuthException
import me.ghost.ffapi.error.PrinterUnreachableException
import me.ghost.ffapi.error.ProtocolException
import me.ghost.ffapi.models.AD5XMaterialMapping
import me.ghost.ffapi.models.CirculateCtlArgs
import me.ghost.ffapi.models.ControlPayload
import me.ghost.ffapi.models.ControlRequest
import me.ghost.ffapi.models.Creator5PrintGcodeRequest
import me.ghost.ffapi.models.CredentialsRequest
import me.ghost.ffapi.models.DelayCloseArgs
import me.ghost.ffapi.models.DetailResponse
import me.ghost.ffapi.models.FFGcodeFileEntry
import me.ghost.ffapi.models.FFPrinterDetail
import me.ghost.ffapi.models.GcodeListResponse
import me.ghost.ffapi.models.GcodeThumbRequest
import me.ghost.ffapi.models.GcodeThumbResponse
import me.ghost.ffapi.models.GenericResponse
import me.ghost.ffapi.models.JobCtlArgs
import me.ghost.ffapi.models.LightControlArgs
import me.ghost.ffapi.models.MsConfigArgs
import me.ghost.ffapi.models.MsCtlArgs
import me.ghost.ffapi.models.PrintGcodeRequest
import me.ghost.ffapi.models.PrintGcodeRequestLegacy
import me.ghost.ffapi.models.Product
import me.ghost.ffapi.models.ProductResponse
import me.ghost.ffapi.models.ReNameArgs
import me.ghost.ffapi.models.StateCtrlArgs
import me.ghost.ffapi.models.TempCtlArgs
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * HTTP REST transport for modern FlashForge printers (port 8898). Ported from the app's
 * `FlashForgeHttpApi`, with the generic exceptions replaced by the library's typed hierarchy:
 * network failures → [PrinterUnreachableException], rejected credentials → [AuthException] (on the
 * credentialed `/detail` and `/product` reads), other non-zero API codes → [ApiErrorException].
 *
 * A single transport is shared by the control modules (DRY); the TS lib instead inlined axios in
 * each module. Cleartext HTTP only — printers do not use TLS.
 */
class FlashForgeHttpApi(
    private val ipAddress: String,
    port: Int = 8898,
    /** Optional OkHttp application interceptor (test seam — short-circuits the network). */
    testInterceptor: Interceptor? = null,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .apply { if (testInterceptor != null) addInterceptor(testInterceptor) }
        .build()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val baseUrl = "http://$ipAddress:$port"
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val octetStream = "application/octet-stream".toMediaType()

    /**
     * Serializes command-submission POSTs so concurrent commands take turns instead of
     * interleaving on the printer. The mutex is fair: waiters acquire it in first-in,
     * first-out (FIFO) order.
     *
     * Scope: `/control`, `/product` and `/printGcode`. Reads (`/detail`, `/gcodeList`,
     * `/gcodeThumb`, camera bytes) and `/uploadGcode` stay OUTSIDE the lock. An upload can run
     * for minutes. A pause or stop command must never queue behind one.
     */
    private val commandMutex = Mutex()

    // ---- Reads (credentialed) ----

    /** `POST /detail`. Non-zero API code is treated as rejected credentials. */
    suspend fun getDetail(serialNumber: String, checkCode: String): Result<FFPrinterDetail> = post(Endpoints.DETAIL,
        json.encodeToString(CredentialsRequest(serialNumber, checkCode))) { body ->
        val w = json.decodeFromString<DetailResponse>(body)
        if (w.code != 0) throw AuthException()
        w.detail ?: throw ProtocolException("No detail in response")
    }

    /** `POST /product`. Doubles as credential validation — non-zero code is rejected credentials. */
    suspend fun getProduct(serialNumber: String, checkCode: String): Result<Product> = postCommand(Endpoints.PRODUCT,
        json.encodeToString(CredentialsRequest(serialNumber, checkCode))) { body ->
        val w = json.decodeFromString<ProductResponse>(body)
        if (w.code != 0) throw AuthException()
        w.product ?: throw ProtocolException("No product in response")
    }

    /** `POST /gcodeList` — recent files. Prefers the rich AD5X detail; normalizes the legacy form. */
    suspend fun getRecentFileList(serialNumber: String, checkCode: String): Result<List<FFGcodeFileEntry>> =
        post(Endpoints.GCODE_LIST, json.encodeToString(CredentialsRequest(serialNumber, checkCode))) { body ->
            val w = json.decodeFromString<GcodeListResponse>(body)
            if (w.code != 0) throw ApiErrorException(w.code, w.message ?: "gcodeList error")
            w.gcodeListDetail?.takeIf { it.isNotEmpty() }?.let { return@post it }
            w.gcodeList.orEmpty().mapNotNull { el ->
                val asString = (el as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                if (asString != null) FFGcodeFileEntry(gcodeFileName = asString)
                else runCatching { json.decodeFromJsonElement(FFGcodeFileEntry.serializer(), el) }.getOrNull()
            }
        }

    /** `POST /gcodeThumb` — decoded PNG bytes, or null when the file has no thumbnail. */
    suspend fun getGcodeThumbnail(serialNumber: String, checkCode: String, fileName: String): Result<ByteArray?> =
        post(Endpoints.GCODE_THUMB, json.encodeToString(GcodeThumbRequest(serialNumber, checkCode, fileName))) { body ->
            val w = json.decodeFromString<GcodeThumbResponse>(body)
            if (w.code != 0) throw ApiErrorException(w.code, w.message ?: "gcodeThumb error")
            w.imageData?.takeIf { it.isNotBlank() }?.let { Base64.getDecoder().decode(it) }
        }

    /** GETs raw bytes from an absolute [url] (e.g. the unauthenticated `printFileThumbUrl`). */
    suspend fun getBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                r.body?.bytes()?.takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }

    // ---- Control commands ----

    suspend fun controlLight(serialNumber: String, checkCode: String, on: Boolean): Result<Unit> =
        control(serialNumber, checkCode, Commands.LIGHT_CONTROL, LightControlArgs(if (on) "open" else "close"))

    suspend fun controlFiltration(serialNumber: String, checkCode: String, internal: String, external: String): Result<Unit> =
        control(serialNumber, checkCode, Commands.CIRCULATION_CONTROL, CirculateCtlArgs(internal, external))

    suspend fun configureSlot(serialNumber: String, checkCode: String, slot: Int, materialName: String, rgb: String): Result<Unit> =
        control(serialNumber, checkCode, Commands.MATERIAL_STATION_CONFIG, MsConfigArgs(slot, materialName, rgb))

    suspend fun slotAction(serialNumber: String, checkCode: String, slot: Int, action: Int): Result<Unit> =
        control(serialNumber, checkCode, Commands.MATERIAL_STATION, MsCtlArgs(slot, action))

    suspend fun renamePrinter(serialNumber: String, checkCode: String, name: String): Result<Unit> =
        control(serialNumber, checkCode, Commands.RENAME, ReNameArgs(name))

    suspend fun setAutoShutdown(serialNumber: String, checkCode: String, enabled: Boolean, minutes: Int): Result<Unit> =
        control(serialNumber, checkCode, Commands.DELAY_CLOSE, DelayCloseArgs(if (enabled) "open" else "close", minutes))

    suspend fun clearPlatform(serialNumber: String, checkCode: String): Result<Unit> =
        control(serialNumber, checkCode, Commands.STATE_CONTROL, StateCtrlArgs("setClearPlatform"))

    /**
     * `temperatureCtl_cmd` — HTTP-only temperature transport for the Creator 5 series. Body is
     * [TempCtlArgs] (scalar heaters + optional `nozzles[]` array). See
     * [TempControl][me.ghost.ffapi.api.controls.TempControl] for the off/no-change sentinels.
     */
    suspend fun sendTempControl(serialNumber: String, checkCode: String, args: TempCtlArgs): Result<Unit> =
        control(serialNumber, checkCode, Commands.TEMP_CONTROL, args)

    suspend fun pauseJob(serialNumber: String, checkCode: String): Result<Unit> =
        control(serialNumber, checkCode, Commands.JOB_CONTROL, JobCtlArgs(action = "pause"))

    suspend fun resumeJob(serialNumber: String, checkCode: String): Result<Unit> =
        control(serialNumber, checkCode, Commands.JOB_CONTROL, JobCtlArgs(action = "continue"))

    suspend fun cancelJob(serialNumber: String, checkCode: String): Result<Unit> =
        control(serialNumber, checkCode, Commands.JOB_CONTROL, JobCtlArgs(action = "cancel"))

    // ---- Printing ----

    suspend fun printGcode(req: PrintGcodeRequest): Result<Unit> =
        postCommand(Endpoints.GCODE_PRINT, json.encodeToString(req)) { checkOk(it) }

    /** Creator 5 `/printGcode` body (distinct field set — see [Creator5PrintGcodeRequest]). */
    suspend fun printGcodeCreator5(req: Creator5PrintGcodeRequest): Result<Unit> =
        postCommand(Endpoints.GCODE_PRINT, json.encodeToString(req)) { checkOk(it) }

    suspend fun printGcodeLegacy(serialNumber: String, checkCode: String, fileName: String, leveling: Boolean): Result<Unit> =
        postCommand(Endpoints.GCODE_PRINT, json.encodeToString(PrintGcodeRequestLegacy(serialNumber, checkCode, fileName, leveling))) { checkOk(it) }

    // ---- Upload (multipart `/uploadGcode`) ----

    /**
     * AD5X file upload (`POST /uploadGcode`). Sends the file as a `gcodeFile` multipart part plus
     * the AD5X material-station headers ([useMatlStation], [gcodeToolCnt], [firstLayerInspection],
     * `flowCalibration`, `timeLapseVideo`) and a base64-encoded [materialMappings] header.
     */
    suspend fun uploadFile(
        serialNumber: String,
        checkCode: String,
        fileName: String,
        fileBytes: ByteArray,
        startPrint: Boolean,
        levelBeforePrint: Boolean,
        flowCalibration: Boolean = false,
        firstLayerInspection: Boolean = false,
        timeLapseVideo: Boolean = false,
        useMatlStation: Boolean = true,
        gcodeToolCnt: Int = 0,
        materialMappings: List<AD5XMaterialMapping> = emptyList(),
    ): Result<Unit> = uploadMultipart(
        serialNumber, checkCode, fileName, fileBytes,
        buildMap {
            put("printNow", startPrint.toString())
            put("levelingBeforePrint", levelBeforePrint.toString())
            put("flowCalibration", flowCalibration.toString())
            put("firstLayerInspection", firstLayerInspection.toString())
            put("timeLapseVideo", timeLapseVideo.toString())
            put("useMatlStation", useMatlStation.toString())
            put("gcodeToolCnt", gcodeToolCnt.toString())
            put("materialMappings", encodeMaterialMappingsBase64(materialMappings))
        },
    )

    /**
     * Creator 5 / Creator 5 Pro file upload (`POST /uploadGcode`). Same multipart shape as the
     * AD5X but with NO `firstLayerInspection` header (the field doesn't exist on the C5) and NO
     * `materialMappings` header (the C5 maps materials at print-start via [printGcodeCreator5],
     * not at upload). Booleans are serialized as the string "true"/"false".
     */
    suspend fun uploadFileCreator5(
        serialNumber: String,
        checkCode: String,
        fileName: String,
        fileBytes: ByteArray,
        startPrint: Boolean,
        levelBeforePrint: Boolean,
        flowCalibration: Boolean = false,
        timeLapseVideo: Boolean = false,
        useMatlStation: Boolean = false,
        gcodeToolCnt: Int = 1,
    ): Result<Unit> = uploadMultipart(
        serialNumber, checkCode, fileName, fileBytes,
        buildMap {
            put("printNow", startPrint.toString())
            put("levelingBeforePrint", levelBeforePrint.toString())
            put("flowCalibration", flowCalibration.toString())
            put("timeLapseVideo", timeLapseVideo.toString())
            put("useMatlStation", useMatlStation.toString())
            put("gcodeToolCnt", gcodeToolCnt.toString())
        },
    )

    /** Base64-encodes a material-mappings array (JSON) for the AD5X `materialMappings` header. */
    fun encodeMaterialMappingsBase64(materialMappings: List<AD5XMaterialMapping>): String {
        val jsonString = json.encodeToString(
            ListSerializer(AD5XMaterialMapping.serializer()),
            materialMappings,
        )
        return Base64.getEncoder().encodeToString(jsonString.toByteArray(Charsets.UTF_8))
    }

    /**
     * Shared multipart upload: posts [fileBytes] as `gcodeFile` plus the credentials, `fileSize`,
     * `Expect` and the caller-supplied [metadataHeaders] to `/uploadGcode`.
     */
    private suspend fun uploadMultipart(
        serialNumber: String,
        checkCode: String,
        fileName: String,
        fileBytes: ByteArray,
        metadataHeaders: Map<String, String>,
    ): Result<Unit> = withContext(Dispatchers.IO) { uploadMultipartBlocking(serialNumber, checkCode, fileName, fileBytes, metadataHeaders) }

    private fun uploadMultipartBlocking(
        serialNumber: String,
        checkCode: String,
        fileName: String,
        fileBytes: ByteArray,
        metadataHeaders: Map<String, String>,
    ): Result<Unit> = try {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("gcodeFile", fileName, fileBytes.toRequestBody(octetStream))
            .build()
        val builder = Request.Builder().url("$baseUrl${Endpoints.UPLOAD_FILE}")
            .header("serialNumber", serialNumber)
            .header("checkCode", checkCode)
            .header("fileSize", fileBytes.size.toString())
        metadataHeaders.forEach { (k, v) -> builder.header(k, v) }
        builder.header("Expect", "100-continue")
        val request = builder.post(body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Result.failure(ApiErrorException(response.code, "HTTP ${response.code}"))
            } else {
                val responseBody = response.body?.string()
                    ?: return@use Result.failure(ProtocolException("Empty response body"))
                Result.success(checkOk(responseBody))
            }
        }
    } catch (e: IOException) {
        Result.failure(PrinterUnreachableException(cause = e))
    } catch (e: Exception) {
        Result.failure(e)
    }

    // ---- Internals ----

    private suspend inline fun <reified T> control(serialNumber: String, checkCode: String, cmd: String, args: T): Result<Unit> =
        postCommand(
            Endpoints.CONTROL,
            json.encodeToString(ControlRequest(serialNumber, checkCode, ControlPayload(cmd, json.encodeToJsonElement(args)))),
        ) { checkOk(it) }

    private fun checkOk(body: String) {
        val w = json.decodeFromString<GenericResponse>(body)
        if (w.code != 0) throw ApiErrorException(w.code, w.message ?: "API error")
    }

    /** Runs a POST on [Dispatchers.IO], mapping transport/parse failures to typed exceptions. */
    private suspend fun <T> post(path: String, bodyStr: String, parse: (String) -> T): Result<T> =
        withContext(Dispatchers.IO) { postBlocking(path, bodyStr, parse) }

    /** Runs a command POST under [commandMutex]: one command in flight, in FIFO order. */
    private suspend fun <T> postCommand(path: String, bodyStr: String, parse: (String) -> T): Result<T> =
        commandMutex.withLock { post(path, bodyStr, parse) }

    private fun <T> postBlocking(path: String, bodyStr: String, parse: (String) -> T): Result<T> = try {
        val request = Request.Builder().url("$baseUrl$path").post(bodyStr.toRequestBody(mediaType)).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Result.failure(ApiErrorException(response.code, "HTTP ${response.code}"))
            } else {
                val body = response.body?.string()
                    ?: return@use Result.failure(ProtocolException("Empty response body"))
                Result.success(parse(body))
            }
        }
    } catch (e: IOException) {
        Result.failure(PrinterUnreachableException(cause = e))
    } catch (e: Exception) {
        Result.failure(e)
    }
}
