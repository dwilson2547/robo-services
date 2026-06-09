package com.robo.phonecompanion.data.model

data class CanFrame(
    val timestampMs: Long,
    val id: Int,
    val isExtended: Boolean,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CanFrame) return false
        return timestampMs == other.timestampMs &&
            id == other.id &&
            isExtended == other.isExtended &&
            data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = timestampMs.hashCode()
        result = 31 * result + id
        result = 31 * result + isExtended.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}
