package com.robo.phonegateway

import android.content.Context

/** Runtime settings; persisted in plain SharedPreferences (no secrets here). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)

    var deviceId: String
        get() = prefs.getString("device_id", null) ?: defaultDeviceId(context)
        set(v) = prefs.edit().putString("device_id", v).apply()

    var brokerHost: String
        get() = prefs.getString("broker_host", "mqtt.danwils.com")!!
        set(v) = prefs.edit().putString("broker_host", v).apply()

    /** WAN port on the router (dst-nat 48213 -> mosquitto 8883). */
    var brokerPort: Int
        get() = prefs.getInt("broker_port", 48213)
        set(v) = prefs.edit().putInt("broker_port", v).apply()

    var topic: String
        get() = prefs.getString("topic", "telemetry/ingest")!!
        set(v) = prefs.edit().putString("topic", v).apply()

    /** Empty = send all CAN ids; otherwise only these (bitmask cost on cellular). */
    var canAllowlist: Set<Int>
        get() = prefs.getStringSet("can_allowlist", emptySet())!!
            .mapNotNull { it.toIntOrNull() }.toSet()
        set(v) = prefs.edit().putStringSet("can_allowlist", v.map { it.toString() }.toSet()).apply()

    companion object {
        private fun defaultDeviceId(context: Context): String {
            val androidId = android.provider.Settings.Secure.getString(
                context.contentResolver, android.provider.Settings.Secure.ANDROID_ID,
            ) ?: "unknown"
            return "phone-$androidId"
        }
    }
}
