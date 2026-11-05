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
 * Firmware up to 1.7.0 verifies and restarts inside the COMMIT write, so COMMIT is never
 * acknowledged and "OK" may not arrive before the link drops. A COMMIT is therefore
 * confirmed by "OK", or by the dongle reconnecting on the target version.
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
            while (true) {
                val s = link.status(COMMIT_TIMEOUT_MS)
                when {
                    s == "OK" -> return
                    s == "VERIFYING" || (s != null && s.startsWith("PROGRESS:")) -> continue
                    s == null || s == DISCONNECTED -> {
                        // restarted (or gone): it is confirmed only by coming back on [version]
                        val back = link.reconnectedVersion(RECONNECT_TIMEOUT_MS)
                        if (back == version) return
                        throw Failed(
                            if (back == null) "dongle did not come back after COMMIT"
                            else "dongle came back on $back, not $version",
                        )
                    }
                    else -> throw Failed("dongle: $s")
                }
            }
        } catch (e: Failed) {
            link.control("ABORT") // harmless if the dongle already dropped the upload
            throw e
        }
    }

    private const val START_TIMEOUT_MS = 10_000L
    private const val COMMIT_TIMEOUT_MS = 30_000L
    private const val RECONNECT_TIMEOUT_MS = 45_000L
}
