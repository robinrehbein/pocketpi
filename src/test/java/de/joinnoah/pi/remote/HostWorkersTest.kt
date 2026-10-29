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
}
