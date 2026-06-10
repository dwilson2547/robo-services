package com.robo.phonecompanion.data.obd2

data class Obd2Pid(
    val pid: Int,
    val name: String,
    val unit: String,
    val minBytes: Int = 1,
    val decode: (ByteArray) -> Double,
)

private fun ByteArray.u8(i: Int = 0): Double = (getOrElse(i) { 0 }.toInt() and 0xFF).toDouble()

object Obd2PidTable {

    val all: List<Obd2Pid> = listOf(
        // ── Engine ────────────────────────────────────────────────────────────
        Obd2Pid(0x04, "Engine load",           "%",     1) { it.u8() / 2.55 },
        Obd2Pid(0x05, "Coolant temp",          "°C",    1) { it.u8() - 40 },
        Obd2Pid(0x0C, "Engine RPM",            "rpm",   2) { (it.u8(0) * 256 + it.u8(1)) / 4.0 },
        Obd2Pid(0x0D, "Vehicle speed",         "km/h",  1) { it.u8() },
        Obd2Pid(0x0E, "Timing advance",        "°",     1) { it.u8() / 2.0 - 64 },
        Obd2Pid(0x0F, "Intake air temp",       "°C",    1) { it.u8() - 40 },
        Obd2Pid(0x10, "MAF",                   "g/s",   2) { (it.u8(0) * 256 + it.u8(1)) / 100.0 },
        Obd2Pid(0x11, "Throttle pos",          "%",     1) { it.u8() / 2.55 },
        Obd2Pid(0x1F, "Run time",              "s",     2) { it.u8(0) * 256 + it.u8(1) },
        Obd2Pid(0x45, "Rel throttle pos",      "%",     1) { it.u8() / 2.55 },
        Obd2Pid(0x46, "Ambient air temp",      "°C",    1) { it.u8() - 40 },
        Obd2Pid(0x47, "Throttle pos B",        "%",     1) { it.u8() / 2.55 },
        Obd2Pid(0x48, "Throttle pos C",        "%",     1) { it.u8() / 2.55 },
        Obd2Pid(0x5C, "Oil temp",              "°C",    1) { it.u8() - 40 },
        Obd2Pid(0x5E, "Fuel rate",             "L/h",   2) { (it.u8(0) * 256 + it.u8(1)) * 0.05 },
        Obd2Pid(0x61, "Demand torque",         "%",     1) { it.u8() - 125.0 },
        Obd2Pid(0x62, "Actual torque",         "%",     1) { it.u8() - 125.0 },
        Obd2Pid(0x63, "Ref torque",            "Nm",    2) { it.u8(0) * 256 + it.u8(1) },

        // ── Fuel system ───────────────────────────────────────────────────────
        Obd2Pid(0x06, "STFT bank 1",           "%",     1) { (it.u8() - 128) * 100.0 / 128 },
        Obd2Pid(0x07, "LTFT bank 1",           "%",     1) { (it.u8() - 128) * 100.0 / 128 },
        Obd2Pid(0x08, "STFT bank 2",           "%",     1) { (it.u8() - 128) * 100.0 / 128 },
        Obd2Pid(0x09, "LTFT bank 2",           "%",     1) { (it.u8() - 128) * 100.0 / 128 },
        Obd2Pid(0x0A, "Fuel pressure",         "kPa",   1) { it.u8() * 3 },
        Obd2Pid(0x0B, "MAP",                   "kPa",   1) { it.u8() },
        Obd2Pid(0x22, "Fuel rail pres",        "kPa",   2) { (it.u8(0) * 256 + it.u8(1)) * 0.079 },
        Obd2Pid(0x23, "Fuel rail pres abs",    "kPa",   2) { (it.u8(0) * 256 + it.u8(1)) * 10.0 },
        Obd2Pid(0x2C, "Commanded EGR",         "%",     1) { it.u8() / 2.55 },
        Obd2Pid(0x2D, "EGR error",             "%",     1) { (it.u8() - 128) * 100.0 / 128 },
        Obd2Pid(0x2E, "Evap purge",            "%",     1) { it.u8() / 2.55 },
        Obd2Pid(0x2F, "Fuel level",            "%",     1) { it.u8() / 2.55 },
        Obd2Pid(0x44, "Air-fuel ratio",        "λ",     2) { (it.u8(0) * 256 + it.u8(1)) * 2.0 / 65536 },
        Obd2Pid(0x52, "Ethanol content",       "%",     1) { it.u8() / 2.55 },
        Obd2Pid(0x5D, "Inject timing",         "°",     2) { (it.u8(0) * 256 + it.u8(1)) / 128.0 - 210 },

        // ── O2 sensors ────────────────────────────────────────────────────────
        Obd2Pid(0x14, "O2 S1",                 "V",     1) { it.u8() / 200.0 },
        Obd2Pid(0x15, "O2 S2",                 "V",     1) { it.u8() / 200.0 },
        Obd2Pid(0x16, "O2 S3",                 "V",     1) { it.u8() / 200.0 },
        Obd2Pid(0x17, "O2 S4",                 "V",     1) { it.u8() / 200.0 },
        Obd2Pid(0x18, "O2 S5",                 "V",     1) { it.u8() / 200.0 },
        Obd2Pid(0x19, "O2 S6",                 "V",     1) { it.u8() / 200.0 },
        Obd2Pid(0x1A, "O2 S7",                 "V",     1) { it.u8() / 200.0 },
        Obd2Pid(0x1B, "O2 S8",                 "V",     1) { it.u8() / 200.0 },

        // ── Catalyst / emissions ──────────────────────────────────────────────
        Obd2Pid(0x3C, "Cat temp B1S1",         "°C",    2) { (it.u8(0) * 256 + it.u8(1)) / 10.0 - 40 },
        Obd2Pid(0x3D, "Cat temp B2S1",         "°C",    2) { (it.u8(0) * 256 + it.u8(1)) / 10.0 - 40 },
        Obd2Pid(0x3E, "Cat temp B1S2",         "°C",    2) { (it.u8(0) * 256 + it.u8(1)) / 10.0 - 40 },
        Obd2Pid(0x3F, "Cat temp B2S2",         "°C",    2) { (it.u8(0) * 256 + it.u8(1)) / 10.0 - 40 },
        Obd2Pid(0x33, "Baro pressure",         "kPa",   1) { it.u8() },

        // ── Diagnostics / MIL ─────────────────────────────────────────────────
        Obd2Pid(0x21, "MIL distance",          "km",    2) { it.u8(0) * 256 + it.u8(1) },
        Obd2Pid(0x30, "Warm-ups since clear",  "",      1) { it.u8() },
        Obd2Pid(0x31, "Dist since clear",      "km",    2) { it.u8(0) * 256 + it.u8(1) },
        Obd2Pid(0x42, "Module voltage",        "V",     2) { (it.u8(0) * 256 + it.u8(1)) / 1000.0 },
        Obd2Pid(0x43, "Absolute load",         "%",     2) { (it.u8(0) * 256 + it.u8(1)) / 2.55 },
        Obd2Pid(0x49, "Accel pedal D",         "%",     1) { it.u8() / 2.55 },
        Obd2Pid(0x4A, "Accel pedal E",         "%",     1) { it.u8() / 2.55 },
        Obd2Pid(0x4B, "Accel pedal F",         "%",     1) { it.u8() / 2.55 },
        Obd2Pid(0x4C, "Throttle actuator",     "%",     1) { it.u8() / 2.55 },
        Obd2Pid(0x4D, "MIL on time",           "min",   2) { it.u8(0) * 256 + it.u8(1) },
        Obd2Pid(0x4E, "Time since clear",      "min",   2) { it.u8(0) * 256 + it.u8(1) },
    )

    private val byPid: Map<Int, Obd2Pid> = all.associateBy { it.pid }

    fun lookup(pid: Int): Obd2Pid? = byPid[pid]

    /** Returns a formatted "value unit" string, or null if the PID is unknown or data is too short. */
    fun decode(pid: Int, dataBytes: ByteArray): String? {
        val entry = byPid[pid] ?: return null
        if (dataBytes.size < entry.minBytes) return null
        val value = runCatching { entry.decode(dataBytes) }.getOrNull() ?: return null
        return if (entry.unit.isEmpty()) "%.0f".format(value)
        else "%.2f %s".format(value, entry.unit)
    }
}
