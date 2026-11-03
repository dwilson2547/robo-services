package com.robo.phonegateway.mqtt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CertificateNameTest {
    @Test
    fun `exact names match case-insensitively`() {
        assertTrue(CertificateName.matches("mqtt.danwils.com", "MQTT.danwils.com"))
        assertFalse(CertificateName.matches("mqtt.danwils.com", "auth.danwils.com"))
    }

    @Test
    fun `a wildcard covers exactly one left-most label`() {
        assertTrue(CertificateName.matches("*.danwils.com", "mqtt.danwils.com"))
        assertFalse(CertificateName.matches("*.danwils.com", "a.mqtt.danwils.com"))
        assertFalse(CertificateName.matches("*.danwils.com", "danwils.com"))
        assertFalse(CertificateName.matches("*.danwils.com", "mqtt.evil-danwils.com"))
    }
}
