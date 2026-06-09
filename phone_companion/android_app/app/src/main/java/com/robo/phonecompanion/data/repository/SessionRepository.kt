package com.robo.phonecompanion.data.repository

import com.robo.phonecompanion.data.model.CanFrame
import com.robo.phonecompanion.data.model.SessionMeta
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileWriter

class SessionRepository(private val sessionsDir: File) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun listAll(): List<SessionMeta> {
        if (!sessionsDir.exists()) return emptyList()
        return sessionsDir.listFiles { f -> f.isDirectory }
            ?.mapNotNull { dir ->
                runCatching {
                    json.decodeFromString<SessionMeta>(File(dir, "meta.json").readText())
                }.getOrNull()
            }
            ?.sortedByDescending { it.startTime }
            ?: emptyList()
    }

    fun createSession(meta: SessionMeta): ActiveSession {
        val dir = File(sessionsDir, meta.id)
        dir.mkdirs()
        File(dir, "meta.json").writeText(json.encodeToString(meta))
        return ActiveSession(dir, meta)
    }

    fun loadMeta(sessionId: String): SessionMeta? {
        val file = File(sessionsDir, "$sessionId/meta.json")
        if (!file.exists()) return null
        return runCatching { json.decodeFromString<SessionMeta>(file.readText()) }.getOrNull()
    }

    fun updateMeta(meta: SessionMeta) {
        val file = File(sessionsDir, "${meta.id}/meta.json")
        if (file.exists()) file.writeText(json.encodeToString(meta))
    }

    fun framesFile(sessionId: String) = File(sessionsDir, "$sessionId/frames.log")

    inner class ActiveSession(private val dir: File, initialMeta: SessionMeta) {
        var meta = initialMeta
            private set

        private val writer = FileWriter(File(dir, "frames.log"), true)
        private var frameCount = 0

        // Frame line format: timestampMs,id,ext,dlc,hex_bytes
        fun appendFrame(frame: CanFrame) {
            try {
                val hex = frame.data.joinToString("") { "%02X".format(it) }
                val ext = if (frame.isExtended) "1" else "0"
                writer.write("${frame.timestampMs},0x%08X,$ext,${frame.data.size},$hex\n".format(frame.id))
                frameCount++
            } catch (_: Exception) { }
        }

        fun close(endTime: String) {
            writer.flush()
            writer.close()
            meta = meta.copy(endTime = endTime, frameCount = frameCount)
            File(dir, "meta.json").writeText(json.encodeToString(meta))
        }
    }
}
