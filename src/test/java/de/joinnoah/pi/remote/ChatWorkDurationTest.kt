package de.joinnoah.pi.remote

import org.junit.Assert.*
import org.junit.Test
import de.joinnoah.pi.remote.reconcileChatWorkTiming as reconcileTiming

class ChatWorkDurationTest {
    private fun reconcileChatWorkTiming(previous: RemoteState, next: RemoteState, now: Long): RemoteState =
        reconcileTiming(previous, next) { now }

    @Test fun unrelatedUpdatesDoNotReadTheClock() {
        reconcileTiming(idle, idle) { error("Clock must stay lazy") }
    }

    private val idle = RemoteState(connected = true, selection = RemoteSelection("host", "project", "session"), status = "idle")
    private fun step(previous: RemoteState, status: String, now: Long) =
        reconcileChatWorkTiming(previous, previous.copy(status = status), now)

    @Test fun observedRunCompletesOnceAndNewRunResets() {
        val running = step(idle, "running", 1000)
        val done = step(running, "idle", 55000)
        assertEquals(54000L, done.chatWorkTiming.durationMillis)
        assertEquals(done.chatWorkTiming, step(done, "idle", 90000).chatWorkTiming)
        val line = sessionActivityLine(done, emptyList(), done.selection)!!
        assertEquals(StatusGlyphKind.WORK_COMPLETED, line.glyphKind)
        assertFalse(line.animate)
        assertEquals(54000L, line.completedDurationMillis)
        assertNull(step(done, "running", 100000).chatWorkTiming.durationMillis)
    }

    @Test fun waitingAndFollowUpRemainPartOfTheRun() {
        val running = step(idle, "running", 1000)
        val waiting = step(running, "waiting", 2000)
        val followUp = reconcileChatWorkTiming(waiting, waiting.copy(sending = true), 3000)
        val resumed = step(followUp, "running", 4000)
        assertEquals(312000L, step(resumed, "idle", 313000).chatWorkTiming.durationMillis)
    }

    @Test fun unknownStartAndLostAuthorityNeverInventADuration() {
        assertNull(step(idle.copy(status = "running"), "idle", 9000).chatWorkTiming.durationMillis)
        val running = step(idle, "running", 1000)
        val invalid = listOf(
            running.copy(connected = false), running.copy(stale = true), running.copy(loading = true),
            running.copy(preview = true), running.copy(selection = RemoteSelection("host", "project", "other")),
        )
        for (next in invalid) {
            val cleared = reconcileChatWorkTiming(running, next, 2000)
            assertEquals(ChatWorkTiming(), cleared.chatWorkTiming)
            assertNull(step(cleared, "idle", 9000).chatWorkTiming.durationMillis)
        }
    }

    @Test fun reconnectFromStaleIdleDoesNotStartAMidRunClock() {
        val running = reconcileChatWorkTiming(idle.copy(stale = true), idle.copy(status = "running"), 1000)
        assertNull(step(running, "idle", 9000).chatWorkTiming.durationMillis)
    }

    @Test fun restoredTimingCannotCrossSelectionOrConnectionBoundaries() {
        val cached = idle.copy(chatWorkTiming = ChatWorkTiming(durationMillis = 5000))
        val other = idle.copy(selection = RemoteSelection("host", "project", "other"))
        assertEquals(ChatWorkTiming(), reconcileChatWorkTiming(other, cached, 9000).chatWorkTiming)
        assertEquals(ChatWorkTiming(), reconcileChatWorkTiming(idle.copy(stale = true), cached, 9000).chatWorkTiming)
    }

    @Test fun summaryDoesNotLeakIntoOtherDestinationsOrDisconnectedStates() {
        val done = step(step(idle, "running", 0), "idle", 5000)
        assertNull(sessionActivityLine(done, emptyList(), RemoteSelection("host", "project", "other")))
        for (state in listOf(done.copy(connected = false), done.copy(stale = true), done.copy(preview = true), done.copy(loading = true))) {
            assertNull(sessionActivityLine(state, emptyList(), done.selection))
        }
        val sending = reconcileChatWorkTiming(done, done.copy(sending = true), 6000)
        assertNull(sending.chatWorkTiming.durationMillis)
    }

    @Test fun durationBoundariesOmitEmptyUnits() {
        assertEquals(Triple(0, 0, 1), completedWorkDurationUnits(0))
        assertEquals(Triple(0, 0, 59), completedWorkDurationUnits(59000))
        assertEquals(Triple(0, 1, 0), completedWorkDurationUnits(60000))
        assertEquals(Triple(0, 5, 12), completedWorkDurationUnits(312000))
        assertEquals(Triple(0, 59, 59), completedWorkDurationUnits(3599000))
        assertEquals(Triple(1, 0, 0), completedWorkDurationUnits(3600000))
    }
}
