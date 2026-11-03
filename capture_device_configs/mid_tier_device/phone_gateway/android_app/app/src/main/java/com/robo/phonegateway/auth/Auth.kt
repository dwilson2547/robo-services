package com.robo.phonegateway.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.robo.phonegateway.Config
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** MQTT credentials: the access token is the password, its subject the username. */
class Credentials(val username: String, val password: String)

sealed interface CredentialResult {
    class Ok(val credentials: Credentials) : CredentialResult
    /** No session, or authentik refused the refresh token: interactive sign-in needed. */
    data object SignInNeeded : CredentialResult
    /** authentik unreachable or erroring; worth retrying later. */
    class Unavailable(val reason: String) : CredentialResult
}

/**
 * authentik sign-in (authorization code + PKCE, via AppAuth) and token refresh,
 * one instance per process ([get]).
 *
 * AppAuth's AuthState is persisted after every change: authentik rotates the
 * refresh token on each use, so a lost update would sign the phone out. Refreshes
 * are serialized for the same reason.
 */
class Auth private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
    private val service = AuthorizationService(context)
    private val lock = Mutex()
    private var state: AuthState =
        prefs.getString(KEY_STATE, null)?.let { runCatching { AuthState.jsonDeserialize(it) }.getOrNull() }
            ?: AuthState()

    private val signedInFlow = MutableStateFlow(state.isAuthorized)
    val signedIn: StateFlow<Boolean> = signedInFlow.asStateFlow()

    /** Intent that opens the authentik sign-in page in a browser tab. */
    suspend fun signInIntent(): Intent {
        val config = discover()
        val request = AuthorizationRequest.Builder(
            config, Config.CLIENT_ID, ResponseTypeValues.CODE, Uri.parse(Config.REDIRECT_URI),
        ).setScopes("openid", "profile", "offline_access").build()
        return service.getAuthorizationRequestIntent(request)
    }

    /** Finish sign-in with the result of [signInIntent]. Throws on failure. */
    suspend fun completeSignIn(result: Intent) {
        val response = AuthorizationResponse.fromIntent(result)
        val error = AuthorizationException.fromIntent(result)
        if (response == null) throw error ?: IllegalStateException("sign-in returned no response")
        lock.withLock {
            val fresh = AuthState(response, null)
            val tokens = suspendCancellableCoroutine { cont ->
                service.performTokenRequest(response.createTokenExchangeRequest()) { tokens, ex ->
                    if (tokens != null) cont.resume(tokens) else cont.resumeWithException(ex ?: IllegalStateException("token exchange failed"))
                }
            }
            fresh.update(tokens, null)
            replace(fresh)
        }
    }

    /**
     * Current credentials, refreshing the access token first when it is near expiry
     * or when [forceRefresh] is set (the broker refused the last one).
     */
    suspend fun credentials(forceRefresh: Boolean = false): CredentialResult = lock.withLock {
        if (!state.isAuthorized) return@withLock CredentialResult.SignInNeeded
        if (forceRefresh) state.needsTokenRefresh = true
        val (token, ex) = suspendCancellableCoroutine { cont ->
            state.performActionWithFreshTokens(service) { access, _, ex -> cont.resume(access to ex) }
        }
        persist()
        when {
            ex == AuthorizationException.TokenRequestErrors.INVALID_GRANT -> {
                replace(AuthState())
                CredentialResult.SignInNeeded
            }
            ex != null || token == null -> CredentialResult.Unavailable(ex?.errorDescription ?: ex?.error ?: "no access token")
            else -> {
                val subject = state.parsedIdToken?.subject ?: prefs.getString(KEY_SUBJECT, null)
                if (subject == null) CredentialResult.SignInNeeded
                else CredentialResult.Ok(Credentials(subject, token))
            }
        }
    }

    suspend fun signOut() = lock.withLock { replace(AuthState()) }

    /** The signed-in user's subject (authentik sub), or null when signed out. */
    fun subject(): String? =
        if (!state.isAuthorized) null else state.parsedIdToken?.subject ?: prefs.getString(KEY_SUBJECT, null)

    private suspend fun discover(): AuthorizationServiceConfiguration =
        state.authorizationServiceConfiguration ?: suspendCancellableCoroutine { cont ->
            AuthorizationServiceConfiguration.fetchFromIssuer(Uri.parse(Config.ISSUER)) { config, ex ->
                if (config != null) cont.resume(config) else cont.resumeWithException(ex ?: IllegalStateException("discovery failed"))
            }
        }

    private fun replace(newState: AuthState) {
        state = newState
        persist()
        signedInFlow.value = newState.isAuthorized
    }

    private fun persist() {
        val edit = prefs.edit().putString(KEY_STATE, state.jsonSerializeString())
        // keep the subject even if a refresh response comes back without an id token
        state.parsedIdToken?.subject?.let { edit.putString(KEY_SUBJECT, it) }
        if (!state.isAuthorized) edit.remove(KEY_SUBJECT)
        edit.apply()
    }

    companion object {
        private const val KEY_STATE = "auth_state"
        private const val KEY_SUBJECT = "subject"

        @Volatile private var instance: Auth? = null

        fun get(context: Context): Auth =
            instance ?: synchronized(this) { instance ?: Auth(context.applicationContext).also { instance = it } }
    }
}
