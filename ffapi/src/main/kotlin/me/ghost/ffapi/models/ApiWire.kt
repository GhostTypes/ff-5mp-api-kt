package me.ghost.ffapi.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Auth body shared by `/detail`, `/product`, `/gcodeList`. */
@Serializable
data class CredentialsRequest(val serialNumber: String, val checkCode: String)

/** Minimal `{code, message}` envelope shared by most responses. `code == 0` means success. */
@Serializable
data class GenericResponse(val code: Int = 0, val message: String? = null)

/** `/detail` wrapper. */
@Serializable
data class DetailResponse(
    val code: Int = 0,
    val message: String? = null,
    val detail: FFPrinterDetail? = null,
)

/** `/product` wrapper. */
@Serializable
data class ProductResponse(
    val code: Int = 0,
    val message: String? = null,
    val product: Product? = null,
)

/**
 * Capability flags from `POST /product`. `0` means unavailable/off; non-zero means available. Used
 * to gate controls per the printer's actual hardware.
 */
@Serializable
data class Product(
    val chamberTempCtrlState: Int = 0,
    val externalFanCtrlState: Int = 0,
    val internalFanCtrlState: Int = 0,
    val lightCtrlState: Int = 0,
    val nozzleTempCtrlState: Int = 0,
    val platformTempCtrlState: Int = 0,
)

/** `/control` request: credentials + a `{cmd, args}` payload. */
@Serializable
data class ControlRequest(
    val serialNumber: String,
    val checkCode: String,
    val payload: ControlPayload,
)

@Serializable
data class ControlPayload(val cmd: String, val args: JsonElement)

// ---- Control command argument shapes ----

@Serializable data class LightControlArgs(val status: String)
@Serializable data class StateCtrlArgs(val action: String)
@Serializable data class CirculateCtlArgs(val internal: String, val external: String)
@Serializable data class JobCtlArgs(val jobID: String = "", val action: String)
@Serializable data class MsConfigArgs(val slot: Int, val mt: String, val rgb: String)
@Serializable data class MsCtlArgs(val slot: Int, val action: Int)
@Serializable data class ReNameArgs(val name: String)
@Serializable data class DelayCloseArgs(val automaticShutdown: String, val shutdownAfterTime: Int)

// ---- Files ----

/** Per-tool material info in a multi-color G-code file (AD5X `gcodeListDetail`). */
@Serializable
data class FFGcodeToolData(
    val toolId: Int = 0,
    val slotId: Int = 0,
    val materialName: String = "",
    val materialColor: String = "",
    val filamentWeight: Double? = null,
)

/** One entry in `/gcodeList`. AD5X populates [gcodeToolDatas]; older printers give only the name. */
@Serializable
data class FFGcodeFileEntry(
    val gcodeFileName: String = "",
    val printingTime: Double? = null,
    val gcodeToolCnt: Int? = null,
    val gcodeToolDatas: List<FFGcodeToolData>? = null,
    val totalFilamentWeight: Double? = null,
    val useMatlStation: Boolean? = null,
) {
    val isMultiColor: Boolean get() = (gcodeToolDatas?.size ?: 0) > 1
}

/** `/gcodeList` wrapper. [gcodeListDetail] is the rich AD5X form; [gcodeList] is legacy/mixed. */
@Serializable
data class GcodeListResponse(
    val code: Int = 0,
    val message: String? = null,
    val gcodeList: List<JsonElement>? = null,
    val gcodeListDetail: List<FFGcodeFileEntry>? = null,
)

/** `/gcodeThumb` request + response. [imageData] is base64 PNG. */
@Serializable
data class GcodeThumbRequest(val serialNumber: String, val checkCode: String, val fileName: String)

@Serializable
data class GcodeThumbResponse(val code: Int = 0, val message: String? = null, val imageData: String? = null)

// ---- Printing ----

/**
 * A tool→slot assignment for an AD5X multi-color print. Sent as a raw JSON array in the
 * `/printGcode` body. [toolId] 0-based (0-3); [slotId] 1-based (1-4); colors are `#RRGGBB`.
 */
@Serializable
data class AD5XMaterialMapping(
    val toolId: Int,
    val slotId: Int,
    val materialName: String,
    val toolMaterialColor: String,
    val slotMaterialColor: String,
)

/** `/printGcode` body (firmware >= 3.1.3 / AD5X superset). */
@Serializable
data class PrintGcodeRequest(
    val serialNumber: String,
    val checkCode: String,
    val fileName: String,
    val levelingBeforePrint: Boolean = false,
    val flowCalibration: Boolean = false,
    val firstLayerInspection: Boolean = false,
    val timeLapseVideo: Boolean = false,
    val useMatlStation: Boolean = false,
    val gcodeToolCnt: Int = 0,
    val materialMappings: List<AD5XMaterialMapping> = emptyList(),
)

/** Minimal `/printGcode` body for pre-3.1.3 firmware. */
@Serializable
data class PrintGcodeRequestLegacy(
    val serialNumber: String,
    val checkCode: String,
    val fileName: String,
    val levelingBeforePrint: Boolean = false,
)
