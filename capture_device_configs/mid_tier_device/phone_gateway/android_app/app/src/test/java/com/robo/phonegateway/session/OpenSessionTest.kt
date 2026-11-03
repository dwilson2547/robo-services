package com.robo.phonegateway.session

import com.robo.phonegateway.log.FrameLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import racelogger.v1.Telemetry.TelemetryFrame
import java.io.File
import java.nio.file.Files

class OpenSessionTest {

    private val dir: File = Files.createTempDirectory("session").toFile()

    private fun frame(sessionId: Long, atMs: Long) = TelemetryFrame.newBuilder()
        .setVersion(1).setDeviceId("phone-1").setSessionId(sessionId).setBaseTimestampUs(atMs * 1000)
        .build().toByteArray()

    @Test
    fun `marker round trips and clears`() {
        val open = OpenSession(File(dir, "session-open"))
        assertNull(open.read())
        open.write(OpenSession.Marker(1_793_000_000_000, "phone-1"))
        assertEquals(OpenSession.Marker(1_793_000_000_000, "phone-1"), open.read())
        open.clear()
        assertNull(open.read())
    }

    @Test
    fun `recovery counts only that session's frames, across a day boundary`() {
        val frames = File(dir, "frames")
        val log = FrameLog(frames)
        val session = 1_793_059_190_000L // 23:59:50 UTC on 2026-10-26
        log.append(session - 60_000, frame(1, session - 60_000)) // an earlier session, same day
        for (i in 0 until 20) log.append(session + i * 1000, frame(session, session + i * 1000)) // crosses midnight
        log.close()

        val end = OpenSession.recover(OpenSession.Marker(session, "phone-1"), frames, nowMs = 0)
        assertEquals(20, end.frames)
        assertEquals(session * 1000, end.firstFrameUs)
        assertEquals((session + 19_000) * 1000, end.lastFrameUs)
        assertEquals(session + 20_000, end.endedAtMs)
        assertEquals("app_killed_recovered", end.reason)
        assertEquals(2, frames.listFiles { f -> f.name.endsWith(".bin") }!!.size)
    }
}
