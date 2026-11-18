package com.robo.phonegateway.session

import java.io.File

/**
 * Session events not yet acknowledged by the broker, one file each.
 *
 * An event is written here before it is published and removed when the broker
 * acks it, so a session_end sent while offline (or lost with the MQTT client when
 * capture stops) goes out at the start of the next capture instead. Names sort in
 * send order: `<session id>-1-start`, `<session id>-2-end`. Sending one twice is
 * harmless; the consumer keys events on (session, type).
 */
class Outbox(private val dir: File) {

    init {
        dir.mkdirs()
    }

    @Synchronized
    fun put(name: String, json: String) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(json)
        tmp.renameTo(File(dir, "$name.json"))
    }

    @Synchronized
    fun remove(name: String) {
        File(dir, "$name.json").delete()
    }

    /** Pending events, oldest first. */
    @Synchronized
    fun pending(): List<Pair<String, String>> =
        (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .sortedBy { it.name }
            .map { it.name.removeSuffix(".json") to it.readText() }

    companion object {
        fun startName(sessionId: Long) = "$sessionId-1-start"
        fun endName(sessionId: Long) = "$sessionId-2-end"
    }
}
