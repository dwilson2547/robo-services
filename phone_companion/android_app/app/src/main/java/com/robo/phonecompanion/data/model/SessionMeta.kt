package com.robo.phonecompanion.data.model

import kotlinx.serialization.Serializable

@Serializable
data class SessionMeta(
    val id: String,
    val vehicleId: String,
    val dbcId: String,
    val startTime: String,
    val endTime: String? = null,
    val frameCount: Int = 0,
    val notes: String = "",
)
