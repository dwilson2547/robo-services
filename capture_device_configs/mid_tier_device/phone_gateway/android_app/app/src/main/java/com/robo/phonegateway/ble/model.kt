package com.robo.phonegateway.ble

/** One CAN frame from the dongle, as parsed from a NUS `STD/EXT,...` line. */
data class CanFrame(
    val timestampMs: Long,
    val id: Int,
    val isExtended: Boolean,
    val data: ByteArray,
    val firmwareTimestampMs: Long? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CanFrame) return false
        return timestampMs == other.timestampMs && id == other.id &&
            isExtended == other.isExtended && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = timestampMs.hashCode()
        result = 31 * result + id
        result = 31 * result + isExtended.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/**
 * One BNO085 sample from the dongle, parsed from an `IMU,...` NUS line.
 * Quaternion is the absolute rotation vector (sensor→world); accel is
 * gravity-removed linear acceleration (m/s²); gyro is angular rate (rad/s).
 * Timestamps are on the dongle's shared clock (same domain as CAN frames).
 */
data class ImuSample(
    val timestampMs: Long,
    val qw: Float, val qx: Float, val qy: Float, val qz: Float,
    val ax: Float, val ay: Float, val az: Float,
    val gx: Float, val gy: Float, val gz: Float,
    val firmwareTimestampMs: Long? = null,
)
