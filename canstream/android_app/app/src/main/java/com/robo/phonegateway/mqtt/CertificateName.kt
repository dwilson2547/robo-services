package com.robo.phonegateway.mqtt

import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier

/**
 * Checks that a server certificate names [expected] in its DNS subject
 * alternative names. Chain trust is the trust manager's job and has already
 * happened by the time a HostnameVerifier runs; this only replaces "does the
 * certificate match the host I dialled" with "does it match the broker's name",
 * so the LAN address can present the same certificate as the public name.
 */
object CertificateName {

    fun verifier(expected: String) = HostnameVerifier { _, session ->
        val leaf = session.peerCertificates.firstOrNull() as? X509Certificate
        leaf != null && names(leaf, expected)
    }

    fun names(cert: X509Certificate, expected: String): Boolean {
        val dnsNames = cert.subjectAlternativeNames.orEmpty()
            .filter { it.size >= 2 && it[0] == DNS_NAME }
            .map { it[1] as String }
        return dnsNames.any { matches(it, expected) }
    }

    internal fun matches(pattern: String, host: String): Boolean {
        if (pattern.equals(host, ignoreCase = true)) return true
        // single left-most wildcard label only: *.example.com matches a.example.com
        if (!pattern.startsWith("*.")) return false
        val suffix = pattern.substring(1)
        val label = host.substringBefore('.')
        return host.endsWith(suffix, ignoreCase = true) && label.isNotEmpty() &&
            host.length == label.length + suffix.length
    }

    private const val DNS_NAME = 2
}
