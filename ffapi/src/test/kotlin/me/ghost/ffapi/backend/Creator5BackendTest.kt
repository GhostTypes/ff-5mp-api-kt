package me.ghost.ffapi.backend

import kotlinx.coroutines.test.runTest
import me.ghost.ffapi.PrinterConfig
import me.ghost.ffapi.PrinterModel
import me.ghost.ffapi.api.FlashForgeHttpApi
import me.ghost.ffapi.error.NotSupportedException
import me.ghost.ffapi.tcpapi.FlashForgeClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [Creator5Backend]: capabilities (chamber + material station on, Pro filtration
 * force-on), the HTTP-only guard, and that TCP-only operations fail fast with
 * [NotSupportedException] instead of touching the (dead) TCP channel.
 *
 * Capabilities are read after [PrinterBackend.initialize]: `getProduct` is pointed at a closed
 * localhost port so it fails fast (ECONNREFUSED), but `initialize` assigns the baseline
 * capabilities *before* that network call, so they reflect [Creator5Backend.baselineCapabilities].
 */
class Creator5BackendTest {

    private fun backend(model: PrinterModel): Creator5Backend {
        // 127.0.0.1:8898 with nothing listening -> connection refused near-instantly.
        val http = FlashForgeHttpApi("127.0.0.1")
        val tcp = FlashForgeClient("127.0.0.1", kotlinx.coroutines.test.TestScope())
        return Creator5Backend(
            printer = PrinterConfig("127.0.0.1", "SN", "CC", firmwareVersion = "1.9.2"),
            http = http,
            tcp = tcp,
            creatorModel = model,
        )
    }

    @Test
    fun `Creator 5 baseline capabilities`() = runTest {
        val b = backend(PrinterModel.CREATOR_5)
        b.initialize() // getProduct fails fast; baseline capabilities are already assigned
        val caps = b.capabilities
        assertEquals(PrinterModel.CREATOR_5, caps.model)
        assertTrue("material station must be on", caps.hasMaterialStation)
        assertTrue("chamber temp control must be on", caps.chamberTempControl)
        assertFalse("plain C5 has no filtration", caps.filtrationControl)
    }

    @Test
    fun `Creator 5 Pro forces filtration on`() = runTest {
        val b = backend(PrinterModel.CREATOR_5_PRO)
        b.initialize()
        val caps = b.capabilities
        assertEquals(PrinterModel.CREATOR_5_PRO, caps.model)
        assertTrue("Pro must force filtration on", caps.filtrationControl)
        assertTrue(caps.hasMaterialStation)
        assertTrue(caps.chamberTempControl)
    }

    @Test
    fun `Creator 5 is http-only`() {
        val b = backend(PrinterModel.CREATOR_5)
        assertEquals(PrinterModel.CREATOR_5, b.model)
        assertTrue("C5 must be httpOnly so the TCP client is never connected", b.httpOnly)
    }

    @Test
    fun `homing (TCP G-code) throws NotSupportedException on Creator 5`() = runTest {
        val b = backend(PrinterModel.CREATOR_5)
        val r = b.home()
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull() is NotSupportedException)
    }

    @Test
    fun `local file list (TCP) throws NotSupportedException on Creator 5`() = runTest {
        val b = backend(PrinterModel.CREATOR_5)
        val r = b.listLocalFiles()
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull() is NotSupportedException)
    }

    @Test
    fun `slotAction ms_cmd is rejected on Creator 5 (AD5X-only)`() = runTest {
        val b = backend(PrinterModel.CREATOR_5)
        b.initialize()
        val r = b.slotAction(slot = 1, action = SlotAction.LOAD)
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull() is NotSupportedException)
    }
}
