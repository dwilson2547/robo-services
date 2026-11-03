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

    /** kreceiver subscribes here; the broker ACL allows publishing nowhere else. */
    const val TOPIC = "telemetry/ingest"
}
