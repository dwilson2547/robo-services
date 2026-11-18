package com.robo.phonegateway.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class ImuGapTrackerTest {

    private fun imu(timeMs: Long, counter: Int) =
        ImuSample(timeMs, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, counter)

    @Test
    fun `steady 50 Hz lines raise nothing`() {
        val t = ImuGapTracker()
        val events = t.add((0 until 50).map { imu(1_000L + it * 20, 65_000 + it * 20) }) // wraps the counter
        assertEquals(emptyList<ImuGapTracker.Event>(), events)
        assertEquals(0, t.dongleGaps + t.clockJumps)
    }

    @Test
    fun `a missing run of lines shows as a dongle gap`() {
        val t = ImuGapTracker()
        t.add(listOf(imu(1_000, 100), imu(1_020, 120)))
        val events = t.add(listOf(imu(1_100, 200)))
        assertEquals(listOf(ImuGapTracker.Event.DongleGap(1_100, 80)), events)
        assertEquals(1, t.dongleGaps)
    }

    @Test
    fun `placement moving while dongle time is steady shows as a clock jump`() {
        val t = ImuGapTracker()
        t.add(listOf(imu(1_000, 100)))
        val events = t.add(listOf(imu(1_065, 120))) // dongle +20, placed +65
        assertEquals(listOf(ImuGapTracker.Event.ClockJump(1_065, 20, 65)), events)
        assertEquals(1, t.clockJumps)
    }

    @Test
    fun `reset forgets the previous line`() {
        val t = ImuGapTracker()
        t.add(listOf(imu(1_000, 100)))
        t.reset()
        assertEquals(emptyList<ImuGapTracker.Event>(), t.add(listOf(imu(9_000, 5_000))))
    }
}
