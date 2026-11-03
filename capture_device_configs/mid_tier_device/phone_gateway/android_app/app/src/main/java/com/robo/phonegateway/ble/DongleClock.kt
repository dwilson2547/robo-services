package com.robo.phonegateway.ble

/**
 * Maps the dongle's capture counter (millis() % 65536 on every line) onto this
 * phone's wall clock.
 *
 * Receive time is not a usable sample time: Android delivers BLE notifications
 * in bursts, so evenly emitted 50 Hz IMU lines arrive tens of milliseconds
 * apart or bunched together. Instead the counter is unwrapped into a continuous
 * dongle clock, and the dongle-to-phone offset is estimated as the smallest
 * (receive time - dongle time) seen: every line arrives at or after its capture,
 * so the least-delayed one bounds the offset most tightly.
 *
 * The estimate may rise by [driftMsPerSecond] per second of dongle time, so a
 * phone clock that runs fast relative to the dongle's (or a wall-clock step)
 * is followed instead of pinning the offset to an old minimum. If the counter
 * is not seen for [resyncAfterMs] it can no longer be unwrapped safely, and the
 * clock starts over; call [reset] on every new connection, because a dongle that
 * rebooted restarts its counter.
 */
class DongleClock(
    private val driftMsPerSecond: Double = 1.0,
    private val resyncAfterMs: Long = 20_000,
) {
    private var lastCounter = -1
    private var dongleMs = 0L
    private var maxDongleMs = 0L
    private var offsetMs = 0.0
    private var lastReceivedAtMs = Long.MIN_VALUE

    fun reset() {
        lastCounter = -1
    }

    /**
     * Wall-clock capture times for the [counters] of one notification (in line
     * order), received at [receivedAtMs]. Every counter tightens the offset before
     * any time is computed, so a notification's lines are placed consistently.
     */
    fun place(counters: List<Int>, receivedAtMs: Long): List<Long> {
        if (counters.isEmpty()) return emptyList()
        if (lastCounter < 0 || receivedAtMs - lastReceivedAtMs > resyncAfterMs) {
            lastCounter = counters.first()
            dongleMs = 0
            maxDongleMs = 0
            offsetMs = receivedAtMs.toDouble()
        }
        val dongleTimes = counters.map { counter ->
            val step = DongleLines.signedOffset(counter, lastCounter)
            dongleMs += step
            lastCounter = counter
            // let the bound rise slowly with elapsed dongle time, then tighten it;
            // lines are not strictly in capture order, so count only new maxima
            if (dongleMs > maxDongleMs) {
                offsetMs += (dongleMs - maxDongleMs) * driftMsPerSecond / 1000.0
                maxDongleMs = dongleMs
            }
            offsetMs = minOf(offsetMs, (receivedAtMs - dongleMs).toDouble())
            dongleMs
        }
        lastReceivedAtMs = maxOf(lastReceivedAtMs, receivedAtMs)
        return dongleTimes.map { it + offsetMs.toLong() }
    }
}
