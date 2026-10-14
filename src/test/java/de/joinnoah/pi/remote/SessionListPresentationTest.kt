package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class SessionListPresentationTest {
    @Test
    fun hidingOfflineSessionsKeepsHistoryThatIsAvailableAndAllLiveStatuses() {
        val sessions =
            listOf(
                Wire.objectOf("id" to "history", "title" to "History", "origin" to "history", "status" to "idle"),
                Wire.objectOf("id" to "offline-rpc", "title" to "Offline RPC", "origin" to "rpc", "status" to "offline"),
                Wire.objectOf("id" to "idle", "title" to "Idle", "origin" to "rpc", "status" to "idle"),
                Wire.objectOf("id" to "running", "title" to "Running", "origin" to "rpc", "status" to "running"),
                Wire.objectOf("id" to "waiting", "title" to "Waiting", "origin" to "rpc", "status" to "waiting"),
            )

        assertEquals(
            listOf("waiting", "history", "idle", "running"),
            visibleSessionListItems(sessions, connected = true, loading = false, hideOffline = true)
                .map(SessionListItem::id),
        )
    }

    @Test
    fun subagentsFollowTheirParentAndKeepTheirOwnPresentationData() {
        val sessions =
            listOf(
                Wire.objectOf(
                    "id" to "child",
                    "title" to "Child",
                    "parentSessionId" to "parent",
                    "origin" to "rpc",
                    "status" to "running",
                    "preview" to "Child preview",
                ),
                Wire.objectOf(
                    "id" to "parent",
                    "title" to "Parent",
                    "origin" to "rpc",
                    "status" to "idle",
                ),
            )

        val visible = visibleSessionListItems(sessions, connected = true, loading = false, hideOffline = false)

        assertEquals(listOf("parent", "child"), visible.map(SessionListItem::id))
        assertEquals(0, visible[0].depth)
        assertEquals("parent", visible[1].parentSessionId)
        assertEquals(1, visible[1].depth)
        assertEquals(SessionAvailability.RUNNING, visible[1].availability)
        assertEquals("Child preview", visible[1].preview)
        assertTrue(visible[0].hasChildren)
        assertFalse(visible[1].hasChildren)
    }

    @Test
    fun collapsingOneSessionHidesOnlyItsDescendants() {
        val sessions = listOf(
            Wire.objectOf("id" to "first", "title" to "First", "origin" to "rpc", "parentSessionId" to "", "status" to "idle"),
            Wire.objectOf("id" to "child", "title" to "Child", "origin" to "rpc", "parentSessionId" to "first", "status" to "idle"),
            Wire.objectOf("id" to "grandchild", "title" to "Grandchild", "origin" to "rpc", "parentSessionId" to "child", "status" to "idle"),
            Wire.objectOf("id" to "second", "title" to "Second", "origin" to "rpc", "status" to "idle"),
            Wire.objectOf("id" to "other-child", "title" to "Other child", "origin" to "rpc", "parentSessionId" to "second", "status" to "idle"),
        )
        fun visible(collapsedIds: Set<String>) = visibleSessionListItems(
            sessions, connected = true, loading = false, hideOffline = false,
            collapsedIds = collapsedIds,
        )

        assertEquals(listOf("first", "second", "other-child"),
            visible(setOf("first")).map(SessionListItem::id))
        assertTrue(visible(setOf("first")).first().hasChildren)
        assertEquals(listOf("first", "child", "second", "other-child"),
            visible(setOf("child")).map(SessionListItem::id))
        assertEquals(listOf("first", "child", "grandchild", "second", "other-child"),
            visible(emptySet()).map(SessionListItem::id))
    }

    @Test
    fun hidingOfflineSessionsKeepsAncestorsOfVisibleDescendantsAndHidesOfflineSubtrees() {
        val sessions =
            listOf(
                Wire.objectOf("id" to "parent", "title" to "Parent", "origin" to "rpc", "status" to "offline"),
                Wire.objectOf(
                    "id" to "child",
                    "title" to "Child",
                    "parentSessionId" to "parent",
                    "origin" to "rpc",
                    "status" to "offline",
                ),
                Wire.objectOf(
                    "id" to "grandchild",
                    "title" to "Grandchild",
                    "parentSessionId" to "child",
                    "origin" to "rpc",
                    "status" to "waiting",
                ),
                Wire.objectOf("id" to "offline", "title" to "Offline", "origin" to "rpc", "status" to "offline"),
                Wire.objectOf(
                    "id" to "offline-child",
                    "title" to "Offline child",
                    "parentSessionId" to "offline",
                    "origin" to "rpc",
                    "status" to "offline",
                ),
            )

        val visible = visibleSessionListItems(sessions, connected = true, loading = false, hideOffline = true)

        assertEquals(listOf("parent", "child", "grandchild"), visible.map(SessionListItem::id))
        assertEquals(listOf(0, 1, 2), visible.map(SessionListItem::depth))
    }

    @Test
    fun missingOrCyclicParentsFallBackToRootRows() {
        val sessions =
            listOf(
                Wire.objectOf(
                    "id" to "missing",
                    "title" to "Missing parent",
                    "parentSessionId" to "not-present",
                    "origin" to "rpc",
                    "status" to "idle",
                ),
                Wire.objectOf(
                    "id" to "first",
                    "title" to "First",
                    "parentSessionId" to "second",
                    "origin" to "rpc",
                    "status" to "idle",
                ),
                Wire.objectOf(
                    "id" to "second",
                    "title" to "Second",
                    "parentSessionId" to "first",
                    "origin" to "rpc",
                    "status" to "idle",
                ),
                Wire.objectOf(
                    "id" to "self",
                    "title" to "Self",
                    "parentSessionId" to "self",
                    "origin" to "rpc",
                    "status" to "idle",
                ),
            )

        val visible = visibleSessionListItems(sessions, connected = true, loading = false, hideOffline = false)

        assertEquals(listOf("missing", "first", "second", "self"), visible.map(SessionListItem::id))
        assertEquals(listOf(0, 0, 0, 0), visible.map(SessionListItem::depth))
    }

    @Test
    fun disconnectionOverridesAvailabilityButLoadingDoesNot() {
        for ((status, availability) in
            listOf(
                "idle" to SessionAvailability.IDLE,
                "running" to SessionAvailability.RUNNING,
                "waiting" to SessionAvailability.WAITING,
                "offline" to SessionAvailability.OFFLINE,
            )) {
            val session =
                Wire.objectOf(
                    "id" to "s",
                    "title" to "Title",
                    "origin" to "rpc",
                    "status" to status,
                )
            assertEquals(
                SessionAvailability.OFFLINE,
                sessionListItem(session, connected = false, loading = false).availability,
            )
            assertEquals(
                availability,
                sessionListItem(session, connected = true, loading = true).availability,
            )
        }
    }

    @Test
    fun sessionStatusAndOptionalMetadataAreMappedWithoutFabricatedValues() {
        for ((status, availability) in
            listOf(
                "idle" to SessionAvailability.IDLE,
                "running" to SessionAvailability.RUNNING,
                "waiting" to SessionAvailability.WAITING,
                "offline" to SessionAvailability.OFFLINE,
            )) {
            val item =
                sessionListItem(
                    Wire.objectOf(
                        "id" to "s",
                        "title" to "Title",
                        "origin" to "history",
                        "status" to status,
                    ),
                    true,
                    false,
                )
            assertEquals(availability, item.availability)
            assertTrue(item.continuesAsCopy)
            assertFalse(item.daemonOwned)
            assertNull(item.preview)
            assertNull(item.updatedAt)
        }
        val item =
            sessionListItem(
                Wire.objectOf(
                    "id" to "s",
                    "title" to "Title",
                    "origin" to "tui",
                    "status" to "idle",
                    "preview" to "Last message",
                    "updatedAt" to 1234,
                ),
                true,
                false,
            )
        assertEquals("Last message", item.preview)
        assertEquals(1234L, item.updatedAt)
        assertFalse(item.continuesAsCopy)
        assertFalse(item.daemonOwned)

        assertTrue(
            sessionListItem(
                    Wire.objectOf("id" to "rpc", "title" to "RPC", "origin" to "rpc", "status" to "idle"),
                    true,
                    false,
                )
                .daemonOwned
        )
    }

    @Test
    fun closingRequiresAConnectedSupportedAndNonOfflineRpcSession() {
        val rpc =
            sessionListItem(
                Wire.objectOf("id" to "rpc", "title" to "RPC", "origin" to "rpc", "status" to "idle"),
                connected = true,
                loading = false,
            )
        assertTrue(canCloseSession(rpc, true, false, setOf(SESSION_CLOSE_CAPABILITY)))
        assertFalse(canCloseSession(rpc, false, false, setOf(SESSION_CLOSE_CAPABILITY)))
        assertFalse(canCloseSession(rpc, true, false, emptySet()))

        val offline =
            sessionListItem(
                Wire.objectOf("id" to "offline", "title" to "Offline", "origin" to "rpc", "status" to "offline"),
                connected = true,
                loading = false,
            )
        assertFalse(canCloseSession(offline, true, false, setOf(SESSION_CLOSE_CAPABILITY)))
    }

    @Test
    fun waitingTopLevelSessionsComeFirstAndOthersKeepTheirOrder() {
        val sessions = listOf(
            Wire.objectOf("id" to "idle", "title" to "Idle", "origin" to "rpc", "status" to "idle"),
            Wire.objectOf("id" to "waiting-a", "title" to "Waiting A", "origin" to "rpc", "status" to "waiting"),
            Wire.objectOf("id" to "running", "title" to "Running", "origin" to "rpc", "status" to "running"),
            Wire.objectOf("id" to "waiting-b", "title" to "Waiting B", "origin" to "rpc", "status" to "waiting"),
            Wire.objectOf("id" to "offline", "title" to "Offline", "origin" to "rpc", "status" to "offline"),
        )

        assertEquals(
            listOf("waiting-a", "waiting-b", "idle", "running", "offline"),
            visibleSessionListItems(sessions, connected = true, loading = false, hideOffline = false)
                .map(SessionListItem::id),
        )
        assertEquals(
            listOf("idle", "waiting-a", "running", "waiting-b", "offline"),
            visibleSessionListItems(sessions, connected = false, loading = false, hideOffline = false)
                .map(SessionListItem::id),
        )
    }

    @Test
    fun subagentsStayUnderTheirParentWhenAWaitingParentMovesUp() {
        val sessions = listOf(
            Wire.objectOf("id" to "idle", "title" to "Idle", "origin" to "rpc", "status" to "idle"),
            Wire.objectOf("id" to "idle-child", "title" to "Idle child", "origin" to "rpc", "parentSessionId" to "idle", "status" to "waiting"),
            Wire.objectOf("id" to "parent", "title" to "Parent", "origin" to "rpc", "status" to "waiting"),
            Wire.objectOf("id" to "child-b", "title" to "Child B", "origin" to "rpc", "parentSessionId" to "parent", "status" to "idle"),
            Wire.objectOf("id" to "child-a", "title" to "Child A", "origin" to "rpc", "parentSessionId" to "parent", "status" to "waiting"),
        )

        val visible = visibleSessionListItems(sessions, connected = true, loading = false, hideOffline = false)

        assertEquals(
            listOf("parent", "child-b", "child-a", "idle", "idle-child"),
            visible.map(SessionListItem::id),
        )
        assertEquals(listOf(0, 1, 1, 0, 1), visible.map(SessionListItem::depth))
    }

    @Test
    fun childCountCountsOnlyVisibleDirectSubagents() {
        val sessions = listOf(
            Wire.objectOf("id" to "parent", "title" to "Parent", "origin" to "rpc", "status" to "running"),
            Wire.objectOf("id" to "live", "title" to "Live", "origin" to "rpc", "parentSessionId" to "parent", "status" to "running"),
            Wire.objectOf("id" to "gone", "title" to "Gone", "origin" to "rpc", "parentSessionId" to "parent", "status" to "offline"),
            Wire.objectOf("id" to "grandchild", "title" to "Grandchild", "origin" to "rpc", "parentSessionId" to "live", "status" to "idle"),
        )
        fun counts(hideOffline: Boolean) =
            visibleSessionListItems(sessions, connected = true, loading = false, hideOffline = hideOffline)
                .associate { it.id to it.childCount }

        assertEquals(mapOf("parent" to 2, "live" to 1, "grandchild" to 0, "gone" to 0), counts(false))
        assertEquals(mapOf("parent" to 1, "live" to 1, "grandchild" to 0), counts(true))
    }

    private fun row(id: String, status: String = "idle", updatedAt: Long = 0) =
        Wire.objectOf("id" to id, "title" to id, "origin" to "rpc", "status" to status, "updatedAt" to updatedAt)

    @Test
    fun projectSessionsCountAVerifiedListAndNotACachedOne() {
        val rows = listOf(row("w", "waiting"), row("r1", "running"), row("r2", "running"), row("i"))

        val verified = projectSessions("p", "Project", rows, verified = true)
        assertEquals(rows, verified.sessions)
        assertEquals(SessionCounts(waiting = 1, running = 2), verified.counts)
        assertFalse(verified.truncated)

        val cached = projectSessions("p", "Project", rows, verified = false)
        assertNull(cached.counts)
        assertEquals(verified.unverified(), cached)
    }

    @Test
    fun projectSessionsKeepWaitingRunningAndRecentRowsOverTheCap() {
        val idle = List(PROJECT_SESSIONS_CAP + 10) { row("idle$it", updatedAt = it.toLong()) }
        val rows = idle + row("old-waiting", "waiting", updatedAt = -1) + row("old-running", "running", updatedAt = -2)

        val entry = projectSessions("p", "Project", rows, verified = true)
        assertEquals(PROJECT_SESSIONS_CAP, entry.sessions.size)
        assertTrue(entry.truncated)
        val kept = entry.sessions.map { it.text("id") }
        assertEquals(listOf("old-waiting", "old-running"), kept.take(2))
        // The oldest idle rows make room.
        assertTrue("idle${idle.size - 1}" in kept)
        assertFalse("idle0" in kept)
        // The counts cover the whole list, not only the kept rows.
        assertEquals(SessionCounts(waiting = 1, running = 1), entry.counts)
    }

    @Test
    fun patchedProjectSessionsFollowTheirRowsChanges() {
        val entry = projectSessions("p", "Project", listOf(row("a", "waiting"), row("b")), verified = true)
        fun status(id: String, value: String): (JsonObject) -> JsonObject =
            { if (it.text("id") == id) Wire.objectOf("id" to id, "status" to value) else it }

        val running = entry.patched(status("b", "running"))
        assertEquals(SessionCounts(waiting = 1, running = 1), running.counts)
        val answered = running.patched(status("a", "idle"))
        assertEquals(SessionCounts(running = 1), answered.counts)
        // A row the patch leaves alone keeps the same entry.
        assertSame(answered, answered.patched { it })
        // A list without counts stays without them.
        assertNull(entry.unverified().patched(status("a", "running")).counts)
    }

    @Test
    fun projectSessionItemsReadOfflineUntilTheMacConfirmsThem() {
        val entry = projectSessions("p", "Project", listOf(row("a", "running")), verified = true)

        assertEquals(
            listOf(SessionAvailability.RUNNING),
            projectSessionItems(entry, connected = true).map(SessionListItem::availability),
        )
        assertEquals(
            listOf(SessionAvailability.OFFLINE),
            projectSessionItems(entry.unverified(), connected = true).map(SessionListItem::availability),
        )
        assertEquals(
            listOf(SessionAvailability.OFFLINE),
            projectSessionItems(entry, connected = false).map(SessionListItem::availability),
        )
        // A dropped connection unverifies the entry: its rows read offline, not as a last-known status.
        assertEquals(
            listOf(false),
            projectSessionItems(entry.unverified(), connected = false).map(SessionListItem::lastKnown),
        )
    }

    @Test
    fun staleRowsKeepTheirLastKnownStatusWhileTheMacReconnects() {
        for ((status, availability) in
            listOf(
                "idle" to SessionAvailability.IDLE,
                "running" to SessionAvailability.RUNNING,
                "waiting" to SessionAvailability.WAITING,
                "offline" to SessionAvailability.OFFLINE,
            )) {
            val session = Wire.objectOf("id" to "s", "title" to "Title", "origin" to "rpc", "status" to status)
            assertEquals(
                availability,
                sessionListItem(session, connected = false, loading = false, stale = true).availability,
            )
            assertEquals(
                availability,
                sessionListItems(listOf(session), connected = false, loading = false, stale = true)
                    .single().availability,
            )
            // Without a reconnect under way a lost connection still shows every row offline.
            assertEquals(
                SessionAvailability.OFFLINE,
                sessionListItem(session, connected = false, loading = false, stale = false).availability,
            )
        }
        // A last-known status offers nothing that needs the connection.
        val stale =
            sessionListItem(
                Wire.objectOf("id" to "rpc", "title" to "RPC", "origin" to "rpc", "status" to "idle"),
                connected = false,
                loading = false,
                stale = true,
            )
        assertFalse(canCloseSession(stale, false, false, setOf(SESSION_CLOSE_CAPABILITY)))
    }

    @Test
    fun hideOfflineAppliesToTheLastKnownStatusWhileStaleAndIsOffWithoutAConnection() {
        assertTrue(hidesOfflineSessions(setting = true, connected = true, stale = false))
        assertTrue(hidesOfflineSessions(setting = true, connected = false, stale = true))
        assertFalse(hidesOfflineSessions(setting = true, connected = false, stale = false))
        assertFalse(hidesOfflineSessions(setting = false, connected = true, stale = false))
        assertFalse(hidesOfflineSessions(setting = false, connected = false, stale = true))

        val sessions =
            listOf(
                Wire.objectOf("id" to "gone", "title" to "Gone", "origin" to "rpc", "status" to "offline"),
                Wire.objectOf("id" to "idle", "title" to "Idle", "origin" to "rpc", "status" to "idle"),
                Wire.objectOf("id" to "running", "title" to "Running", "origin" to "rpc", "status" to "running"),
            )
        fun visible(connected: Boolean, stale: Boolean) =
            visibleSessionListItems(
                sessions,
                connected = connected,
                loading = false,
                hideOffline = hidesOfflineSessions(true, connected, stale),
                stale = stale,
            ).map { it.id to it.availability }

        // Connected: the real availability decides.
        assertEquals(
            listOf("idle" to SessionAvailability.IDLE, "running" to SessionAvailability.RUNNING),
            visible(connected = true, stale = false),
        )
        // Reconnecting: the same rows, with the status they last had.
        assertEquals(visible(connected = true, stale = false), visible(connected = false, stale = true))
        // Offline with nothing kept (another Mac, a denied device, a fresh start): every row is
        // offline, and the filter steps aside rather than hide them all.
        assertEquals(
            listOf(
                "gone" to SessionAvailability.OFFLINE,
                "idle" to SessionAvailability.OFFLINE,
                "running" to SessionAvailability.OFFLINE,
            ),
            visible(connected = false, stale = false),
        )
    }

    @Test
    fun aLastKnownStatusIsMarkedAlsoOnceTheReconnectHasGivenUp() {
        val running = Wire.objectOf("id" to "r", "title" to "Running", "origin" to "rpc", "status" to "running")
        val gone = Wire.objectOf("id" to "g", "title" to "Gone", "origin" to "rpc", "status" to "offline")
        // After a second transient failure, or once the reconnect stopped, the connection reads
        // "Offline" while the state stays stale: the rows still carry the last-known status, marked.
        val item = sessionListItem(running, connected = false, loading = false, stale = true)
        assertEquals(SessionAvailability.RUNNING, item.availability)
        assertTrue(item.lastKnown)
        assertFalse(sessionListItem(gone, connected = false, loading = false, stale = true).lastKnown)
        assertFalse(sessionListItem(running, connected = true, loading = false, stale = false).lastKnown)
        assertFalse(sessionListItem(running, connected = false, loading = false, stale = false).lastKnown)
        // Sorting and the live filter still go by the last-known status.
        assertTrue(item.live())
        assertEquals(
            listOf("r" to true),
            visibleSessionListItems(
                listOf(gone, running),
                connected = false,
                loading = false,
                hideOffline = hidesOfflineSessions(true, connected = false, stale = true),
                stale = true,
            ).map { it.id to it.lastKnown },
        )
    }

    @Test
    fun pinnedSessionsListFirstAndUnreadFollowsTheMarks() {
        val sessions =
            listOf(
                Wire.objectOf("id" to "waiting", "title" to "Waiting", "origin" to "rpc", "status" to "waiting", "updatedAt" to 300L),
                Wire.objectOf("id" to "plain", "title" to "Plain", "origin" to "rpc", "status" to "idle", "updatedAt" to 200L),
                Wire.objectOf("id" to "pinned", "title" to "Pinned", "origin" to "rpc", "status" to "idle", "updatedAt" to 100L),
                Wire.objectOf("id" to "changed", "title" to "Changed", "origin" to "rpc", "status" to "idle", "updatedAt" to 400L),
                Wire.objectOf("id" to "open", "title" to "Open", "origin" to "rpc", "status" to "idle", "updatedAt" to 500L),
            )
        val marks =
            mapOf(
                "pinned" to SessionMark(pinned = true, seenUpdatedAt = 100),
                "changed" to SessionMark(seenUpdatedAt = 350),
                "open" to SessionMark(seenUpdatedAt = 100),
            )

        val visible =
            visibleSessionListItems(
                sessions,
                connected = true,
                loading = false,
                hideOffline = false,
                marks = marks,
                openSessionId = "open",
            )

        assertEquals(listOf("pinned", "waiting", "plain", "changed", "open"), visible.map(SessionListItem::id))
        assertEquals(listOf("changed"), visible.filter { it.unread }.map(SessionListItem::id))
        assertTrue(visible.single { it.id == "pinned" }.pinned)
        assertFalse(visible.single { it.id == "waiting" }.pinned)
    }

    @Test
    fun aFinishedRunIsUnreadUntilTheChatIsSeen() {
        val sessions =
            listOf(Wire.objectOf("id" to "done", "title" to "Done", "origin" to "rpc", "status" to "idle", "updatedAt" to 100L))

        val finished =
            visibleSessionListItems(sessions, connected = true, loading = false, hideOffline = false,
                marks = mapOf("done" to SessionMark(finishedAt = 90)))
        val read =
            visibleSessionListItems(sessions, connected = true, loading = false, hideOffline = false,
                marks = mapOf("done" to SessionMark(seenAt = 95, seenUpdatedAt = 100)))

        assertTrue(finished.single().unread)
        assertFalse(read.single().unread)
    }
}
