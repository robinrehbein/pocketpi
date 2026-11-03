package de.joinnoah.pi.remote

import org.junit.Assert.*
import org.junit.Test

class SubagentChangesTest {
    private val status = GitStatus(GitBase.HEAD, "Q2hhbmdlc1NuYXBzaG90MQ", listOf(GitFile("a.kt", GitChange.MODIFIED, 1, 1)))
    private val row = Wire.objectOf("id" to "c", "title" to "Child", "origin" to "rpc", "status" to "running", "parentSessionId" to "p")

    @Test
    fun aConfirmedRunningChildReadsRunning() {
        val sub = subagentChangesFor(sessionListItem(row, connected = true, loading = false, confirmed = true), status)
        assertTrue(sub.running)
        assertFalse(sub.lastKnown)
        assertEquals(R.string.remote_changes_subagent_running, subagentChangesStatusLabel(sub))
    }

    @Test
    fun aLastKnownRunningChildIsNeitherRunningNorFinished() {
        for (child in listOf(
            sessionListItem(row, connected = true, loading = false, confirmed = false),
            // Dropped connection with the state kept (stale): the last reported status, marked last known.
            sessionListItem(row, connected = false, loading = false, stale = true, confirmed = false),
        )) {
            assertTrue(child.lastKnown)
            val sub = subagentChangesFor(child, status)
            assertFalse(sub.running)
            assertTrue(sub.lastKnown)
            val label = subagentChangesStatusLabel(sub)
            assertEquals(R.string.remote_changes_subagent_last_known, label)
            assertNotEquals(R.string.remote_changes_subagent_finished, label)
        }
    }

    @Test
    fun anOfflineChildNeverReadsFinished() {
        // Without a connection and without kept state the row reads offline (not last known, see
        // projectSessionItems); offline proves no end state, so the group still reads last known.
        for (child in listOf(
            sessionListItem(row, connected = false, loading = false, confirmed = false),
            sessionListItem(row, connected = false, loading = false, confirmed = true),
        )) {
            val sub = subagentChangesFor(child, status)
            assertFalse(sub.running)
            assertTrue(sub.lastKnown)
            assertNotEquals(R.string.remote_changes_subagent_finished, subagentChangesStatusLabel(sub))
        }
    }

    @Test
    fun aConfirmedIdleChildReadsFinished() {
        val idle = Wire.objectOf("id" to "c", "title" to "Child", "origin" to "rpc", "status" to "idle", "parentSessionId" to "p")
        val sub = subagentChangesFor(sessionListItem(idle, connected = true, loading = false, confirmed = true), status)
        assertEquals(R.string.remote_changes_subagent_finished, subagentChangesStatusLabel(sub))
    }
}
