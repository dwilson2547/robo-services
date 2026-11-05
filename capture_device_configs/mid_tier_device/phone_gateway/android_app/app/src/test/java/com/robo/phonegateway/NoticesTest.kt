package com.robo.phonegateway

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class NoticesTest {
    @Test
    fun gatewaySaysWhatTheDongleIsDoing() {
        assertEquals("Dongle connected", Notices.gateway(false, "connected to RoboDongle (filter off)"))
        assertEquals("Looking for the dongle", Notices.gateway(false, "scanning"))
        assertEquals("Connecting to the dongle", Notices.gateway(false, "connecting to RoboDongle"))
        assertEquals("Bluetooth is off", Notices.gateway(false, "bluetooth off"))
        assertEquals("Dongle not connected", Notices.gateway(false, "disconnected (status 8)"))
        assertEquals("Recording · Looking for the dongle", Notices.gateway(true, "scanning"))
    }

    @Test
    fun catchUpNamesTheDriveByItsLocalStart() {
        val ny = ZoneId.of("America/New_York")
        assertEquals("Uploading drive Wed 12:11: 40 of 417 s", Notices.catchUpProgress(1793812262671, 40, 417, ny))
        assertEquals("Uploaded 17 s from 2 drives", Notices.catchUpDone(17, 2))
        assertEquals("Uploaded 1 s from 1 drive", Notices.catchUpDone(1, 1))
    }
}
