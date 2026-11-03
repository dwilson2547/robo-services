package com.robo.phonegateway.registry

import org.json.JSONArray
import org.json.JSONObject

/** A vehicle from the registry (GET /api/app/vehicles). id 0 = not created yet. */
data class Vehicle(
    val id: Int,
    val name: String,
    val year: Int? = null,
    val make: String? = null,
    val model: String? = null,
    /** CAN ids the dongle forwards in this vehicle; empty = all. */
    val canFilter: List<Int> = emptyList(),
) {
    fun label(): String {
        val detail = listOfNotNull(year?.toString(), make, model).joinToString(" ")
        return if (detail.isEmpty() || detail == name) name else "$name ($detail)"
    }

    /** Request body for create/update, and the offline cache's format. */
    fun toJson(): JSONObject = JSONObject().apply {
        if (id != 0) put("id", id)
        put("name", name)
        put("year", year ?: JSONObject.NULL)
        put("make", make ?: JSONObject.NULL)
        put("model", model ?: JSONObject.NULL)
        put("can_filter", JSONArray(canFilter))
    }

    companion object {
        fun fromJson(o: JSONObject) = Vehicle(
            id = o.getInt("id"),
            name = o.getString("name"),
            year = if (o.isNull("year")) null else o.optInt("year"),
            make = o.optString("make").takeUnless { o.isNull("make") || it.isEmpty() },
            model = o.optString("model").takeUnless { o.isNull("model") || it.isEmpty() },
            canFilter = o.optJSONArray("can_filter")?.let { a -> List(a.length()) { a.getInt(it) } } ?: emptyList(),
        )

        fun listFromJson(text: String): List<Vehicle> {
            val a = JSONArray(text)
            return List(a.length()) { fromJson(a.getJSONObject(it)) }
        }

        fun listToJson(vehicles: List<Vehicle>): String = JSONArray(vehicles.map { it.toJson() }).toString()
    }
}
