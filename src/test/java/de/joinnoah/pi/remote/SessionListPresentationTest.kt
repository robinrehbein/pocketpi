package de.joinnoah.pi.remote

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
}
