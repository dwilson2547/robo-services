package com.robo.phonegateway.auth

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import net.openid.appauth.AppAuthConfiguration
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * OIDC (PKCE) sign-in against authentik for the `phone-gateway` provider.
 * The access token is used as the MQTT password; the refresh token gives ~weekly
 * interactive sign-in (provider: 30-min access / 7-day refresh validity).
 *
 * Tokens live in EncryptedSharedPreferences (Keystore-backed). Refresh tokens are
 * rotated by authentik on every use, so the new one is persisted immediately.
 */
class OidcAuth(private val context: Context) {

    companion object {
        const val ISSUER = "https://auth.danwils.com/application/o/phone-gateway/"
        const val CLIENT_ID = "phone-gateway"
        val REDIRECT_URI: Uri = Uri.parse("com.robo.phonegateway://oauth2/callback")
        private const val PREF_TOKENS = "oidc_tokens"
        private const val KEY_ACCESS = "access"
        private const val KEY_REFRESH = "refresh"
        private const val KEY_EXPIRES_AT = "expires_at"
        /** Refresh this early so a mid-drive expiry never reaches the broker. */
        private const val EXPIRY_MARGIN_MS = 5 * 60_000L
    }

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, PREF_TOKENS, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val authService by lazy {
        AuthorizationService(context, AppAuthConfiguration.Builder().build())
    }

    @Volatile
    private var config: AuthorizationServiceConfiguration? = null

    suspend fun discover(): AuthorizationServiceConfiguration {
        config?.let { return it }
        return suspendCoroutine { cont ->
            AuthorizationServiceConfiguration.fetchFromIssuer(
                Uri.parse(ISSUER),
            ) { cfg, ex ->
                if (cfg != null) { config = cfg; cont.resume(cfg) }
                else cont.resume(AuthorizationServiceConfiguration().also { config = it })
            }
        }
    }

    fun buildAuthRequest(): Intent {
        val cfg = config ?: throw IllegalStateException("call discover() first")
        val req = AuthorizationRequest.Builder(
            cfg,
            CLIENT_ID,
            ResponseTypeValues.CODE,
            REDIRECT_URI,
        ).setScopes("openid", "email", "profile", "offline_access")
            .build()
        return authService.getAuthorizationRequestIntent(req)
    }

    /** Exchange the redirect intent's code for tokens and persist them. */
    suspend fun handleAuthResponse(intent: Intent) {
        val cfg = discover()
        val resp = AuthorizationService.getAuthorizationResponse(intent)
            ?: throw IllegalArgumentException("not an auth response")
        val ex = resp.createTokenExchangeRequest()
        // public client (PKCE): no client authentication on the token endpoint
        val tokenResp = suspendCoroutine<net.openid.appauth.TokenResponse> { cont ->
            authService.performTokenRequest(ex) { r, e ->
                if (r != null) cont.resume(r)
                else throw e ?: IllegalStateException("token exchange failed")
            }
        }
        persist(tokenResp)
    }

    private fun persist(tokenResp: net.openid.appauth.TokenResponse) {
        val expiresAt = tokenResp.accessTokenExpirationTime ?: 0L
        prefs.edit().apply {
            tokenResp.accessToken?.let { putString(KEY_ACCESS, it) }
            tokenResp.refreshToken?.let { putString(KEY_REFRESH, it) } // rotated: persist new one
            putLong(KEY_EXPIRES_AT, expiresAt)
            apply()
        }
    }

    fun accessToken(): String? = prefs.getString(KEY_ACCESS, null)
    fun isSignedIn(): Boolean = accessToken() != null

    fun username(): String? {
        // sub claim; the broker requires MQTT username == token sub
        val tok = accessToken() ?: return null
        val payload = tok.split(".")[1]
        val json = String(android.util.Base64.decode(payload, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP))
        return Regex("\"sub\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
    }

    /**
     * A valid access token, refreshing silently if within EXPIRY_MARGIN_MS of
     * expiry. Returns null if no refresh token is available (needs interactive
     * sign-in) or the refresh fails (revoked session).
     */
    suspend fun validAccessToken(): String? {
        val tok = accessToken() ?: return null
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        if (expiresAt - System.currentTimeMillis() > EXPIRY_MARGIN_MS) return tok
        return refresh()
    }

    suspend fun refresh(): String? {
        val cfg = discover()
        val refreshTok = prefs.getString(KEY_REFRESH, null) ?: return null
        val req = TokenRequest.Builder(cfg, CLIENT_ID)
            .setGrantType("refresh_token")
            .setRefreshToken(refreshTok)
            .build()
        val resp = suspendCoroutine<net.openid.appauth.TokenResponse?> { cont ->
            authService.performTokenRequest(req) { r, _ -> cont.resume(r) }
        } ?: return null
        persist(resp)
        return resp.accessToken
    }

    fun signOut() {
        prefs.edit().clear().apply()
    }
}
