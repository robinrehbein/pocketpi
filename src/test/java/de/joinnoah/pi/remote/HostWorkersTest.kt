package de.joinnoah.pi.remote

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

/** Runs the shared `sessions-host-worker` entry of `session-tree-v1.json` and the host worker rules. */
class HostWorkersTest {
    private val listed =
        Wire.json.parseToJsonElement(javaClass.getResource("/session-tree-v1.json")!!.readText()).jsonObject
            .array("wireValid")
            .single { it.text("name") == "sessions-host-worker" }
            .obj("payload")

    @Test
    fun onlySessionListsCreatesAndOpensCarryThePrefix() {
        assertEquals("host_worker.v1:abc", sessionsListRequestId("sessions.list", "abc"))
        assertEquals("host_worker.v1:abc", sessionsListRequestId("sessions.open", "abc"))
        assertEquals("host_worker.v1:abc", sessionsListRequestId("sessions.create", "abc"))
        assertEquals("abc", sessionsListRequestId("session.snapshot", "abc"))
        assertTrue(listed.text("requestId").startsWith(HOST_WORKER_REQUEST_PREFIX))
    }

    @Test
    fun aWorkerRowBecomesAMarkedTerminalSession() {
        val rows = listed.obj("data").array("items").map(::withHostWorker).associateBy { it.text("id") }
        val worker = rows.getValue("worker-session")
        assertEquals("tui", worker.text("origin"))
        assertTrue(isHostWorker(worker))
        val live = rows.getValue("live-session")
        assertEquals("tui", live.text("origin"))
        assertFalse(isHostWorker(live))
        assertSame(live, withHostWorker(live))
    }

    @Test
    fun aHostWorkerIsListedAsSuchEvenBeforeItsFirstReport() {
        val worker = withHostWorker(listed.obj("data").array("items").first { it.text("id") == "worker-session" })
        val item = sessionListItem(worker, true, false)
        assertTrue(item.hostWorker)
        assertEquals(SessionAvailability.OFFLINE, item.availability)
        assertFalse(item.liveMacTui)
        assertFalse(item.daemonOwned)
        assertFalse(sessionListItem(Wire.objectOf("id" to "s", "title" to "T", "origin" to "tui", "status" to "idle"), true, false).hostWorker)
    }

    @Test
    fun onlyABooleanTrueMarksAHostWorker() {
        assertFalse(isHostWorker(Wire.objectOf("id" to "s", HOST_WORKER_KEY to "true")))
        assertFalse(isHostWorker(Wire.objectOf("id" to "s", HOST_WORKER_KEY to false)))
        assertTrue(isHostWorker(Wire.objectOf("id" to "s", HOST_WORKER_KEY to true)))
    }

    private val controlsFixture =
        Wire.json.parseToJsonElement(javaClass.getResource("/session-worker-controls-v1.json")!!.readText()).jsonObject
            .array("wireValid")
            .associate { it.text("name") to it.obj("payload") }

    private val capable = setOf(HOST_WORKER_V2_CAPABILITY, FOLLOW_UP_CAPABILITY, STEER_CAPABILITY)

    private fun tuiState(
        capabilities: Set<String> = capable,
        selected: String = "s",
        session: kotlinx.serialization.json.JsonObject =
            Wire.objectOf("id" to selected, "origin" to "tui"),
        unsupported: Map<String, Set<ExtensionControl>> = emptyMap(),
    ) = RemoteState(
        selection = RemoteSelection("host", "project", selected),
        session = session,
        capabilities = capabilities,
        sessionControlUnsupported = unsupported,
    )

    @Test
    fun theFixturesWorkerStaysMarkedAndItsRefusalIsRecognised() {
        val worker = withHostWorker(controlsFixture.getValue("worker-create-result").obj("data").obj("session"))
        assertTrue(isHostWorker(worker))
        val error = controlsFixture.getValue("controls-unsupported").obj("error")
        assertTrue(RemoteRequestException(error.text("code"), hostMessage = error.text("message")).isExtensionControlRefusal)
        assertFalse(RemoteRequestException("busy", hostMessage = error.text("message")).isExtensionControlRefusal)
        assertFalse(RemoteRequestException(error.text("code"), hostMessage = "Reload the pi remote extension").isExtensionControlRefusal)
        assertFalse(RemoteRequestException(error.text("code"), hostMessage = "Reload the pi remote extension to use attachments").isExtensionControlRefusal)
        assertFalse(RemoteRequestException(error.text("code")).isExtensionControlRefusal)
    }

    @Test
    fun extensionControlsNeedATerminalSessionAndV2() {
        for (control in ExtensionControl.entries) {
            assertTrue(extensionControls(tuiState(), control))
            assertFalse(extensionControls(tuiState(capabilities = emptySet()), control))
            assertFalse(extensionControls(tuiState().copy(unavailableCapabilities = setOf(HOST_WORKER_V2_CAPABILITY)), control))
            assertFalse(extensionControls(tuiState(session = Wire.objectOf("id" to "s", "origin" to "rpc")), control))
            assertFalse(extensionControls(tuiState(session = Wire.objectOf("id" to "s", "origin" to "history")), control))
            assertFalse(extensionControls(RemoteState(capabilities = capable), control))
        }
    }

    @Test
    fun aSubagentChildStaysOnTheChildControls() {
        val child = tuiState(session = Wire.objectOf("id" to "s", "origin" to "tui", "parentSessionId" to "p"))
        assertFalse(extensionControls(child, ExtensionControl.FOLLOW_UP))
        assertFalse(canFollowUp(child))
        assertFalse(canSteer(child))
        val connected = child.copy(connected = true, status = "idle", capabilities = capable + RELOAD_CAPABILITY)
        assertEquals(ReloadAvailability.NONE, reloadAvailability(connected))
        val sibling = tuiState(capabilities = capable + RELOAD_CAPABILITY).copy(connected = true, status = "idle")
        assertEquals(ReloadAvailability.RUNS, reloadAvailability(sibling))
    }

    @Test
    fun aMarkHidesOnlyThatControlOfThatSession() {
        val marks = mapOf("a" to setOf(ExtensionControl.FOLLOW_UP))
        val marked = tuiState(selected = "a", unsupported = marks)
        assertFalse(extensionControls(marked, ExtensionControl.FOLLOW_UP))
        assertTrue(extensionControls(marked, ExtensionControl.STEER))
        assertTrue(extensionControls(marked, ExtensionControl.RELOAD))
        assertFalse(canFollowUp(marked))
        assertTrue(canSteer(marked))
        assertTrue(canFollowUp(tuiState(selected = "b", unsupported = marks)))
    }

    @Test
    fun theMacOnlyHintShowsOnlyWhileFollowUpIsUnavailable() {
        assertFalse(showsTuiInfo(tuiState()))
        assertTrue(showsTuiInfo(tuiState(capabilities = setOf(FOLLOW_UP_CAPABILITY))))
        assertTrue(showsTuiInfo(tuiState(unsupported = mapOf("s" to setOf(ExtensionControl.FOLLOW_UP)))))
        assertFalse(showsTuiInfo(tuiState(session = Wire.objectOf("id" to "s", "origin" to "rpc"), capabilities = setOf(FOLLOW_UP_CAPABILITY))))
        // A live child keeps its controls whatever v2 says.
        assertFalse(
            showsTuiInfo(
                tuiState(
                    capabilities = setOf(SUBAGENT_CONTROL_CAPABILITY),
                    session = Wire.objectOf("id" to "s", "origin" to "tui", "parentSessionId" to "p"),
                ),
            ),
        )
    }

    @Test
    fun onlyAnRpcSessionOrAMarkedWorkerWithV2IsHostOwned() {
        val worker = withHostWorker(Wire.objectOf("id" to "s", "origin" to "worker"))
        val terminal = Wire.objectOf("id" to "s", "origin" to "tui")
        val rpc = Wire.objectOf("id" to "s", "origin" to "rpc")
        assertTrue(hostOwned(tuiState(session = worker)))
        assertFalse(hostOwned(tuiState(session = worker, capabilities = emptySet())))
        assertFalse(hostOwned(tuiState(session = worker).copy(unavailableCapabilities = setOf(HOST_WORKER_V2_CAPABILITY))))
        assertFalse(hostOwned(tuiState(session = terminal)))
        assertTrue(hostOwned(tuiState(session = rpc, capabilities = emptySet())))
        assertFalse(hostOwned(RemoteState(capabilities = capable)))
        assertTrue(hostOwned(RemoteState(capabilities = capable), worker))
        // Close does not depend on the session's pi: a refused reload changes neither answer.
        val reloadMarked = mapOf("s" to setOf(ExtensionControl.RELOAD))
        assertTrue(hostOwned(tuiState(session = worker, unsupported = reloadMarked)))
        assertFalse(hostOwned(tuiState(session = terminal, unsupported = reloadMarked)))
        assertFalse(extensionControls(tuiState(session = worker, unsupported = reloadMarked), ExtensionControl.RELOAD))
    }
}
