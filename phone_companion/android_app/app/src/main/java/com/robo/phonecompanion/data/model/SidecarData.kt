package com.robo.phonecompanion.data.model

import kotlinx.serialization.Serializable

@Serializable
data class SidecarData(
    val schema: Int = 1,
    val signals: Map<String, SignalSidecar> = emptyMap(),
    val hiddenIds: Set<String> = emptySet(),
    val blacklist: Set<String> = emptySet(),
)

@Serializable
data class SignalSidecar(
    val verifications: List<SignalVerification> = emptyList(),
)

@Serializable
data class SignalVerification(
    val status: VerificationStatus,
    val vehicleId: String,
    val sessionId: String,
    val timestamp: String,
    val notes: String = "",
)

@Serializable
enum class VerificationStatus { VERIFIED, UNVERIFIED, SUSPECT }
