package me.ghost.ffapi.backend

import me.ghost.ffapi.PrinterCapabilities
import me.ghost.ffapi.PrinterConfig
import me.ghost.ffapi.PrinterModel
import me.ghost.ffapi.api.FlashForgeHttpApi
import me.ghost.ffapi.error.NotSupportedException
import me.ghost.ffapi.models.AD5XMaterialMapping
import me.ghost.ffapi.models.FFGcodeFileEntry
import me.ghost.ffapi.models.FFPrinterDetail
import me.ghost.ffapi.models.MatlStationInfo
import me.ghost.ffapi.models.PrintGcodeRequest
import me.ghost.ffapi.models.Product
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

    /** Resolved capabilities. Empty until [initialize] runs. */
    var capabilities: PrinterCapabilities = PrinterCapabilities()
        protected set

    protected open fun baselineCapabilities(): PrinterCapabilities = PrinterCapabilities(model = model)

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

    /** True when firmware is >= 3.1.3 (selects the richer `/printGcode` payload). */
    protected fun isNewFirmware(): Boolean {
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

    // ---- Temperature / motion (TCP G-code) ----

    open suspend fun setNozzleTemp(celsius: Int): Result<Unit> = tcp.setExtruderTemp(celsius)
    open suspend fun setBedTemp(celsius: Int): Result<Unit> = tcp.setBedTemp(celsius)
    open suspend fun home(): Result<Unit> = tcp.homeAxes()

    // ---- Filtration (HTTP; 5M Pro only) ----

    open suspend fun setFiltration(mode: FiltrationMode): Result<Unit> {
        if (!capabilities.filtrationControl) return Result.failure(NotSupportedException("Filtration control"))
        val (internal, external) = when (mode) {
            FiltrationMode.EXTERNAL -> "close" to "open"
            FiltrationMode.INTERNAL -> "open" to "close"
            FiltrationMode.OFF -> "close" to "close"
        }
        return http.controlFiltration(printer.serialNumber, printer.checkCode, internal, external)
    }

    // ---- Material station (HTTP; AD5X only) ----

    open suspend fun setSlotMaterial(slot: Int, materialName: String, hexRgb: String): Result<Unit> =
        Result.failure(NotSupportedException("Material station"))

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
