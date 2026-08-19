package me.ghost.ffapi.backend

import me.ghost.ffapi.PrinterCapabilities
import me.ghost.ffapi.PrinterConfig
import me.ghost.ffapi.PrinterModel
import me.ghost.ffapi.api.FlashForgeHttpApi
import me.ghost.ffapi.api.controls.creator5.Creator5Palette
import me.ghost.ffapi.api.controls.TempControl
import me.ghost.ffapi.error.NotSupportedException
import me.ghost.ffapi.models.AD5XMaterialMapping
import me.ghost.ffapi.models.FFGcodeFileEntry
import me.ghost.ffapi.models.FFPrinterDetail
import me.ghost.ffapi.models.MatlStationInfo
import me.ghost.ffapi.models.PrintGcodeRequest
import me.ghost.ffapi.models.Product
import me.ghost.ffapi.models.TempCtlArgs
import me.ghost.ffapi.tcpapi.FlashForgeClient

/** Air-filtration mode for the 5M Pro circulation fans. */
enum class FiltrationMode { EXTERNAL, INTERNAL, OFF }

/** AD5X IFS slot operation, carrying the on-wire `ms_cmd` action code. */
enum class SlotAction(val code: Int) { LOAD(0), UNLOAD(1), CANCEL(2) }

/**
 * Per-model strategy for talking to one connected printer. Ported from the app's
 * `PrinterBackend` hierarchy (itself mirroring FlashForgeUI-Electron's backends).
 *
 * Modern (5M / 5M Pro / AD5X): status polls HTTP `/detail`; TCP is control-only (custom LEDs,
 * homing, temps). Only [GenericLegacyBackend] polls over TCP. Capability-gated operations fail with
 * [NotSupportedException] on models that lack them.
 */
abstract class PrinterBackend(
    protected val printer: PrinterConfig,
    val http: FlashForgeHttpApi,
    val tcp: FlashForgeClient,
) {
    abstract val model: PrinterModel

    /** `true` when status must be polled over TCP (legacy machines); modern backends use HTTP. */
    open val pollsOverTcp: Boolean = false

    /**
     * `true` for HTTP-only models (Creator 5 series) that expose no usable TCP command channel for
     * control. [setNozzleTemp], [setBedTemp], [cancelNozzleTemp] and [cancelBedTemp] check this
     * flag and route through the HTTP `temperatureCtl_cmd` instead of TCP; [Creator5Backend]
     * overrides the TCP-only [home] / [listLocalFiles] to fail fast.
     */
    open val httpOnly: Boolean
        get() = model == PrinterModel.CREATOR_5 || model == PrinterModel.CREATOR_5_PRO

    /** Resolved capabilities. Empty until [initialize] runs. */
    var capabilities: PrinterCapabilities = PrinterCapabilities()
        protected set

    protected open fun baselineCapabilities(): PrinterCapabilities = when (model) {
        PrinterModel.CREATOR_5, PrinterModel.CREATOR_5_PRO -> PrinterCapabilities(
            model = model,
            hasMaterialStation = true,
            chamberTempControl = true,
            // Filtration forced on for the Pro only — see Creator5Backend.baselineCapabilities.
            filtrationControl = model == PrinterModel.CREATOR_5_PRO,
        )
        else -> PrinterCapabilities(model = model)
    }

    protected open fun applyProduct(base: PrinterCapabilities, product: Product): PrinterCapabilities = base

    /**
     * Validates credentials and resolves [capabilities]. For modern printers this fetches `/product`
     * (which doubles as the credential check). Returns failure when credentials are rejected.
     */
    open suspend fun initialize(): Result<PrinterCapabilities> {
        capabilities = baselineCapabilities()
        if (model.isModern) {
            http.getProduct(printer.serialNumber, printer.checkCode)
                .onSuccess { product -> capabilities = applyProduct(capabilities, product) }
                .onFailure { return Result.failure(it) }
        }
        return Result.success(capabilities)
    }

    /** Fetches a fresh status snapshot. */
    abstract suspend fun pollStatus(): Result<FFPrinterDetail>

    /** Material-station view for a snapshot (AD5X only; null elsewhere). */
    open fun materialStation(detail: FFPrinterDetail): MatlStationInfo? = null

    // ---- Files ----

    open suspend fun listRecentFiles(): Result<List<FFGcodeFileEntry>> =
        http.getRecentFileList(printer.serialNumber, printer.checkCode)

    open suspend fun listLocalFiles(): Result<List<String>> = tcp.getFileList()

    open suspend fun getThumbnail(fileName: String): Result<ByteArray?> =
        http.getGcodeThumbnail(printer.serialNumber, printer.checkCode, fileName)

    /**
     * Starts a print of a file already on the printer. Full payload on firmware >= 3.1.3, minimal
     * below. [mappings] are ignored here; [AD5XBackend] overrides to honor them.
     */
    open suspend fun startPrint(
        fileName: String,
        leveling: Boolean,
        mappings: List<AD5XMaterialMapping> = emptyList(),
    ): Result<Unit> = if (isNewFirmware()) {
        http.printGcode(PrintGcodeRequest(printer.serialNumber, printer.checkCode, fileName, leveling))
    } else {
        http.printGcodeLegacy(printer.serialNumber, printer.checkCode, fileName, leveling)
    }

    /**
     * True when firmware is >= 3.1.3 (selects the richer `/printGcode` payload). The AD5X and
     * Creator 5 series short-circuit to `true`: their versioning isn't comparable to the 5M's 3.x
     * line (the C5 reports 1.9.2, the AD5X 1.1.7), which the numeric check below would wrongly read
     * as "old". They always use the new payload/header format.
     */
    protected fun isNewFirmware(): Boolean {
        if (model == PrinterModel.AD5X || model.isCreator5) return true
        val parts = (printer.firmwareVersion ?: return false)
            .split(".")
            .map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val min = listOf(3, 1, 3)
        for (i in 0..2) {
            val cur = parts.getOrElse(i) { 0 }
            if (cur > min[i]) return true
            if (cur < min[i]) return false
        }
        return true
    }

    // ---- Temperature / motion (TCP G-code, or HTTP `temperatureCtl_cmd` when httpOnly) ----

    /**
     * Sets the primary extruder/nozzle temperature. Over TCP on dual-API printers; over the HTTP
     * `temperatureCtl_cmd` on httpOnly models (Creator 5 series). On the Creator 5 the firmware's
     * temp handler only reads the `nozzles[]` array, so the httpOnly+Creator5 path targets T0 via
     * the array rather than the `rightNozzle` scalar the single-tool AD5X / 5M path uses.
     */
    open suspend fun setNozzleTemp(celsius: Int): Result<Unit> =
        if (httpOnly) {
            if (model.isCreator5) {
                val nozzles = TempControl.buildNozzleArray(0, celsius)
                    ?: return Result.failure(IllegalArgumentException("toolIndex out of range"))
                sendHttpTempCommand(nozzles = nozzles)
            } else {
                sendHttpTempCommand(rightNozzle = celsius)
            }
        } else {
            tcp.setExtruderTemp(celsius)
        }

    /**
     * Sets the print bed (platform) temperature. Over TCP on dual-API printers; over HTTP
     * `temperatureCtl_cmd` (`platform`) when httpOnly.
     */
    open suspend fun setBedTemp(celsius: Int): Result<Unit> =
        if (httpOnly) sendHttpTempCommand(platform = celsius) else tcp.setBedTemp(celsius)

    /** Cancels extruder/nozzle heating (off). Over TCP on dual-API; HTTP when httpOnly. */
    open suspend fun cancelNozzleTemp(): Result<Unit> =
        if (httpOnly) {
            if (model.isCreator5) {
                val nozzles = TempControl.buildNozzleArray(0, TempControl.NOZZLE_OFF)
                    ?: return Result.failure(IllegalArgumentException("toolIndex out of range"))
                sendHttpTempCommand(nozzles = nozzles)
            } else {
                sendHttpTempCommand(rightNozzle = TempControl.TEMP_OFF)
            }
        } else {
            tcp.cancelExtruderTemp()
        }

    /** Cancels print bed heating (off). Over TCP on dual-API; HTTP when httpOnly. */
    open suspend fun cancelBedTemp(): Result<Unit> =
        if (httpOnly) sendHttpTempCommand(platform = TempControl.TEMP_OFF) else tcp.cancelBedTemp()

    /**
     * Sets the target temperature for a single tool/nozzle on a Creator 5 series tool-changer,
     * leaving the other tools unchanged. Sent as a `nozzles[]` array via the HTTP-only transport.
     * @param toolIndex Zero-based tool index (0-3 for T0-T3).
     * @param temp Target temperature in Celsius.
     */
    open suspend fun setToolTemp(toolIndex: Int, temp: Int): Result<Unit> {
        val nozzles = TempControl.buildNozzleArray(toolIndex, temp)
            ?: return Result.failure(IllegalArgumentException("toolIndex $toolIndex out of range (0-${TempControl.NOZZLE_COUNT - 1})"))
        return sendHttpTempCommand(nozzles = nozzles)
    }

    /**
     * Sets the target temperatures for all tools/nozzles in one command. Use
     * [TempControl.TEMP_NO_CHANGE] to leave a tool unchanged, [TempControl.NOZZLE_OFF] (0) to turn
     * one off. Must contain exactly [TempControl.NOZZLE_COUNT] entries.
     */
    open suspend fun setToolTemps(temps: List<Int>): Result<Unit> {
        if (temps.size != TempControl.NOZZLE_COUNT) {
            return Result.failure(
                IllegalArgumentException("Expected ${TempControl.NOZZLE_COUNT} temps, got ${temps.size}")
            )
        }
        return sendHttpTempCommand(nozzles = temps.toList())
    }

    /** Cancels heating for a single tool/nozzle (sets its target to 0), leaving the others unchanged. */
    open suspend fun cancelToolTemp(toolIndex: Int): Result<Unit> {
        val nozzles = TempControl.buildNozzleArray(toolIndex, TempControl.NOZZLE_OFF)
            ?: return Result.failure(IllegalArgumentException("toolIndex $toolIndex out of range (0-${TempControl.NOZZLE_COUNT - 1})"))
        return sendHttpTempCommand(nozzles = nozzles)
    }

    /**
     * Sets the heated-chamber target temperature (Creator 5 series only — capability-gated). The
     * chamber is driven over the HTTP `temperatureCtl_cmd`; the firmware caps it at 80°C.
     */
    open suspend fun setChamberTemp(celsius: Int): Result<Unit> {
        if (!capabilities.chamberTempControl) {
            return Result.failure(NotSupportedException("Chamber temperature control"))
        }
        return sendHttpTempCommand(chamber = celsius)
    }

    /** Cancels chamber heating (Creator 5 series only — capability-gated). */
    open suspend fun cancelChamberTemp(): Result<Unit> {
        if (!capabilities.chamberTempControl) {
            return Result.failure(NotSupportedException("Chamber temperature control"))
        }
        return sendHttpTempCommand(chamber = TempControl.TEMP_OFF)
    }

    /**
     * Builds the `temperatureCtl_cmd` body. Unspecified heaters default to
     * [TempControl.TEMP_NO_CHANGE]. On the Creator 5 the confirmed payload always carries a
     * 4-entry `nozzles[]` array (even for a bed/chamber-only command), so an unspecified array is
     * filled with [TempControl.TEMP_NO_CHANGE] there; single-tool models omit it entirely.
     */
    protected open suspend fun sendHttpTempCommand(
        rightNozzle: Int = TempControl.TEMP_NO_CHANGE,
        leftNozzle: Int = TempControl.TEMP_NO_CHANGE,
        platform: Int = TempControl.TEMP_NO_CHANGE,
        chamber: Int = TempControl.TEMP_NO_CHANGE,
        nozzles: List<Int>? = null,
    ): Result<Unit> {
        val finalNozzles: List<Int>? = when {
            nozzles != null -> nozzles
            model.isCreator5 -> List(TempControl.NOZZLE_COUNT) { TempControl.TEMP_NO_CHANGE }
            else -> null
        }
        return sendTempControl(TempCtlArgs(rightNozzle, leftNozzle, platform, chamber, finalNozzles))
    }

    /** HTTP transport for a fully-built [TempCtlArgs] body. Overridable test seam. */
    open suspend fun sendTempControl(args: TempCtlArgs): Result<Unit> =
        http.sendTempControl(printer.serialNumber, printer.checkCode, args)

    open suspend fun home(): Result<Unit> = tcp.homeAxes()

    // ---- Filtration (HTTP; 5M Pro only) ----

    open suspend fun setFiltration(mode: FiltrationMode): Result<Unit> {
        if (!capabilities.filtrationControl) return Result.failure(NotSupportedException("Filtration control"))
        val (internal, external) = when (mode) {
            FiltrationMode.EXTERNAL -> "close" to "open"
            FiltrationMode.INTERNAL -> "open" to "close"
            FiltrationMode.OFF -> "close" to "close"
        }
        return sendFiltration(internal, external)
    }

    /** HTTP transport for the circulation-control command. Overridable test seam. */
    protected open suspend fun sendFiltration(internal: String, external: String): Result<Unit> =
        http.controlFiltration(printer.serialNumber, printer.checkCode, internal, external)

    // ---- Material station (HTTP; AD5X + Creator 5 series) ----

    /**
     * Configures material name + color metadata for a material-station slot (no filament motion).
     * Capability-gated to models with a material station (AD5X + Creator 5 series).
     *
     * The `msConfig_cmd` handler is present on both, but they render the slot color icon with
     * mutually exclusive wire formats:
     *  - AD5X: accepts freeform hex; the leading `#` is stripped before sending (`RRGGBB`).
     *  - Creator 5 / 5 Pro: renders an icon ONLY on a byte-for-byte, case-sensitive match against
     *    the firmware's 24-entry palette (WITH the `#`); any other value falls back to White. The
     *    caller's color is snapped to the nearest palette entry in uppercase `#RRGGBB`.
     */
    open suspend fun setSlotMaterial(slot: Int, materialName: String, hexRgb: String): Result<Unit> {
        if (!capabilities.hasMaterialStation) {
            return Result.failure(NotSupportedException("Material station"))
        }
        val rgb = if (model.isCreator5) {
            Creator5Palette.snapToCreator5Palette(hexRgb).hex
        } else {
            hexRgb.removePrefix("#")
        }
        return sendConfigureSlot(slot, materialName, rgb)
    }

    /** HTTP transport for a fully-resolved slot config. Overridable test seam. */
    protected open suspend fun sendConfigureSlot(slot: Int, materialName: String, rgb: String): Result<Unit> =
        http.configureSlot(printer.serialNumber, printer.checkCode, slot, materialName, rgb)

    /**
     * Performs a load/unload/cancel on a material-station slot. **AD5X-only** — the Creator 5
     * firmware has no `ms_cmd` (LAN filament load/unload), only the metadata `msConfig_cmd`.
     */
    open suspend fun slotAction(slot: Int, action: SlotAction): Result<Unit> =
        Result.failure(NotSupportedException("Material station"))

    // ---- Info / settings (HTTP; modern) ----

    open suspend fun rename(name: String): Result<Unit> =
        http.renamePrinter(printer.serialNumber, printer.checkCode, name)

    open suspend fun setAutoShutdown(enabled: Boolean, minutes: Int): Result<Unit> =
        http.setAutoShutdown(printer.serialNumber, printer.checkCode, enabled, minutes)

    // ---- Job control (HTTP; overridable for TCP-only legacy) ----

    open suspend fun pause(): Result<Unit> = http.pauseJob(printer.serialNumber, printer.checkCode)
    open suspend fun resume(): Result<Unit> = http.resumeJob(printer.serialNumber, printer.checkCode)
    open suspend fun cancel(): Result<Unit> = http.cancelJob(printer.serialNumber, printer.checkCode)
    open suspend fun clearPlatform(): Result<Unit> = http.clearPlatform(printer.serialNumber, printer.checkCode)

    /** Turns the LED on/off via HTTP `lightControl_cmd` or TCP `~M146` per [PrinterCapabilities.ledViaHttp]. */
    open suspend fun setLight(on: Boolean): Result<Unit> {
        val caps = capabilities
        if (!caps.ledControl) return Result.failure(NotSupportedException("LED control"))
        return if (caps.ledViaHttp) {
            http.controlLight(printer.serialNumber, printer.checkCode, on)
        } else {
            if (on) tcp.ledOn() else tcp.ledOff()
        }
    }
}
