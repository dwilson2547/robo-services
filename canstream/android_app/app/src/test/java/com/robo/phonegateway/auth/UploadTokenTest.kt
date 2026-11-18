package com.robo.phonegateway.auth

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class UploadTokenTest {
    private val dec = Base64.getUrlDecoder()

    @Test
    fun aSignedTokenCarriesTheClaimsAndVerifiesWithThePublicKey() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val input = UploadToken.signingInput("phone-1", "sub-a", nowS = 1_793_800_000, jti = "j1")
        val der = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(input.toByteArray()); sign() }
        val token = UploadToken.assemble(input, der)

        val (h, c, sig) = token.split(".")
        assertEquals("ES256", JSONObject(String(dec.decode(h))).getString("alg"))
        val claims = JSONObject(String(dec.decode(c)))
        assertEquals("device:phone-1", claims.getString("iss"))
        assertEquals("sub-a", claims.getString("sub"))
        assertEquals("mqtt-ingest", claims.getString("aud"))
        assertEquals(1_793_800_000 + UploadToken.LIFETIME_S, claims.getLong("exp"))

        val raw = dec.decode(sig)
        assertEquals(64, raw.size)
        val verifier = Signature.getInstance("SHA256withECDSA").apply { initVerify(pair.public); update(input.toByteArray()) }
        assertTrue(verifier.verify(joseToDer(raw)))
    }

    @Test
    fun derIntegersArePaddedAndStrippedToThirtyTwoBytes() {
        // r has a 0x00 sign byte (33 bytes), s is short (31 bytes)
        val r = ByteArray(33).also { it[0] = 0; it[1] = 0x80.toByte(); it[32] = 1 }
        val s = ByteArray(31) { 7 }
        val der = byteArrayOf(0x30, (4 + r.size + s.size).toByte(), 0x02, r.size.toByte()) + r + byteArrayOf(0x02, s.size.toByte()) + s
        val jose = UploadToken.derToJose(der)
        assertArrayEquals(r.copyOfRange(1, 33), jose.copyOfRange(0, 32))
        assertEquals(0.toByte(), jose[32])
        assertArrayEquals(s, jose.copyOfRange(33, 64))
    }

    private fun joseToDer(raw: ByteArray): ByteArray {
        fun int(b: ByteArray) = BigInteger(1, b).toByteArray().let { byteArrayOf(0x02, it.size.toByte()) + it }
        val body = int(raw.copyOfRange(0, 32)) + int(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }
}
