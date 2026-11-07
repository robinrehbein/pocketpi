package de.joinnoah.pi.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledActivityPresentationAdapterTest {
    private fun continuations(freshness: ContinuationFreshness = ContinuationFreshness.LIVE) =
        SessionContinuations(
            owner = "runtime-a",
            items = listOf(
                SessionContinuation(
                    id = "check-a",
                    kind = ContinuationKind.WAKEUP,
                    phase = ContinuationPhase.SCHEDULED,
                    restartPolicy = ContinuationRestartPolicy.VOLATILE,
                    purpose = "Observe CI",
                    dueAt = 120_000,
                ),
            ),
            freshness = freshness,
        )

    @Test fun preservesAuthoritativeFieldsAndCommandState() {
        val presentation = scheduledActivityPresentation(
            continuations(), connected = true, stale = false,
            stoppingIds = setOf("check-a"), stopFailedIds = setOf("previous"),
        )
        assertFalse(presentation.stale)
        assertEquals(setOf("check-a"), presentation.stoppingIds)
        assertEquals(setOf("previous"), presentation.stopFailedIds)
        assertEquals(
            ScheduledActivityItem(
                id = "check-a", kind = ScheduledActivityKind.WAKEUP,
                phase = ScheduledActivityPhase.SCHEDULED, purpose = "Observe CI", dueAt = 120_000,
            ),
            presentation.items.single(),
        )
    }

    @Test fun staleConnectionNeverLooksLive() {
        assertTrue(scheduledActivityPresentation(continuations(), connected = false, stale = false).stale)
        assertTrue(scheduledActivityPresentation(continuations(), connected = true, stale = true).stale)
        for (freshness in ContinuationFreshness.entries.filter { it != ContinuationFreshness.LIVE }) {
            val presentation = scheduledActivityPresentation(continuations(freshness), connected = true, stale = false)
            assertTrue(freshness.name, presentation.stale)
            assertEquals("check-a", presentation.items.single().id)
        }
    }

    @Test fun dispatchedDoesNotBecomeRunningBasedOnTime() {
        val source = continuations().let { state ->
            state.copy(items = listOf(state.items.single().copy(phase = ContinuationPhase.CONTINUING, dueAt = null)))
        }
        assertEquals(
            ScheduledActivityPhase.CONTINUING,
            scheduledActivityPresentation(source, connected = true, stale = false).items.single().phase,
        )
    }

    @Test fun authoritativeEmptyRemainsEmpty() {
        val empty = SessionContinuations(owner = "runtime-a", freshness = ContinuationFreshness.LIVE)
        val presentation = scheduledActivityPresentation(empty, connected = true, stale = false)
        assertTrue(presentation.items.isEmpty())
        assertFalse(presentation.stale)
    }
}
