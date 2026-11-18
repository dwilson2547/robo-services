package com.robo.phonegateway.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class CatchUpTest {
    @Test
    fun onlySessionLogsAreCaughtUp() {
        assertEquals(1793812262671L, CatchUp.sessionIdOf(File("session-1793812262671.bin")))
        assertNull(CatchUp.sessionIdOf(File("frames-20261103.bin")))
        assertNull(CatchUp.sessionIdOf(File("session-1.bin.tmp")))
    }

    @Test
    fun sendsWhatThePipelineLacksOldestFirstOnce() {
        val logged = listOf(3L to "c", 1L to "a", 2L to "b", 3L to "c-again")
        assertEquals(listOf(1L to "a", 3L to "c"), CatchUp.missing(logged, have = setOf(2L)))
        assertEquals(emptyList<Pair<Long, String>>(), CatchUp.missing(logged, have = setOf(1L, 2L, 3L)))
    }

    @Test
    fun decidesDoneSendWaitAndGivesUpOnlyAfterFruitlessSends() {
        val fresh = CatchUp.State()
        assertEquals(CatchUp.Action.DONE, CatchUp.decide(0, ended = true, fresh))
        assertEquals(CatchUp.Action.SEND, CatchUp.decide(16, ended = true, fresh))
        assertEquals(CatchUp.Action.WAIT, CatchUp.decide(0, ended = false, fresh))
        var s = fresh.afterSend(16)
        assertEquals(0, s.fruitless) // first send
        s = s.afterSend(10)
        assertEquals(0, s.fruitless) // progress
        repeat(CatchUp.MAX_FRUITLESS_SENDS) { s = s.afterSend(10) }
        assertEquals(CatchUp.Action.GIVE_UP, CatchUp.decide(10, ended = true, s))
        assertEquals(CatchUp.Action.DONE, CatchUp.decide(0, ended = true, s)) // done still wins
    }

    @Test
    fun stateSurvivesItsFile() {
        val s = CatchUp.State(done = false, lastMissing = 7, fruitless = 2)
        assertEquals(s, CatchUp.State.fromJson(s.toJson()))
        assertEquals(CatchUp.State(), CatchUp.State.fromJson("garbage"))
    }
}
