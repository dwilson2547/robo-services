package com.robo.phonecompanion.data.repository

import com.robo.phonecompanion.data.model.SidecarData
import com.robo.phonecompanion.data.model.SignalSidecar
import com.robo.phonecompanion.data.model.SignalVerification
import com.robo.phonecompanion.data.model.VerificationStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class SidecarRepository(private val sidecarFile: File) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun load(): SidecarData {
        if (!sidecarFile.exists()) return SidecarData()
        return runCatching { json.decodeFromString<SidecarData>(sidecarFile.readText()) }
            .getOrDefault(SidecarData())
    }

    fun save(data: SidecarData) {
        sidecarFile.parentFile?.mkdirs()
        sidecarFile.writeText(json.encodeToString(data))
    }

    fun addVerification(
        signalName: String,
        status: VerificationStatus,
        vehicleId: String,
        sessionId: String,
        timestamp: String,
        notes: String = "",
    ) {
        val current = load()
        val existing = current.signals[signalName] ?: SignalSidecar()
        val verification = SignalVerification(status, vehicleId, sessionId, timestamp, notes)
        // Replace existing entry for the same vehicle, or append
        val updated = existing.verifications.filterNot { it.vehicleId == vehicleId } + verification
        val newSignals = current.signals + (signalName to existing.copy(verifications = updated))
        save(current.copy(signals = newSignals))
    }

    fun setHidden(id: String, hidden: Boolean) {
        val current = load()
        val updated = if (hidden) current.hiddenIds + id else current.hiddenIds - id
        save(current.copy(hiddenIds = updated))
    }

    fun setBlacklisted(id: String, blacklisted: Boolean) {
        val current = load()
        val updated = if (blacklisted) current.blacklist + id else current.blacklist - id
        save(current.copy(blacklist = updated))
    }

    fun clearHidden() {
        val current = load()
        save(current.copy(hiddenIds = emptySet()))
    }
}
