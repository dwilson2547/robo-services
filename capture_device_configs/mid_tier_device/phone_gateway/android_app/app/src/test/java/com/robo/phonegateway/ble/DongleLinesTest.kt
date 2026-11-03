package com.robo.phonegateway.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class DongleLinesTest {

    @Test
    fun `parses standard, extended and imu lines`() {
        val parsed = DongleLines.parse(
            "STD,0x1E5,8,82,00,00,80,00,00,00,AB,1000\n" +
                "EXT,0x10242040,1,01,1000\n" +
                "IMU,1.0,0.0,0.0,0.0,0.5,-0.25,9.8,0.01,0.02,-0.03,1000\n",
            receivedAtMs = 50_000,
        )
        assertEquals(2, parsed.frames.size)
        val std = parsed.frames[0]
        assertEquals(0x1E5, std.id)
        assertEquals(false, std.extended)
        assertArrayEquals(byteArrayOf(0x82.toByte(), 0, 0, 0x80.toByte(), 0, 0, 0, 0xAB.toByte()), std.data)
        val ext = parsed.frames[1]
        assertEquals(0x10242040, ext.id)
        assertEquals(true, ext.extended)
        assertEquals(1, parsed.imu.size)
        assertEquals(-0.25f, parsed.imu[0].ay)
        assertEquals(0, parsed.badLines)
    }

    @Test
    fun `latest entry is placed at receive time and others before it`() {
        val parsed = DongleLines.parse(
            "STD,0x100,1,00,5000\nSTD,0x101,1,00,5040\nSTD,0x102,1,00,5020\n",
            receivedAtMs = 100_000,
        )
        assertEquals(listOf(99_960L, 100_000L, 99_980L), parsed.frames.map { it.timeMs })
    }

    @Test
    fun `timestamps stay ordered across the 16-bit counter wrap`() {
        val parsed = DongleLines.parse(
            "STD,0x100,1,00,65530\nSTD,0x101,1,00,4\n",
            receivedAtMs = 100_000,
        )
        // 65530 -> 4 is 10 ms later, not 65 s earlier
        assertEquals(listOf(99_990L, 100_000L), parsed.frames.map { it.timeMs })
    }

    @Test
    fun `bad lines are counted, not thrown`() {
        val parsed = DongleLines.parse("garbage\nSTD,0xZZ,1,00,1\nSTD,0x100,8,00\nIMU,1,2\n", 0)
        assertEquals(0, parsed.frames.size)
        assertEquals(4, parsed.badLines)
    }

    @Test
    fun `entries without a counter take the receive time`() {
        val parsed = DongleLines.parse("STD,0x100,1,00\n", receivedAtMs = 7)
        assertEquals(7L, parsed.frames.single().timeMs)
    }

    @Test
    fun `a line cut off by the MTU is dropped, not parsed with a short counter`() {
        // the second line lost its last digits ("12345" -> "12") and its newline
        val parsed = DongleLines.parse("STD,0x100,1,00,12340\nSTD,0x101,1,00,12", receivedAtMs = 100_000)
        assertEquals(listOf(0x100), parsed.frames.map { it.id })
        assertEquals(100_000L, parsed.frames.single().timeMs)
        assertEquals(0, parsed.badLines)
        assertEquals(true, parsed.truncated)
    }

    @Test
    fun `a notification ending in a newline is not truncated`() {
        assertEquals(false, DongleLines.parse("STD,0x100,1,00,1\n", 0).truncated)
    }

    @Test
    fun `stats lines are parsed, not counted as bad`() {
        val parsed = DongleLines.parse("STAT,250,0,560,1,1,1400,3,7,12\nSTD,0x100,1,00,5\n", 0)
        assertEquals(DongleStats(250, 0, 560, 1, true, 1400, 3, 7, 12), parsed.stats)
        assertEquals(0, parsed.badLines)
        assertEquals(1, parsed.frames.size)
    }
}
