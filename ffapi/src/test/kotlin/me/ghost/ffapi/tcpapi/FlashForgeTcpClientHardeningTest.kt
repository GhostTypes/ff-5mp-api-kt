package me.ghost.ffapi.tcpapi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Collections.synchronizedList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Hardening tests for the TCP lifecycle (audit-A finding): the fire-and-forget [connect] used
 * to leak a socket — and the `~M601` control lock it may hold — when the login handshake
 * failed, and its teardown could double-close under a concurrent [disconnect].
 *
 * These tests run against a real loopback [ServerSocket]; no printer or emulator needed.
 */
class FlashForgeTcpClientHardeningTest {

    /** A loopback TCP server that accepts one connection and records every line it receives. */
    private class RecordingServer {
        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port: Int = server.localPort
        val received: MutableList<String> = synchronizedList(mutableListOf())
        private val closed = CountDownLatch(1)

        fun start() {
            Thread({
                try {
                    server.soTimeout = 15_000
                    val socket = server.accept()
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isNotEmpty()) received += line
                    }
                    socket.close()
                } catch (_: Exception) {
                    // Test fixture — a client-side abort ends up here.
                } finally {
                    closed.countDown()
                    try { server.close() } catch (_: Exception) {}
                }
            }, "recording-server").apply { isDaemon = true }.start()
        }

        fun awaitClosed(seconds: Long): Boolean = closed.await(seconds, TimeUnit.SECONDS)
    }

    private fun awaitTrue(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(25)
        }
        return predicate()
    }

    @Test
    fun `a failed login releases the M601 lock and closes the socket`() {
        // Server accepts but NEVER replies -> ~M601 times out after 3 s. The client must
        // still send ~M602 before closing: the printer may have taken the lock even though
        // its reply never arrived, and a leaked lock blocks other clients for ~30 s.
        val server = RecordingServer().apply { start() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = FlashForgeTcpClient("127.0.0.1", scope, port = server.port).apply {
            keepAliveMode = KeepAliveMode.NONE
        }

        try {
            client.connect()

            // The session must come up first, then die on the 3 s login timeout.
            assertTrue(
                "client should connect to the fixture; got ${server.received}",
                awaitTrue(10_000) { client.isConnected.value }
            )
            assertTrue("login should time out", awaitTrue(10_000) { !client.isConnected.value })
            assertTrue(
                "server socket must be closed by the client; received=${server.received}",
                server.awaitClosed(5)
            )
            assertTrue(
                "the client must release the lock with ~M602 before closing; got ${server.received}",
                server.received.contains("~M602"),
            )
            assertEquals(listOf("~M601 S1", "~M602"), server.received)
        } finally {
            client.disconnect()
            scope.cancel()
        }
    }

    @Test
    fun `a refused connection cleans up and repeated disconnects are safe`() {
        // Grab a port and close it again -> nothing listening -> connect fails fast.
        val hole = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val deadPort = hole.localPort
        hole.close()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = FlashForgeTcpClient("127.0.0.1", scope, port = deadPort)

        try {
            client.connect()
            assertTrue("connect must fail cleanly", awaitTrue(15_000) { !client.isConnected.value })

            // Double-disconnect racing the connect-failure cleanup must not throw or deadlock.
            client.disconnect()
            client.disconnect()
            assertTrue("cleanup must finish", awaitTrue(5_000) { !client.isConnected.value })
        } finally {
            client.disconnect()
            scope.cancel()
        }
    }

    @Test
    fun `disconnect on a never-connected client is a no-op`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = FlashForgeTcpClient("127.0.0.1", scope, port = 1)

        try {
            client.disconnect()
            assertFalse(client.isConnected.value)
        } finally {
            scope.cancel()
        }
    }
}
