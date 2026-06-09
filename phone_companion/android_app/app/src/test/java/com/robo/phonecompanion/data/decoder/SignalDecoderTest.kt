package com.robo.phonecompanion.data.decoder

import com.robo.phonecompanion.data.model.ByteOrder
import com.robo.phonecompanion.data.model.DbcSignal
import org.junit.Assert.assertEquals
import org.junit.Test

class SignalDecoderTest {

    private fun signal(
        startBit: Int,
        length: Int,
        byteOrder: ByteOrder = ByteOrder.INTEL,
        signed: Boolean = false,
        factor: Double = 1.0,
        offset: Double = 0.0,
    ) = DbcSignal("test", startBit, length, byteOrder, signed, factor, offset, 0.0, 0.0, "")

    // ── Intel ────────────────────────────────────────────────────────────────

    @Test fun `intel 16-bit unsigned with factor`() {
        // 0x1388 = 5000 raw → 5000 * 0.25 = 1250 rpm
        val data = byteArrayOf(0x88.toByte(), 0x13, 0, 0, 0, 0, 0, 0)
        val sig = signal(0, 16, ByteOrder.INTEL, factor = 0.25)
        assertEquals(1250.0, SignalDecoder.decode(sig, data), 0.001)
    }

    @Test fun `intel 8-bit unsigned with offset`() {
        // raw 0x82 = 130 → 130 - 40 = 90 °C
        val data = byteArrayOf(0x82.toByte(), 0, 0, 0, 0, 0, 0, 0)
        val sig = signal(0, 8, ByteOrder.INTEL, offset = -40.0)
        assertEquals(90.0, SignalDecoder.decode(sig, data), 0.001)
    }

    @Test fun `intel 8-bit signed negative`() {
        // raw 0xFF = 255 unsigned, but as signed 8-bit = -1
        val data = byteArrayOf(0xFF.toByte(), 0, 0, 0, 0, 0, 0, 0)
        val sig = signal(0, 8, ByteOrder.INTEL, signed = true)
        assertEquals(-1.0, SignalDecoder.decode(sig, data), 0.001)
    }

    @Test fun `intel single bit flag`() {
        val data = byteArrayOf(0b00000100.toByte(), 0, 0, 0, 0, 0, 0, 0)
        val sig = signal(startBit = 2, length = 1)
        assertEquals(1.0, SignalDecoder.decode(sig, data), 0.0)
    }

    @Test fun `intel single bit flag off`() {
        val data = byteArrayOf(0b11111011.toByte(), 0, 0, 0, 0, 0, 0, 0)
        val sig = signal(startBit = 2, length = 1)
        assertEquals(0.0, SignalDecoder.decode(sig, data), 0.0)
    }

    @Test fun `intel signal spanning two bytes`() {
        // bits 4-11: upper nibble of byte 0 + lower nibble of byte 1
        // data[0]=0xA0, data[1]=0x0B → bits 4-7 = 0xA, bits 8-11 = 0xB
        // raw = 0xBA = 186
        val data = byteArrayOf(0xA0.toByte(), 0x0B.toByte(), 0, 0, 0, 0, 0, 0)
        val sig = signal(startBit = 4, length = 8)
        assertEquals(186.0, SignalDecoder.decode(sig, data), 0.001)
    }

    // ── Motorola ──────────────────────────────────────────────────────────────

    @Test fun `motorola 8-bit aligned at byte 0`() {
        // startBit=7 (MSB of byte 0), length=8 → entire byte 0
        val data = byteArrayOf(0x82.toByte(), 0, 0, 0, 0, 0, 0, 0)
        val sig = signal(7, 8, ByteOrder.MOTOROLA, offset = -40.0)
        assertEquals(90.0, SignalDecoder.decode(sig, data), 0.001)
    }

    @Test fun `motorola 16-bit spanning bytes 0 and 1`() {
        // startBit=7 (MSB at byte0 bit7), length=16
        // MSB byte = data[0], LSB byte = data[1]
        // data[0]=0x13, data[1]=0x88 → raw = 0x1388 = 5000 → *0.25 = 1250
        val data = byteArrayOf(0x13, 0x88.toByte(), 0, 0, 0, 0, 0, 0)
        val sig = signal(7, 16, ByteOrder.MOTOROLA, factor = 0.25)
        assertEquals(1250.0, SignalDecoder.decode(sig, data), 0.001)
    }

    @Test fun `motorola 1-bit flag`() {
        val data = byteArrayOf(0b10000000.toByte(), 0, 0, 0, 0, 0, 0, 0)
        val sig = signal(7, 1, ByteOrder.MOTOROLA)
        assertEquals(1.0, SignalDecoder.decode(sig, data), 0.0)
    }

    // ── Round-trip raw extraction ─────────────────────────────────────────────

    @Test fun `physicalToRaw round trip`() {
        val sig = signal(0, 16, ByteOrder.INTEL, factor = 0.25)
        val raw = SignalDecoder.physicalToRaw(sig, 1250.0)
        assertEquals(5000L, raw)
    }
}
