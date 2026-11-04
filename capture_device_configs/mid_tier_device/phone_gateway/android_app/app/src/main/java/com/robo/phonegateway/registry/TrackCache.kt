package com.robo.phonegateway.registry

import android.content.Context
import java.io.File

/**
 * Session routes kept on the phone: in memory, and as files for settled sessions
 * (their route no longer changes), so History thumbnails don't refetch.
 */
object TrackCache {
    /** Bump when the registry adds track columns, so routes cached without them are fetched again. */
    private const val FORMAT = 2  // 2: brake_kpa
    private val memory = mutableMapOf<Long, RouteTrack>()
    private var swept = false

    private fun dir(context: Context): File {
        if (!swept) {  // drop routes cached in earlier formats
            context.cacheDir.listFiles { f -> f.isDirectory && (f.name == "tracks" || f.name.startsWith("tracks-v")) && f.name != "tracks-v$FORMAT" }
                ?.forEach { it.deleteRecursively() }
            swept = true
        }
        return File(context.cacheDir, "tracks-v$FORMAT")
    }

    suspend fun get(context: Context, registry: RegistryClient, sessionId: Long, settled: Boolean): RouteTrack {
        memory[sessionId]?.let { return it }
        val file = File(dir(context), "$sessionId.json")
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
