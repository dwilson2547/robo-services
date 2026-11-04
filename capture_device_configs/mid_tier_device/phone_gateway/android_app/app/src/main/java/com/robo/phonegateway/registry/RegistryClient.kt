package com.robo.phonegateway.registry

import com.robo.phonegateway.Config
import com.robo.phonegateway.auth.Auth
import com.robo.phonegateway.auth.CredentialResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class RegistryException(message: String) : Exception(message)

/** The frames (base_timestamp_us) of a session the pipeline has, and whether its end arrived. */
class SessionFrames(val ended: Boolean, val bases: Set<Long>)

/**
 * The registry's phone API (robo.danwils.com/api/app), authenticated with the
 * same authentik access token the app uses for MQTT. A 401 gets one forced token
 * refresh and a retry.
 */
class RegistryClient(
    private val auth: Auth,
    private val base: String = Config.API_BASE,
    /** The phone's upload token for the catch-up endpoint, so it works while signed out. */
    private val uploadToken: () -> String? = { null },
) {

    /** The signed-in user as the registry knows them: (name, email). */
    suspend fun me(): Pair<String, String> =
        JSONObject(call("GET", "/me")).let { it.getString("name") to it.getString("email") }

    suspend fun delete(vehicleId: Int) {
        call("DELETE", "/vehicles/$vehicleId")
    }

    suspend fun vehicles(): List<Vehicle> = Vehicle.listFromJson(call("GET", "/vehicles"))

    suspend fun dbcs(): List<DbcChoice> = DbcChoice.listFromJson(call("GET", "/dbcs"))

    suspend fun create(draft: Vehicle): Vehicle = Vehicle.fromJson(JSONObject(call("POST", "/vehicles", draft.toJson())))

    suspend fun update(vehicle: Vehicle): Vehicle =
        Vehicle.fromJson(JSONObject(call("PUT", "/vehicles/${vehicle.id}", vehicle.toJson())))

    /** Every track; nearest first when the position is given. */
    suspend fun tracks(near: Pair<Double, Double>? = null): List<AppTrack> =
        AppTrack.listFromJson(call("GET", "/tracks" + (near?.let { "?lat=${it.first}&lon=${it.second}" } ?: "")))

    /** OSM tracks near a point, or near a US ZIP code. */
    suspend fun discoverTracks(near: Pair<Double, Double>?, zip: String?, radiusM: Int): TrackSearch {
        val body = JSONObject().put("radius_m", radiusM)
        if (zip != null) body.put("zip", zip) else near?.let { body.put("lat", it.first).put("lon", it.second) }
        // the registry asks OpenStreetMap's Overpass, which takes ~30 s for a 25 mi search
        return TrackSearch.fromJson(call("POST", "/tracks/discover", body, timeoutMs = SEARCH_TIMEOUT_MS))
    }

    /** Add a search's candidates (by index) as tracks; returns the tracks added. */
    suspend fun addTracks(token: String, indices: List<Int>): List<AppTrack> {
        val o = JSONObject(call("POST", "/tracks/ingest",
            JSONObject().put("token", token).put("selected_indices", org.json.JSONArray(indices))))
        return o.getJSONArray("ingested").let { a -> (0 until a.length()).map { AppTrack.fromJson(a.getJSONObject(it)) } }
    }

    /** The user's sessions, newest first. */
    suspend fun sessions(limit: Int = 30, offset: Int = 0): List<SessionItem> =
        SessionItem.listFromJson(call("GET", "/sessions?limit=$limit&offset=$offset"))

    /** The session's route (empty for sessions recorded before vehicle-state snapshots). */
    suspend fun track(sessionId: Long): RouteTrack = RouteTrack.fromJson(trackJson(sessionId))

    /** The track as the registry sends it (TrackCache stores this). */
    suspend fun trackJson(sessionId: Long): String = call("GET", "/sessions/$sessionId/track")

    /** The session's drive summary, or null while the registry has none yet (404). */
    suspend fun summary(sessionId: Long): DriveSummary? =
        send("GET", "/sessions/$sessionId/summary", null, nullOn404 = true)?.let { DriveSummary.fromJson(it) }

    /** Which frames of a session reached the pipeline; null while the registry doesn't know the session (404). */
    suspend fun sessionFrames(sessionId: Long): SessionFrames? =
        // only the registry's own "session not found" means none: a 404 from an older registry
        // without this route must not read as "has nothing" (the catch-up would resend it all)
        send("GET", "/sessions/$sessionId/frames", null, nullOn404 = true, notFoundDetail = "session not found",
            bearer = uploadToken())?.let { text ->
            val o = JSONObject(text)
            val a = o.getJSONArray("base_timestamp_us")
            SessionFrames(o.getBoolean("ended"), (0 until a.length()).mapTo(HashSet()) { a.getLong(it) })
        }

    /** Register this phone's upload key (the public half) for the signed-in user. */
    suspend fun registerUploadKey(deviceId: String, publicKeyPem: String) {
        call("PUT", "/devices/$deviceId/upload-key", JSONObject().put("public_key_pem", publicKeyPem))
    }

    /** Revoke this phone's upload key (sign-out). */
    suspend fun revokeUploadKey(deviceId: String) {
        call("DELETE", "/devices/$deviceId/upload-key")
    }

    private suspend fun call(method: String, path: String, body: JSONObject? = null, timeoutMs: Int = TIMEOUT_MS): String =
        send(method, path, body, nullOn404 = false, timeoutMs = timeoutMs)!!

    private suspend fun send(
        method: String, path: String, body: JSONObject?, nullOn404: Boolean, timeoutMs: Int = TIMEOUT_MS,
        notFoundDetail: String? = null,
        /** Send this token first (the upload token); on a 401, fall back to the authentik one. */
        bearer: String? = null,
    ): String? {
        var refreshed = false
        var preset = bearer
        while (true) {
            val token = preset ?: when (val c = auth.credentials(forceRefresh = refreshed)) {
                is CredentialResult.Ok -> c.credentials.password
                CredentialResult.SignInNeeded -> throw RegistryException("sign in first")
                is CredentialResult.Unavailable -> throw RegistryException("sign-in unavailable: ${c.reason}")
            }
            val (code, text) = withContext(Dispatchers.IO) { request(method, path, body, token, timeoutMs) }
            if (code == 401 && preset != null) {
                preset = null
                continue
            }
            if (code == 401 && !refreshed) {
                refreshed = true
                continue
            }
            if (code == 404 && nullOn404 && (notFoundDetail == null || detail(text) == notFoundDetail)) return null
            if (code !in 200..299) throw RegistryException("registry $method $path: HTTP $code ${detail(text)}")
            return text
        }
    }

    private fun request(method: String, path: String, body: JSONObject?, token: String, timeoutMs: Int): Pair<Int, String> {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } catch (e: java.io.IOException) {
            throw RegistryException("registry unreachable: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    private fun detail(text: String): String =
        runCatching { JSONObject(text).opt("detail")?.toString() }.getOrNull()?.take(200) ?: ""

    companion object {
        private const val TIMEOUT_MS = 10_000
        private const val SEARCH_TIMEOUT_MS = 120_000
    }
}
