package com.robo.phonegateway

/**
 * Fixed endpoints. The broker is the pub-sub mosquitto's public TLS listener
 * (infra/cluster-config/pub-sub), the OIDC provider the authentik
 * `phone-gateway` blueprint (infra/cluster-config/authentik/blueprints).
 */
object Config {
    const val ISSUER = "https://auth.danwils.com/application/o/phone-gateway/"
    const val CLIENT_ID = "phone-gateway"
    const val REDIRECT_URI = "com.robo.phonegateway:/oauth2redirect"

    /** Name on the broker's certificate; checked for every URI below. */
    const val BROKER_TLS_NAME = "mqtt.danwils.com"

    /**
     * Tried in order on every connect: the router's WAN forward, then the broker's
     * LAN address for when the phone is on the home network (the router does not
     * hairpin the WAN address back in).
     */
    val BROKER_URIS = arrayOf("ssl://mqtt.danwils.com:48213", "ssl://192.168.0.71:8883")

    /**
     * Each user publishes to `telemetry/ingest/<their sub>`: the broker (mqtt-auth ACL)
     * lets a user publish only there, and kreceiver stamps the user from the topic, so
     * every session is tied to the sign-in rather than to anything the app claims.
     * The bare base topic is the fallback while the server still allows it.
     */
    const val TOPIC_BASE = "telemetry/ingest"

    /** The registry's phone API (vehicles, later tracks and history); token-checked. */
    const val API_BASE = "https://robo.danwils.com/api/app"

    fun topicFor(subject: String) = "$TOPIC_BASE/$subject"
}
