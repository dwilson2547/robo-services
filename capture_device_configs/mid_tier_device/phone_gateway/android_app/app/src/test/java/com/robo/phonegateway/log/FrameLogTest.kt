package com.robo.phonegateway.log

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FrameLogTest {

    private val dir: File = Files.createTempDirectory("framelog").toFile()

    @Test
    fun `round trips and splits files by utc day of capture`() {
        val log = FrameLog(dir)
        val day1 = 1_793_000_000_000L // 2026-10-26 UTC
        log.append(day1, byteArrayOf(1, 2, 3))
        log.append(day1 + 1000, byteArrayOf(4))
        log.append(day1 + 86_400_000, byteArrayOf(5, 6))
        log.close()
        val files = dir.listFiles()!!.sortedBy { it.name }
        assertEquals(listOf("frames-20261026.bin", "frames-20261027.bin"), files.map { it.name })
        val first = FrameLog.read(files[0])
        assertEquals(2, first.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), first[0])
        assertArrayEquals(byteArrayOf(5, 6), FrameLog.read(files[1]).single())
    }

    @Test
    fun `a truncated last record is ignored`() {
        val log = FrameLog(dir)
        log.append(0, byteArrayOf(9, 9))
        log.close()
        val file = dir.listFiles()!!.single()
        file.appendBytes(byteArrayOf(0, 0, 0, 10, 1, 2)) // header says 10, only 2 written
        assertArrayEquals(byteArrayOf(9, 9), FrameLog.read(file).single())
    }
}
