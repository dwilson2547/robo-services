package com.robo.phonecompanion.data.model

data class Dbc(
    val version: String = "",
    // keyed by rawId (DBC convention, bit 31 set for extended)
    val messages: Map<Int, DbcMessage> = emptyMap(),
) {
    fun messageForCanId(canId: Int): DbcMessage? =
        messages[canId] ?: messages[canId or 0x80000000.toInt()]
}
