package com.robo.phonecompanion.data.model

data class DbcMessage(
    // Raw DBC id — bit 31 set for extended frames (DBC convention)
    val rawId: Int,
    val name: String,
    val dlc: Int,
    val transmitter: String = "Vector__XXX",
    val signals: List<DbcSignal> = emptyList(),
    val comment: String? = null,
) {
    val isExtended: Boolean get() = (rawId and 0x80000000.toInt()) != 0
    val canId: Int get() = rawId and 0x1FFFFFFF
}
