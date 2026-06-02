package me.ghost.ffapi.api

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.ghost.ffapi.PrinterModel
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException

/** A printer found via UDP discovery. */
data class DiscoveredPrinter(
    val ipAddress: String,
    val name: String,
    /** Serial number (modern 276-byte protocol only; empty for legacy). */
    val serialNumber: String,
    /** True for the modern 276-byte protocol (5M/5M Pro/AD5X); false for legacy 140-byte (A3/A4). */
    val isModern: Boolean,
    /** TCP G-code command port (typically 8899). */
    val commandPort: Int,
    /** HTTP API port (8898 for modern; defaulted for legacy). */
    val httpPort: Int,
    /** Best-effort model from the broadcast name; authoritative detection is via `/detail`. */
    val model: PrinterModel,
)

/**
 * FlashForge LAN discovery over UDP broadcast/multicast. Ported from the app's `UdpDiscovery`.
 *
 * The UDP work is pure JVM (so it is unit-testable off-device); the Android [Context] is only used
 * to hold a `MulticastLock` while scanning and is optional — pass null on the JVM/desktop. The empty
 * broadcast payload is deliberate and verified against real hardware; do not change it.
 */
object PrinterDiscovery {

    private const val MULTICAST_ADDRESS = "225.0.0.9"
    private val TARGETS = listOf(
        MULTICAST_ADDRESS to 19000,
        MULTICAST_ADDRESS to 8899,
        "255.255.255.255" to 48899,
        "255.255.255.255" to 19000,
        "255.255.255.255" to 8899,
    )

    /**
     * Scans the LAN for printers. [retries] rounds, each listening [roundTimeoutMs}; stops early on
     * the first round that finds anything. Provide [context] on Android to hold a MulticastLock.
     */
    suspend fun discover(
        context: Context? = null,
        retries: Int = 3,
        roundTimeoutMs: Long = 3000,
    ): List<DiscoveredPrinter> = withContext(Dispatchers.IO) {
        val printers = mutableListOf<DiscoveredPrinter>()
        val multicastLock = context?.let {
            (it.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.createMulticastLock("FfApiMulticastLock")
                ?.apply { setReferenceCounted(true); acquire() }
        }

        var socket: MulticastSocket? = null
        try {
            socket = MulticastSocket(0).apply {
                broadcast = true
                reuseAddress = true
                soTimeout = 1500
            }
            val group = InetAddress.getByName(MULTICAST_ADDRESS)
            runCatching { socket.joinGroup(group) }

            val empty = ByteArray(0)
            val recvBuf = ByteArray(512)
            val recvPacket = DatagramPacket(recvBuf, recvBuf.size)

            for (retry in 0 until retries) {
                TARGETS.forEach { (ip, port) ->
                    runCatching {
                        socket.send(DatagramPacket(empty, 0, InetAddress.getByName(ip), port))
                    }
                }
                val endTime = System.currentTimeMillis() + roundTimeoutMs
                while (System.currentTimeMillis() < endTime) {
                    try {
                        socket.receive(recvPacket)
                        val ip = recvPacket.address.hostAddress ?: continue
                        val parsed = parseResponse(recvBuf, recvPacket.length, ip) ?: continue
                        val existing = printers.find { it.ipAddress == parsed.ipAddress && it.commandPort == parsed.commandPort }
                        if (existing == null) {
                            printers.add(parsed)
                        } else if (parsed.isModern && !existing.isModern) {
                            printers.remove(existing); printers.add(parsed)
                        }
                    } catch (_: SocketTimeoutException) {
                        break
                    } catch (_: Exception) {
                        break
                    }
                }
                if (printers.isNotEmpty()) break
                if (retry < retries - 1) delay(1000)
            }
        } catch (_: Exception) {
            // Discovery is best-effort; return whatever we collected.
        } finally {
            runCatching { socket?.leaveGroup(InetAddress.getByName(MULTICAST_ADDRESS)) }
            socket?.close()
            multicastLock?.let { if (it.isHeld) it.release() }
        }
        printers
    }

    /**
     * Parses one discovery datagram. Modern (>=276 bytes): name @0 (128B), command port @0x84 (BE
     * u16), event/HTTP port @0x8E, serial @0x92 (128B). Legacy (>=140 bytes): name + command port,
     * HTTP defaulted to 8898. Returns null for too-short buffers. Visible for testing.
     */
    internal fun parseResponse(buf: ByteArray, len: Int, ip: String): DiscoveredPrinter? {
        if (len < 140) return null
        val name = decodeCString(buf, 0, 128)
        val isModern = len >= 276
        val commandPort = ((buf[0x84].toInt() and 0xFF) shl 8) or (buf[0x85].toInt() and 0xFF)
        val httpPort = if (isModern) ((buf[0x8E].toInt() and 0xFF) shl 8) or (buf[0x8F].toInt() and 0xFF) else 8898
        val serial = if (isModern) decodeCString(buf, 146, 128) else ""
        return DiscoveredPrinter(ip, name, serial, isModern, commandPort, httpPort, detectModel(name, isModern))
    }

    /** Decodes a null-terminated UTF-8 string from [buf] starting at [offset], up to [maxLen] bytes. */
    private fun decodeCString(buf: ByteArray, offset: Int, maxLen: Int): String {
        val end = (offset + maxLen).coerceAtMost(buf.size)
        var nullAt = end
        for (i in offset until end) if (buf[i].toInt() == 0) { nullAt = i; break }
        return String(buf, offset, nullAt - offset, Charsets.UTF_8).trim()
    }

    private fun detectModel(name: String, isModern: Boolean): PrinterModel {
        val upper = name.uppercase()
        return if (isModern) when {
            upper == "AD5X" || upper.contains("5X") -> PrinterModel.AD5X
            upper.contains("PRO") -> PrinterModel.ADVENTURER_5M_PRO
            upper.contains("5M") || upper.contains("AD5M") -> PrinterModel.ADVENTURER_5M
            else -> PrinterModel.UNKNOWN
        } else when {
            upper.contains("ADVENTURER 4") || upper.contains("AD4") -> PrinterModel.ADVENTURER_4
            upper.contains("ADVENTURER 3") || upper.contains("AD3") -> PrinterModel.ADVENTURER_3
            else -> PrinterModel.GENERIC_LEGACY
        }
    }
}
