package com.robo.phonegateway.registry

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VehicleTest {

    private val registryJson = """
        [{"id": 3, "name": "Impala", "year": 2014, "make": "Chevrolet", "model": "Impala",
          "can_filter": [201, 485], "dbc_id": 2, "notes": null, "created_at": "2026-11-03T19:00:00Z", "updated_at": "2026-11-03T19:00:00Z"},
         {"id": 4, "name": "Truck", "year": null, "make": null, "model": null, "can_filter": [],
          "notes": null, "created_at": "2026-11-03T19:00:00Z", "updated_at": "2026-11-03T19:00:00Z"}]
    """.trimIndent()

    @Test
    fun `parses what the registry returns`() {
        val (impala, truck) = Vehicle.listFromJson(registryJson)
        assertEquals(Vehicle(3, "Impala", 2014, "Chevrolet", "Impala", listOf(0xC9, 0x1E5), dbcId = 2), impala)
        assertEquals(Vehicle(4, "Truck"), truck)
        assertEquals("Impala (2014 Chevrolet Impala)", impala.label())
        assertEquals("Truck", truck.label())
    }

    @Test
    fun `round trips through the offline cache`() {
        val list = Vehicle.listFromJson(registryJson)
        assertEquals(list, Vehicle.listFromJson(Vehicle.listToJson(list)))
    }

    @Test
    fun `a new vehicle is sent without an id`() {
        val body = Vehicle(0, "Truck", canFilter = listOf(0x1E5)).toJson()
        assertFalse(body.has("id"))
        assertEquals(0x1E5, body.getJSONArray("can_filter").getInt(0))
        assertEquals(JSONObject.NULL, body.get("year"))
        assertEquals(JSONObject.NULL, body.get("dbc_id")) // sent even when null: an update replaces the vehicle
    }
}

class DbcChoiceTest {
    @Test
    fun `parses the dbc list`() {
        assertEquals(listOf(DbcChoice(1, "chevy_impala_2008_hscan")),
            DbcChoice.listFromJson("""[{"id": 1, "name": "chevy_impala_2008_hscan", "notes": null}]"""))
    }
}
