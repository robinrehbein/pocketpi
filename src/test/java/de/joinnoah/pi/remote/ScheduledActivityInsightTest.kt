package de.joinnoah.pi.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduledActivityInsightTest {
    private val now = 1_000_000L
    private fun item(
        phase: ScheduledActivityPhase = ScheduledActivityPhase.SCHEDULED,
        dueAt: Long? = null,
    ) = ScheduledActivityItem("check", ScheduledActivityKind.WAKEUP, phase, dueAt = dueAt)

    private fun label(item: ScheduledActivityItem) =
        scheduledActivityLabel(ScheduledActivityPresentation(listOf(item)), now)

    @Test fun `empty presentation has no pill category`() {
        assertEquals(ScheduledActivityLabel.EMPTY, scheduledActivityLabel(ScheduledActivityPresentation(), now).category)
    }

    @Test fun `future check rounds up to visible minutes`() {
        listOf(1L to 1L, 59_999L to 1L, 60_000L to 1L, 60_001L to 2L, 120_000L to 2L).forEach { (delay, minutes) ->
            assertEquals(ScheduledActivityLabelSelection(ScheduledActivityLabel.CHECK_IN, minutes), label(item(dueAt = now + delay)))
        }
    }

    @Test fun `due overdue and unknown time wait for authoritative status`() {
        listOf(null, now, now - 1, now - 120_000).forEach { dueAt ->
            assertEquals(ScheduledActivityLabel.WAITING_FOR_STATUS, label(item(dueAt = dueAt)).category)
        }
        assertEquals(ScheduledActivityLabel.WAITING_FOR_STATUS, label(item().copy(delayMs = 60_000)).category)
    }

    @Test fun `stale overrides future countdown and multiple count`() {
        val one = item(dueAt = now + 60_000)
        listOf(listOf(one), listOf(one, one.copy(id = "other"))).forEach { items ->
            val result = scheduledActivityLabel(ScheduledActivityPresentation(items, stale = true), now)
            assertEquals(ScheduledActivityLabel.STALE, result.category)
            assertNull(result.minutes)
        }
    }

    @Test fun `pending stop stays visible without claiming cancellation`() {
        val pending = ScheduledActivityPresentation(listOf(item(dueAt = now + 60_000)), stoppingIds = setOf("check"))
        assertEquals(ScheduledActivityLabel.STOPPING, scheduledActivityLabel(pending, now).category)
        assertEquals(ScheduledActivityLabel.STALE, scheduledActivityLabel(pending.copy(stale = true), now).category)
        assertEquals(ScheduledActivityLabel.CHECK_IN, scheduledActivityLabel(pending.copy(stoppingIds = setOf("old")), now).category)
    }

    @Test fun `multiple activities use count rather than earliest countdown`() {
        assertEquals(ScheduledActivityLabel.MULTIPLE, scheduledActivityLabel(
            ScheduledActivityPresentation(listOf(item(dueAt = now + 1), item().copy(id = "other"))), now,
        ).category)
    }

    @Test fun `phase takes precedence over due time and continuation is not running`() {
        mapOf(
            ScheduledActivityPhase.AFTER_TURN to ScheduledActivityLabel.AFTER_TURN,
            ScheduledActivityPhase.CONTINUING to ScheduledActivityLabel.CONTINUING,
            ScheduledActivityPhase.HOLDING to ScheduledActivityLabel.HOLDING,
            ScheduledActivityPhase.PAUSED to ScheduledActivityLabel.PAUSED,
        ).forEach { (phase, expected) ->
            assertEquals(ScheduledActivityLabelSelection(expected), label(item(phase, now + 60_000)))
        }
    }

    @Test fun `stop state is scoped and pending takes precedence over retry`() {
        val presentation = ScheduledActivityPresentation(
            stoppingIds = setOf("pending"), stopFailedIds = setOf("pending", "failed"),
        )
        assertEquals(ScheduledActivityStopState.AVAILABLE, scheduledActivityStopState(presentation, "other"))
        assertEquals(ScheduledActivityStopState.PENDING, scheduledActivityStopState(presentation, "pending"))
        assertEquals(ScheduledActivityStopState.FAILED, scheduledActivityStopState(presentation, "failed"))
        listOf("pending", "failed", "other").forEach { id ->
            assertEquals(ScheduledActivityStopState.DISABLED, scheduledActivityStopState(presentation.copy(stale = true), id))
        }
    }
}
