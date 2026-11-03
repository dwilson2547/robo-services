package com.robo.phonegateway.mqtt

import com.robo.phonegateway.auth.CredentialResult
import com.robo.phonegateway.auth.Credentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.Collections
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/**
 * Runs Publisher against a real broker: mosquitto-go-auth configured like the
 * pub-sub broker, with mqtt-auth behind it. Skipped unless these are set:
 *
 *   MQTT_IT_TLS_URI    e.g. ssl://127.0.0.1:8883 (the authenticated listener)
 *   MQTT_IT_PLAIN_URI  e.g. tcp://127.0.0.1:1883 (anonymous, to watch deliveries)
 *   MQTT_IT_CA         PEM of the broker certificate
 *   MQTT_IT_TLS_NAME   name on that certificate
 *   MQTT_IT_USER, MQTT_IT_TOKEN  a token mqtt-auth accepts, and its sub
 */
class PublisherBrokerTest {

    private val env = System.getenv()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun required(name: String): String {
        val value = env[name]
        assumeTrue("$name not set", !value.isNullOrEmpty())
        return value!!
    }

    private fun trusting(pem: String): SSLSocketFactory {
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(File(pem).inputStream())
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("broker", cert) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        return SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }.socketFactory
    }

    private fun watch(uri: String): MutableList<ByteArray> {
        val seen = Collections.synchronizedList(mutableListOf<ByteArray>())
        MqttClient(uri, "it-watch-${System.nanoTime()}", MemoryPersistence()).apply {
            connect()
            subscribe("telemetry/ingest", 1) { _, m -> seen.add(m.payload) }
        }
        return seen
    }

    private fun publisher(tlsName: String, creds: suspend (Boolean) -> CredentialResult, states: MutableList<String>) =
        Publisher(
            scope = scope,
            serverUris = arrayOf(required("MQTT_IT_TLS_URI")),
            tlsName = tlsName,
            clientId = "it-phone-${System.nanoTime()}",
            topic = "telemetry/ingest",
            persistence = MemoryPersistence(),
            credentials = creds,
            onState = { synchronized(states) { states.add(it) } },
            socketFactory = trusting(required("MQTT_IT_CA")),
        )

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `frames published before the first connect are delivered once connected`() = runBlocking {
        val good = CredentialResult.Ok(Credentials(required("MQTT_IT_USER"), required("MQTT_IT_TOKEN")))
        val seen = watch(required("MQTT_IT_PLAIN_URI"))
        val states = mutableListOf<String>()
        val p = publisher(required("MQTT_IT_TLS_NAME"), { good }, states)
        repeat(3) { p.publish(byteArrayOf(it.toByte())) }
        p.start()
        withTimeout(20_000) { while (seen.size < 3) delay(100) }
        assertEquals(listOf(0, 1, 2), seen.map { it.single().toInt() })
        p.publish(byteArrayOf(9))
        withTimeout(10_000) { while (p.acked.get() < 4) delay(100) }
        p.stop()
    }

    @Test
    fun `a refused token gets one forced refresh, then connects`() = runBlocking {
        val good = CredentialResult.Ok(Credentials(required("MQTT_IT_USER"), required("MQTT_IT_TOKEN")))
        val calls = mutableListOf<Boolean>()
        val states = mutableListOf<String>()
        val p = publisher(required("MQTT_IT_TLS_NAME"), { force ->
            calls.add(force)
            if (force) good else CredentialResult.Ok(Credentials("x", "expired-token"))
        }, states)
        p.start()
        withTimeout(20_000) { while (states.none { it.startsWith("connected") }) delay(100) }
        assertEquals(listOf(false, true), calls)
        p.stop()
    }

    @Test
    fun `certificate must match the broker name, not the dialled host`() = runBlocking {
        val good = CredentialResult.Ok(Credentials(required("MQTT_IT_USER"), required("MQTT_IT_TOKEN")))
        val states = mutableListOf<String>()
        val p = publisher("not-the-broker.example", { good }, states)
        p.start()
        withTimeout(20_000) { while (synchronized(states) { states.none { it.startsWith("unreachable") } }) delay(100) }
        assertTrue(synchronized(states) { states.none { it.startsWith("connected") } })
        p.stop()
    }
}
