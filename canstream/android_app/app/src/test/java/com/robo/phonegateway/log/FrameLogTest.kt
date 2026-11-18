package com.robo.phonegateway.log

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FrameLogTest {

    private val dir: File = Files.createTempDirectory("framelog").toFile()

    @Test
    fun `round trips into one file per session`() {
        val log = FrameLog(dir, 1_793_000_000_000)
        log.append(byteArrayOf(1, 2, 3))
        log.append(byteArrayOf(4))
        log.close()
        FrameLog(dir, 1_793_000_500_000).apply { append(byteArrayOf(5, 6)); close() }
        val files = dir.listFiles()!!.sortedBy { it.name }
        assertEquals(listOf("session-1793000000000.bin", "session-1793000500000.bin"), files.map { it.name })
        val first = FrameLog.read(files[0])
        assertEquals(2, first.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), first[0])
        assertArrayEquals(byteArrayOf(5, 6), FrameLog.read(files[1]).single())
    }

    @Test
    fun `a truncated last record is ignored`() {
        val log = FrameLog(dir, 1)
        log.append(byteArrayOf(9, 9))
        log.close()
        log.file.appendBytes(byteArrayOf(0, 0, 0, 10, 1, 2)) // header says 10, only 2 written
        assertArrayEquals(byteArrayOf(9, 9), FrameLog.read(log.file).single())
    }

    @Test
    fun `trim deletes oldest first, day files before sessions, and keeps the protected ones`() {
        fun file(name: String, size: Int) = File(dir, name).apply { writeBytes(ByteArray(size)) }
        val day = file("frames-20261103.bin", 100)
        val old = file("session-1793000000000.bin", 100)
        val unclosed = file("session-1793000500000.bin", 100)
        val current = file("session-1793001000000.bin", 100)
        val deleted = FrameLog.trim(dir, keep = setOf(unclosed, current), maxBytes = 150)
        assertEquals(listOf(day, old), deleted)
        assertEquals(setOf(unclosed.name, current.name), dir.list()!!.toSet())
    }
}
