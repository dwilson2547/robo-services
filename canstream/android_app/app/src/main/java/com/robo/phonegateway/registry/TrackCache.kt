package com.robo.phonegateway.registry

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Session routes kept on the phone: in memory, and as files for settled sessions
 * (their route no longer changes), so History thumbnails don't refetch.
 */
object TrackCache {
    /** Bump when the registry adds track columns, so routes cached without them are fetched again. */
    private const val FORMAT = 2  // 2: brake_kpa
    /** Most recently used routes; bounded, since every card of a long history would otherwise stay in the heap. */
    private const val MEMORY_MAX = 40
    private val memory = object : LinkedHashMap<Long, RouteTrack>(MEMORY_MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, RouteTrack>) = size > MEMORY_MAX
    }
    private fun cached(id: Long): RouteTrack? = synchronized(memory) { memory[id] }
    private fun remember(id: Long, t: RouteTrack) { synchronized(memory) { memory[id] = t } }
    private var swept = false

    private fun dir(context: Context): File {
        if (!swept) {  // drop routes cached in earlier formats
            context.cacheDir.listFiles { f -> f.isDirectory && (f.name == "tracks" || f.name.startsWith("tracks-v")) && f.name != "tracks-v$FORMAT" }
                ?.forEach { it.deleteRecursively() }
            swept = true
        }
        return File(context.cacheDir, "tracks-v$FORMAT")
    }

    /** Disk read and JSON parse run off the main thread: a history list asks for one per visible card. */
    suspend fun get(context: Context, registry: RegistryClient, sessionId: Long, settled: Boolean): RouteTrack {
        cached(sessionId)?.let { return it }
        val file = File(dir(context), "$sessionId.json")
        withContext(Dispatchers.IO) {
            if (file.exists()) runCatching { RouteTrack.fromJson(file.readText()) }.getOrNull() else null
        }?.let { remember(sessionId, it); return it }
        val text = registry.trackJson(sessionId)
        val track = withContext(Dispatchers.Default) { RouteTrack.fromJson(text) }
        if (settled) {
            withContext(Dispatchers.IO) {
                file.parentFile?.mkdirs()
                file.writeText(text)
            }
            remember(sessionId, track)
        }
        return track
    }

    /** Drop a session's cached route (the drive was deleted). */
    fun forget(context: Context, sessionId: Long) {
        synchronized(memory) { memory.remove(sessionId) }
        File(dir(context), "$sessionId.json").delete()
    }
}
