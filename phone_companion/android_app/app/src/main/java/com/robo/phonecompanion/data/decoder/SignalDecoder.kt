package com.robo.phonecompanion.data.decoder

import com.robo.phonecompanion.data.model.ByteOrder
import com.robo.phonecompanion.data.model.DbcSignal

object SignalDecoder {

    /**
     * Extract the raw integer value of [signal] from [frameData], then apply
     * factor and offset to return the physical value.
     *
     * Intel (little-endian): startBit is the LSB position, bits increment
     * through memory in order (byte 0 bit 0, byte 0 bit 1, ...).
     *
     * Motorola (big-endian): startBit is the MSB position using DBC numbering
     * (bit N = byte N/8, bit-in-byte N%8). Bits descend within the byte then
     * jump to the MSB of the next byte.
     */
    fun decode(signal: DbcSignal, frameData: ByteArray): Double {
        val raw = extractRaw(signal, frameData)
        return raw * signal.factor + signal.offset
    }

    fun extractRaw(signal: DbcSignal, frameData: ByteArray): Long {
        var rawValue = 0L

        when (signal.byteOrder) {
            ByteOrder.INTEL -> {
                for (i in 0 until signal.length) {
                    val bitPos = signal.startBit + i
                    if (bitSet(frameData, bitPos)) rawValue = rawValue or (1L shl i)
                }
            }
            ByteOrder.MOTOROLA -> {
                var byteIdx = signal.startBit / 8
                var bitIdx = signal.startBit % 8
                for (i in 0 until signal.length) {
                    if (byteIdx < frameData.size && bitSet(frameData, byteIdx * 8 + bitIdx)) {
                        rawValue = rawValue or (1L shl (signal.length - 1 - i))
                    }
                    if (bitIdx == 0) {
                        byteIdx += 1
                        bitIdx = 7
                    } else {
                        bitIdx -= 1
                    }
                }
            }
        }

        if (signal.signed && signal.length > 0) {
            val signBit = 1L shl (signal.length - 1)
            if (rawValue and signBit != 0L) rawValue -= (signBit shl 1)
        }

        return rawValue
    }

    fun decodeOrNull(signal: DbcSignal, frameData: ByteArray): Double? {
        val maxByte = when (signal.byteOrder) {
            ByteOrder.INTEL -> (signal.startBit + signal.length - 1) / 8
            ByteOrder.MOTOROLA -> {
                var byteIdx = signal.startBit / 8
                var bitIdx = signal.startBit % 8
                var maxByteIdx = byteIdx
                repeat(signal.length) {
                    maxByteIdx = maxOf(maxByteIdx, byteIdx)
                    if (bitIdx == 0) { byteIdx++; bitIdx = 7 } else bitIdx--
                }
                maxByteIdx
            }
        }
        if (maxByte >= frameData.size) return null
        return decode(signal, frameData)
    }

    fun physicalToRaw(signal: DbcSignal, physical: Double): Long {
        val raw = ((physical - signal.offset) / signal.factor).toLong()
        val mask = if (signal.length >= 64) -1L else (1L shl signal.length) - 1L
        return raw and mask
    }

    private fun bitSet(data: ByteArray, bitPos: Int): Boolean {
        val byteIdx = bitPos / 8
        val bitIdx = bitPos % 8
        if (byteIdx >= data.size) return false
        return (data[byteIdx].toInt() and (1 shl bitIdx)) != 0
    }
}
