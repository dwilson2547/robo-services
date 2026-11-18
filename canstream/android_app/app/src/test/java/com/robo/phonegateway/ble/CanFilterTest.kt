package com.robo.phonegateway.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanFilterTest {
    @Test
    fun `parses hex ids with or without 0x, separated by commas or spaces`() {
        assertEquals(listOf(0x1E5, 0xC9, 0x10242040), CanFilter.parse("1E5, 0xC9 10242040"))
    }

    @Test
    fun `empty text means every id`() {
        assertEquals(emptyList<Int>(), CanFilter.parse("  "))
        assertEquals("FILTER:", CanFilter.command(emptyList()))
    }

    @Test
    fun `rejects non-hex, over-29-bit and too many ids`() {
        assertNull(CanFilter.parse("1E5, xyz"))
        assertNull(CanFilter.parse("20000000"))
        assertNull(CanFilter.parse((1..65).joinToString(",") { Integer.toHexString(it) }))
    }

    @Test
    fun `command matches the firmware's FILTER syntax`() {
        assertEquals("FILTER:1E5,C9,10242040", CanFilter.command(listOf(0x1E5, 0xC9, 0x10242040)))
    }
}
