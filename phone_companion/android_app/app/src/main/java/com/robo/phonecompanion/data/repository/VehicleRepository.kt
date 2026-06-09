package com.robo.phonecompanion.data.repository

import com.robo.phonecompanion.data.model.VehicleProfile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class VehicleRepository(private val vehiclesDir: File) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun loadAll(): List<VehicleProfile> {
        if (!vehiclesDir.exists()) return emptyList()
        return vehiclesDir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f ->
                runCatching { json.decodeFromString<VehicleProfile>(f.readText()) }.getOrNull()
            }
            ?.sortedBy { it.nickname }
            ?: emptyList()
    }

    fun load(id: String): VehicleProfile? {
        val file = fileFor(id)
        if (!file.exists()) return null
        return runCatching { json.decodeFromString<VehicleProfile>(file.readText()) }.getOrNull()
    }

    fun save(profile: VehicleProfile) {
        vehiclesDir.mkdirs()
        fileFor(profile.id).writeText(json.encodeToString(profile))
    }

    fun delete(id: String) {
        fileFor(id).delete()
    }

    private fun fileFor(id: String) = File(vehiclesDir, "$id.json")
}
