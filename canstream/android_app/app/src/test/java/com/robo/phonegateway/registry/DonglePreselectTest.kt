package com.robo.phonegateway.registry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DonglePreselectTest {
    @Test
    fun offersOncePerDongleSoAManualPickSticks() {
        val p = DonglePreselect()
        assertTrue(p.shouldOffer("58:E6:C5:1A:CE:22")) // app opened with the dongle connected: preselect
        assertFalse(p.shouldOffer("58:E6:C5:1A:CE:22")) // Drive screen shown again after picking the Blazer
        assertTrue(p.shouldOffer("AA:BB:CC:DD:EE:FF")) // a different dongle
    }

    @Test
    fun noRememberedDongleNeverOffers() {
        assertFalse(DonglePreselect().shouldOffer(null))
    }
}
