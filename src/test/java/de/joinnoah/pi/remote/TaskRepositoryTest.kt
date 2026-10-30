package de.joinnoah.pi.remote

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TaskRepositoryTest {
    private val host = PairedHost("host", "https://relay.test", "device", "secret", "Host")
    private val selection = RemoteSelection("host", "project", "session")
    private val project = Wire.objectOf("id" to "project")
    private fun session(id: String) = Wire.objectOf("id" to id, "origin" to "rpc", "status" to "idle", "projectId" to "project")
    private fun tasks(text: String) = JsonArray(listOf(Wire.objectOf("id" to 1, "text" to text, "status" to "pending")))

    private inner class Transport : RemoteTransport {
        override lateinit var listener: RemoteTransport.Listener
        val sent = mutableListOf<JsonObject>()
        var v1Only = false
        var advertised = true
        var holdSnapshots = false
        var revision = 3
        var taskValue: JsonElement? = tasks("first")
        var cursor: String? = null
        var handshake = emptySet<String>()
        override fun connect(host: PairedHost) = listener.ready(handshake)
        override fun close() = Unit
        override fun pair(value: JsonObject) = Unit
        fun deliver(value: JsonObject) { listener.inbound(); listener.message(value) }
        fun result(request: JsonObject, data: JsonObject) = deliver(Wire.objectOf(
            "type" to "result", "requestId" to request.text("requestId"), "ok" to true, "data" to data,
        ))
        fun snapshot(request: JsonObject, rev: Int = revision, value: JsonElement? = taskValue): JsonObject {
            val base = Wire.objectOf(
                "kind" to "snapshot", "sessionId" to request.text("sessionId"), "revision" to rev,
                "status" to "idle", "messages" to JsonArray(emptyList()), "pendingQuestions" to JsonArray(emptyList()),
                "nextCursor" to cursor,
            )
            return if (value == null) base else JsonObject(base + ("tasks" to value))
        }
        override fun send(payload: JsonObject) {
            sent += payload
            val responseData = when (payload.text("type")) {
                "projects.list" -> {
                    val base = Wire.objectOf("kind" to "projects", "items" to JsonArray(listOf(project)))
                    if (v1Only && payload.text("requestId").startsWith("capabilities.v2:")) base
                    else JsonObject(base + ("capabilities" to JsonArray(
                        if (advertised) listOf(JsonPrimitive(SESSION_TASKS_CAPABILITY)) else emptyList(),
                    )))
                }
                "sessions.list" -> Wire.objectOf("kind" to "sessions", "items" to JsonArray(listOf(session("session"), session("other"))))
                "sessions.open" -> Wire.objectOf("kind" to "session", "session" to session(payload.text("sessionId")))
                "session.snapshot" -> if (holdSnapshots) null else snapshot(payload)
                else -> Wire.objectOf("kind" to "accepted")
            }
            if (responseData != null) result(payload, responseData)
        }
        fun snapshots() = sent.filter { it.text("type") == "session.snapshot" }
    }

    private fun TestScope.repository(transport: Transport): DefaultRemoteRepository {
        val pairing = object : PairingStorage {
            override fun load() = listOf(host)
            override fun save(hosts: List<PairedHost>) = Unit
        }
        val drafts = object : DraftStorage {
            override fun load() = emptyMap<DraftKey, StoredDraft>()
            override fun save(drafts: Map<DraftKey, StoredDraft>) = Unit
        }
        return DefaultRemoteRepository(pairing, drafts, transport, backgroundScope,
            StandardTestDispatcher(testScheduler), now = { testScheduler.currentTime }, jitter = { 0.5 })
    }

    @Test fun v1AndHandshakeCannotOptIn() = runTest {
        val transport = Transport().apply { v1Only = true; handshake = setOf(SESSION_TASKS_CAPABILITY) }
        val repo = repository(transport)
        repo.activate(selection); runCurrent()
        assertTrue(transport.sent.any { it.text("type") == "projects.list" && it.text("requestId").startsWith("capabilities.v1:") })
        assertTrue(transport.snapshots().isNotEmpty())
        assertTrue(transport.snapshots().all { "includeTasks" !in it })
        assertEquals(TaskFreshness.UNKNOWN, repo.state.value.tasks.freshness)
    }

    @Test fun lateV2DiscoveryRequestsFreshSnapshot() = runTest {
        val transport = Transport().apply { advertised = false }
        val repo = repository(transport)
        repo.activate(selection); runCurrent()
        assertEquals(TaskFreshness.UNKNOWN, repo.state.value.tasks.freshness)
        assertTrue(transport.snapshots().all { "includeTasks" !in it })
        transport.advertised = true
        repo.openNotification("host", "elsewhere"); runCurrent()
        assertTrue(transport.snapshots().any { it["includeTasks"] == JsonPrimitive(true) })
        assertEquals(TaskFreshness.LIVE, repo.state.value.tasks.freshness)
    }

    @Test fun contiguousEventDuringTaskSnapshotRetriesAtCurrentRevision() = runTest {
        val transport = Transport().apply { advertised = false }
        val repo = repository(transport)
        repo.activate(selection); runCurrent()
        transport.holdSnapshots = true
        transport.advertised = true
        repo.openNotification("host", "elsewhere"); runCurrent()
        val old = transport.snapshots().last()
        assertEquals(JsonPrimitive(true), old["includeTasks"])
        transport.deliver(Wire.objectOf(
            "type" to "event", "sessionId" to "session", "revision" to 4, "kind" to "message.upsert",
            "message" to Wire.objectOf("id" to "m1", "role" to "assistant", "text" to "Hello", "state" to "complete"),
        ))
        transport.result(old, transport.snapshot(old, 3, tasks("outdated"))); runCurrent()
        assertEquals(TaskFreshness.UNKNOWN, repo.state.value.tasks.freshness)
        assertTrue(repo.state.value.tasks.items.isEmpty())
        val fresh = transport.snapshots().last()
        assertNotEquals(old.text("requestId"), fresh.text("requestId"))
        assertEquals("session", fresh.text("sessionId"))
        assertEquals(JsonPrimitive(true), fresh["includeTasks"])
        transport.result(fresh, transport.snapshot(fresh, 4, tasks("current"))); runCurrent()
        assertEquals(TaskFreshness.LIVE, repo.state.value.tasks.freshness)
        assertEquals("current", repo.state.value.tasks.items.single().text)
    }

    @Test fun lateDiscoveryDuringOlderPageWaitsForItThenRefreshesSelectedSession() = runTest {
        val transport = Transport().apply { advertised = false; cursor = "older" }
        val repo = repository(transport)
        repo.activate(selection); runCurrent()
        transport.holdSnapshots = true
        repo.older(); runCurrent()
        val page = transport.snapshots().last()
        assertEquals("older", page.text("cursor"))
        val count = transport.snapshots().size
        transport.advertised = true
        repo.openNotification("host", "elsewhere"); runCurrent()
        assertEquals(count, transport.snapshots().size)
        assertEquals(TaskFreshness.UNKNOWN, repo.state.value.tasks.freshness)
        transport.holdSnapshots = false
        transport.result(page, transport.snapshot(page)); runCurrent()
        val fresh = transport.snapshots().last()
        assertEquals(JsonPrimitive(true), fresh["includeTasks"])
        assertEquals("session", fresh.text("sessionId"))
        assertFalse("cursor" in fresh)
        assertEquals(TaskFreshness.LIVE, repo.state.value.tasks.freshness)
    }

    @Test fun reconnectKeepsStaleUntilNewSnapshotAndSwitchClears() = runTest {
        val transport = Transport()
        val repo = repository(transport)
        repo.activate(selection); runCurrent()
        assertEquals("first", repo.state.value.tasks.items.single().text)
        transport.listener.failed(true, R.string.remote_connection_error)
        assertEquals(TaskFreshness.STALE, repo.state.value.tasks.freshness)
        transport.holdSnapshots = true
        val reconnect = backgroundScope.launch { repo.activate(selection) }
        runCurrent()
        assertEquals(TaskFreshness.STALE, repo.state.value.tasks.freshness)
        val pending = transport.snapshots().last()
        assertFalse("includeTasks" in pending)
        transport.taskValue = tasks("reconnected")
        transport.holdSnapshots = false
        transport.result(pending, transport.snapshot(pending)); runCurrent()
        assertTrue(transport.snapshots().any { it["includeTasks"] == JsonPrimitive(true) && it != pending })
        assertEquals(TaskFreshness.LIVE, repo.state.value.tasks.freshness)
        assertEquals("reconnected", repo.state.value.tasks.items.single().text)
        assertTrue(reconnect.isCompleted)
        transport.holdSnapshots = true
        backgroundScope.launch { repo.activate(RemoteSelection("host", "project", "other")) }
        runCurrent()
        assertEquals(SessionTasks(), repo.state.value.tasks)
    }

    @Test fun absentMalformedAndEmptyAreDistinct() = runTest {
        val transport = Transport().apply { taskValue = null }
        val repo = repository(transport)
        repo.activate(selection); runCurrent()
        assertEquals(TaskFreshness.UNAVAILABLE, repo.state.value.tasks.freshness)
        transport.taskValue = JsonArray(listOf(Wire.objectOf("id" to 0, "text" to "x", "status" to "pending")))
        transport.revision = 4
        transport.deliver(Wire.objectOf("type" to "event", "sessionId" to "session", "revision" to 4, "kind" to "snapshot.required")); runCurrent()
        assertEquals(TaskFreshness.UNAVAILABLE, repo.state.value.tasks.freshness)
        transport.taskValue = JsonArray(emptyList()); transport.revision = 5
        transport.deliver(Wire.objectOf("type" to "event", "sessionId" to "session", "revision" to 5, "kind" to "snapshot.required")); runCurrent()
        assertEquals(SessionTasks(freshness = TaskFreshness.LIVE), repo.state.value.tasks)
    }

    @Test fun cursorCannotOverwriteTasksAndOlderSnapshotCannotBeatEvent() = runTest {
        val transport = Transport().apply { cursor = "older" }
        val repo = repository(transport)
        repo.activate(selection); runCurrent()
        transport.taskValue = tasks("cursor")
        repo.older(); runCurrent()
        val page = transport.snapshots().last()
        assertEquals("older", page.text("cursor"))
        assertFalse("includeTasks" in page)
        assertEquals("first", repo.state.value.tasks.items.single().text)
        transport.holdSnapshots = true
        transport.deliver(Wire.objectOf("type" to "event", "sessionId" to "session", "revision" to 4, "kind" to "snapshot.required")); runCurrent()
        assertEquals(TaskFreshness.STALE, repo.state.value.tasks.freshness)
        val old = transport.snapshots().last()
        transport.deliver(Wire.objectOf("type" to "event", "sessionId" to "session", "revision" to 5, "kind" to "snapshot.required"))
        transport.result(old, transport.snapshot(old, 3, tasks("old"))); runCurrent()
        assertEquals("first", repo.state.value.tasks.items.single().text)
        val newer = transport.snapshots().last()
        assertNotEquals(old.text("requestId"), newer.text("requestId"))
        transport.result(newer, transport.snapshot(newer, 5, tasks("new"))); runCurrent()
        assertEquals("new", repo.state.value.tasks.items.single().text)
        assertEquals(TaskFreshness.LIVE, repo.state.value.tasks.freshness)
    }
}
