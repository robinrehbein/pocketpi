package de.joinnoah.pi.remote

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ContinuationRepositoryTest {
    private val host = PairedHost("host", "https://relay.test", "device", "secret", "Host")
    private val selection = RemoteSelection("host", "project", "session")
    private inner class Transport : RemoteTransport {
        override lateinit var listener: RemoteTransport.Listener
        val sent = mutableListOf<JsonObject>()
        var supported = true
        var capabilityRevision = 3
        var hold = false
        var holdCancel = false
        var failSnapshot = false
        var malformedSnapshot = false
        var items = JsonArray(listOf(Wire.objectOf("id" to "wake", "kind" to "wakeup", "phase" to "scheduled", "restartPolicy" to "volatile", "dueAt" to 1000L)))
        override fun connect(host: PairedHost) = listener.ready(emptySet())
        override fun close() = Unit
        override fun pair(value: JsonObject) = Unit
        fun result(request: JsonObject, data: JsonObject) {
            listener.inbound()
            val response = if (capabilityRevision == 2 && request.text("type") == "projects.list" && request.text("requestId").startsWith("capabilities.v3:")) JsonObject(data - "capabilities") else data
            listener.message(Wire.objectOf("type" to "result", "requestId" to request.text("requestId"), "ok" to true, "data" to response))
        }
        fun snapshot(request: JsonObject) = result(request, Wire.objectOf(
            "kind" to "snapshot", "sessionId" to request.text("sessionId"), "revision" to 3,
            "status" to "idle", "messages" to JsonArray(emptyList()), "pendingQuestions" to JsonArray(emptyList()),
            "continuations" to if (malformedSnapshot) JsonPrimitive("invalid") else Wire.objectOf("owner" to "owner", "items" to items)))
        override fun send(payload: JsonObject) {
            sent += payload
            when (payload.text("type")) {
                "projects.list" -> result(payload, Wire.objectOf("kind" to "projects", "items" to JsonArray(listOf(Wire.objectOf("id" to "project"))), "capabilities" to JsonArray(if (supported) listOf(JsonPrimitive(SESSION_CONTINUATIONS_CAPABILITY)) else emptyList())))
                "sessions.list" -> result(payload, Wire.objectOf("kind" to "sessions", "items" to JsonArray(listOf(session()))))
                "sessions.open" -> result(payload, Wire.objectOf("kind" to "session", "session" to session()))
                "session.snapshot" -> if (failSnapshot) {
                    listener.message(Wire.objectOf("type" to "result", "requestId" to payload.text("requestId"), "ok" to false, "error" to Wire.objectOf("code" to "internal", "message" to "Failed")))
                } else if (!hold) snapshot(payload)
                "session.continuations.cancel" -> if (!holdCancel) cancelled(payload)
                else -> result(payload, Wire.objectOf("kind" to "accepted"))
            }
        }
        fun cancelled(request: JsonObject) = result(request, Wire.objectOf("kind" to "continuations.cancelled", "sessionId" to "session", "outcome" to "cancelled"))
        fun session() = Wire.objectOf("id" to "session", "projectId" to "project", "origin" to "rpc", "status" to "idle")
    }

    @Test fun capabilityAndSelectionGateCancellation() = cancellationIsGated(false, 3)

    @Test fun oldV2CannotAdvertiseContinuations() = cancellationIsGated(true, 2)

    private fun cancellationIsGated(supported: Boolean, revision: Int) = runTest {
        val transport = Transport().apply { this.supported = supported; capabilityRevision = revision }
        val repo = DefaultRemoteRepository(object : PairingStorage {
            override fun load() = listOf(host)
            override fun save(hosts: List<PairedHost>) = Unit
        }, object : DraftStorage {
            override fun load() = emptyMap<DraftKey, StoredDraft>()
            override fun save(drafts: Map<DraftKey, StoredDraft>) = Unit
        }, transport, backgroundScope, StandardTestDispatcher(testScheduler), now = { testScheduler.currentTime }, jitter = { 0.5 })
        repo.stopContinuation("wake"); runCurrent()
        repo.activate(selection); runCurrent()
        repo.stopContinuation("wake"); runCurrent()
        assertTrue(transport.sent.none { it.text("type") == "session.continuations.cancel" })
        if (revision == 2) assertTrue(transport.sent.any { it.text("type") == "projects.list" && it.text("requestId").startsWith("capabilities.v2:") })
        assertTrue(transport.sent.filter { it.text("type") == "session.snapshot" }.all { "includeContinuations" !in it })
    }

    @Test fun independentReadsCannotReleaseInFlightStops() = runTest {
        val transport = Transport()
        val repo = DefaultRemoteRepository(object : PairingStorage {
            override fun load() = listOf(host)
            override fun save(hosts: List<PairedHost>) = Unit
        }, object : DraftStorage {
            override fun load() = emptyMap<DraftKey, StoredDraft>()
            override fun save(drafts: Map<DraftKey, StoredDraft>) = Unit
        }, transport, backgroundScope, StandardTestDispatcher(testScheduler), now = { testScheduler.currentTime }, jitter = { 0.5 })
        transport.items = JsonArray(listOf("wake", "other").map { Wire.objectOf("id" to it, "kind" to "wakeup", "phase" to "scheduled", "restartPolicy" to "volatile", "dueAt" to 1000L) })
        repo.activate(selection); runCurrent()
        transport.holdCancel = true
        repo.stopContinuation("wake"); repo.stopContinuation("other"); runCurrent()
        val original = transport.sent.filter { it.text("type") == "session.continuations.cancel" }
        transport.failSnapshot = true
        repo.refresh(); runCurrent()
        assertEquals(setOf("wake", "other"), repo.state.value.continuationStopping)
        transport.failSnapshot = false
        transport.malformedSnapshot = true
        repo.refresh(); runCurrent()
        assertEquals(setOf("wake", "other"), repo.state.value.continuationStopping)
        transport.malformedSnapshot = false
        repo.refresh(); runCurrent()
        repo.stopContinuation("wake"); repo.stopContinuation("other"); runCurrent()
        assertEquals(2, transport.sent.count { it.text("type") == "session.continuations.cancel" })
        transport.cancelled(original.first()); runCurrent()
        assertEquals(setOf("other"), repo.state.value.continuationStopping)
        assertTrue("wake" in repo.state.value.continuationStopFailed)
        repo.stopContinuation("wake"); runCurrent()
        assertEquals(setOf("wake", "other"), repo.state.value.continuationStopping)
        transport.cancelled(original.first()); runCurrent()
        assertEquals(setOf("wake", "other"), repo.state.value.continuationStopping)
        transport.cancelled(original.last()); runCurrent()
        assertEquals(setOf("wake"), repo.state.value.continuationStopping)
        assertTrue("other" in repo.state.value.continuationStopFailed)
    }

    @Test fun acknowledgementWaitsForAuthoritativeRemoval() = runTest {
        val transport = Transport()
        val repo = DefaultRemoteRepository(object : PairingStorage {
            override fun load() = listOf(host)
            override fun save(hosts: List<PairedHost>) = Unit
        }, object : DraftStorage {
            override fun load() = emptyMap<DraftKey, StoredDraft>()
            override fun save(drafts: Map<DraftKey, StoredDraft>) = Unit
        }, transport, backgroundScope, StandardTestDispatcher(testScheduler), now = { testScheduler.currentTime }, jitter = { 0.5 })
        repo.activate(selection); runCurrent()
        assertEquals(ContinuationFreshness.LIVE, repo.state.value.continuations.freshness)
        transport.hold = true
        repo.stopContinuation("wake"); runCurrent()
        assertEquals(setOf("wake"), repo.state.value.continuationStopping)
        assertEquals("wake", repo.state.value.continuations.items.single().id)
        val cancel = transport.sent.single { it.text("type") == "session.continuations.cancel" }
        assertEquals("owner", cancel.text("owner"))
        transport.items = JsonArray(emptyList())
        transport.snapshot(transport.sent.last { it.text("type") == "session.snapshot" }); runCurrent()
        assertTrue(repo.state.value.continuationStopping.isEmpty())
        assertTrue(repo.state.value.continuations.items.isEmpty())
    }
}
