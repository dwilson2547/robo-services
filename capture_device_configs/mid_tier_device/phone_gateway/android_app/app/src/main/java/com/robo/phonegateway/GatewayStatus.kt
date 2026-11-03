package com.robo.phonegateway

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the capture service is doing, for the UI. One per process. */
object GatewayStatus {

    data class Snapshot(
        val running: Boolean = false,
        val dongle: String = "idle",
        val broker: String = "idle",
        val framesLogged: Long = 0,
        val framesAcked: Long = 0,
        val framesNotSent: Long = 0,
        val imuIncomplete: Long = 0,
        val lateSamples: Long = 0,
        val badLines: Long = 0,
        val lastError: String? = null,
    )

    private val state = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = state.asStateFlow()

    fun update(change: (Snapshot) -> Snapshot) = state.update(change)
}
