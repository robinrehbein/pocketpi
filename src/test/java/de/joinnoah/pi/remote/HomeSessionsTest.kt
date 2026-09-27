package de.joinnoah.pi.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSessionsTest {
    private fun home(
        id: String,
        status: String = "idle",
        updatedAt: Long? = null,
        openedAt: Long? = null,
        project: String = "p",
        route: String = "r",
    ) = HomeSession(route, project, "Project", id, "Title $id", status, updatedAt, openedAt)

    @Test
    fun shortcutsPutWaitingSessionsFirstThenMostRecentlyUsed() {
        val snapshot =
            HomeSnapshot(
                true,
                listOf(
                    home("old", openedAt = 10),
                    home("recent", openedAt = 50),
                    home("waiting", status = "waiting", openedAt = 1),
                    home("running", status = "running", openedAt = 20),
                    home("never", updatedAt = 999),
                ),
            )
        assertEquals(
            listOf("waiting", "recent", "running", "old"),
            shortcutSessions(snapshot).map { it.sessionId },
        )
    }

    @Test
    fun shortcutsFallBackToHostUpdateTimeAndRespectTheLimit() {
        val snapshot = HomeSnapshot(true, listOf(home("a", updatedAt = 1), home("b", updatedAt = 2)))
        assertEquals(listOf("b", "a"), shortcutSessions(snapshot).map { it.sessionId })
        assertEquals(listOf("b"), shortcutSessions(snapshot, limit = 1).map { it.sessionId })
        assertEquals(emptyList<HomeSession>(), shortcutSessions(snapshot, limit = -1))
    }

    @Test
    fun widgetShowsOnlyWaitingAndRunningSessionsWaitingFirst() {
        val snapshot =
            HomeSnapshot(
                true,
                listOf(
                    home("idle"),
                    home("run1", status = "running", updatedAt = 5),
                    home("wait", status = "waiting", updatedAt = 1),
                    home("run2", status = "running", updatedAt = 9),
                    home("run3", status = "running", updatedAt = 1),
                ),
            )
        assertEquals(listOf("wait", "run2", "run1"), widgetSessions(snapshot).map { it.sessionId })
        assertEquals(4, widgetSessions(snapshot, 6).size)
        assertEquals(1, snapshot.waitingCount())
    }

    @Test
    fun openProjectListReplacesKnownRowsSoClosedSessionsDisappear() {
        val previous = HomeSnapshot(true, listOf(home("closed", status = "waiting"), home("kept")))
        val state =
            connected(
                sessions = listOf(session("kept", "running"), session("new", "idle")),
                projectId = "p",
            )
        val next = mergeHomeSnapshot(previous, state, true, setOf("r"), now = 0)
        assertEquals(setOf("kept", "new"), next.sessions.map { it.sessionId }.toSet())
        assertEquals("running", next.sessions.first { it.sessionId == "kept" }.status)
        assertEquals("Project", next.sessions.first().projectName)
    }

    @Test
    fun historySessionsAreNotHomeRows() {
        val state =
            connected(sessions = listOf(session("h", "offline", origin = "history")), projectId = "p")
        assertTrue(mergeHomeSnapshot(HomeSnapshot(), state, true, setOf("r"), 0).sessions.isEmpty())
    }

    @Test
    fun verifiedProjectChatsUpdateOtherProjectsAndClearStaleAttention() {
        val previous =
            HomeSnapshot(true, listOf(home("stale", status = "waiting", project = "q"), home("other", project = "z")))
        val state =
            connected(
                chats = listOf(ProjectChatSummary("q", "Q", session("active", "running"), verified = true)),
            )
        val next = mergeHomeSnapshot(previous, state, true, setOf("r"), 0)
        assertEquals("idle", next.sessions.first { it.sessionId == "stale" }.status)
        assertEquals("running", next.sessions.first { it.sessionId == "active" }.status)
        assertEquals("Q", next.sessions.first { it.sessionId == "active" }.projectName)
        assertTrue(next.sessions.any { it.sessionId == "other" })
    }

    @Test
    fun anOfflineAppKeepsTheLastKnownRows() {
        val previous = HomeSnapshot(true, listOf(home("a", status = "waiting")))
        val state =
            connected(sessions = listOf(session("b", "idle")), projectId = "p").copy(connected = false)
        assertEquals(previous, mergeHomeSnapshot(previous, state, true, setOf("r"), 0))
    }

    @Test
    fun unpairedHostsAreDropped() {
        val previous = HomeSnapshot(true, listOf(home("a", route = "gone"), home("b")))
        val next = mergeHomeSnapshot(previous, RemoteState(), true, setOf("r"), 0)
        assertEquals(listOf("b"), next.sessions.map { it.sessionId })
        assertFalse(mergeHomeSnapshot(previous, RemoteState(), false, emptySet(), 0).paired)
    }

    @Test
    fun theOpenChatCountsAsUsed() {
        val previous = HomeSnapshot(true, listOf(home("a")))
        val state = RemoteState(selection = RemoteSelection("r", "p", "a"))
        val next = mergeHomeSnapshot(previous, state, true, setOf("r"), now = 120_000)
        assertEquals(120_000L, next.sessions.single().openedAt)
        // Within a minute nothing changes, so the snapshot is not rewritten.
        assertEquals(next, mergeHomeSnapshot(next, state, true, setOf("r"), now = 150_000))
    }

    @Test
    fun pushesUpdateTheStatusOfKnownSessions() {
        val snapshot = HomeSnapshot(true, listOf(home("a"), home("b")))
        val next = snapshot.withStatus("r", "a", "waiting")
        assertEquals(listOf("waiting", "idle"), next.sessions.map { it.status })
        assertEquals(snapshot, snapshot.withStatus("r", "unknown", "waiting"))
    }

    @Test
    fun widgetRowsFollowTheHeightAndWideWidgetsUseTwoColumns() {
        assertEquals(40f, widgetRowDp(1f))
        assertEquals(WidgetLayout(1, 1), widgetLayout(180f, 80f))
        assertEquals(WidgetLayout(1, 1), widgetLayout(180f, 120f))
        assertEquals(WidgetLayout(1, 2), widgetLayout(180f, 130f))
        assertEquals(WidgetLayout(1, 3), widgetLayout(260f, 175f))
        assertEquals(WidgetLayout(2, 2), widgetLayout(400f, 130f))
        assertEquals(WidgetLayout(2, 3), widgetLayout(520f, 600f))
        assertEquals(WidgetLayout(1, 1), widgetLayout(100f, 10f))
    }

    @Test
    fun largerFontsFitFewerRows() {
        assertEquals(WidgetLayout(1, 3), widgetLayout(180f, 175f, 1f))
        assertEquals(WidgetLayout(1, 2), widgetLayout(180f, 175f, 1.3f))
        assertEquals(WidgetLayout(1, 1), widgetLayout(180f, 175f, 2f))
    }

    @Test
    fun onlySessionsMissingFromTheOpenProjectsFullListCountAsClosed() {
        val previous =
            HomeSnapshot(true, listOf(home("gone"), home("kept"), home("elsewhere", project = "q")))
        val state = connected(sessions = listOf(session("kept", "idle")), projectId = "p")
        assertEquals(setOf(shortcutId("r", "gone")), closedHomeSessions(previous, state))
        assertEquals(emptySet<String>(), closedHomeSessions(previous, state.copy(connected = false)))
        assertEquals(emptySet<String>(), closedHomeSessions(previous, state.copy(loading = true)))
        // Project chats are only the most active rows, not a full list.
        val chatsOnly = connected(chats = listOf(ProjectChatSummary("p", "P", session("kept", "idle"), verified = true)))
        assertEquals(emptySet<String>(), closedHomeSessions(previous, chatsOnly))
    }

    @Test
    fun historyEntriesOfAClosedSessionDoNotKeepItOpen() {
        val previous = HomeSnapshot(true, listOf(home("closed")))
        val state = connected(sessions = listOf(session("closed", "offline", origin = "history")), projectId = "p")
        assertEquals(setOf(shortcutId("r", "closed")), closedHomeSessions(previous, state))
    }

    @Test
    fun shortcutPlanDisablesOnlyClosedAndReenablesKnownOnes() {
        val a = shortcutId("r", "a")
        val b = shortcutId("r", "b")
        val c = shortcutId("r", "c")
        val d = shortcutId("r", "d")
        val e = shortcutId("r", "e")
        val known = shortcutId("r", "known")
        val plan =
            shortcutPlan(
                chosen = listOf(a, b),
                existing = mapOf(a to false, b to true, c to true, d to true, e to false, known to false),
                present = setOf(a, b, known),
                closed = setOf(c, e, b),
                pairedRoutes = setOf("r"),
            )
        // "known" is in the snapshot again although not among the chosen four.
        assertEquals(listOf(a, known).sorted(), plan.enable)
        // "d" merely fell out of the list; "e" is already disabled; "b" is chosen again.
        assertEquals(listOf(c), plan.disable)
    }

    @Test
    fun shortcutsOfUnpairedHostsAreDisabledAndStayDisabled() {
        val kept = shortcutId("r", "a")
        val gone = shortcutId("old", "a")
        val goneDisabled = shortcutId("old", "b")
        val plan =
            shortcutPlan(
                chosen = listOf(kept),
                existing = mapOf(kept to true, gone to true, goneDisabled to false, "foreign" to true),
                present = setOf(kept, goneDisabled),
                closed = emptySet(),
                pairedRoutes = setOf("r"),
            )
        assertEquals(emptyList<String>(), plan.enable)
        assertEquals(listOf(gone), plan.disable)
        assertEquals("old", shortcutRoute(gone))
        assertNull(shortcutRoute("foreign"))
    }

    @Test
    fun aCachedSessionListIsNoEvidenceAndRemovesNothing() {
        val previous = HomeSnapshot(true, listOf(home("gone"), home("kept")))
        val cached =
            connected(sessions = listOf(session("kept", "running")), projectId = "p").copy(sessionsFresh = false)
        assertEquals(emptySet<String>(), closedHomeSessions(previous, cached))
        val next = mergeHomeSnapshot(previous, cached, true, setOf("r"), 0)
        assertEquals(setOf("gone", "kept"), next.sessions.map { it.sessionId }.toSet())
        assertEquals("running", next.sessions.first { it.sessionId == "kept" }.status)
    }

    @Test
    fun shortcutIdsCarryTheirHostPrefix() {
        assertEquals(true, shortcutId("r", "s").startsWith(routeShortcutPrefix("r")))
        assertEquals(false, shortcutId("r2", "s").startsWith(routeShortcutPrefix("r")))
    }

    @Test
    fun snapshotRoundTripsThroughJson() {
        val snapshot = HomeSnapshot(true, listOf(home("a", "waiting", 5, 7), home("b")))
        assertEquals(snapshot, homeSnapshot(Wire.parse(snapshot.json().toString())))
    }

    private fun session(id: String, status: String, origin: String = "rpc") =
        Wire.objectOf(
            "id" to id,
            "projectId" to "p",
            "title" to "Title $id",
            "origin" to origin,
            "status" to status,
        )

    private fun connected(
        sessions: List<kotlinx.serialization.json.JsonObject> = emptyList(),
        projectId: String? = null,
        chats: List<ProjectChatSummary> = emptyList(),
    ): RemoteState {
        val host = PairedHost("r", "https://relay.example", "d", "s", "Mac")
        return RemoteState(
            hosts = listOf(host),
            host = host,
            selection = RemoteSelection("r", projectId),
            connected = true,
            sessionsFresh = true,
            project = projectId?.let { Wire.objectOf("id" to it, "name" to "Project") },
            sessions = sessions,
            projectChats = chats,
        )
    }
}
