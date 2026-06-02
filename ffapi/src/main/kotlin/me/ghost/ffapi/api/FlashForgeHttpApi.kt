package me.ghost.ffapi.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import me.ghost.ffapi.models.CirculateCtlArgs
import me.ghost.ffapi.models.ControlPayload
import me.ghost.ffapi.models.ControlRequest
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * HTTP REST transport for modern FlashForge printers (port 8898). Ported from the flashforgeui-app
 * `FlashForgeHttpApi`, with the generic exceptions replaced by the library's typed hierarchy:
 * network failures → [PrinterUnreachableException], rejected credentials → [AuthException] (on the
 * credentialed `/detail` and `/product` reads), other non-zero API codes → [ApiErrorException].
 *
 * A single transport is shared by the control modules (DRY); the TS lib instead inlined axios in
 * each module. Cleartext HTTP only — printers do not use TLS.
 */
class FlashForgeHttpApi(private val ipAddress: String, port: Int = 8898) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val baseUrl = "http://$ipAddress:$port"
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    // ---- Reads (credentialed) ----

    /** `POST /detail`. Non-zero API code is treated as rejected credentials. */
    suspend fun getDetail(serialNumber: String, checkCode: String): Result<FFPrinterDetail> = post(Endpoints.DETAIL,
        json.encodeToString(CredentialsRequest(serialNumber, checkCode))) { body ->
        val w = json.decodeFromString<DetailResponse>(body)
        if (w.code != 0) throw AuthException()
        w.detail ?: throw ProtocolException("No detail in response")
    }

    /** `POST /product`. Doubles as credential validation — non-zero code is rejected credentials. */
    suspend fun getProduct(serialNumber: String, checkCode: String): Result<Product> = post(Endpoints.PRODUCT,
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

    suspend fun configureSlot(serialNumber: String, checkCode: String, slot: Int, materialName: String, hexRgb: String): Result<Unit> =
        control(serialNumber, checkCode, Commands.MATERIAL_STATION_CONFIG, MsConfigArgs(slot, materialName, hexRgb.removePrefix("#")))

    suspend fun slotAction(serialNumber: String, checkCode: String, slot: Int, action: Int): Result<Unit> =
        control(serialNumber, checkCode, Commands.MATERIAL_STATION, MsCtlArgs(slot, action))

    suspend fun renamePrinter(serialNumber: String, checkCode: String, name: String): Result<Unit> =
        control(serialNumber, checkCode, Commands.RENAME, ReNameArgs(name))

    suspend fun setAutoShutdown(serialNumber: String, checkCode: String, enabled: Boolean, minutes: Int): Result<Unit> =
        control(serialNumber, checkCode, Commands.DELAY_CLOSE, DelayCloseArgs(if (enabled) "open" else "close", minutes))

    suspend fun clearPlatform(serialNumber: String, checkCode: String): Result<Unit> =
        control(serialNumber, checkCode, Commands.STATE_CONTROL, StateCtrlArgs("setClearPlatform"))

    suspend fun pauseJob(serialNumber: String, checkCode: String): Result<Unit> =
        control(serialNumber, checkCode, Commands.JOB_CONTROL, JobCtlArgs(action = "pause"))

    suspend fun resumeJob(serialNumber: String, checkCode: String): Result<Unit> =
        control(serialNumber, checkCode, Commands.JOB_CONTROL, JobCtlArgs(action = "continue"))

    suspend fun cancelJob(serialNumber: String, checkCode: String): Result<Unit> =
        control(serialNumber, checkCode, Commands.JOB_CONTROL, JobCtlArgs(action = "cancel"))

    // ---- Printing ----

    suspend fun printGcode(req: PrintGcodeRequest): Result<Unit> =
        post(Endpoints.GCODE_PRINT, json.encodeToString(req)) { checkOk(it) }

    suspend fun printGcodeLegacy(serialNumber: String, checkCode: String, fileName: String, leveling: Boolean): Result<Unit> =
        post(Endpoints.GCODE_PRINT, json.encodeToString(PrintGcodeRequestLegacy(serialNumber, checkCode, fileName, leveling))) { checkOk(it) }

    // ---- Internals ----

    private suspend inline fun <reified T> control(serialNumber: String, checkCode: String, cmd: String, args: T): Result<Unit> =
        post(
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
