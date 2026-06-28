package me.ghost.ffapi.api

import me.ghost.ffapi.PrinterModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic parse tests for the UDP discovery packet formats (no network). */
class PrinterDiscoveryParseTest {

    private fun putString(buf: ByteArray, offset: Int, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        System.arraycopy(bytes, 0, buf, offset, bytes.size)
    }

    private fun putPortBE(buf: ByteArray, offset: Int, port: Int) {
        buf[offset] = ((port shr 8) and 0xFF).toByte()
        buf[offset + 1] = (port and 0xFF).toByte()
    }

    @Test fun `parses a modern 276-byte AD5X response`() {
        val buf = ByteArray(276)
        putString(buf, 0, "AD5X")
        putPortBE(buf, 0x84, 8899)
        putPortBE(buf, 0x8E, 8898)
        putString(buf, 146, "SNADXTEST123")

        val p = PrinterDiscovery.parseResponse(buf, 276, "192.168.1.50")!!
        assertTrue(p.isModern)
        assertEquals("AD5X", p.name)
        assertEquals(8899, p.commandPort)
        assertEquals(8898, p.httpPort)
        assertEquals("SNADXTEST123", p.serialNumber)
        assertEquals(PrinterModel.AD5X, p.model)
        assertEquals("192.168.1.50", p.ipAddress)
    }

    @Test fun `parses a modern 5M Pro response by name`() {
        val buf = ByteArray(276)
        putString(buf, 0, "Adventurer 5M Pro")
        putPortBE(buf, 0x84, 8899)
        putPortBE(buf, 0x8E, 8898)
        val p = PrinterDiscovery.parseResponse(buf, 276, "192.168.1.51")!!
        assertEquals(PrinterModel.ADVENTURER_5M_PRO, p.model)
    }

    @Test fun `parses a legacy 140-byte Adventurer 4 response`() {
        val buf = ByteArray(140)
        putString(buf, 0, "FlashForge Adventurer 4")
        putPortBE(buf, 0x84, 8899)
        val p = PrinterDiscovery.parseResponse(buf, 140, "192.168.1.52")!!
        assertFalse(p.isModern)
        assertEquals(8899, p.commandPort)
        assertEquals(8898, p.httpPort)
        assertEquals("", p.serialNumber)
        assertEquals(PrinterModel.ADVENTURER_4, p.model)
    }

    @Test fun `returns null for too-short buffers`() {
        assertNull(PrinterDiscovery.parseResponse(ByteArray(100), 100, "192.168.1.53"))
    }

    @Test fun `parses a modern Creator 5 response by product id`() {
        val buf = ByteArray(276)
        putString(buf, 0, "Creator 5")
        putPortBE(buf, 0x84, 8899)
        putPortBE(buf, 0x88, 0x0028)
        putPortBE(buf, 0x8E, 8898)
        val p = PrinterDiscovery.parseResponse(buf, 276, "192.168.1.60")!!
        assertTrue(p.isModern)
        assertEquals(0x0028, p.productId)
        assertEquals(PrinterModel.CREATOR_5, p.model)
    }

    @Test fun `parses a modern Creator 5 Pro response by product id`() {
        val buf = ByteArray(276)
        putString(buf, 0, "Creator 5 Pro")
        putPortBE(buf, 0x84, 8899)
        putPortBE(buf, 0x88, 0x0029)
        val p = PrinterDiscovery.parseResponse(buf, 276, "192.168.1.61")!!
        assertEquals(0x0029, p.productId)
        assertEquals(PrinterModel.CREATOR_5_PRO, p.model)
    }

    @Test fun `prefers product id over name for model detection`() {
        val buf = ByteArray(276)
        putString(buf, 0, "AD5X")
        putPortBE(buf, 0x84, 8899)
        putPortBE(buf, 0x88, 0x0028)
        val p = PrinterDiscovery.parseResponse(buf, 276, "192.168.1.62")!!
        assertEquals(PrinterModel.CREATOR_5, p.model)
    }
}
