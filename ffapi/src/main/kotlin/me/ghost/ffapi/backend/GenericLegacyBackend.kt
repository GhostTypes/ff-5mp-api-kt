package me.ghost.ffapi.backend

import me.ghost.ffapi.PrinterCapabilities
import me.ghost.ffapi.PrinterConfig
import me.ghost.ffapi.PrinterModel
import me.ghost.ffapi.api.FlashForgeHttpApi
import me.ghost.ffapi.error.NotSupportedException
import me.ghost.ffapi.models.AD5XMaterialMapping
import me.ghost.ffapi.models.FFGcodeFileEntry
import me.ghost.ffapi.models.FFPrinterDetail
import me.ghost.ffapi.tcpapi.FlashForgeClient

/**
 * Backend for legacy printers (Adventurer 3/4 and other older machines) that lack the HTTP REST
 * API. Status polls over TCP G-code (`~M119`, `~M105`, `~M27`) and is mapped onto [FFPrinterDetail]
 * so consumers treat all backends uniformly. HTTP-only operations (rename, auto-shutdown, clear
 * platform) fail. Ported from the app's `GenericLegacyBackend`.
 */
class GenericLegacyBackend(
    printer: PrinterConfig,
    http: FlashForgeHttpApi,
    tcp: FlashForgeClient,
    override val model: PrinterModel = PrinterModel.GENERIC_LEGACY,
) : PrinterBackend(printer, http, tcp) {

    override val pollsOverTcp = true

    override suspend fun initialize(): Result<PrinterCapabilities> {
        capabilities = PrinterCapabilities(
            model = model, ledControl = true, ledViaHttp = false,
            filtrationControl = false, hasMaterialStation = false,
        )
        return Result.success(capabilities)
    }

    override suspend fun pollStatus(): Result<FFPrinterDetail> {
        if (!tcp.isConnected.value) {
            return Result.failure(IllegalStateException("Legacy TCP transport not connected"))
        }
        val m119 = tcp.sendRawCommand("~M119")
        if (m119.isFailure) return Result.failure(m119.exceptionOrNull()!!)
        val m105 = tcp.sendRawCommand("~M105")

        val status = normalizeStatus(parseMachineStatus(m119.getOrDefault("")))
        val m27 = if (status == "printing" || status == "paused") tcp.sendRawCommand("~M27")
        else Result.success("")

        tcp.resetReconnectBackoff()
        return Result.success(
            buildDetail(m119.getOrDefault(""), m105.getOrDefault(""), m27.getOrDefault("")),
        )
    }

    override suspend fun pause(): Result<Unit> = tcp.sendRawCommand("~M25").map { }
    override suspend fun resume(): Result<Unit> = tcp.sendRawCommand("~M24").map { }
    override suspend fun cancel(): Result<Unit> = tcp.sendRawCommand("~M26").map { }

    override suspend fun startPrint(
        fileName: String,
        leveling: Boolean,
        mappings: List<AD5XMaterialMapping>,
    ): Result<Unit> {
        val path = if (model == PrinterModel.ADVENTURER_3) "/data/$fileName" else "0:/user/$fileName"
        tcp.sendRawCommand("~M23 $path").onFailure { return Result.failure(it) }
        return tcp.sendRawCommand("~M24").map { }
    }

    override suspend fun clearPlatform(): Result<Unit> =
        Result.failure(NotSupportedException("Clear platform (TCP)"))

    /** A3 uses `~M146 1/0`; A4/generic uses RGB `~M146 r255 g255 b255 F0`. */
    override suspend fun setLight(on: Boolean): Result<Unit> {
        if (!capabilities.ledControl) return Result.failure(NotSupportedException("LED control"))
        if (model == PrinterModel.ADVENTURER_3) {
            tcp.sendCommand(if (on) "~M146 1" else "~M146 0")
            return Result.success(Unit)
        }
        return if (on) tcp.ledOn() else tcp.ledOff()
    }

    override suspend fun listRecentFiles(): Result<List<FFGcodeFileEntry>> =
        listLocalFiles().map { names -> names.map { FFGcodeFileEntry(gcodeFileName = it) } }

    override suspend fun getThumbnail(fileName: String): Result<ByteArray?> = tcp.getThumbnail(fileName)

    override suspend fun rename(name: String): Result<Unit> =
        Result.failure(NotSupportedException("Rename (TCP)"))

    override suspend fun setAutoShutdown(enabled: Boolean, minutes: Int): Result<Unit> =
        Result.failure(NotSupportedException("Auto-shutdown (TCP)"))

    // ---- Parsing helpers ----

    private fun normalizeStatus(raw: String): String = when (raw.uppercase()) {
        "READY", "IDLE" -> "ready"
        "BUILDING_FROM_SD", "PRINTING" -> "printing"
        "BUILDING_COMPLETED" -> "completed"
        "PAUSED" -> "paused"
        "BUSY" -> "busy"
        else -> raw.lowercase()
    }

    private fun parseMachineStatus(response: String): String =
        response.lineSequence().map { it.trim() }
            .find { it.startsWith("MachineStatus:") }
            ?.substringAfter("MachineStatus:")?.trim().orEmpty()

    private fun parseTemps(response: String): Temps {
        var eCur: Float? = null; var eTar: Float? = null
        var bCur: Float? = null; var bTar: Float? = null
        for (part in response.split(" ", "\n")) {
            val t = part.trim()
            if (t.startsWith("T0:")) {
                val s = t.substring(3)
                if (s.contains("/")) { val p = s.split("/"); eCur = p[0].toFloatOrNull(); eTar = p[1].toFloatOrNull() }
                else eCur = s.toFloatOrNull()
            } else if (t.startsWith("B:") && !t.startsWith("B@")) {
                val s = t.substring(2)
                if (s.contains("/")) { val p = s.split("/"); bCur = p[0].toFloatOrNull(); bTar = p[1].toFloatOrNull() }
                else bCur = s.toFloatOrNull()
            }
        }
        return Temps(eCur, eTar, bCur, bTar)
    }

    private fun parseProgress(response: String): Progress {
        val sdMatch = response.lineSequence().map { it.trim() }
            .find { it.contains("SD printing byte", ignoreCase = true) }
            ?.let { Regex("""SD printing byte\s+(\d+)\s*/\s*(\d+)""", RegexOption.IGNORE_CASE).find(it) }
        val layerMatch = response.lineSequence().map { it.trim() }
            .find { it.startsWith("Layer:") }
            ?.let { Regex("""Layer:\s*(\d+)\s*/\s*(\d+)""", RegexOption.IGNORE_CASE).find(it) }
        return Progress(
            sdMatch?.groupValues?.get(1)?.toFloatOrNull(),
            sdMatch?.groupValues?.get(2)?.toFloatOrNull(),
            layerMatch?.groupValues?.get(1)?.toFloatOrNull(),
            layerMatch?.groupValues?.get(2)?.toFloatOrNull(),
        )
    }

    private fun parseCurrentFile(response: String): String? =
        response.lineSequence().map { it.trim() }
            .find { it.startsWith("CurrentFile:") || it.startsWith("PrintFileName:") }
            ?.substringAfter(":")?.trim()?.takeIf { it.isNotEmpty() }

    private fun parseLedState(response: String): Boolean {
        val value = response.lineSequence().map { it.trim() }
            .find { it.startsWith("LED:") || it.startsWith("LEDStatus:") }
            ?.substringAfter(":")?.trim()?.lowercase()
        return value == "1" || value == "on"
    }

    private fun buildDetail(statusResp: String, tempResp: String, progressResp: String): FFPrinterDetail {
        val status = normalizeStatus(parseMachineStatus(statusResp))
        val temps = parseTemps(tempResp)
        val progress = parseProgress(progressResp)
        val printProgress = run {
            val cur = progress.progressPercent; val total = progress.progressTotal
            if (cur != null && total != null && total > 0f) return@run cur / total
            val layer = progress.currentLayer ?: return@run null
            val layers = progress.totalLayers ?: return@run null
            if (layers > 0f) layer / layers else null
        }
        return FFPrinterDetail(
            status = status,
            rightTemp = temps.extCurrent,
            rightTargetTemp = temps.extTarget,
            platTemp = temps.bedCurrent,
            platTargetTemp = temps.bedTarget,
            printProgress = printProgress,
            printLayer = progress.currentLayer,
            targetPrintLayer = progress.totalLayers,
            printFileName = parseCurrentFile(statusResp),
            lightStatus = if (parseLedState(statusResp)) "open" else "close",
            name = printer.name,
        )
    }

    private data class Temps(
        val extCurrent: Float? = null, val extTarget: Float? = null,
        val bedCurrent: Float? = null, val bedTarget: Float? = null,
    )

    private data class Progress(
        val progressPercent: Float? = null, val progressTotal: Float? = null,
        val currentLayer: Float? = null, val totalLayers: Float? = null,
    )
}
