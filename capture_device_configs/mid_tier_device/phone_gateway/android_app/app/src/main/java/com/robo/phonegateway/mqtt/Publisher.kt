package com.robo.phonegateway.mqtt

import com.robo.phonegateway.auth.CredentialResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.DisconnectedBufferOptions
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClientPersistence
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocketFactory

/**
 * Publishes frames to the broker with QoS 1 over TLS, authenticating with the
 * current access token on every CONNECT.
 *
 * One client for the publisher's lifetime, with Paho's disconnected buffer (on
 * [persistence]) so frames published during a short outage go out on reconnect;
 * the frame log covers anything longer. Reconnects are driven here rather than by
 * Paho, because each CONNECT needs a fresh token: a refused CONNECT gets one
 * forced token refresh and an immediate retry, then backs off.
 */
class Publisher(
    private val scope: CoroutineScope,
    private val serverUris: Array<String>,
    private val tlsName: String,
    private val clientId: String,
    private val topic: String,
    persistence: MqttClientPersistence,
    private val credentials: suspend (forceRefresh: Boolean) -> CredentialResult,
    private val onState: (String) -> Unit,
    private val socketFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
    private val bufferedFrames: Int = 600,
) {
    val acked = AtomicLong()
    val notSent = AtomicLong()

    private val client = MqttAsyncClient(serverUris.first(), clientId, persistence).apply {
        setBufferOpts(
            DisconnectedBufferOptions().apply {
                isBufferEnabled = true
                bufferSize = bufferedFrames
                isPersistBuffer = true
                isDeleteOldestMessages = true
            },
        )
    }
    private var lost = CompletableDeferred<Throwable?>()
    private var loop: Job? = null

    init {
        client.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String) {}
            override fun connectionLost(cause: Throwable?) {
                lost.complete(cause)
            }
            override fun messageArrived(topic: String, message: MqttMessage) {}
            override fun deliveryComplete(token: IMqttDeliveryToken) {
                if (token.userContext !== EVENT) acked.incrementAndGet() // count frames only
            }
        })
    }

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch(Dispatchers.IO) { run() }
    }

    suspend fun stop() {
        loop?.cancel()
        withContext(Dispatchers.IO) {
            runCatching { client.disconnect(2_000).waitForCompletion(3_000) }
            runCatching { client.close() }
        }
        onState("stopped")
    }

    /** Hand one frame to the client: sent now if connected, else buffered. */
    fun publish(frame: ByteArray) {
        try {
            client.publish(topic, frame, 1, false)
        } catch (e: MqttException) {
            notSent.incrementAndGet() // buffer full or client closed; the frame log has it
        }
    }

    /**
     * Publish a session event (QoS 1, buffered while offline). [onDelivered] runs
     * when the broker acks it. Returns false if the client refused it outright.
     */
    fun publishEvent(json: String, onDelivered: () -> Unit): Boolean = try {
        client.publish(
            topic, json.toByteArray(Charsets.UTF_8), 1, false, EVENT,
            object : IMqttActionListener {
                override fun onSuccess(token: IMqttToken) = onDelivered()
                override fun onFailure(token: IMqttToken, cause: Throwable?) {}
            },
        )
        true
    } catch (e: MqttException) {
        false
    }

    private suspend fun run() {
        var backoffMs = MIN_BACKOFF_MS
        var refused = false
        while (currentCoroutineContext().isActive) {
            when (val result = credentials(refused)) {
                CredentialResult.SignInNeeded -> {
                    onState("sign-in needed")
                    delay(SIGN_IN_POLL_MS)
                    continue
                }
                is CredentialResult.Unavailable -> {
                    onState("auth unavailable: ${result.reason}")
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                    continue
                }
                is CredentialResult.Ok -> {
                    onState("connecting")
                    try {
                        lost = CompletableDeferred()
                        client.connect(options(result.credentials.username, result.credentials.password)).waitForCompletion(CONNECT_TIMEOUT_MS)
                        onState("connected to ${client.currentServerURI}")
                        refused = false
                        backoffMs = MIN_BACKOFF_MS
                        val cause = lost.await()
                        onState("connection lost: ${cause?.message ?: "closed"}")
                        delay(MIN_BACKOFF_MS)
                    } catch (e: MqttException) {
                        val authRefusal = e.reasonCode == MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt() ||
                            e.reasonCode == MqttException.REASON_CODE_NOT_AUTHORIZED.toInt()
                        if (authRefusal && !refused) {
                            // one forced refresh, straight away: the token may have just expired
                            refused = true
                            onState("refused; refreshing token")
                            continue
                        }
                        onState(if (authRefusal) "refused by broker" else "unreachable: ${e.message}")
                        delay(if (authRefusal) MAX_BACKOFF_MS else backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                    }
                }
            }
        }
    }

    private fun options(username: String, password: String) =
        connectOptions(serverUris, tlsName, username, password, cleanSession = false, socketFactory)

    companion object {
        /** CONNECT options for the broker, shared with the background catch-up. */
        fun connectOptions(
            serverUris: Array<String>, tlsName: String, username: String, password: String,
            cleanSession: Boolean, socketFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
        ) = MqttConnectOptions().apply {
            serverURIs = serverUris
            userName = username
            this.password = password.toCharArray()
            isCleanSession = cleanSession
            isAutomaticReconnect = false
            keepAliveInterval = 60
            connectionTimeout = (CONNECT_TIMEOUT_MS / 1000).toInt()
            this.socketFactory = socketFactory
            // The LAN URI is an IP address, so check the certificate against the broker's
            // name for every URI instead of against the host that was dialled. Paho's own
            // check (endpoint identification) would compare it with the IP and fail.
            isHttpsHostnameVerificationEnabled = false
            sslHostnameVerifier = CertificateName.verifier(tlsName)
        }

        /** userContext marking session events, so [acked] counts frames only. */
        private val EVENT = Any()
        private const val MIN_BACKOFF_MS = 5_000L
        private const val MAX_BACKOFF_MS = 120_000L
        private const val SIGN_IN_POLL_MS = 15_000L
        private const val CONNECT_TIMEOUT_MS = 20_000L
    }
}
