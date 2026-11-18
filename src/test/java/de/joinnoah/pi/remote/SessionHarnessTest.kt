package de.joinnoah.pi.remote

import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Runs `session-harness-v1.json`: the `harness` of `sessions.create` and of session rows. */
class SessionHarnessTest {
    private val fixture =
        Wire.json.parseToJsonElement(javaClass.getResource("/session-harness-v1.json")!!.readText()).jsonObject
    private val valid = fixture.array("wireValid").associate { it.text("name") to it.obj("payload") }
    private val invalid = fixture.array("wireInvalid").associate { it.text("name") to it.obj("payload") }

    private val capable = setOf(SESSION_HARNESS_CAPABILITY)

    @Test
    fun onlyClaudeOnACapableHostSendsTheHarness() {
        val claude = valid.getValue("create-claude")
        assertEquals(
            listOf("projectId" to claude.text("projectId"), "harness" to claude.text("harness")),
            sessionCreateFields(claude.text("projectId"), SessionHarness.CLAUDE, capable),
        )
        // pi is the host's default: the request stays what an older host accepts.
        assertEquals(listOf("projectId" to "project"), sessionCreateFields("project", SessionHarness.PI, capable))
        assertEquals(listOf("projectId" to "project"), sessionCreateFields("project", SessionHarness.PI, emptySet()))
        // A host without the capability would reject the field.
        assertNull(sessionCreateFields("project", SessionHarness.CLAUDE, emptySet()))
        assertTrue(harnessSelectable(capable))
        assertFalse(harnessSelectable(setOf(HOST_WORKER_V2_CAPABILITY)))
    }

    @Test
    fun rowsReadTheirHarness() {
        val rows = valid.getValue("list-with-claude").obj("data").array("items").associateBy { it.text("id") }
        val claude = sessionListItem(rows.getValue("claude-session"), true, false, confirmed = true)
        assertEquals(SessionHarness.CLAUDE, claude.harness)
        assertTrue(claude.liveMacTui)
        assertEquals(SessionHarness.PI, sessionListItem(rows.getValue("pi-session"), true, false, confirmed = true).harness)
        val created = valid.getValue("create-claude-result").obj("data").obj("session")
        assertEquals(SessionHarness.CLAUDE, sessionHarness(created))
        // The host never sends `pi`; should one arrive, it is pi all the same, as is an unknown value.
        assertEquals(SessionHarness.PI, sessionHarness(invalid.getValue("session-harness-pi").obj("data").obj("session")))
        assertEquals(SessionHarness.PI, sessionHarness(Wire.objectOf("id" to "s", "harness" to "junie")))
    }

    private val host = PairedHost("host", "https://relay.test", "device", "secret", "Host")

    private class Pairings(private val host: PairedHost) : PairingStorage {
        override fun load() = listOf(host)
        override fun save(hosts: List<PairedHost>) = Unit
    }

    private class Drafts : DraftStorage {
        override fun load() = emptyMap<DraftKey, StoredDraft>()
        override fun save(drafts: Map<DraftKey, StoredDraft>) = Unit
    }

    private inner class Transport(val capabilities: Set<String>, val cliMissing: Boolean = false) : RemoteTransport {
        override lateinit var listener: RemoteTransport.Listener
        val sent = mutableListOf<JsonObject>()

        override fun connect(host: PairedHost) {
            listener.ready(emptySet())
        }

        override fun pair(value: JsonObject) = Unit

        override fun close() = Unit

        override fun send(payload: JsonObject) {
            sent += payload
            val requestId = payload.text("requestId")
            if (payload.text("type") == "sessions.create" && payload.optionalText("harness") == "claude") {
                val result = valid.getValue(if (cliMissing) "claude-unavailable" else "create-claude-result")
                listener.message(JsonObject(result + ("requestId" to JsonPrimitive(requestId))))
                return
            }
            val pi = Wire.objectOf("id" to "pi-session", "projectId" to "project", "origin" to "tui", "status" to "idle")
            val data =
                when (payload.text("type")) {
                    "projects.list" ->
                        Wire.objectOf(
                            "kind" to "projects",
                            "items" to JsonArray(listOf(Wire.objectOf("id" to "project"))),
                            "capabilities" to JsonArray(capabilities.map(::JsonPrimitive)),
                        )
                    "sessions.list" -> Wire.objectOf("kind" to "sessions", "items" to JsonArray(emptyList()))
                    "sessions.create" -> Wire.objectOf("kind" to "session", "session" to pi)
                    "session.snapshot" ->
                        Wire.objectOf(
                            "kind" to "snapshot",
                            "sessionId" to payload.text("sessionId"),
                            "revision" to 0,
                            "status" to "idle",
                            "messages" to JsonArray(emptyList()),
                            "pendingQuestions" to JsonArray(emptyList()),
                        )
                    else -> Wire.objectOf("kind" to "accepted")
                }
            listener.message(Wire.objectOf("type" to "result", "requestId" to requestId, "ok" to true, "data" to data))
        }

        fun creates() = sent.filter { it.text("type") == "sessions.create" }
    }

    private suspend fun TestScope.connected(transport: Transport): DefaultRemoteRepository {
        val repository =
            DefaultRemoteRepository(
                Pairings(host),
                Drafts(),
                transport,
                backgroundScope,
                StandardTestDispatcher(testScheduler),
                now = { testScheduler.currentTime },
            )
        repository.activate(RemoteSelection("host", "project"))
        runCurrent()
        return repository
    }

    @Test
    fun aCapableHostCreatesAClaudeCodeSession() = runTest {
        val transport = Transport(capable)
        val repository = connected(transport)
        assertTrue(SESSION_HARNESS_CAPABILITY in repository.state.value.capabilities)

        assertEquals(
            RemoteSelection("host", "project", "claude-session"),
            repository.createSession("host", "project", SessionHarness.CLAUDE),
        )
        assertEquals("claude", transport.creates().single().text("harness"))
        assertEquals(SessionHarness.CLAUDE, sessionHarness(checkNotNull(repository.state.value.session)))
    }

    @Test
    fun piNeverSendsTheHarness() = runTest {
        val transport = Transport(capable)
        val repository = connected(transport)

        assertEquals(RemoteSelection("host", "project", "pi-session"), repository.createSession("host", "project"))
        assertFalse(transport.creates().single().containsKey("harness"))
    }

    @Test
    fun anOlderHostIsNotAskedForClaudeCode() = runTest {
        val transport = Transport(emptySet())
        val repository = connected(transport)

        assertEquals(RemoteSelection("host", "project"), repository.createSession("host", "project", SessionHarness.CLAUDE))
        assertTrue(transport.creates().isEmpty())
        assertEquals(R.string.remote_new_session_claude_unavailable, repository.state.value.error)
    }

    @Test
    fun aMissingClaudeCodeCliIsReported() = runTest {
        val transport = Transport(capable, cliMissing = true)
        val repository = connected(transport)

        assertEquals(RemoteSelection("host", "project"), repository.createSession("host", "project", SessionHarness.CLAUDE))
        assertEquals("claude", transport.creates().single().text("harness"))
        assertEquals(R.string.remote_new_session_claude_unavailable, repository.state.value.error)
    }
}
