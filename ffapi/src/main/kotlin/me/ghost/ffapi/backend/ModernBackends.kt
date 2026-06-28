package me.ghost.ffapi.backend

import me.ghost.ffapi.PrinterCapabilities
import me.ghost.ffapi.PrinterConfig
import me.ghost.ffapi.PrinterModel
import me.ghost.ffapi.api.FlashForgeHttpApi
import me.ghost.ffapi.models.FFPrinterDetail
import me.ghost.ffapi.models.Product
import me.ghost.ffapi.tcpapi.FlashForgeClient

/**
 * Shared base for the modern 5M / 5M Pro / AD5X series (HTTP REST API). Status comes from
 * `POST /detail`; TCP is control-only. Mirrors `DualAPIBackend` from FlashForgeUI-Electron.
 */
abstract class DualApiBackend(
    printer: PrinterConfig,
    http: FlashForgeHttpApi,
    tcp: FlashForgeClient,
) : PrinterBackend(printer, http, tcp) {

    override suspend fun pollStatus(): Result<FFPrinterDetail> =
        http.getDetail(printer.serialNumber, printer.checkCode)

    override fun applyProduct(base: PrinterCapabilities, product: Product): PrinterCapabilities {
        val httpLed = product.lightCtrlState != 0
        return base.copy(
            ledControl = httpLed || base.ledControl,
            ledViaHttp = httpLed,
            // The /product fan flags are unreliable on the plain 5M (reports both non-zero despite
            // no filtration), so gate on the model as well. Creator 5 Pro filtration is forced on
            // in baselineCapabilities(); preserve it here via `base.filtrationControl`.
            filtrationControl = base.filtrationControl || (model == PrinterModel.ADVENTURER_5M_PRO &&
                product.internalFanCtrlState != 0 && product.externalFanCtrlState != 0),
        )
    }
}

/**
 * Adventurer 5M: no factory LED, no filtration, no material station. LEDs only if the user wired
 * their own ("Custom LEDs"), driven over TCP `~M146`.
 */
class Adventurer5MBackend(
    printer: PrinterConfig,
    http: FlashForgeHttpApi,
    tcp: FlashForgeClient,
) : DualApiBackend(printer, http, tcp) {
    override val model = PrinterModel.ADVENTURER_5M
    override fun baselineCapabilities() = PrinterCapabilities(
        model = model, ledControl = printer.customLedEnabled, ledViaHttp = false,
    )
}

/**
 * Adventurer 5M Pro: factory LEDs, air filtration, enclosed chamber. LED + filtration availability
 * are resolved from the `/product` flags by [DualApiBackend].
 */
class Adventurer5MProBackend(
    printer: PrinterConfig,
    http: FlashForgeHttpApi,
    tcp: FlashForgeClient,
) : DualApiBackend(printer, http, tcp) {
    override val model = PrinterModel.ADVENTURER_5M_PRO
}
