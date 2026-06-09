package com.robo.phonecompanion.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DbcRoundTripTest {

    private val original = """
        VERSION "1.0"

        NS_ :

        BS_:

        BU_:

        BO_ 2229 EngineData: 8 Vector__XXX
         SG_ EngineRPM : 0|16@1+ (0.25,0) [0|8000] "rpm" Vector__XXX
         SG_ ThrottlePos : 16|8@1+ (0.4,0) [0|100] "%" Vector__XXX

        BO_ 768 BrakeData: 4 Vector__XXX
         SG_ BrakePressure : 0|12@1+ (0.1,0) [0|400] "bar" Vector__XXX

        CM_ BO_ 2229 "Engine sensor data";
        CM_ SG_ 2229 EngineRPM "Engine speed in RPM";

        VAL_ 768 BrakePressure 0 "Off" 1 "On" ;
    """.trimIndent()

    @Test fun `parse-write-parse round trip preserves messages`() {
        val first = DbcParser.parse(original)
        val written = DbcWriter.write(first)
        val second = DbcParser.parse(written)

        assertEquals(first.messages.size, second.messages.size)
    }

    @Test fun `round trip preserves signal definitions`() {
        val first = DbcParser.parse(original)
        val written = DbcWriter.write(first)
        val second = DbcParser.parse(written)

        val sig1 = first.messages[2229]!!.signals.first { it.name == "EngineRPM" }
        val sig2 = second.messages[2229]!!.signals.first { it.name == "EngineRPM" }

        assertEquals(sig1.startBit, sig2.startBit)
        assertEquals(sig1.length, sig2.length)
        assertEquals(sig1.byteOrder, sig2.byteOrder)
        assertEquals(sig1.factor, sig2.factor, 0.0001)
        assertEquals(sig1.offset, sig2.offset, 0.0001)
        assertEquals(sig1.unit, sig2.unit)
    }

    @Test fun `round trip preserves comments`() {
        val first = DbcParser.parse(original)
        val written = DbcWriter.write(first)
        val second = DbcParser.parse(written)

        assertEquals(first.messages[2229]!!.comment, second.messages[2229]!!.comment)
        val sig = second.messages[2229]!!.signals.first { it.name == "EngineRPM" }
        assertNotNull(sig.comment)
    }
}
