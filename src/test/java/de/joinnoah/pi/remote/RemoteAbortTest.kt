package de.joinnoah.pi.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteAbortTest {
    @Test
    fun abortIsBlockedWhileAnAnswerIsInFlight() {
        val selection = RemoteSelection(routeId = "host", projectId = "project", sessionId = "session")

        assertFalse(
            canAbortRemoteRun(
                RemoteState(selection = selection, connected = true, answering = setOf("question")),
                activeRouteId = "host",
            )
        )
        assertTrue(
            canAbortRemoteRun(RemoteState(selection = selection, connected = true), activeRouteId = "host")
        )
    }

    private val parent = RemoteSelection(routeId = "host", projectId = "project", sessionId = "parent")

    private fun child(id: String, parentId: String?, status: String) =
        Wire.objectOf(
            "id" to id,
            "projectId" to "project",
            "status" to status,
            "origin" to "tui",
            "parentSessionId" to parentId,
        )

    private val sessions = listOf(
        child("parent", null, "running"),
        child("running-child", "parent", "running"),
        child("waiting-child", "parent", "waiting"),
        child("idle-child", "parent", "idle"),
        child("offline-child", "parent", "offline"),
        child("unrelated", "other", "running"),
        child("top", null, "running"),
    )

    @Test
    fun subagentAbortTruthTable() {
        val state = RemoteState(selection = parent, connected = true, sessions = sessions)
        assertTrue(canAbortSubagent(state, "running-child"))
        assertTrue(canAbortSubagent(state, "waiting-child", activeRouteId = "host"))
        assertFalse(canAbortSubagent(state, "idle-child"))
        assertFalse(canAbortSubagent(state, "offline-child"))
        assertFalse(canAbortSubagent(state, "unrelated"))
        assertFalse(canAbortSubagent(state, "top"))
        assertFalse(canAbortSubagent(state, "parent"))
        assertFalse(canAbortSubagent(state, "missing"))
        assertFalse(canAbortSubagent(state, "running-child", activeRouteId = "other-host"))
        assertFalse(canAbortSubagent(state, "running-child", activeRouteId = null))
        assertFalse(canAbortSubagent(state.copy(loading = true), "running-child"))
        assertFalse(canAbortSubagent(state.copy(connected = false), "running-child"))
        assertFalse(canAbortSubagent(state.copy(selection = parent.copy(sessionId = null)), "running-child"))
    }
}
