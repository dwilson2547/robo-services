package com.robo.phonegateway.session

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class OutboxTest {

    @Test
    fun `pending events come back in send order until removed`() {
        val outbox = Outbox(Files.createTempDirectory("outbox").toFile())
        outbox.put(Outbox.endName(1_793_000_000_000), "end-a")
        outbox.put(Outbox.startName(1_793_000_500_000), "start-b")
        outbox.put(Outbox.startName(1_793_000_000_000), "start-a")
        assertEquals(listOf("start-a", "end-a", "start-b"), outbox.pending().map { it.second })
        outbox.remove(Outbox.startName(1_793_000_000_000))
        assertEquals(listOf("end-a", "start-b"), outbox.pending().map { it.second })
    }
}
