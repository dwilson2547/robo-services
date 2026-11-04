package com.robo.phonegateway.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID

/**
 * The phone's upload key (docs/plans/2026-11-04-reliable-uploads.md §2): EC P-256 in
 * the Android Keystore (hardware-backed where the phone has it). The private half
 * can't be exported, so it never leaves the phone. The public half is registered with
 * the registry for the signed-in user; [token] signs the phone's own upload tokens
 * with no network and no refresh.
 */
class UploadKey private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("upload_key", Context.MODE_PRIVATE)
    private val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    /** The user the key is registered for, or null when it isn't registered. */
    val registeredSub: String? get() = prefs.getString(KEY_SUB, null)

    fun markRegistered(sub: String) = prefs.edit().putString(KEY_SUB, sub).apply()

    /** The public key as PEM (SubjectPublicKeyInfo), creating the key pair the first time. */
    @Synchronized
    fun publicKeyPem(): String {
        val cert = store.getCertificate(ALIAS) ?: run { generate(); store.getCertificate(ALIAS) }
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(cert.publicKey.encoded)
        return "-----BEGIN PUBLIC KEY-----\n$body\n-----END PUBLIC KEY-----\n"
    }

    /** A fresh upload token for [sub] from this device. */
    fun token(deviceId: String, sub: String, nowS: Long = System.currentTimeMillis() / 1000): String {
        val key = store.getKey(ALIAS, null) as? PrivateKey ?: error("no upload key")
        val input = UploadToken.signingInput(deviceId, sub, nowS, UUID.randomUUID().toString())
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(input.toByteArray(Charsets.US_ASCII))
            sign()
        }
        return UploadToken.assemble(input, der)
    }

    /** Delete the key pair and the registration (sign-out). */
    @Synchronized
    fun forget() {
        runCatching { store.deleteEntry(ALIAS) }
        prefs.edit().remove(KEY_SUB).apply()
    }

    private fun generate() {
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply {
            initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build(),
            )
            generateKeyPair()
        }
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "upload-key-v1"
        private const val KEY_SUB = "registered_sub"

        @Volatile private var instance: UploadKey? = null

        fun get(context: Context): UploadKey =
            instance ?: synchronized(this) { instance ?: UploadKey(context.applicationContext).also { instance = it } }
    }
}
