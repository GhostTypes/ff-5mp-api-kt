package me.ghost.ffapi.backend

import me.ghost.ffapi.PrinterConfig
import me.ghost.ffapi.PrinterModel
import me.ghost.ffapi.api.FlashForgeHttpApi
import me.ghost.ffapi.tcpapi.FlashForgeClient

/** Builds the [PrinterBackend] strategy for a detected [PrinterModel]. */
object PrinterBackendFactory {
    fun create(
        model: PrinterModel,
        printer: PrinterConfig,
        http: FlashForgeHttpApi,
        tcp: FlashForgeClient,
    ): PrinterBackend = when (model) {
        PrinterModel.ADVENTURER_5M -> Adventurer5MBackend(printer, http, tcp)
        PrinterModel.ADVENTURER_5M_PRO -> Adventurer5MProBackend(printer, http, tcp)
        PrinterModel.AD5X -> AD5XBackend(printer, http, tcp)
        // Creator 5 series: HTTP-only modern printer (no TCP control channel). Dedicated backend
        // drives the chamber + material-station + HTTP temperature transport; TCP-only ops fail
        // fast. httpOnly=true ensures the TCP client is never connect()ed on this path.
        PrinterModel.CREATOR_5 -> Creator5Backend(printer, http, tcp, PrinterModel.CREATOR_5)
        PrinterModel.CREATOR_5_PRO -> Creator5Backend(printer, http, tcp, PrinterModel.CREATOR_5_PRO)
        PrinterModel.ADVENTURER_3,
        PrinterModel.ADVENTURER_4,
        PrinterModel.GENERIC_LEGACY -> GenericLegacyBackend(printer, http, tcp, model)
        // Default unknown machines to the modern 5M backend; the next /detail refines it.
        PrinterModel.UNKNOWN -> Adventurer5MBackend(printer, http, tcp)
    }
}
