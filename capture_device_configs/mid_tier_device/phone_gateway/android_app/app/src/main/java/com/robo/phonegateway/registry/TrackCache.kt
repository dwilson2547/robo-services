package com.robo.phonegateway.registry

import android.content.Context
import java.io.File

/**
 * Session routes kept on the phone: in memory, and as files for settled sessions
 * (their route no longer changes), so History thumbnails don't refetch.
 */
object TrackCache {
    private val memory = mutableMapOf<Long, RouteTrack>()

    suspend fun get(context: Context, registry: RegistryClient, sessionId: Long, settled: Boolean): RouteTrack {
        memory[sessionId]?.let { return it }
        val file = File(File(context.cacheDir, "tracks"), "$sessionId.json")
        if (file.exists()) {
            runCatching { RouteTrack.fromJson(file.readText()) }.getOrNull()?.let { memory[sessionId] = it; return it }
        }
        val text = registry.trackJson(sessionId)
        val track = RouteTrack.fromJson(text)
        if (settled) {
            file.parentFile?.mkdirs()
            file.writeText(text)
            memory[sessionId] = track
        }
        return track
    }
}
