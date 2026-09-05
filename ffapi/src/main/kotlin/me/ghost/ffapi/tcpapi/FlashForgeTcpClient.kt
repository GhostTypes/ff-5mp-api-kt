package me.ghost.ffapi.tcpapi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.BindException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Controls how (and whether) the TCP keep-alive heartbeat runs after [connect].
 *
 * - [MODERN] — light `~M27` heartbeat every 5 s to hold the `~M601` control lock. Modern printers
 *   get all status from HTTP `/detail`; TCP is control-only.
 * - [LEGACY_POLL] — no automatic heartbeat. The legacy backend drives status polling explicitly via
 *   [sendCommandWithResponse] each tick, which implicitly keeps the connection alive.
 * - [NONE] — no heartbeat at all (used during short identification probes).
 */
enum class KeepAliveMode { MODERN, LEGACY_POLL, NONE }

/**
 * Low-level TCP socket client for FlashForge printers (port 8899). Ported from the app's Kotlin
 * implementation (verified on live hardware), which itself implements the FlashForge
 * wire protocol from ff-5mp-api-ts. Uses a persistent read loop + [Mutex]-serialized command
 * exchange + keep-alive + auto-reconnect, rather than the TS one-shot listener model.
 *
 * Networking runs on [Dispatchers.IO]. The control lock (`~M601`) is acquired in [connect] and
 * released (`~M602`) in [disconnect].
 */
class FlashForgeTcpClient(
    private val ipAddress: String,
    private val scope: CoroutineScope,
    private val port: Int = 8899,
) {
    private companion object {
        /** Characters that are NOT valid in a printer filename — used to trim M661 binary framing. */
        val INVALID_FILENAME_CHARS = Regex("""[^\w\s\-.()+%,@\[\]{}:;!#$^&*=<>?/]""")
        /** PNG file signature bytes, used to locate thumbnail data in M662 responses. */
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        /** A3 thumbnail wrapper magic: 0xa2 0xa2 0x2a 0x2a + 4-byte BE length + PNG. */
        val A3_THUMB_MAGIC = byteArrayOf(0xa2.toByte(), 0xa2.toByte(), 0x2a.toByte(), 0x2a.toByte())
        const val RESPONSE_POLL_MS = 40L
        /** Idle gap before a sentinel-less reply (legacy `~M115` / `~M119`) is considered complete. */
        const val RESPONSE_SETTLE_MS = 300L

        /**
         * Bounded connect timeout for every socket this client opens, consistent with the 10 s
         * read timeout. The two-argument `Socket(address, port)` constructor connects with NO
         * timeout — on an unreachable host the OS SYN retries can hold the calling coroutine for
         * minutes before failing.
         */
        const val CONNECT_TIMEOUT_MS = 10_000
    }

    /** Controls keep-alive behaviour. Change before calling [connect]. */
    var keepAliveMode: KeepAliveMode = KeepAliveMode.MODERN

    private var socket: Socket? = null
    private var outWriter: PrintWriter? = null
    private var inReader: BufferedReader? = null

    private var connectionJob: Job? = null
    private var keepAliveJob: Job? = null
    private var readLoopJob: Job? = null

    @Volatile private var manuallyDisconnected = false

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    /** Parsed telemetry populated by the background read loop in [KeepAliveMode.MODERN]. */
    data class TcpTelemetry(
        val extCurrentTemp: Float = 0f,
        val extTargetTemp: Float = 0f,
        val bedCurrentTemp: Float = 0f,
        val bedTargetTemp: Float = 0f,
        val machineStatus: String = "READY",
    )

    private val _telemetry = MutableStateFlow(TcpTelemetry())
    val telemetry: StateFlow<TcpTelemetry> = _telemetry

    // ---- Synchronous command/response machinery ----

    private val commandMutex = Mutex()

    @Volatile private var awaitingResponse = false
    @Volatile private var responseHardComplete = false
    @Volatile private var lastResponseLineAt = 0L
    private val responseBuffer = StringBuilder()

    /**
     * Sends [cmd] and waits for its multi-line response. Completion is detected two ways (the legacy
     * A3 wire formats are inconsistent): a hard `"ok"` / `"ok …"` terminator, or — failing that — no
     * new line for [settleMs]. Runs its I/O on [Dispatchers.IO], so it is safe to call from any
     * dispatcher.
     */
    suspend fun sendCommandWithResponse(
        cmd: String,
        timeoutMs: Long = 5_000,
        settleMs: Long = RESPONSE_SETTLE_MS,
    ): Result<String> = withContext(Dispatchers.IO) {
        commandMutex.withLock {
            if (!_isConnected.value) {
                return@withLock Result.failure(IllegalStateException("TCP not connected"))
            }
            responseBuffer.clear()
            responseHardComplete = false
            lastResponseLineAt = 0L
            awaitingResponse = true
            writeLine(cmd)
            try {
                withTimeout(timeoutMs) {
                    while (true) {
                        if (!_isConnected.value) break
                        if (responseHardComplete) break
                        val last = lastResponseLineAt
                        if (last != 0L &&
                            responseBuffer.isNotEmpty() &&
                            System.currentTimeMillis() - last >= settleMs
                        ) break
                        delay(RESPONSE_POLL_MS)
                    }
                }
                if (responseBuffer.isNotEmpty()) Result.success(responseBuffer.toString())
                else Result.failure(IllegalStateException("No response to $cmd"))
            } catch (e: Exception) {
                if (responseBuffer.isNotEmpty()) Result.success(responseBuffer.toString())
                else Result.failure(e)
            } finally {
                awaitingResponse = false
            }
        }
    }

    /**
     * Sends [cmd] and resolves to success only if the reply indicates acknowledgement (contains
     * "ok" or "Received."). Mirrors the TS `sendCmdOk`.
     */
    suspend fun sendCmdOk(cmd: String, timeoutMs: Long = 5_000): Result<Unit> =
        sendCommandWithResponse(cmd, timeoutMs).mapCatching { reply ->
            if (reply.contains("ok") || reply.contains("Received.")) Unit
            else throw IllegalStateException("Command not acknowledged: $cmd")
        }

    // ---- Connection lifecycle ----

    /** Opens the socket, acquires the `~M601` lock, starts the read loop and (MODERN) keep-alive. */
    fun connect() {
        if (connectionJob?.isActive == true) return
        manuallyDisconnected = false

        connectionJob = scope.launch(Dispatchers.IO) {
            try {
                socket = openSocket(soTimeoutMs = 10_000)
                outWriter = PrintWriter(
                    OutputStreamWriter(socket!!.getOutputStream(), Charsets.US_ASCII), true,
                )
                inReader = BufferedReader(InputStreamReader(socket!!.getInputStream(), Charsets.US_ASCII))
                _isConnected.value = true

                // Read loop must start BEFORE the login handshake.
                readLoopJob = launch(Dispatchers.IO) { readLoop() }

                val loginResult = sendCommandWithResponse(GCodesLogin, timeoutMs = 3_000)
                if (loginResult.isFailure) {
                    // The printer may still have taken the ~M601 lock even though the reply
                    // never arrived — release before closing so a dying session cannot hold
                    // the control lock (which would block other clients for the ~30 s idle
                    // timeout). teardown also releases the reader/writer the old path leaked.
                    teardown(releaseLock = true)
                    return@launch
                }

                if (keepAliveMode == KeepAliveMode.MODERN) {
                    keepAliveJob = launch(Dispatchers.IO) {
                        while (scope.isActive && _isConnected.value && keepAliveMode == KeepAliveMode.MODERN) {
                            sendCommand("~M27")
                            delay(5000)
                        }
                    }
                }
            } catch (_: Exception) {
                // Connect refusal/timeout or stream-setup failure: nothing holds the lock yet
                // (teardown's release is gated on isConnected), so this is pure cleanup.
                teardown(releaseLock = true)
            }
        }
    }

    private suspend fun readLoop() {
        try {
            while (scope.isActive && socket?.isClosed == false) {
                val line = inReader?.readLine() ?: break
                val trimmed = line.trim()
                if (awaitingResponse) {
                    if (responseBuffer.isNotEmpty()) responseBuffer.append('\n')
                    responseBuffer.append(trimmed)
                    lastResponseLineAt = System.currentTimeMillis()
                    if (trimmed == "ok" || trimmed.startsWith("ok ")) responseHardComplete = true
                } else {
                    parseLine(trimmed)
                }
            }
        } catch (_: Exception) {
            // Socket closed or read error — handled below.
        } finally {
            val wasConnected = _isConnected.value
            _isConnected.value = false
            if (wasConnected && !manuallyDisconnected) scheduleReconnect()
        }
    }

    // ---- Auto-reconnect with exponential backoff ----

    private var reconnectDelayMs = 1_000L
    private var reconnectJob: Job? = null

    private fun scheduleReconnect() {
        if (manuallyDisconnected) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            delay(reconnectDelayMs)
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(15_000L)
            if (!manuallyDisconnected) connect()
        }
    }

    fun resetReconnectBackoff() { reconnectDelayMs = 1_000L }

    // ---- Background telemetry parsing (MODERN keep-alive path) ----

    private fun parseLine(line: String) {
        if (line.isEmpty()) return
        if (line.startsWith("T0:") || line.startsWith("B:")) {
            parseTemps(line)
        } else if (line.startsWith("MachineStatus:")) {
            _telemetry.update { it.copy(machineStatus = line.substringAfter("MachineStatus:").trim()) }
        }
    }

    private fun parseTemps(line: String) {
        var eCur = _telemetry.value.extCurrentTemp
        var eTar = _telemetry.value.extTargetTemp
        var bCur = _telemetry.value.bedCurrentTemp
        var bTar = _telemetry.value.bedTargetTemp
        for (part in line.split(" ")) {
            if (part.startsWith("T0:")) {
                val t = part.substring(3)
                if (t.contains("/")) { val s = t.split("/"); eCur = s[0].toFloatOrNull() ?: eCur; eTar = s[1].toFloatOrNull() ?: eTar }
                else eCur = t.toFloatOrNull() ?: eCur
            } else if (part.startsWith("B:")) {
                val t = part.substring(2)
                if (t.contains("/")) { val s = t.split("/"); bCur = s[0].toFloatOrNull() ?: bCur; bTar = s[1].toFloatOrNull() ?: bTar }
                else bCur = t.toFloatOrNull() ?: bCur
            }
        }
        _telemetry.update { it.copy(extCurrentTemp = eCur, extTargetTemp = eTar, bedCurrentTemp = bCur, bedTargetTemp = bTar) }
    }

    // ---- Fire-and-forget command sending ----

    private fun writeLine(cmd: String) {
        try {
            outWriter?.print("$cmd\r\n")
            outWriter?.flush()
        } catch (_: Exception) {
        }
    }

    /** Fire-and-forget: writes [cmd] without waiting for a response (control commands). */
    fun sendCommand(cmd: String) {
        if (!_isConnected.value) return
        scope.launch(Dispatchers.IO) {
            commandMutex.withLock { writeLine(cmd) }
        }
    }

    // ---- File list (~M661) via a dedicated short-lived socket ----

    /**
     * Lists local G-code files via `~M661`. The reply is a binary blob that doesn't fit the
     * line-reader, so this runs on its own short-lived socket. Completion: stop once `ok` has been
     * seen and the stream has been quiet ~1.2s, capped at 10s.
     */
    suspend fun getFileList(): Result<List<String>> = withContext(Dispatchers.IO) {
        var sock: Socket? = null
        try {
            sock = openSocket(soTimeoutMs = 500)
            val out = sock.getOutputStream()
            val input = sock.getInputStream()
            out.write("~M661\r\n".toByteArray(Charsets.US_ASCII)); out.flush()

            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            val deadline = System.currentTimeMillis() + 10_000
            var lastDataAt = System.currentTimeMillis()
            var completionSeen = false
            while (System.currentTimeMillis() < deadline) {
                val n = try { input.read(chunk) } catch (_: SocketTimeoutException) {
                    if (completionSeen && System.currentTimeMillis() - lastDataAt >= 1200) break
                    continue
                }
                if (n == -1) break
                if (n > 0) {
                    buffer.write(chunk, 0, n)
                    lastDataAt = System.currentTimeMillis()
                    if (!completionSeen && buffer.toString("ISO-8859-1").contains("ok")) completionSeen = true
                }
                if (completionSeen && System.currentTimeMillis() - lastDataAt >= 1200) break
            }
            Result.success(parseFileListResponse(buffer.toString("ISO-8859-1")))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { sock?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Parses the raw `~M661` reply. A4/generic: `::`-delimited segments with `/data/` paths.
     * A3: lines after `info_list.size: N` contain one filename per line.
     */
    private fun parseFileListResponse(response: String): List<String> {
        val a3Match = Regex("""info_list\.size:\s*(\d+)""", RegexOption.IGNORE_CASE).find(response)
        if (a3Match != null) {
            val count = a3Match.groupValues[1].toIntOrNull() ?: 0
            val afterSize = response.substring(a3Match.range.last + 1)
            return afterSize.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != "ok" && !it.startsWith("CMD ") }
                .take(count)
                .toList()
        }
        val files = mutableListOf<String>()
        for (segment in response.split("::")) {
            val dataIndex = segment.indexOf("/data/")
            if (dataIndex == -1) continue
            var filename = segment.substring(dataIndex + "/data/".length)
            val invalid = INVALID_FILENAME_CHARS.find(filename)
            if (invalid != null) filename = filename.substring(0, invalid.range.first)
            if (filename.isNotBlank()) files.add(filename)
        }
        return files
    }

    // ---- File thumbnail (~M662) via a dedicated short-lived socket ----

    /**
     * Retrieves a file thumbnail via `~M662 <path>`. Returns the PNG bytes, or null when the file
     * has no thumbnail. Handles both the A3 magic-header wrapper and A4/generic raw PNG.
     */
    suspend fun getFileThumbnail(fileName: String): Result<ByteArray?> = withContext(Dispatchers.IO) {
        var sock: Socket? = null
        try {
            sock = openSocket(soTimeoutMs = 500)
            val out = sock.getOutputStream()
            val input = sock.getInputStream()
            out.write("~M662 /data/$fileName\r\n".toByteArray(Charsets.US_ASCII)); out.flush()

            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            val deadline = System.currentTimeMillis() + 10_000
            var lastDataAt = System.currentTimeMillis()
            var okSeen = false
            while (System.currentTimeMillis() < deadline) {
                val n = try { input.read(chunk) } catch (_: SocketTimeoutException) {
                    if (okSeen && System.currentTimeMillis() - lastDataAt >= 1200) break
                    continue
                }
                if (n == -1) break
                if (n > 0) {
                    buffer.write(chunk, 0, n)
                    lastDataAt = System.currentTimeMillis()
                    if (!okSeen) {
                        val text = buffer.toString("ISO-8859-1")
                        if (text.contains("Error")) return@withContext Result.success(null)
                        if (text.contains("ok")) okSeen = true
                    }
                }
                if (okSeen && System.currentTimeMillis() - lastDataAt >= 1200) break
            }

            val bytes = buffer.toByteArray()
            val a3Start = indexOfBytes(bytes, A3_THUMB_MAGIC)
            if (a3Start >= 0 && bytes.size >= a3Start + 8) {
                val length = ((bytes[a3Start + 4].toLong() and 0xFF) shl 24) or
                    ((bytes[a3Start + 5].toLong() and 0xFF) shl 16) or
                    ((bytes[a3Start + 6].toLong() and 0xFF) shl 8) or
                    (bytes[a3Start + 7].toLong() and 0xFF)
                val dataStart = a3Start + 8
                if (bytes.size >= dataStart + length) {
                    return@withContext Result.success(bytes.copyOfRange(dataStart, dataStart + length.toInt()))
                }
            }
            val pngStart = indexOfBytes(bytes, PNG_SIGNATURE)
            if (pngStart >= 0) Result.success(bytes.copyOfRange(pngStart, bytes.size))
            else Result.success(null)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { sock?.close() } catch (_: Exception) {}
        }
    }

    private fun indexOfBytes(data: ByteArray, needle: ByteArray): Int {
        if (data.size < needle.size) return -1
        outer@ for (i in 0..(data.size - needle.size)) {
            for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    // ---- Disconnect ----

    /** Serializes [teardown] so concurrent closers cannot double-close or interleave. */
    private val teardownMutex = Mutex()

    fun disconnect() {
        manuallyDisconnected = true
        reconnectJob?.cancel()
        reconnectJob = null
        scope.launch(Dispatchers.IO) {
            teardown(releaseLock = true)
            connectionJob?.cancel()
            connectionJob = null
        }
    }

    /**
     * Closes the socket/reader/writer and stops the read + keep-alive jobs — the single
     * cleanup path for [disconnect], a failed [connect], and anything else that tears a
     * session down. The fields are swapped to `null` under [teardownMutex] *before* anything
     * is closed, so concurrent callers (a user [disconnect] racing the connect-failure path,
     * or a second [disconnect]) find `null`s and do nothing: the release + close runs exactly
     * once, on every exit path. When [releaseLock] is set and the session was connected, a
     * fire-and-forget `~M602` is attempted first so the printer's control lock is not left
     * held by a dying session.
     */
    private suspend fun teardown(releaseLock: Boolean) = teardownMutex.withLock {
        if (releaseLock && _isConnected.value) {
            try { writeLine(GCodesLogout) } catch (_: Exception) {}
        }
        val sock = socket
        val out = outWriter
        val input = inReader
        socket = null
        outWriter = null
        inReader = null
        _isConnected.value = false
        keepAliveJob?.cancel()
        keepAliveJob = null
        readLoopJob?.cancel()
        readLoopJob = null
        try { out?.close() } catch (_: Exception) {}
        try { input?.close() } catch (_: Exception) {}
        try { sock?.close() } catch (_: Exception) {}
    }

    /**
     * Opens a socket with a bounded connect timeout (see [CONNECT_TIMEOUT_MS]). Used by the
     * command connection and the short-lived `~M661` / `~M662` sockets alike.
     */
    private fun openSocket(soTimeoutMs: Int): Socket {
        // Deliberately plain statements, not `Socket().apply { connect(...) }`: the apply form
        // was observed failing every connect with BindException("Cannot assign requested
        // address") against a Windows loopback listener (JDK 25), while this form connects
        // reliably. Same construction the pre-hardening `Socket(address, port)` used.
        val socket = Socket()
        socket.connect(InetSocketAddress(ipAddress, port), CONNECT_TIMEOUT_MS)
        socket.soTimeout = soTimeoutMs
        return socket
    }

    private val GCodesLogin get() = "~M601 S1"
    private val GCodesLogout get() = "~M602"
}
