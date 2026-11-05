package com.robo.phonegateway

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Notification wording, kept free of Android so it is tested. */
object Notices {

    /** The service's notification: what the dongle is actually doing (it used to say "connected" always). */
    fun gateway(capturing: Boolean, dongleState: String): String {
        val dongle = when {
            dongleState.startsWith("connected") -> "Dongle connected"
            dongleState.startsWith("connecting") -> "Connecting to the dongle"
            dongleState.startsWith("scanning") -> "Looking for the dongle"
            dongleState.startsWith("bluetooth off") -> "Bluetooth is off"
            else -> "Dongle not connected"
        }
        return if (capturing) "Recording · $dongle" else dongle
    }

    private val DRIVE = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.US)

    /** A drive by its start (the session id is its start in epoch ms), in the phone's time zone. */
    fun drive(sessionId: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DRIVE.format(Instant.ofEpochMilli(sessionId).atZone(zone))

    fun catchUpProgress(sessionId: Long, sent: Int, total: Int, zone: ZoneId = ZoneId.systemDefault()): String =
        "Uploading drive ${drive(sessionId, zone)}: $sent of $total s"

    fun catchUpDone(seconds: Int, drives: Int): String =
        "Uploaded $seconds s from $drives ${if (drives == 1) "drive" else "drives"}"
}
