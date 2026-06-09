package com.robo.phonecompanion.data.parser

import com.robo.phonecompanion.data.model.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DbcParserTest {

    private val sampleDbc = """
        VERSION "1.0"

        NS_ :

        BS_:

        BU_:

        BO_ 2229 EngineData: 8 Vector__XXX
         SG_ EngineRPM : 0|16@1+ (0.25,0) [0|8000] "rpm" Vector__XXX
         SG_ ThrottlePos : 16|8@1+ (0.4,0) [0|100] "%" Vector__XXX
         SG_ IgnTiming : 24|8@1- (0.5,-64) [-64|63.5] "deg" Vector__XXX

        BO_ 768 BrakeData: 4 Vector__XXX
         SG_ BrakePressure : 0|12@1+ (0.1,0) [0|400] "bar" Vector__XXX
         SG_ BrakeSwitch : 12|1@1+ (1,0) [0|1] "" Vector__XXX

        CM_ BO_ 2229 "Engine sensor data";
        CM_ SG_ 2229 EngineRPM "Engine speed in RPM";

        VAL_ 768 BrakeSwitch 0 "Off" 1 "On" ;
    """.trimIndent()

    @Test fun `parses version`() {
        val dbc = DbcParser.parse(sampleDbc)
        assertEquals("1.0", dbc.version)
    }

    @Test fun `parses message count`() {
        val dbc = DbcParser.parse(sampleDbc)
        assertEquals(2, dbc.messages.size)
    }

    @Test fun `parses engine message`() {
        val dbc = DbcParser.parse(sampleDbc)
        val msg = dbc.messages[2229]
        assertNotNull(msg)
        assertEquals("EngineData", msg!!.name)
        assertEquals(8, msg.dlc)
        assertEquals(3, msg.signals.size)
    }

    @Test fun `parses RPM signal`() {
        val dbc = DbcParser.parse(sampleDbc)
        val sig = dbc.messages[2229]!!.signals.first { it.name == "EngineRPM" }
        assertEquals(0, sig.startBit)
        assertEquals(16, sig.length)
        assertEquals(ByteOrder.INTEL, sig.byteOrder)
        assertEquals(false, sig.signed)
        assertEquals(0.25, sig.factor, 0.0001)
        assertEquals(0.0, sig.offset, 0.0001)
        assertEquals("rpm", sig.unit)
    }

    @Test fun `parses signed signal`() {
        val dbc = DbcParser.parse(sampleDbc)
        val sig = dbc.messages[2229]!!.signals.first { it.name == "IgnTiming" }
        assertEquals(true, sig.signed)
        assertEquals(0.5, sig.factor, 0.0001)
        assertEquals(-64.0, sig.offset, 0.0001)
    }

    @Test fun `parses message comment`() {
        val dbc = DbcParser.parse(sampleDbc)
        assertEquals("Engine sensor data", dbc.messages[2229]!!.comment)
    }

    @Test fun `parses signal comment`() {
        val dbc = DbcParser.parse(sampleDbc)
        val sig = dbc.messages[2229]!!.signals.first { it.name == "EngineRPM" }
        assertEquals("Engine speed in RPM", sig.comment)
    }

    @Test fun `parses value descriptions`() {
        val dbc = DbcParser.parse(sampleDbc)
        val sig = dbc.messages[768]!!.signals.first { it.name == "BrakeSwitch" }
        assertEquals("Off", sig.valueDescriptions[0L])
        assertEquals("On", sig.valueDescriptions[1L])
    }

    @Test fun `messageForCanId finds standard frame`() {
        val dbc = DbcParser.parse(sampleDbc)
        assertNotNull(dbc.messageForCanId(2229))
    }

    @Test fun `messageForCanId returns null for unknown`() {
        val dbc = DbcParser.parse(sampleDbc)
        assertNull(dbc.messageForCanId(0x999))
    }

    @Test fun `empty input returns empty dbc`() {
        val dbc = DbcParser.parse("")
        assertTrue(dbc.messages.isEmpty())
    }
}
