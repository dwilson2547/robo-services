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

/**
 * The registry's phone API (robo.danwils.com/api/app), authenticated with the
 * same authentik access token the app uses for MQTT. A 401 gets one forced token
 * refresh and a retry.
 */
class RegistryClient(private val auth: Auth, private val base: String = Config.API_BASE) {

    suspend fun vehicles(): List<Vehicle> = Vehicle.listFromJson(call("GET", "/vehicles"))

    suspend fun create(draft: Vehicle): Vehicle = Vehicle.fromJson(JSONObject(call("POST", "/vehicles", draft.toJson())))

    suspend fun update(vehicle: Vehicle): Vehicle =
        Vehicle.fromJson(JSONObject(call("PUT", "/vehicles/${vehicle.id}", vehicle.toJson())))

    private suspend fun call(method: String, path: String, body: JSONObject? = null): String {
        var refreshed = false
        while (true) {
            val token = when (val c = auth.credentials(forceRefresh = refreshed)) {
                is CredentialResult.Ok -> c.credentials.password
                CredentialResult.SignInNeeded -> throw RegistryException("sign in first")
                is CredentialResult.Unavailable -> throw RegistryException("sign-in unavailable: ${c.reason}")
            }
            val (code, text) = withContext(Dispatchers.IO) { request(method, path, body, token) }
            if (code == 401 && !refreshed) {
                refreshed = true
                continue
            }
            if (code !in 200..299) throw RegistryException("registry $method $path: HTTP $code ${detail(text)}")
            return text
        }
    }

    private fun request(method: String, path: String, body: JSONObject?, token: String): Pair<Int, String> {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
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
    }
}
