package com.robo.phonegateway.telemetry

import com.robo.phonegateway.ble.CanFrame
import com.robo.phonegateway.ble.ImuSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameAssemblerTest {

    private val second = 1_793_000_000_000L // a whole second, epoch ms

    private fun imu(t: Long, ax: Float = 9.80665f, gz: Float = 1f) =
        ImuSample(t, 1f, 0f, 0f, 0f, ax, 0f, 0f, 0f, 0f, gz)

    private fun fullSecondOfImu(start: Long) = (0 until 50).map { imu(start + it * 20 + 3) }

    @Test
    fun `full second of imu becomes a 50 point grid with proto scaling`() {
        val a = FrameAssembler("phone-1", sessionId = 1_792_999_000_000)
        a.addImu(fullSecondOfImu(second))
        val frames = a.drain(second + 3000)
        val f = frames.single().frame
        assertEquals(second * 1000, f.baseTimestampUs)
        assertEquals("phone-1", f.deviceId)
        assertEquals(1_792_999_000_000, f.sessionId)
        assertEquals(50, f.imu.count)
        assertEquals(20_000, f.imu.samplePeriodUs)
        assertEquals(50, f.imu.accelXCount)
        assertEquals(1000, f.imu.getAccelX(0)) // 1 g
        assertEquals(5730, f.imu.getGyroZ(0)) // 1 rad/s = 57.30 deg/s
        assertEquals(10, f.imu.quatWCount)
        assertEquals(100_000, f.imu.quatPeriodUs)
        assertEquals(32767, f.imu.getQuatW(0))
        assertFalse(frames.single().imuIncomplete)
        assertTrue(f.imu.missingList.isEmpty())
    }

    @Test
    fun `mostly missing second of imu is left out and flagged`() {
        val a = FrameAssembler("phone-1")
        a.addImu(fullSecondOfImu(second).drop(30)) // BLE connected 600 ms in
        a.addCan(listOf(CanFrame(second + 700, 0x1E5, false, byteArrayOf(1))))
        val out = a.drain(second + 3000).single()
        assertFalse(out.frame.hasImu())
        assertTrue(out.imuIncomplete)
        assertEquals(1, out.frame.can.messagesCount)
    }

    @Test
    fun `a gap inside the second keeps the section and lists the filled points`() {
        val a = FrameAssembler("phone-1")
        // lines 20..24 lost: grid points 21..24 have no sample within 20 ms (20 still has 19's, 17 ms away)
        a.addImu(fullSecondOfImu(second).filterIndexed { i, _ -> i !in 20..24 })
        val out = a.drain(second + 3000).single()
        assertTrue(out.frame.hasImu())
        assertEquals(50, out.frame.imu.accelXCount)
        assertEquals(listOf(21, 22, 23, 24), out.frame.imu.missingList)
        assertEquals(4, out.imuMissingPoints)
        assertFalse(out.imuIncomplete)
    }

    @Test
    fun `half a second of imu is still kept`() {
        val a = FrameAssembler("phone-1")
        a.addImu(fullSecondOfImu(second).drop(25))
        val out = a.drain(second + 3000).single()
        assertTrue(out.frame.hasImu())
        assertEquals((0..24).toList(), out.frame.imu.missingList)
    }

    @Test
    fun `can and gps carry offsets from the frame base`() {
        val a = FrameAssembler("phone-1")
        a.addCan(listOf(CanFrame(second + 250, 0x10242040, true, byteArrayOf(1, 2))))
        a.addGps(GpsFix(second + 400, 42.1234567, -83.7654321, 250.5, 12.34f, 359.99f))
        val f = a.drain(second + 3000).single().frame
        val m = f.can.getMessages(0)
        assertEquals(250_000, m.dtUs)
        assertEquals(0x10242040, m.canId)
        assertEquals(1, f.gps.count)
        assertEquals(400_000, f.gps.getDtUs(0))
        assertEquals(421234567, f.gps.getLat1E7(0))
        assertEquals(-837654321, f.gps.getLon1E7(0))
        assertEquals(250500, f.gps.getAltMm(0))
        assertEquals(1234, f.gps.getSpeedCms(0))
        assertEquals(35999, f.gps.getHeadingCdeg(0))
    }

    @Test
    fun `a second stays open for the grace period, then late samples are dropped`() {
        val a = FrameAssembler("phone-1", graceMs = 1500)
        a.addCan(listOf(CanFrame(second + 10, 0x100, false, byteArrayOf())))
        assertTrue(a.drain(second + 2400).isEmpty()) // 1000 + 1500 not yet passed
        a.addCan(listOf(CanFrame(second + 900, 0x101, false, byteArrayOf()))) // late but in grace
        val out = a.drain(second + 2500)
        assertEquals(2, out.single().frame.can.messagesCount)
        a.addCan(listOf(CanFrame(second + 950, 0x102, false, byteArrayOf())))
        assertEquals(1, a.lateSamples)
        assertTrue(a.drain(second + 10_000).isEmpty())
    }

    @Test
    fun `seconds are emitted in order and empty seconds are skipped`() {
        val a = FrameAssembler("phone-1")
        a.addCan(listOf(CanFrame(second + 3_100, 0x100, false, byteArrayOf())))
        a.addCan(listOf(CanFrame(second + 100, 0x100, false, byteArrayOf())))
        val out = a.drain(second + 10_000)
        assertEquals(listOf(second, second + 3000), out.map { it.windowStartMs })
    }
}
