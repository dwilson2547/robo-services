package com.robo.phonecompanion.data.model

enum class ByteOrder { INTEL, MOTOROLA }

data class DbcSignal(
    val name: String,
    val startBit: Int,
    val length: Int,
    val byteOrder: ByteOrder,
    val signed: Boolean,
    val factor: Double,
    val offset: Double,
    val min: Double,
    val max: Double,
    val unit: String,
    val comment: String? = null,
    val valueDescriptions: Map<Long, String> = emptyMap(),
)
