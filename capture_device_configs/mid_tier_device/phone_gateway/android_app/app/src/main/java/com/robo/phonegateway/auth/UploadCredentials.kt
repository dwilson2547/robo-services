package com.robo.phonegateway.auth

import android.content.Context
import android.util.Log
import com.robo.phonegateway.GatewayService
import com.robo.phonegateway.registry.RegistryClient

/**
 * What uploads authenticate with (docs/plans/2026-11-04-reliable-uploads.md §2): the
 * phone's own token from its [UploadKey] once that is registered, else the authentik
 * access token. A refused upload token (key revoked, registry down) falls back to the
 * authentik token once, and back again if there is none: recording and catch-up keep
 * working while the authentik session is gone.
 */
class UploadCredentials private constructor(private val context: Context) {
    private val auth = Auth.get(context)
    private val key = UploadKey.get(context)
    private val deviceId get() = GatewayService.deviceId(context)

    /** The user uploads go out as (their topic), or null when neither is set up. */
    fun subject(): String? = key.registeredSub ?: auth.subject()

    suspend fun credentials(forceRefresh: Boolean = false): CredentialResult {
        val sub = key.registeredSub
        if (sub != null && !forceRefresh) deviceToken(sub)?.let { return it }
        val viaAuthentik = auth.credentials(forceRefresh)
        if (viaAuthentik is CredentialResult.Ok || sub == null) return viaAuthentik
        return deviceToken(sub) ?: viaAuthentik
    }

    /** An upload token for the registry's catch-up endpoint, or null when not registered. */
    fun registryToken(): String? = key.registeredSub?.let { runCatching { key.token(deviceId, it) }.getOrNull() }

    private fun deviceToken(sub: String): CredentialResult? =
        runCatching { CredentialResult.Ok(Credentials(sub, key.token(deviceId, sub))) }
            .onFailure { Log.w(TAG, "upload key unusable", it) }.getOrNull()

    /** Register the key for the signed-in user if it isn't already (after sign-in, at app start). */
    suspend fun ensureRegistered(registry: RegistryClient) {
        val sub = auth.subject() ?: return
        if (key.registeredSub == sub) return
        runCatching { registry.registerUploadKey(deviceId, key.publicKeyPem()) }
            .onSuccess { key.markRegistered(sub); Log.i(TAG, "upload key registered") }
            .onFailure { Log.i(TAG, "upload key not registered yet: ${it.message}") }
    }

    /** Sign-out: revoke at the registry (best effort, while still signed in), then forget the key. */
    suspend fun revoke(registry: RegistryClient) {
        if (key.registeredSub != null) runCatching { registry.revokeUploadKey(deviceId) }
        key.forget()
    }

    companion object {
        private const val TAG = "UploadCredentials"

        @Volatile private var instance: UploadCredentials? = null

        fun get(context: Context): UploadCredentials =
            instance ?: synchronized(this) { instance ?: UploadCredentials(context.applicationContext).also { instance = it } }
    }
}
