package me.ghost.ffapi.backend

import me.ghost.ffapi.PrinterCapabilities
import me.ghost.ffapi.PrinterConfig
import me.ghost.ffapi.PrinterModel
import me.ghost.ffapi.api.FlashForgeHttpApi
import me.ghost.ffapi.error.NotSupportedException
import me.ghost.ffapi.models.AD5XMaterialMapping
import me.ghost.ffapi.models.Creator5PrintGcodeRequest
import me.ghost.ffapi.models.FFPrinterDetail
import me.ghost.ffapi.models.MatlStationInfo
import me.ghost.ffapi.tcpapi.FlashForgeClient

/**
 * Creator 5 / Creator 5 Pro: HTTP-only modern printer (no usable TCP control channel). Status polls
 * HTTP `/detail`; ALL control goes over HTTP — there is no legacy TCP command path on these models,
 * so the [httpOnly] flag is `true` and TCP-only operations fail fast instead of hanging on a dead
 * socket. The TCP client is accepted by the constructor for API symmetry but must never be
 * `connect()`ed on this path (consumers should gate on [httpOnly]).
 *
 * Hardware: a 4-tool material station (the 4 tool heads surface as slots via `msConfig_cmd`), a
 * heated chamber, and (Pro only) confirmed air-filtration hardware (forced on regardless of
 * `/product` flags). Filament load/unload (`ms_cmd`) is NOT available — only the AD5X has that.
 *
 * @param creatorModel the specific Creator 5 series model ([PrinterModel.CREATOR_5] or
 *   [PrinterModel.CREATOR_5_PRO]).
 */
class Creator5Backend(
    printer: PrinterConfig,
    http: FlashForgeHttpApi,
    tcp: FlashForgeClient,
    private val creatorModel: PrinterModel,
) : DualApiBackend(printer, http, tcp) {

    override val model: PrinterModel = creatorModel

    override fun baselineCapabilities(): PrinterCapabilities = PrinterCapabilities(
        model = model,
        hasMaterialStation = true,
        chamberTempControl = true,
        // The Pro has confirmed filtration hardware; force it on regardless of /product flags.
        filtrationControl = model == PrinterModel.CREATOR_5_PRO,
    )

    // The Creator 5 surfaces its 4 tool heads as the material-station slots (same /detail inline
    // shape as the AD5X). msConfig_cmd (metadata) is available; ms_cmd (filament motion) is not.
    override fun materialStation(detail: FFPrinterDetail): MatlStationInfo? = detail.matlStationInfo

    // ---- Print start (Creator 5-native /printGcode body) ----

    /**
     * Starts a local print on the Creator 5 via `POST /printGcode` with the C5-native body.
     *
     * The Creator 5 splits its material-station workflow across two requests (unlike the AD5X,
     * which maps materials at upload): the file is uploaded first, then THIS command carries the
     * per-tool [materialMappings] at print-start. The body omits `useMatlStation` / `gcodeToolCnt`
     * (those live on the upload) and `firstLayerInspection` (doesn't exist on the C5);
     * `flowCalibration` / `timeLapseVideo` are always present. Omit [materialMappings] (or pass an
     * empty list) for a single-tool print.
     */
    suspend fun startCreator5Job(
        fileName: String,
        levelingBeforePrint: Boolean,
        flowCalibration: Boolean = false,
        timeLapseVideo: Boolean = false,
        materialMappings: List<AD5XMaterialMapping>? = null,
    ): Result<Unit> {
        if (fileName.isBlank()) {
            return Result.failure(IllegalArgumentException("Creator 5 job error: fileName cannot be empty"))
        }
        val mappings = materialMappings?.takeIf { it.isNotEmpty() }
        if (mappings != null && !validateCreator5MaterialMappings(mappings)) {
            return Result.failure(IllegalArgumentException("Invalid Creator 5 material mappings"))
        }
        return http.printGcodeCreator5(
            Creator5PrintGcodeRequest(
                serialNumber = printer.serialNumber,
                checkCode = printer.checkCode,
                fileName = fileName,
                levelingBeforePrint = levelingBeforePrint,
                flowCalibration = flowCalibration,
                timeLapseVideo = timeLapseVideo,
                materialMappings = mappings,
            ),
        )
    }

    /** On the Creator 5, [startPrint] routes to the C5-native [startCreator5Job]. */
    override suspend fun startPrint(
        fileName: String,
        leveling: Boolean,
        mappings: List<AD5XMaterialMapping>,
    ): Result<Unit> = startCreator5Job(fileName, leveling, materialMappings = mappings.takeIf { it.isNotEmpty() })

    /**
     * Validates Creator 5 material mappings: toolId 0-3, slotId 1-4, non-empty materialName, and
     * `#RRGGBB` tool/slot colors. The C5 mapping shape is identical to the AD5X (confirmed via a
     * live `/printGcode` capture). At most 4 mappings.
     */
    internal fun validateCreator5MaterialMappings(mappings: List<AD5XMaterialMapping>): Boolean {
        if (mappings.size > 4) return false
        val hexColor = Regex("^#[0-9A-Fa-f]{6}$")
        return mappings.all { m ->
            m.toolId in 0..3 &&
                m.slotId in 1..4 &&
                m.materialName.isNotBlank() &&
                hexColor.matches(m.toolMaterialColor) &&
                hexColor.matches(m.slotMaterialColor)
        }
    }

    // ---- TCP-only operations are unavailable on this HTTP-only model ----
    // The Creator 5 has no legacy TCP control channel. These would hang on a dead socket, so they
    // fail fast with NotSupportedException. (The Creator 5 may expose some via its Klipper HTTP
    // g-code path, but those schemas are unconfirmed; route them here once they are.)

    /** Homing requires the TCP G-code channel — unavailable on the HTTP-only Creator 5. */
    override suspend fun home(): Result<Unit> =
        Result.failure(NotSupportedException("Homing (TCP G-code) is unavailable on the HTTP-only Creator 5"))

    /** Local file listing (M661) requires TCP — unavailable on the HTTP-only Creator 5. */
    override suspend fun listLocalFiles(): Result<List<String>> =
        Result.failure(NotSupportedException("Local file list (TCP) is unavailable on the HTTP-only Creator 5"))
}
