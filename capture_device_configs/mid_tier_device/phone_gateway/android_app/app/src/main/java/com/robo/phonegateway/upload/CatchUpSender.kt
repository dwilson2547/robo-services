package com.robo.phonegateway.upload

import com.robo.phonegateway.Config
import com.robo.phonegateway.auth.Auth
import com.robo.phonegateway.auth.CredentialResult
import com.robo.phonegateway.mqtt.Publisher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

/**
 * A short-lived MQTT connection for the background catch-up. It has its own client
 * id (`<device>-catchup`): a second client with the recording's id would make the
 * broker drop the recording's connection. Clean session, in-memory persistence:
 * anything not acked is simply found missing again by the next run.
 */
class CatchUpSender private constructor(private val client: MqttAsyncClient, private val topic: String) {

    /** Publish at QoS 1 and wait for every ack; throws if the broker stops acking. */
    suspend fun send(payloads: List<ByteArray>) = withContext(Dispatchers.IO) {
        val window = ArrayDeque<IMqttDeliveryToken>()
        for (p in payloads) {
            if (window.size >= WINDOW) window.removeFirst().waitForCompletion(ACK_TIMEOUT_MS)
            window.addLast(client.publish(topic, p, 1, false))
        }
        while (window.isNotEmpty()) window.removeFirst().waitForCompletion(ACK_TIMEOUT_MS)
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        runCatching { client.disconnect(2_000).waitForCompletion(3_000) }
        runCatching { client.close() }
    }

    companion object {
        private const val WINDOW = 50
        private const val ACK_TIMEOUT_MS = 30_000L
        private const val CONNECT_TIMEOUT_MS = 20_000L

        /** Connected sender, or null when not signed in or the broker can't be reached. */
        suspend fun connect(auth: Auth, deviceId: String): CatchUpSender? = withContext(Dispatchers.IO) {
            var forced = false
            while (true) {
                val creds = (auth.credentials(forced) as? CredentialResult.Ok)?.credentials ?: return@withContext null
                val client = MqttAsyncClient(Config.BROKER_URIS.first(), "$deviceId-catchup", MemoryPersistence())
                try {
                    client.connect(
                        Publisher.connectOptions(Config.BROKER_URIS, Config.BROKER_TLS_NAME, creds.username, creds.password,
                            cleanSession = true).apply { maxInflight = WINDOW * 2 },
                    ).waitForCompletion(CONNECT_TIMEOUT_MS)
                    return@withContext CatchUpSender(client, Config.topicFor(creds.username))
                } catch (e: MqttException) {
                    runCatching { client.close() }
                    val refused = e.reasonCode == MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt() ||
                        e.reasonCode == MqttException.REASON_CODE_NOT_AUTHORIZED.toInt()
                    if (refused && !forced) { forced = true; continue } // the token may have just expired
                    return@withContext null
                }
            }
            @Suppress("UNREACHABLE_CODE") null
        }
    }
}
