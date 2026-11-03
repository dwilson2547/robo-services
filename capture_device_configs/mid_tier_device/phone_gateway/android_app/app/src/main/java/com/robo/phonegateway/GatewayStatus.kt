package com.robo.phonegateway

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the capture service is doing, for the UI. One per process. */
object GatewayStatus {

    data class Snapshot(
        /** Capturing (GPS, frames, publishing). */
        val running: Boolean = false,
        val dongle: String = "idle",
        /** BLE connected with notifications on. */
        val dongleConnected: Boolean = false,
        /** Wall-clock ms of the last dongle notification; 0 = none yet. */
        val lastDongleDataMs: Long = 0,
        /** Wall-clock ms of the last GPS fix while capturing; 0 = none. */
        val lastFixMs: Long = 0,
        val lastAnchor: String? = null,
        val broker: String = "idle",
        val framesLogged: Long = 0,
        val framesAcked: Long = 0,
        val framesNotSent: Long = 0,
        /** Seconds whose IMU section was left out (over half the grid missing). */
        val imuIncomplete: Long = 0,
        /** IMU grid points filled from a neighbour, over the capture. */
        val imuMissingPoints: Long = 0,
        /** Gaps between consecutive IMU lines: in dongle time, or added by placement. */
        val imuDongleGaps: Long = 0,
        val imuClockJumps: Long = 0,
        val lateSamples: Long = 0,
        /** IMU samples and longest gap (ms) in the last closed second. */
        val lastImuSamples: Int = 0,
        val lastImuMaxGapMs: Long = 0,
        val badLines: Long = 0,
        val truncatedNotifications: Long = 0,
        val bleMtu: Int = 0,
        /** Last 5-second diagnostics line from the dongle. */
        val dongleStats: String? = null,
        val lastError: String? = null,
    )

    private val state = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = state.asStateFlow()

    fun update(change: (Snapshot) -> Snapshot) = state.update(change)
}
