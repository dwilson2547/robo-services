package com.robo.phonecompanion.data.model

import kotlinx.serialization.Serializable

@Serializable
data class VehicleProfile(
    val id: String,
    val nickname: String,
    val year: Int,
    val make: String,
    val model: String,
    val engine: String = "",
    val notes: String = "",
    val dbcIds: List<String> = emptyList(),
)
