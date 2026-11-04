package com.robo.phonegateway.registry

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.roundToLong

/** How every number on screen is shown. The pipeline is metric; this is display only. */
enum class Units {
    IMPERIAL, METRIC;

    fun distance(km: Double): String = if (this == IMPERIAL) "${f2(km / KM_PER_MI)} mi" else "${f2(km)} km"
    fun speed(kph: Double): String = if (this == IMPERIAL) "${f1(kph / KM_PER_MI)} mph" else "${f1(kph)} km/h"
    /** Speed without its unit, for lists that state the unit once. */
    fun speedValue(kph: Double): String = if (this == IMPERIAL) f1(kph / KM_PER_MI) else f1(kph)
    val speedUnit: String get() = if (this == IMPERIAL) "mph" else "km/h"
    fun temperature(c: Double): String = if (this == IMPERIAL) "${(c * 9 / 5 + 32).roundToLong()} °F" else "${c.roundToLong()} °C"
    fun temperatureValue(c: Double): String = if (this == IMPERIAL) (c * 9 / 5 + 32).roundToLong().toString() else c.roundToLong().toString()
    val temperatureUnit: String get() = if (this == IMPERIAL) "°F" else "°C"

    companion object {
        const val KM_PER_MI = 1.609344
        private fun f1(x: Double) = String.format(Locale.US, "%.1f", x)
        private fun f2(x: Double) = String.format(Locale.US, "%.2f", x)

        private val flow = MutableStateFlow(IMPERIAL)
        private var loaded = false

        /** The chosen units, observable; imperial until set. */
        fun state(context: Context): StateFlow<Units> {
            if (!loaded) {
                loaded = true
                flow.value = runCatching {
                    valueOf(context.getSharedPreferences("display", Context.MODE_PRIVATE).getString("units", IMPERIAL.name)!!)
                }.getOrDefault(IMPERIAL)
            }
            return flow.asStateFlow()
        }

        fun set(context: Context, units: Units) {
            context.getSharedPreferences("display", Context.MODE_PRIVATE).edit().putString("units", units.name).apply()
            flow.value = units
        }
    }
}
