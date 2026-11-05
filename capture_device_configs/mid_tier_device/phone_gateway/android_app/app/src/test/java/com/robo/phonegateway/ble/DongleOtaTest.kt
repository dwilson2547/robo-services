package com.robo.phonegateway.ble

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DongleOtaTest {

    /** A dongle that answers like the firmware, recording what it was sent. */
    private class FakeDongle(
        val startReply: String? = "READY",
        val commitReplies: List<String> = listOf("VERIFYING", "OK"),
        val failDataAt: Int = -1,
        val commitAcked: Boolean = true,
        val comesBackOn: String? = null,
    ) : DongleOta.Link {
        val controls = mutableListOf<String>()
        val chunks = mutableListOf<ByteArray>()
        private val pending = ArrayDeque<String>()

        override suspend fun control(command: String): Boolean {
            controls += command
            when {
                command.startsWith("START:") -> startReply?.let { pending += it }
                command == "COMMIT" -> { pending += commitReplies; return commitAcked }
            }
            return true
        }

        override suspend fun data(chunk: ByteArray): Boolean {
            if (chunks.size == failDataAt) return false
            chunks += chunk
            if (chunks.size % 10 == 0) pending += "PROGRESS:${chunks.sumOf { it.size }}"
            return true
        }

        override suspend fun status(timeoutMs: Long): String? = pending.removeFirstOrNull()
        override fun clearStatus() = pending.clear()
        override suspend fun reconnectedVersion(timeoutMs: Long): String? = comesBackOn
    }

    private val image = ByteArray(2_500) { (it % 251).toByte() }

    @Test
    fun `uploads the image in mtu-sized chunks and commits`() = runBlocking {
        val dongle = FakeDongle()
        val progress = mutableListOf<DongleOta.Progress>()
        DongleOta.upload(dongle, image, "1.7.1", chunkSize = 244) { progress += it }
        assertEquals(listOf("START:2500", "COMMIT"), dongle.controls)
        assertTrue(dongle.chunks.all { it.size <= 244 })
        assertTrue(image.contentEquals(dongle.chunks.reduce { a, b -> a + b }))
        assertEquals(DongleOta.Progress.Sending(2500, 2500), progress[progress.size - 2])
        assertEquals(DongleOta.Progress.Verifying, progress.last())
    }

    @Test
    fun `chunks never exceed 512 bytes`() = runBlocking {
        val dongle = FakeDongle()
        DongleOta.upload(dongle, image, "1.7.1", chunkSize = 514) {}
        assertEquals(512, dongle.chunks.maxOf { it.size })
    }

    @Test
    fun `a dongle that does not answer START fails without sending data`() = runBlocking {
        val dongle = FakeDongle(startReply = null)
        val e = runCatching { DongleOta.upload(dongle, image, "1.7.1", 244) {} }.exceptionOrNull()
        assertTrue(e is DongleOta.Failed)
        assertTrue(dongle.chunks.isEmpty())
    }

    @Test
    fun `a failed chunk aborts the upload`() = runBlocking {
        val dongle = FakeDongle(failDataAt = 3)
        try {
            DongleOta.upload(dongle, image, "1.7.1", 244) {}
            fail("expected Failed")
        } catch (e: DongleOta.Failed) {
            assertEquals("write failed at byte 732", e.message)
        }
        assertEquals("ABORT", dongle.controls.last())
        assertFalse("COMMIT" in dongle.controls)
    }

    @Test
    fun `a rejected image reports the dongle's reason`() = runBlocking {
        val dongle = FakeDongle(commitReplies = listOf("VERIFYING", "ERROR:verify_failed_5379"))
        val e = runCatching { DongleOta.upload(dongle, image, "1.7.1", 244) {} }.exceptionOrNull()
        assertEquals("dongle: ERROR:verify_failed_5379", e?.message)
    }

    @Test
    fun `version comparison`() {
        assertTrue(DongleOta.isNewer("1.7.0", "1.6.4"))
        assertTrue(DongleOta.isNewer("1.10.0", "1.9.9"))
        assertTrue(DongleOta.isNewer("1.7.1", "1.7"))
        assertFalse(DongleOta.isNewer("1.7.0", "1.7.0"))
        assertFalse(DongleOta.isNewer("1.6.4", "1.7.0"))
        assertFalse(DongleOta.isNewer("1.7.0", null))
        assertTrue(DongleOta.isNewer("1.7.0", "garbage"))
    }

    @Test
    fun `an unacknowledged COMMIT confirmed by OK succeeds`() = runBlocking {
        DongleOta.upload(FakeDongle(commitAcked = false), image, "1.7.1", 244) {}
    }

    @Test
    fun `a dongle that restarts before OK is confirmed by its new version`() = runBlocking {
        val dongle = FakeDongle(commitAcked = false, commitReplies = listOf("VERIFYING", DongleOta.DISCONNECTED), comesBackOn = "1.7.1")
        DongleOta.upload(dongle, image, "1.7.1", 244) {}
    }

    @Test
    fun `a dongle that comes back on the old version failed`() = runBlocking {
        val dongle = FakeDongle(commitAcked = false, commitReplies = listOf(DongleOta.DISCONNECTED), comesBackOn = "1.7.0")
        val e = runCatching { DongleOta.upload(dongle, image, "1.7.1", 244) {} }.exceptionOrNull()
        assertEquals("dongle came back on 1.7.0, not 1.7.1", e?.message)
    }

    @Test
    fun `a dongle that never comes back failed`() = runBlocking {
        val dongle = FakeDongle(commitAcked = false, commitReplies = emptyList(), comesBackOn = null)
        val e = runCatching { DongleOta.upload(dongle, image, "1.7.1", 244) {} }.exceptionOrNull()
        assertEquals("dongle did not come back after COMMIT", e?.message)
    }
}
