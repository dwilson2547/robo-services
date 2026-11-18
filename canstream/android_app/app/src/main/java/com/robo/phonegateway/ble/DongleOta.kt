package com.robo.phonegateway.ble

import android.content.Context

/**
 * Firmware update over BLE, against the dbc-forge firmware's OTA service:
 *
 *   control  write "START:<bytes>"  -> status "READY"
 *   data     the image in chunks of at most ATT MTU - 3 bytes, each acknowledged
 *   control  write "COMMIT"         -> status "VERIFYING", then "OK" or "ERROR:<reason>"
 *
 * The dongle checks the image (magic and SHA-256) before it switches partitions and
 * restarts, so an image cut short or corrupted is refused and the old one keeps running.
 * "ABORT" on the control characteristic drops a partial upload.
 *
 * "OK" only means the image passed the dongle's check. The dongle then restarts, and
 * from firmware 1.7.2 it keeps the image only once a phone reconnects; one that can't
 * serve BLE is rolled back on its next reset (verified on the bench, 2026-11-04). So an
 * update counts as done only when the dongle reconnects on the target version.
 * Firmware up to 1.7.0 restarts inside the COMMIT write: COMMIT is never acknowledged
 * and "OK" may not arrive, which the reconnect check also covers.
 */
object DongleOta {

    /** The GATT side, so the protocol can be tested without a dongle. */
    interface Link {
        /** Write to the control characteristic; false if the write failed. */
        suspend fun control(command: String): Boolean
        /** Write one chunk to the data characteristic and wait for its acknowledgement. */
        suspend fun data(chunk: ByteArray): Boolean
        /** The next status notification, [DISCONNECTED] if the link dropped, or null after [timeoutMs]. */
        suspend fun status(timeoutMs: Long): String?
        /** Drop status notifications received so far. */
        fun clearStatus()
        /** The firmware version the dongle reports after it next reconnects, or null after [timeoutMs]. */
        suspend fun reconnectedVersion(timeoutMs: Long): String?
    }

    /** What [Link.status] returns once the link has dropped. */
    const val DISCONNECTED = "<disconnected>"

    sealed class Progress {
        data class Sending(val sent: Int, val total: Int) : Progress()
        object Verifying : Progress()
        /** Accepted; waiting for the dongle to reconnect on the new image. */
        object Restarting : Progress()
    }

    class Failed(message: String) : Exception(message)

    /** The firmware image this app carries, or null if none was bundled. */
    class Bundled(val version: String, val image: ByteArray)

    fun bundled(context: Context): Bundled? = runCatching {
        val version = bundledVersion(context) ?: return null
        Bundled(version, context.assets.open("dongle/firmware.bin").use { it.readBytes() })
    }.getOrNull()

    /** The bundled firmware's version, without loading the image. */
    fun bundledVersion(context: Context): String? = runCatching {
        context.assets.open("dongle/version.txt").use { it.readBytes().toString(Charsets.UTF_8).trim() }
    }.getOrNull()

    /** True if [bundled] is a different, higher version than [device] (dotted numbers). */
    fun isNewer(bundled: String, device: String?): Boolean {
        if (device == null) return false
        val a = parts(bundled) ?: return false
        val b = parts(device) ?: return true // unreadable version on the dongle: offer the update
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parts(v: String): List<Int>? =
        v.trim().split('.').map { it.toIntOrNull() ?: return null }.takeIf { it.isNotEmpty() }

    /**
     * Sends [image] and commits it. Throws [Failed] with the reason; on any failure after
     * START it sends ABORT so the dongle is not left mid-upload.
     */
    suspend fun upload(link: Link, image: ByteArray, version: String, chunkSize: Int, onProgress: (Progress) -> Unit) {
        require(image.isNotEmpty()) { "empty image" }
        val size = chunkSize.coerceIn(20, 512)
        link.clearStatus()
        if (!link.control("START:${image.size}")) throw Failed("could not send START")
        val ready = link.status(START_TIMEOUT_MS)
        if (ready != "READY") throw Failed(if (ready == null) "dongle did not answer START" else "dongle: $ready")
        try {
            var offset = 0
            while (offset < image.size) {
                val end = minOf(offset + size, image.size)
                if (!link.data(image.copyOfRange(offset, end))) throw Failed("write failed at byte $offset")
                offset = end
                onProgress(Progress.Sending(offset, image.size))
            }
            link.clearStatus() // PROGRESS lines sent while uploading
            onProgress(Progress.Verifying)
            link.control("COMMIT") // not acknowledged by firmware <= 1.7.0: the answer is the status
            commit@ while (true) {
                val s = link.status(COMMIT_TIMEOUT_MS)
                when {
                    // OK: accepted, restarting; null / DISCONNECTED: restarted (or gone)
                    s == "OK" || s == null || s == DISCONNECTED -> break@commit
                    s == "VERIFYING" || s.startsWith("PROGRESS:") -> continue@commit
                    else -> throw Failed("dongle: $s")
                }
            }
            onProgress(Progress.Restarting)
            val back = link.reconnectedVersion(RECONNECT_TIMEOUT_MS)
            if (back == version) return
            throw Failed(
                if (back == null) "the dongle hasn't reconnected; if $version can't, the dongle returns to its previous firmware on its next restart"
                else "the dongle came back on $back: $version was rolled back",
            )
        } catch (e: Failed) {
            link.control("ABORT") // harmless if the dongle already dropped the upload
            throw e
        }
    }

    private const val START_TIMEOUT_MS = 10_000L
    private const val COMMIT_TIMEOUT_MS = 30_000L
    private const val RECONNECT_TIMEOUT_MS = 45_000L
}
