package com.robo.phonegateway.mqtt

import kotlinx.coroutines.flow.MutableStateFlow
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence


/**
 * MQTT publisher for the public broker (mqtt.danwils.com:48213 → 8883 TLS).
 *
 * Auth/reconnect state machine (handles token death mid-drive):
 *  - MQTT 3.1.1 checks credentials at CONNECT only, so an expired access token
 *    does not kill a live connection; the risk is reconnect → CONNACK 5.
 *  - connect() takes a fresh token via [tokenProvider]; on CONNACK 5 it calls
 *    [onAuthRejected], which refreshes the token and retries once.
 *  - cleanSession=false + fixed clientId lets the broker queue QoS 1 frames
 *    during short outages.
 *  - Every frame is already in the local FrameLog before publish is attempted,
 *    so auth/endpoint outages cost the live stream, never the data.
 */
class MqttPublisher(
    private val host: String,
    private val port: Int,
    private val topic: String,
    private val clientId: String,
    private val tokenProvider: suspend () -> Pair<String, String>?, // (username=sub, accessToken)
    private val onAuthRejected: suspend () -> Unit,
) {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED, AUTH_LOST }

    val state = MutableStateFlow(State.DISCONNECTED)
    val publishedCount = MutableStateFlow(0L)
    val lastError = MutableStateFlow<String?>(null)

    private var client: MqttAsyncClient? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        connect()
    }

    fun stop() {
        running = false
        runCatching { client?.disconnect(2000)?.waitForCompletion(2000) }
        runCatching { client?.close() }
        client = null
        state.value = State.DISCONNECTED
    }

    private fun connect() {
        if (!running) return
        state.value = State.CONNECTING
        // Token fetch is suspend; bridge into the callback thread.
        kotlinx.coroutines.GlobalScope.launch {
            val creds = try { tokenProvider() } catch (e: Exception) {
                lastError.value = "token: ${e.message}"
                null
            }
            if (creds == null) {
                state.value = State.AUTH_LOST
                retryLater()
                return@launch
            }
            val (username, token) = creds
            try {
                val c = MqttAsyncClient(
                    "ssl://$host:$port", clientId, MemoryPersistence(),
                )
                client = c
                val opts = MqttConnectOptions().apply {
                    isCleanSession = false // resume session, broker queues QoS 1
                    isAutomaticReconnect = false // we drive reconnects (token refresh)
                    connectionTimeout = 15
                    keepAliveInterval = 30
                    userName = username
                    password = token.toCharArray()
                    // default socket factory: the letsencrypt-prod cert is publicly
                    // trusted, so the system CA store validates it
                }
                c.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String) {
                        state.value = State.CONNECTED
                        lastError.value = null
                    }
                    override fun connectionLost(cause: Throwable?) {
                        state.value = State.DISCONNECTED
                        lastError.value = cause?.message
                        // Reconnect; if the token expired meanwhile the CONNECT will
                        // fail with reason 5 and we refresh + retry.
                        connect()
                    }
                    override fun messageArrived(topic: String, message: MqttMessage) {}
                    override fun deliveryComplete(token: IMqttDeliveryToken?) {
                        publishedCount.value++
                    }
                })
                c.connect(opts).waitForCompletion(20_000)
                if (!c.isConnected) {
                    // CONNACK 5 (not authorized) lands here as an exception
                    state.value = State.DISCONNECTED
                    onAuthRejectedBridge()
                }
            } catch (e: Exception) {
                lastError.value = e.message
                val msg = e.message.orEmpty()
                if (msg.contains("not authorized", ignoreCase = true) || msg.contains("5")) {
                    onAuthRejectedBridge()
                } else {
                    retryLater()
                }
            }
        }
    }

    private fun onAuthRejectedBridge() {
        kotlinx.coroutines.GlobalScope.launch {
            onAuthRejected() // refresh token
            connect()       // retry with fresh token
        }
    }

    private fun retryLater() {
        if (!running) return
        kotlinx.coroutines.GlobalScope.launch {
            kotlinx.coroutines.delay(10_000)
            connect()
        }
    }

    /** Publish one frame, QoS 1. Fire-and-forget; delivery tracked via callback. */
    fun publish(frameBytes: ByteArray) {
        val c = client
        if (c == null || !c.isConnected) return
        runCatching {
            c.publish(topic, MqttMessage(frameBytes).apply { qos = 1 })
        }.onFailure { lastError.value = it.message }
    }

}
