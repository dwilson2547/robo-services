package com.robo.phonegateway.ble

/**
 * Explains IMU gaps by walking the dongle's IMU lines in stream order (BLE
 * delivers them in capture order) and comparing each step in the dongle's own
 * counter with the step in placed phone time:
 *
 * - a large **dongle** step means the dongle emitted late or lines never reached
 *   the app (firmware or BLE);
 * - a normal dongle step with a placed step that differs from it means
 *   [DongleClock] moved its offset between the two lines (phone-side placement).
 */
class ImuGapTracker(
    private val gapMs: Long = 40,
    private val clockJumpMs: Long = 10,
) {
    sealed interface Event {
        /** Dongle time between two consecutive IMU lines was [dongleStepMs]. */
        data class DongleGap(val atMs: Long, val dongleStepMs: Long) : Event

        /** Placed times moved [placedStepMs] while dongle time moved [dongleStepMs]. */
        data class ClockJump(val atMs: Long, val dongleStepMs: Long, val placedStepMs: Long) : Event
    }

    var dongleGaps = 0L
        private set
    var clockJumps = 0L
        private set

    private var prevCounter: Int? = null
    private var prevTimeMs = 0L

    fun reset() {
        prevCounter = null
    }

    /** Feed one notification's IMU samples, in line order; returns what looked wrong. */
    fun add(samples: List<ImuSample>): List<Event> {
        val events = mutableListOf<Event>()
        for (s in samples) {
            val counter = s.counter ?: continue
            val prev = prevCounter
            if (prev != null) {
                val dongleStep = DongleLines.signedOffset(counter, prev)
                val placedStep = s.timeMs - prevTimeMs
                if (dongleStep > gapMs) {
                    dongleGaps++
                    events += Event.DongleGap(s.timeMs, dongleStep)
                } else if (kotlin.math.abs(placedStep - dongleStep) > clockJumpMs) {
                    clockJumps++
                    events += Event.ClockJump(s.timeMs, dongleStep, placedStep)
                }
            }
            prevCounter = counter
            prevTimeMs = s.timeMs
        }
        return events
    }
}
