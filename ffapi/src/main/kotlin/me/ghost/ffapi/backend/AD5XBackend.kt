package me.ghost.ffapi.backend

import me.ghost.ffapi.PrinterCapabilities
import me.ghost.ffapi.PrinterConfig
import me.ghost.ffapi.PrinterModel
import me.ghost.ffapi.api.FlashForgeHttpApi
import me.ghost.ffapi.models.AD5XMaterialMapping
import me.ghost.ffapi.models.FFPrinterDetail
import me.ghost.ffapi.models.MatlStationInfo
import me.ghost.ffapi.models.PrintGcodeRequest
import me.ghost.ffapi.tcpapi.FlashForgeClient

/**
 * AD5X: modern HTTP API plus the independent material station (IFS, 4 slots) reported inline on
 * `/detail` via `matlStationInfo`. No factory LEDs (custom-only over TCP) and no air filtration.
 */
class AD5XBackend(
    printer: PrinterConfig,
    http: FlashForgeHttpApi,
    tcp: FlashForgeClient,
) : DualApiBackend(printer, http, tcp) {

    override val model = PrinterModel.AD5X

    override fun baselineCapabilities() = PrinterCapabilities(
        model = model,
        ledControl = printer.customLedEnabled,
        ledViaHttp = false,
        hasMaterialStation = true,
    )

    override fun materialStation(detail: FFPrinterDetail): MatlStationInfo? = detail.matlStationInfo

    override suspend fun slotAction(slot: Int, action: SlotAction): Result<Unit> =
        http.slotAction(printer.serialNumber, printer.checkCode, slot, action.code)

    /**
     * AD5X always sends the full `/printGcode` payload: non-empty [mappings] = a multi-color job
     * (`useMatlStation=true`); empty = a single-color job that bypasses the station.
     */
    override suspend fun startPrint(
        fileName: String,
        leveling: Boolean,
        mappings: List<AD5XMaterialMapping>,
    ): Result<Unit> = http.printGcode(
        PrintGcodeRequest(
            serialNumber = printer.serialNumber,
            checkCode = printer.checkCode,
            fileName = fileName,
            levelingBeforePrint = leveling,
            useMatlStation = mappings.isNotEmpty(),
            gcodeToolCnt = mappings.size,
            materialMappings = mappings,
        ),
    )
}
