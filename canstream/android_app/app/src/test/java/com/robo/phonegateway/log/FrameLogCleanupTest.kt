package com.robo.phonegateway.log

import com.robo.phonegateway.log.FrameLogCleanup.Known
import com.robo.phonegateway.log.FrameLogCleanup.LogFile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FrameLogCleanupTest {
    @get:Rule val tmp = TemporaryFolder()

    private val day = 86_400_000L
    private val now = 1_800_000_000_000L

    @Test fun onlyCompleteSessionsEndedThirtyDaysAgoGo() {
        val logs = (1L..7L).map { LogFile(it, 100) }
        val known = mapOf(
            1L to Known("complete", now - 31 * day),      // goes
            2L to Known("complete", now - 30 * day),      // goes: 30 days exactly
            3L to Known("complete", now - 29 * day),      // too recent
            4L to Known("incomplete", now - 90 * day),    // may need a re-send
            5L to Known("unterminated", null),
            6L to Known("complete", now - 60 * day),      // being recorded (can't really be, but never)
            // 7: unknown to the registry
        )
        assertEquals(listOf(1L, 2L), FrameLogCleanup.toDelete(logs, known, now, recording = setOf(6L)).map { it.sessionId })
    }

    @Test fun listsOnlySessionLogs() {
        val dir = tmp.newFolder("frames")
        dir.resolve("session-1793745445702.bin").writeBytes(ByteArray(10))
        dir.resolve("session-5.bin").writeBytes(ByteArray(3))
        dir.resolve("notes.txt").writeText("x")
        dir.resolve("session-x.bin").writeText("x")
        assertEquals(listOf(LogFile(5, 3), LogFile(1793745445702, 10)), FrameLogCleanup.list(dir))
    }
}
