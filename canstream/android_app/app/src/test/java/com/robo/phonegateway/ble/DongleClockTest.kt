package com.robo.phonegateway.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DongleClockTest {

    @Test
    fun `bursty delivery does not move evenly emitted samples`() {
        // 50 Hz lines, one per notification, delivered with 0-80 ms of random delay
        val clock = DongleClock()
        val rnd = Random(1)
        val start = 1_000_000L
        val placed = (0 until 500).map { i ->
            val captured = start + i * 20L
            clock.place(listOf((i * 20) % 65536), captured + 5 + rnd.nextLong(0, 80)).single()
        }
        // within 2 ms of the true 20 ms spacing (offset re-tightening), against 0-80 ms of delivery jitter
        val gaps = placed.drop(100).zipWithNext { a, b -> b - a }
        assertTrue("gaps ${gaps.distinct()}", gaps.all { it in 18..22 })
    }

    @Test
    fun `offset follows the least-delayed arrival`() {
        val clock = DongleClock()
        assertEquals(10_050L, clock.place(listOf(0), 10_050).single())
        // 20 ms of dongle time later but only 25 ms of phone time: the first line was
        // at least 25 ms late, so both move onto the tighter bound
        assertEquals(10_025L, clock.place(listOf(20), 10_025).single())
        assertEquals(10_045L, clock.place(listOf(40), 10_090).single()) // a late one does not loosen it
    }

    @Test
    fun `counter wrap is unwrapped across notifications`() {
        val clock = DongleClock()
        val a = clock.place(listOf(65_530), 100_000).single()
        val b = clock.place(listOf(10), 100_016).single()
        assertEquals(16L, b - a)
    }

    @Test
    fun `lines of one notification share the tightened offset`() {
        val clock = DongleClock()
        assertEquals(listOf(99_960L, 100_000L, 99_980L), clock.place(listOf(5000, 5040, 5020), 100_000))
    }

    @Test
    fun `reset starts over for a rebooted dongle`() {
        val clock = DongleClock()
        clock.place(listOf(30_000), 50_000)
        clock.reset()
        assertEquals(50_100L, clock.place(listOf(5), 50_100).single())
    }
}
