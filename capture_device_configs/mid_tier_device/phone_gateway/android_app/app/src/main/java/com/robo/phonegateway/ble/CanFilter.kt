package com.robo.phonegateway.ble

import android.content.SharedPreferences

/**
 * The CAN id allowlist sent to the dongle with its FILTER command
 * (embedded/dbc-forge firmware 1.6.0+): only these ids cross BLE. Empty forwards
 * every id. Firmware before 1.6.0 ignores the command and sends everything.
 */
object CanFilter {
    const val MAX_IDS = 64
    private const val KEY = "can_filter"

    /** `FILTER:1E5,C9,10242040` */
    fun command(ids: List<Int>): String =
        "FILTER:" + ids.joinToString(",") { Integer.toHexString(it).uppercase() }

    /** Parse "1E5, 0xC9 10242040" into ids; null if any entry is not a hex CAN id or there are too many. */
    fun parse(text: String): List<Int>? {
        val ids = text.split(',', ' ', '\n', '\t').filter { it.isNotBlank() }.map { raw ->
            val hex = raw.trim().removePrefix("0x").removePrefix("0X")
            hex.toLongOrNull(16)?.takeIf { it in 0..0x1FFFFFFF }?.toInt() ?: return null
        }.distinct()
        return ids.takeIf { it.size <= MAX_IDS }
    }

    fun format(ids: List<Int>): String = ids.joinToString(", ") { Integer.toHexString(it).uppercase() }

    internal fun ids(prefs: SharedPreferences): List<Int> =
        prefs.getString(KEY, "")?.let(::parse).orEmpty()

    internal fun save(prefs: SharedPreferences, ids: List<Int>) {
        prefs.edit().putString(KEY, format(ids)).apply()
    }
}
