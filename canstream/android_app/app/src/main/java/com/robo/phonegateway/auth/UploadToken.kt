package com.robo.phonegateway.auth

import org.json.JSONObject
import java.util.Base64

/**
 * The phone's own upload token (docs/plans/2026-11-04-reliable-uploads.md §2): an ES256
 * JWT signed with the hardware-backed [UploadKey], used as the MQTT password and for
 * the catch-up's registry call. Pure, so it is tested without a keystore.
 *
 *     header {"alg": "ES256", "typ": "JWT", "kid": <device id>}
 *     claims {"iss": "device:<device id>", "sub", "aud": "mqtt-ingest", "iat", "exp", "jti"}
 */
object UploadToken {
    const val AUDIENCE = "mqtt-ingest"
    const val LIFETIME_S = 10 * 60L

    private val b64 = Base64.getUrlEncoder().withoutPadding()

    /** `base64url(header).base64url(claims)`: the bytes the key signs. */
    fun signingInput(deviceId: String, sub: String, nowS: Long, jti: String): String {
        val header = JSONObject().put("alg", "ES256").put("typ", "JWT").put("kid", deviceId)
        val claims = JSONObject()
            .put("iss", "device:$deviceId").put("sub", sub).put("aud", AUDIENCE)
            .put("iat", nowS).put("exp", nowS + LIFETIME_S).put("jti", jti)
        return enc(header.toString()) + "." + enc(claims.toString())
    }

    fun assemble(signingInput: String, derSignature: ByteArray): String =
        signingInput + "." + b64.encodeToString(derToJose(derSignature))

    /**
     * The keystore signs ECDSA as DER `SEQUENCE { INTEGER r, INTEGER s }`; a JWT wants
     * r and s as two 32-byte big-endian numbers back to back.
     */
    fun derToJose(der: ByteArray, size: Int = 32): ByteArray {
        require(der[0] == 0x30.toByte()) { "not a DER sequence" }
        var i = if (der[1].toInt() and 0x80 != 0) 2 + (der[1].toInt() and 0x7f) else 2
        val out = ByteArray(size * 2)
        for (part in 0..1) {
            require(der[i] == 0x02.toByte()) { "not a DER integer" }
            val len = der[i + 1].toInt() and 0xff
            var start = i + 2
            var n = len
            while (n > size && der[start] == 0.toByte()) { start++; n-- } // sign padding
            require(n <= size) { "integer too long" }
            System.arraycopy(der, start, out, part * size + (size - n), n)
            i += 2 + len
        }
        return out
    }

    private fun enc(s: String) = b64.encodeToString(s.toByteArray(Charsets.UTF_8))
}
