package com.robo.phonecompanion.data.model

data class CanFrame(
    val timestampMs: Long,
    val id: Int,
    val isExtended: Boolean,
    val data: ByteArray,
    // Populated during parsing when firmware embeds capture time; cleared after intra-packet adjustment.
    val firmwareTimestampMs: Long? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CanFrame) return false
        return timestampMs == other.timestampMs &&
            id == other.id &&
            isExtended == other.isExtended &&
            data.contentEquals(other.data)
        // firmwareTimestampMs is an adjustment artifact and excluded from equality
    }

    override fun hashCode(): Int {
        var result = timestampMs.hashCode()
        result = 31 * result + id
        result = 31 * result + isExtended.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}
