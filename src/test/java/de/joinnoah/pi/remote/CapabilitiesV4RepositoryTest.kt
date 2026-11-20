package de.joinnoah.pi.remote

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Negotiation of the `capabilities.v4:` route and what its list and limits enable. */
class CapabilitiesV4RepositoryTest {
    private val host = PairedHost("host", "https://relay.test", "device", "secret", "Host")
    private val selection = RemoteSelection("host", "project", "session")
    private val limitsFixture: JsonObject by lazy {
        Wire.json.parseToJsonElement(javaClass.getResource("/session-limits-v1.json")!!.readText()).jsonObject
    }
    private val fixtureLimits: JsonObject by lazy {
        limitsFixture.getValue("wireValid").jsonArray.map { it.jsonObject }
            .single { it.text("name") == "result-with-limits" }.obj("payload").obj("data").obj("limits")
    }

    private inner class Transport : RemoteTransport {
        override lateinit var listener: RemoteTransport.Listener
        val sent = mutableListOf<JsonObject>()
        /** The newest route the host knows; an older route ID is opaque to it and gets no list. */
        var hostRoute = 4
        var advertised = listOf(SESSION_TERMINAL_CAPABILITY, SESSION_BROWSER_CAPABILITY, SESSION_LIMITS_CAPABILITY,
            SESSION_BROWSER_HISTORY_CAPABILITY, SESSION_EMULATOR_CONTROLS_CAPABILITY, SESSION_EMULATOR_KEYBOARD_CAPABILITY, SESSION_TERMINAL_SHELL_CAPABILITY)
        var limits: JsonElement? = null
        /** Limits on the v3 answer too, which the client must ignore. */
        var limitsOnV3 = false
        var session = Wire.objectOf("id" to "session", "projectId" to "project", "origin" to "tui", "status" to "idle")
        override fun connect(host: PairedHost) = listener.ready(emptySet())
        override fun close() = Unit
        override fun pair(value: JsonObject) = Unit
        private fun result(request: JsonObject, data: JsonObject) {
            listener.inbound()
            listener.message(Wire.objectOf("type" to "result", "requestId" to request.text("requestId"), "ok" to true, "data" to data))
        }
        private fun projects(request: JsonObject): JsonObject {
            val id = request.text("requestId")
            val route = Regex("^capabilities\\.v(\\d):").find(id)?.groupValues?.get(1)?.toInt() ?: 0
            var data = Wire.objectOf("kind" to "projects", "items" to JsonArray(listOf(Wire.objectOf("id" to "project"))))
            if (route > hostRoute) return data
            data = JsonObject(data + ("capabilities" to JsonArray(advertised.map(::JsonPrimitive))))
            val withLimits = limits?.takeIf { route == 4 || limitsOnV3 && route == 3 }
            return if (withLimits != null) JsonObject(data + ("limits" to withLimits)) else data
        }
        override fun send(payload: JsonObject) {
            sent += payload
            when (payload.text("type")) {
                "projects.list" -> result(payload, projects(payload))
                "sessions.list" -> result(payload, Wire.objectOf("kind" to "sessions", "items" to JsonArray(listOf(session))))
                "sessions.open" -> result(payload, Wire.objectOf("kind" to "session", "session" to session))
                "session.snapshot" -> result(payload, Wire.objectOf("kind" to "snapshot", "sessionId" to "session", "revision" to 1,
                    "status" to "idle", "messages" to JsonArray(emptyList()), "pendingQuestions" to JsonArray(emptyList())))
                else -> result(payload, Wire.objectOf("kind" to "accepted"))
            }
        }
        fun discoveries() = sent.filter { it.text("type") == "projects.list" }.map { it.text("requestId") }
    }

    private fun TestScope.repository(transport: Transport) = DefaultRemoteRepository(object : PairingStorage {
        override fun load() = listOf(host)
        override fun save(hosts: List<PairedHost>) = Unit
    }, object : DraftStorage {
        override fun load() = emptyMap<DraftKey, StoredDraft>()
        override fun save(drafts: Map<DraftKey, StoredDraft>) = Unit
    }, transport, backgroundScope, StandardTestDispatcher(testScheduler), now = { testScheduler.currentTime }, jitter = { 0.5 })

    @Test fun aV4HostIsAskedOnV4WithTheV3OptInsAndGrantsTheV4OnlyNames() = runTest {
        val transport = Transport().apply { limits = fixtureLimits }
        val repo = repository(transport)
        repo.activate(selection); runCurrent()
        val first = transport.discoveries().first()
        assertTrue(first.startsWith("capabilities.v4:optin="))
        // The same opt-ins as the v3 route.
        assertTrue(SESSION_ACTIVITY_CAPABILITY in first.substringAfter("optin=").substringBefore(':').split('+'))
        assertTrue(subagentMetadataOptedIn(first))
        assertTrue(transport.discoveries().all { it.startsWith("capabilities.v4:") })
        val caps = repo.state.value.capabilities
        for (name in listOf(SESSION_LIMITS_CAPABILITY, SESSION_BROWSER_HISTORY_CAPABILITY, SESSION_EMULATOR_CONTROLS_CAPABILITY, SESSION_EMULATOR_KEYBOARD_CAPABILITY, SESSION_TERMINAL_SHELL_CAPABILITY))
            assertTrue(name, name in caps)
        assertEquals(parseSessionLimits(fixtureLimits), repo.state.value.hostLimits)
        assertEquals(32L, repo.state.value.hostLimits?.emulator?.maxInFlightInputs)
    }

    @Test fun anOlderHostWithoutV4FallsBackToV3AndGrantsNoV4OnlyName() = runTest {
        val transport = Transport().apply { hostRoute = 3; limits = fixtureLimits; limitsOnV3 = true }
        val repo = repository(transport)
        repo.activate(selection); runCurrent()
        val routes = transport.discoveries().map { it.substringBefore(':') }
        assertEquals(listOf("capabilities.v4", "capabilities.v3"), routes.take(2))
        assertTrue(transport.discoveries()[1].startsWith("capabilities.v3:optin="))
        // Later discoveries stay on v3.
        assertTrue(routes.drop(1).all { it == "capabilities.v3" })
        val caps = repo.state.value.capabilities
        assertTrue(SESSION_BROWSER_CAPABILITY in caps)
        for (name in listOf(SESSION_LIMITS_CAPABILITY, SESSION_BROWSER_HISTORY_CAPABILITY, SESSION_EMULATOR_CONTROLS_CAPABILITY, SESSION_EMULATOR_KEYBOARD_CAPABILITY, SESSION_TERMINAL_SHELL_CAPABILITY))
            assertFalse(name, name in caps)
        // Limits on a v3 answer are never read.
        assertNull(repo.state.value.hostLimits)
        assertTrue(repo.state.value.capabilitiesKnown)
    }

    @Test fun theV4ListMayHold128EntriesButNotMore() = runTest {
        val extra = List(128 - Transport().advertised.size) { "x.cap$it.v1" }
        val ok = Transport().apply { advertised = advertised + extra }
        assertEquals(128, ok.advertised.size)
        val repo = repository(ok)
        repo.activate(selection); runCurrent()
        assertTrue(repo.state.value.capabilitiesKnown)
        assertTrue(SESSION_BROWSER_HISTORY_CAPABILITY in repo.state.value.capabilities)

        val tooLong = Transport().apply { advertised = advertised + extra + "x.one-more.v1" }
        val refused = repository(tooLong)
        refused.activate(selection); runCurrent()
        assertFalse(refused.state.value.capabilitiesKnown)
        // A malformed list is an error, not a fallback signal.
        assertTrue(tooLong.discoveries().all { it.startsWith("capabilities.v4:") })
    }

    @Test fun aV3ListStillHasItsOwnBoundOf64() = runTest {
        val transport = Transport().apply { hostRoute = 3; advertised = advertised + List(65 - advertised.size) { "x.cap$it.v1" } }
        assertEquals(65, transport.advertised.size)
        val repo = repository(transport)
        repo.activate(selection); runCurrent()
        assertFalse(repo.state.value.capabilitiesKnown)
    }

    @Test fun malformedLimitsKeepTheBuiltInValues() = runTest {
        val invalid = limitsFixture.getValue("wireInvalid").jsonArray.map { it.jsonObject }
            .single { it.text("name") == "in-flight-zero" }.obj("payload").obj("data").getValue("limits")
        val transport = Transport().apply { limits = invalid }
        val repo = repository(transport)
        repo.activate(selection); runCurrent()
        assertTrue(repo.state.value.capabilitiesKnown)
        assertTrue(SESSION_LIMITS_CAPABILITY in repo.state.value.capabilities)
        assertNull(repo.state.value.hostLimits)
    }

    @Test fun aHandStartedSessionOffersTheTerminalOnlyWithTheShellCapability() = runTest {
        val shell = Transport()
        val repo = repository(shell)
        repo.activate(selection); runCurrent()
        assertTrue(repo.canAttachTerminal())
        assertTrue(repo.attachTerminal(80, 24))
        assertTrue(repo.state.value.terminal?.shell == true)
        runCurrent()
        assertEquals("session", shell.sent.last { it.text("type") == "session.terminal.attach" }.text("sessionId"))

        val v3Host = Transport().apply { hostRoute = 3 }
        val old = repository(v3Host)
        old.activate(selection); runCurrent()
        assertFalse(old.canAttachTerminal())
        assertFalse(old.attachTerminal(80, 24))

        val child = Transport().apply { session = JsonObject(session + ("parentSessionId" to JsonPrimitive("parent"))) }
        val childRepo = repository(child)
        childRepo.activate(selection); runCurrent()
        assertFalse(childRepo.canAttachTerminal())

        val rpc = Transport().apply { session = JsonObject(session + ("origin" to JsonPrimitive("rpc"))) }
        val rpcRepo = repository(rpc)
        rpcRepo.activate(selection); runCurrent()
        assertFalse(rpcRepo.canAttachTerminal())
    }
}
