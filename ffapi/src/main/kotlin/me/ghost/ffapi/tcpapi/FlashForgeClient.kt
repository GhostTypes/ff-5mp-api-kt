package me.ghost.ffapi.tcpapi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import me.ghost.ffapi.error.ProtocolException
import me.ghost.ffapi.tcpapi.client.GCodeController
import me.ghost.ffapi.tcpapi.client.GCodes
import me.ghost.ffapi.tcpapi.replays.EndstopStatus
import me.ghost.ffapi.tcpapi.replays.LocationInfo
import me.ghost.ffapi.tcpapi.replays.PrintStatus
import me.ghost.ffapi.tcpapi.replays.PrinterInfo
import me.ghost.ffapi.tcpapi.replays.TempInfo

/**
 * High-level TCP client for FlashForge printers. Wraps [FlashForgeTcpClient] (transport) and exposes
 * the TS-shaped surface: typed status queries that parse replies via the `replays` parsers, plus a
 * [GCodeController] for control commands. Ported from the TS `FlashForgeClient`.
 *
 * All queries return [Result]; on a malformed reply the failure carries a [ProtocolException].
 */
class FlashForgeClient(
    ipAddress: String,
    scope: CoroutineScope,
    port: Int = 8899,
) {
    /** The underlying transport. Exposed for advanced/raw use. */
    val tcp = FlashForgeTcpClient(ipAddress, scope, port)

    /** Controller for control-only G-code commands (LED, job, homing, temperature). */
    val gcode = GCodeController(this)

    /** Connection state and modern-keep-alive telemetry, forwarded from the transport. */
    val isConnected: StateFlow<Boolean> get() = tcp.isConnected
    val telemetry: StateFlow<FlashForgeTcpClient.TcpTelemetry> get() = tcp.telemetry

    var keepAliveMode: KeepAliveMode
        get() = tcp.keepAliveMode
        set(value) { tcp.keepAliveMode = value }

    /** Opens the socket and acquires the `~M601` control lock (see [FlashForgeTcpClient.connect]). */
    fun connect() = tcp.connect()
    fun disconnect() = tcp.disconnect()
    fun resetReconnectBackoff() = tcp.resetReconnectBackoff()

    // ---- Raw command helpers ----

    suspend fun sendCmdOk(cmd: String, timeoutMs: Long = 5_000): Result<Unit> =
        tcp.sendCmdOk(cmd, timeoutMs)

    suspend fun sendRawCommand(cmd: String, timeoutMs: Long = 5_000): Result<String> =
        tcp.sendCommandWithResponse(cmd, timeoutMs)

    // ---- Typed status queries ----

    suspend fun getPrinterInfo(): Result<PrinterInfo> =
        tcp.sendCommandWithResponse(GCodes.CMD_INFO_STATUS).mapCatching {
            PrinterInfo().fromReplay(it) ?: throw ProtocolException("Unparseable M115 reply")
        }

    suspend fun getTempInfo(): Result<TempInfo> =
        tcp.sendCommandWithResponse(GCodes.CMD_TEMP).mapCatching {
            TempInfo().fromReplay(it) ?: throw ProtocolException("Unparseable M105 reply")
        }

    suspend fun getPrintStatus(): Result<PrintStatus> =
        tcp.sendCommandWithResponse(GCodes.CMD_PRINT_STATUS).mapCatching {
            PrintStatus().fromReplay(it) ?: throw ProtocolException("Unparseable M27 reply")
        }

    suspend fun getEndstopInfo(): Result<EndstopStatus> =
        tcp.sendCommandWithResponse(GCodes.CMD_ENDSTOP_INFO).mapCatching {
            EndstopStatus().fromReplay(it) ?: throw ProtocolException("Unparseable M119 reply")
        }

    suspend fun getLocationInfo(): Result<LocationInfo> =
        tcp.sendCommandWithResponse(GCodes.CMD_INFO_XYZAB).mapCatching {
            LocationInfo().fromReplay(it) ?: throw ProtocolException("Unparseable M114 reply")
        }

    // ---- Files / thumbnails (dedicated short-lived sockets) ----

    suspend fun getFileList(): Result<List<String>> = tcp.getFileList()

    /** Thumbnail PNG bytes, or null when the file has none. */
    suspend fun getThumbnail(fileName: String): Result<ByteArray?> = tcp.getFileThumbnail(fileName)

    // ---- Control convenience (delegates to the controller) ----

    suspend fun ledOn(): Result<Unit> = gcode.ledOn()
    suspend fun ledOff(): Result<Unit> = gcode.ledOff()
    suspend fun homeAxes(): Result<Unit> = gcode.home()
    suspend fun rapidHome(): Result<Unit> = gcode.rapidHome()
    suspend fun pauseJob(): Result<Unit> = gcode.pauseJob()
    suspend fun resumeJob(): Result<Unit> = gcode.resumeJob()
    suspend fun stopJob(): Result<Unit> = gcode.stopJob()
    suspend fun startJob(name: String): Result<Unit> = gcode.startJob(name)
    suspend fun setExtruderTemp(temp: Int, waitFor: Boolean = false): Result<Unit> =
        gcode.setExtruderTemp(temp, waitFor)
    suspend fun setBedTemp(temp: Int, waitFor: Boolean = false): Result<Unit> =
        gcode.setBedTemp(temp, waitFor)
    suspend fun cancelExtruderTemp(): Result<Unit> = gcode.cancelExtruderTemp()
    suspend fun cancelBedTemp(waitForCool: Boolean = false): Result<Unit> = gcode.cancelBedTemp(waitForCool)
}
