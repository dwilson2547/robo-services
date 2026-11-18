package com.robo.phonegateway.telemetry

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Drive tab's live distance and speed, from the phone's own GPS (the phone does
 * not decode CAN). Distance only grows between fixes that are both accurate and
 * while moving, so a parked phone's drift adds nothing (the drive summary learned
 * that the hard way).
 */
class TripMeter(
    private val maxAccuracyM: Float = 25f,
    private val movingMps: Float = 1f,
) {
    var distanceM: Double = 0.0
        private set
    /** The latest fix's speed, m/s; null until a fix reports one. */
    var speedMps: Float? = null
        private set
    private var last: Pair<Double, Double>? = null

    fun add(latDeg: Double, lonDeg: Double, accuracyM: Float?, speedMps: Float?) {
        this.speedMps = speedMps ?: this.speedMps
        if (accuracyM == null || accuracyM > maxAccuracyM) return // too vague to measure from
        val prev = last
        last = latDeg to lonDeg
        if (prev != null && (speedMps ?: 0f) >= movingMps) distanceM += haversineM(prev.first, prev.second, latDeg, lonDeg)
    }

    companion object {
        fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dp = p2 - p1
            val dl = Math.toRadians(lon2 - lon1)
            val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * 6_371_008.8 * asin(sqrt(a))
        }
    }
}
