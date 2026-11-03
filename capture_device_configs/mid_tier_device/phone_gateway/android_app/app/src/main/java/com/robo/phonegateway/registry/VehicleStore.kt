package com.robo.phonegateway.registry

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * The user's vehicles as last fetched (so a capture can start with no signal),
 * the selected one, and which vehicle each dongle was last used in: a dongle
 * usually lives in one car, so connecting to it selects that car again.
 */
class VehicleStore private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("vehicles", Context.MODE_PRIVATE)

    private val vehiclesFlow = MutableStateFlow(load())
    val vehicles: StateFlow<List<Vehicle>> = vehiclesFlow.asStateFlow()

    private val selectedFlow = MutableStateFlow(prefs.getInt(KEY_SELECTED, 0).takeIf { it != 0 })
    val selectedId: StateFlow<Int?> = selectedFlow.asStateFlow()

    val selected: Vehicle? get() = selectedFlow.value?.let { id -> vehiclesFlow.value.firstOrNull { it.id == id } }

    fun replaceAll(list: List<Vehicle>) {
        prefs.edit().putString(KEY_LIST, Vehicle.listToJson(list)).apply()
        vehiclesFlow.value = list
        if (selectedFlow.value != null && list.none { it.id == selectedFlow.value }) select(null)
    }

    fun upsert(vehicle: Vehicle) = replaceAll(vehiclesFlow.value.filter { it.id != vehicle.id } + vehicle)

    fun select(id: Int?) {
        prefs.edit().putInt(KEY_SELECTED, id ?: 0).apply()
        selectedFlow.value = id
    }

    fun rememberForDongle(address: String, vehicleId: Int) {
        val map = dongleMap().put(address, vehicleId)
        prefs.edit().putString(KEY_BY_DONGLE, map.toString()).apply()
    }

    fun vehicleForDongle(address: String): Vehicle? =
        dongleMap().optInt(address, 0).takeIf { it != 0 }?.let { id -> vehiclesFlow.value.firstOrNull { it.id == id } }

    private fun dongleMap() = runCatching { JSONObject(prefs.getString(KEY_BY_DONGLE, "{}")!!) }.getOrDefault(JSONObject())

    private fun load(): List<Vehicle> =
        prefs.getString(KEY_LIST, null)?.let { runCatching { Vehicle.listFromJson(it) }.getOrNull() } ?: emptyList()

    companion object {
        private const val KEY_LIST = "list"
        private const val KEY_SELECTED = "selected"
        private const val KEY_BY_DONGLE = "by_dongle"

        @Volatile private var instance: VehicleStore? = null

        fun get(context: Context): VehicleStore =
            instance ?: synchronized(this) { instance ?: VehicleStore(context.applicationContext).also { instance = it } }
    }
}
