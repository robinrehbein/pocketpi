package de.joinnoah.pi.remote

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class GitChangesSubagentsTest {
    private fun session(id: String, status: String, parent: String? = null, updatedAt: Long = 0) =
        JsonObject(
            buildMap {
                put("id", JsonPrimitive(id))
                put("title", JsonPrimitive("Agent $id"))
                put("status", JsonPrimitive(status))
                put("origin", JsonPrimitive("tui"))
                put("updatedAt", JsonPrimitive(updatedAt))
                parent?.let { put("parentSessionId", JsonPrimitive(it)) }
            }
        )

    private class Source : GitSource {
        val statuses = mutableListOf<Pair<String, GitBase>>()
        val diffs = mutableListOf<Triple<String, String, String>>()
        val failing = mutableSetOf<String>()
        override suspend fun gitStatus(sessionId: String, base: GitBase): GitStatus {
            statuses += sessionId to base
            if (sessionId in failing) throw RemoteRequestException("forbidden")
            return when (sessionId) {
                "session" -> GitStatus(base, "own", listOf(GitFile("own.kt", GitChange.MODIFIED, 1, 0)), branch = "feature")
                "same-folder" -> GitStatus(base, "same", listOf(GitFile("own.kt", GitChange.MODIFIED, 1, 0)), branch = "feature")
                "clean" -> GitStatus(base, "clean", emptyList(), branch = "pi-agent-clean")
                else -> GitStatus(base, "snap-$sessionId", listOf(GitFile("$sessionId.kt", GitChange.ADDED, 2, 0)), branch = "pi-agent-$sessionId")
            }
        }

        override suspend fun gitLog(sessionId: String, snapshotId: String) = GitLog()

        override suspend fun gitDiff(sessionId: String, snapshotId: String, path: String): GitDiff {
            diffs += Triple(sessionId, snapshotId, path)
            return GitDiff(path, snapshotId, "@@ -0,0 +1 @@\n+x\n", false, false)
        }
    }

    private class Harness(scope: kotlinx.coroutines.CoroutineScope, sessions: List<JsonObject>) {
        val source = Source()
        var state =
            RemoteState(
                selection = RemoteSelection(sessionId = "session"),
                connected = true,
                capabilities = setOf(GIT_CAPABILITY),
                sessions = sessions,
            )
        val loader = GitChangesLoader(scope, source, { state }) { state = it(state) }
    }

    @Test
    fun opensGroupsForSubagentsWithChangesOfTheirOwn() = runTest {
        val h =
            Harness(
                backgroundScope,
                listOf(
                    session("session", "running"),
                    session("done", "idle", parent = "session", updatedAt = 5),
                    session("live", "running", parent = "session", updatedAt = 1),
                    session("same-folder", "running", parent = "session"),
                    session("clean", "idle", parent = "session"),
                    session("broken", "idle", parent = "session"),
                    session("elsewhere", "running", parent = "other"),
                ),
            )
        h.source.failing += "broken"
        h.loader.open(emptyList())
        runCurrent()
        // Running subagents first, all against HEAD, at most four; another session's child is not asked.
        val asked = h.source.statuses.filter { it.first != "session" }
        assertEquals(listOf("live", "same-folder", "done", "clean"), asked.map { it.first })
        assertTrue(asked.all { it.second == GitBase.HEAD })
        val changes = h.state.changes!!
        assertEquals(listOf("live", "same-folder", "done"), changes.subagents.map { it.sessionId })
        assertTrue(changes.subagents.first().running)
        assertFalse(changes.subagents.last().running)
        // The one on the session's own branch works in its checkout: no group of its own.
        assertEquals(listOf("live", "done"), visibleSubagents(changes).map { it.sessionId })
    }

    @Test
    fun aSubagentFileReadsThatSubagentsSnapshot() = runTest {
        val h = Harness(backgroundScope, listOf(session("session", "running"), session("done", "idle", parent = "session")))
        h.loader.open(emptyList())
        runCurrent()
        h.loader.openSubagentFile("done", "done.kt")
        runCurrent()
        assertEquals(Triple("done", "snap-done", "done.kt"), h.source.diffs.last())
        val changes = h.state.changes!!
        assertEquals("done.kt", changes.file)
        assertEquals("done", changes.fileSession)
        assertEquals("done.kt", changes.diff?.path)
        // A reload keeps the subagent file and reads it again from the fresh snapshot.
        h.loader.reload()
        runCurrent()
        assertEquals("done", h.state.changes!!.fileSession)
        assertEquals("done.kt", h.state.changes!!.file)
        assertEquals("done", h.source.diffs.last().first)
        assertNotNull(h.state.changes!!.diff)
        // Back to the list, then the session's own file reads the session's snapshot.
        h.loader.openFile(null)
        h.loader.openFile("own.kt")
        runCurrent()
        assertNull(h.state.changes!!.fileSession)
        assertEquals(Triple("session", "own", "own.kt"), h.source.diffs.last())
    }

    @Test
    fun aSessionWithoutSubagentsAsksOnlyForItsOwnChanges() = runTest {
        val h = Harness(backgroundScope, listOf(session("session", "running")))
        h.loader.open(emptyList())
        runCurrent()
        assertEquals(listOf("session"), h.source.statuses.map { it.first }.distinct())
        assertTrue(h.state.changes!!.subagents.isEmpty())
    }
}
