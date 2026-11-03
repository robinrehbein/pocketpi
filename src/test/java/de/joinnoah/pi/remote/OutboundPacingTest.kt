package de.joinnoah.pi.remote

import org.junit.Assert.*
import org.junit.Test

class OutboundPacingTest {
    private fun invalid(action: () -> Unit) {
        try {
            action()
            fail("Expected invalid frame size to be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun rollingMessageLimitExpiresAt1100MillisAndResetClearsIt() {
        val budget = OutboundFrameBudget()
        repeat(24) {
            assertEquals(0L, budget.delayMillis(100, 1))
            budget.record(100, 1)
        }
        assertEquals(1100L, budget.delayMillis(100, 1))
        assertEquals(1L, budget.delayMillis(1199, 1))
        assertEquals(0L, budget.delayMillis(1200, 1))
        budget.record(1200, 1)
        budget.reset()
        repeat(24) { assertEquals(0L, budget.delayMillis(1200, 1)); budget.record(1200, 1) }
    }

    @Test
    fun rollingByteLimitWaitsUntilEnoughActualBytesExpire() {
        val budget = OutboundFrameBudget()
        budget.record(0, 300_000)
        budget.record(200, 300_000)
        budget.record(400, 186_432)
        assertEquals(1L, budget.delayMillis(1099, 1))
        assertEquals(0L, budget.delayMillis(1100, 300_000))
        assertEquals(200L, budget.delayMillis(1100, 300_001))
        assertEquals(0L, budget.delayMillis(1300, 300_001))
    }

    @Test
    fun mixedFramesRemainBelowBothHeadroomLimitsAndDelayIsBounded() {
        val budget = OutboundFrameBudget()
        var now = 0L
        val sends = mutableListOf<Pair<Long, Int>>()
        repeat(120) { index ->
            val bytes = if (index % 3 == 0) 256 else 88_500
            val delay = budget.delayMillis(now, bytes)
            assertTrue(delay in 0..1100)
            now += delay
            assertEquals(0L, budget.delayMillis(now, bytes))
            budget.record(now, bytes)
            sends += now to bytes
            val live = sends.filter { now - it.first < 1100 }
            assertTrue(live.size <= 24)
            assertTrue(live.sumOf { it.second } <= 768 * 1024)
        }
    }

    @Test
    fun invalidAndImpossibleFrameSizesFailWithoutChangingTheLedger() {
        val budget = OutboundFrameBudget()
        invalid { budget.delayMillis(0, -1) }
        invalid { budget.delayMillis(0, 768 * 1024 + 1) }
        invalid { budget.record(0, -1) }
        invalid { budget.record(0, 768 * 1024 + 1) }
        invalid { OutboundFrameBudget.encryptedFrameUpperBound(-1) }
        invalid { OutboundFrameBudget.encryptedFrameUpperBound(Int.MAX_VALUE) }
        repeat(24) { assertEquals(0L, budget.delayMillis(0, 1)); budget.record(0, 1) }
        assertEquals(1100L, budget.delayMillis(0, 1))
    }

    @Test
    fun realSecureChannelFramesNeverExceedEstimatedSerializedCost() {
        val context = Wire.objectOf(
            "v" to 1, "type" to "hello", "mode" to "device",
            "routeId" to Wire.random(16), "keyId" to Wire.random(16),
            "connectionId" to Wire.random(16), "clientNonce" to Wire.random(32),
            "serverNonce" to Wire.random(32),
        )
        val channel = SecureChannel(Wire.random(32), context)
        val payloads = listOf(
            Wire.objectOf("type" to "device.authenticate"),
            Wire.objectOf("type" to "session.editor.presence", "active" to true),
            Wire.objectOf("type" to "chunk", "data" to "A".repeat(65_536)),
            Wire.objectOf("type" to "editor", "text" to ("😀\\\"\n".repeat(8000))),
            Wire.objectOf("type" to "large", "text" to "x".repeat(250_000)),
        )
        for (payload in payloads) {
            val plainBytes = payload.toString().toByteArray(Charsets.UTF_8).size
            val estimate = OutboundFrameBudget.encryptedFrameUpperBound(plainBytes)
            val actual = channel.seal(payload).toString().toByteArray(Charsets.UTF_8).size
            assertTrue("actual $actual exceeds estimate $estimate", actual <= estimate)
        }
    }

    @Test
    fun queueReservesSlotsForControlsWithoutLimitingControlsToEight() {
        val queue = OutboundQueueBudget()
        repeat(56) { assertTrue(queue.tryReserve(1, false)) }
        assertFalse(queue.tryReserve(1, false))
        repeat(8) { assertTrue(queue.tryReserve(1, true)) }
        assertFalse(queue.tryReserve(1, true))
        queue.release(1, false)
        assertTrue(queue.tryReserve(1, true))
        queue.reset()
        repeat(64) { assertTrue(queue.tryReserve(1, true)) }
        assertFalse(queue.tryReserve(1, true))
    }

    @Test
    fun queueReservesBytesForControlsAndReleaseMakesCapacityAvailable() {
        val queue = OutboundQueueBudget()
        assertTrue(queue.tryReserve(496 * 1024, false))
        assertFalse(queue.tryReserve(1, false))
        assertTrue(queue.tryReserve(16 * 1024, true))
        assertFalse(queue.tryReserve(1, true))
        queue.release(16 * 1024, true)
        queue.release(496 * 1024, false)
        assertTrue(queue.tryReserve(496 * 1024, false))
        queue.reset()
        assertTrue(queue.tryReserve(512 * 1024, true))
    }

    @Test
    fun rejectedQueueSizesAndOverflowDoNotConsumeCapacity() {
        val queue = OutboundQueueBudget()
        assertFalse(queue.tryReserve(-1, false))
        assertFalse(queue.tryReserve(512 * 1024 + 1, true))
        repeat(56) { assertTrue(queue.tryReserve(1, false)) }
        repeat(20) { assertFalse(queue.tryReserve(1, false)) }
        repeat(8) { assertTrue(queue.tryReserve(1, true)) }
        queue.release(1, true)
        assertTrue(queue.tryReserve(1, true))
    }

    @Test
    fun signedMonotonicTimeWorksAtBothLongBoundaries() {
        val low = OutboundFrameBudget()
        repeat(24) { low.record(Long.MIN_VALUE + 10, 1) }
        assertEquals(1L, low.delayMillis(Long.MIN_VALUE + 1109, 1))
        assertEquals(0L, low.delayMillis(Long.MIN_VALUE + 1110, 1))
        low.record(Long.MIN_VALUE + 1110, 1)
        val high = OutboundFrameBudget()
        repeat(24) { high.record(Long.MAX_VALUE - 1100, 1) }
        assertEquals(1L, high.delayMillis(Long.MAX_VALUE - 1, 1))
        assertEquals(0L, high.delayMillis(Long.MAX_VALUE, 1))
        high.record(Long.MAX_VALUE, 1)
    }

    @Test
    fun signedLeapOverflowExpiresOldEntriesAndBackwardTimeIsAtomic() {
        val empty = OutboundFrameBudget()
        assertEquals(0L, empty.delayMillis(-5, 1))
        invalid { empty.delayMillis(-6, 1) }
        assertEquals(0L, empty.delayMillis(-5, 1))
        val budget = OutboundFrameBudget()
        repeat(24) { budget.record(Long.MIN_VALUE, 1) }
        assertEquals(0L, budget.delayMillis(Long.MAX_VALUE, 1))
        budget.record(Long.MAX_VALUE, 1)
        invalid { budget.delayMillis(Long.MAX_VALUE - 1, 1) }
        invalid { budget.record(Long.MAX_VALUE - 1, 1) }
        repeat(23) { budget.record(Long.MAX_VALUE, 1) }
        assertEquals(1100L, budget.delayMillis(Long.MAX_VALUE, 1))
    }

    @Test
    fun recordWhenFullAndUnmatchedQueueReleaseLeaveBudgetsIntact() {
        val frames = OutboundFrameBudget()
        repeat(24) { frames.record(-10, 1) }
        invalid { frames.record(-10, 1) }
        assertEquals(1L, frames.delayMillis(1089, 1))
        assertEquals(0L, frames.delayMillis(1090, 1))
        val queue = OutboundQueueBudget()
        repeat(56) { assertTrue(queue.tryReserve(2, false)) }
        invalid { queue.release(1, false) }
        invalid { queue.release(2, true) }
        assertFalse(queue.tryReserve(1, false))
        queue.release(2, false)
        assertTrue(queue.tryReserve(2, false))
        repeat(8) { assertTrue(queue.tryReserve(1, true)) }
        assertFalse(queue.tryReserve(1, true))
    }

}
