package de.joinnoah.pi.remote

import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TicketsTest {
    private val host = PairedHost("host", "https://relay.test", "device", "secret", "Host")
    private val projectId = "019a2f3c-7d41-7b1e-9c55-3e8f1a2b4c6d"
    private val startId = "RmlsZVZlcnNpb25BYmMxMg"

    private val fixture: JsonObject by lazy {
        Wire.json.parseToJsonElement(javaClass.getResource("/tickets-v1.json")!!.readText()).jsonObject
    }

    private fun entries(group: String) = fixture.getValue(group).jsonArray.map { it.jsonObject }

    private fun valid(name: String): JsonObject = entries("valid").single { it.text("name") == name }.obj("payload")

    private fun data(name: String): JsonObject = valid(name).obj("data")

    private fun command(name: String): JsonObject = JsonObject(valid(name) - "requestId")

    private class Pairings(val host: PairedHost) : PairingStorage {
        override fun load() = listOf(host)

        override fun save(hosts: List<PairedHost>) {}
    }

    private class Drafts : DraftStorage {
        var values = emptyMap<DraftKey, StoredDraft>()

        override fun load() = values

        override fun save(drafts: Map<DraftKey, StoredDraft>) {
            values = drafts.toMap()
        }
    }

    private class Transport : RemoteTransport {
        override lateinit var listener: RemoteTransport.Listener
        val sent = mutableListOf<JsonObject>()
        var response: (JsonObject) -> JsonObject? = { null }

        override fun connect(host: PairedHost) = listener.ready(emptySet())

        override fun pair(value: JsonObject) {}

        override fun close() {}

        override fun send(payload: JsonObject) {
            sent += payload
            response(payload)?.let {
                listener.message(
                    Wire.objectOf("type" to "result", "requestId" to payload.text("requestId"), "ok" to true, "data" to it)
                )
            }
        }

        fun tickets() = sent.filter { it.text("type").startsWith("tickets.") }
    }

    private fun Transport.configure(capabilities: List<String>, tickets: (JsonObject) -> JsonObject? = { null }) {
        response = { request ->
            when (request.text("type")) {
                "projects.list" ->
                    Wire.objectOf(
                        "kind" to "projects",
                        "items" to JsonArray(listOf(Wire.objectOf("id" to "project"))),
                        "capabilities" to JsonArray(capabilities.map(::JsonPrimitive)),
                    )
                "sessions.list" -> Wire.objectOf("kind" to "sessions", "items" to JsonArray(emptyList()))
                else -> if (request.text("type").startsWith("tickets.")) tickets(request) else Wire.objectOf("kind" to "accepted")
            }
        }
    }

    private fun TestScope.repository(transport: Transport) =
        DefaultRemoteRepository(
            Pairings(host),
            Drafts(),
            transport,
            backgroundScope,
            StandardTestDispatcher(testScheduler),
            now = { testScheduler.currentTime },
        )

    private suspend fun TestScope.connected(transport: Transport): DefaultRemoteRepository {
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", null))
        runCurrent()
        return repository
    }

    private fun parse(data: JsonObject) {
        when (data.text("kind")) {
            "tickets.projects" -> parseTicketProjects(data)
            "tickets.list" -> parseTicketsPage(data, data.text("projectId"))
            "tickets.ticket" -> parseTicketDetail(data, data.text("projectId"))
            "tickets.moved" -> parseTicketMoved(data, data.text("projectId"), data.text("ref"))
            "tickets.start.started" -> parseTicketStartStarted(data)
            "tickets.start" -> parseTicketStart(data, data.text("startId"))
            "tickets.reloaded" -> parseTicketReloaded(data, data.text("projectId"))
            "tickets.commented" -> parseTicketCommented(data, data.text("projectId"), data.text("ref"))
            else -> error("unknown kind")
        }
    }

    // ---- Wire parsing -------------------------------------------------------------------

    @Test
    fun parsesEveryValidFixtureResult() {
        var results = 0
        for (entry in entries("valid")) {
            val payload = entry.obj("payload")
            if (payload.text("type") != "result") continue
            parse(payload.obj("data"))
            results++
        }
        assertTrue(results >= 10)
        val projects = parseTicketProjects(data("projects-result"))
        assertEquals(2, projects.size)
        assertTrue(projects[0].writable && projects[0].writeBlock == null && projects[0].available)
        assertEquals(listOf("DEV", "TOOLING"), projects[0].planeProjects)
        assertEquals(TicketWriteBlock.WRITES_OFF, projects[1].writeBlock)
        assertEquals(TicketReason.PLANE_UNREACHABLE, projects[1].reason)
        val page = parseTicketsPage(data("list-result"), projectId)
        assertEquals(1761552000000, page.syncedAt)
        assertEquals("page-2", page.nextCursor)
        val review = page.items[0]
        assertEquals("DEV-12", review.ref)
        assertEquals(TicketSection.ACCEPTANCE, review.section)
        assertEquals(setOf(TicketAction.ACCEPT, TicketAction.COMMENT, TicketAction.MOVE), review.actions)
        assertEquals(listOf("Done", "Cancelled"), review.moveTargets)
        assertEquals(TicketStateGroup.STARTED, review.stateGroup)
        assertEquals(TicketPriority.HIGH, review.priority)
        assertEquals("idle", review.sessions.single().status.wire)
        assertFalse(review.prOpen)
        assertNull(review.pr)
        val none = parseTicketsPage(data("list-result-unavailable"), projectId)
        assertFalse(none.available)
        assertEquals(TicketReason.entries.single { it.wire == data("list-result-unavailable").text("reason") }, none.reason)
        assertTrue(none.items.isEmpty())
        val detail = parseTicketDetail(data("ticket-result"), projectId)
        assertEquals("DEV-12", detail.ticket?.ref)
        assertEquals("Redirect loops after login.", detail.description)
        assertEquals("Looks good.", detail.comments.single().text)
        assertFalse(detail.commentsTruncated)
        val gone = parseTicketDetail(JsonObject(data("ticket-result").filterKeys { it != "ticket" } + mapOf("available" to JsonPrimitive(false), "reason" to JsonPrimitive("plane_rejected"))), projectId)
        assertNull(gone.ticket)
        assertEquals(TicketReason.PLANE_REJECTED, gone.reason)
        val moved = parseTicketMoved(data("moved-result"), projectId, "DEV-12")
        assertEquals(false, moved)
        val failed = parseTicketStart(data("start-failed-result"), startId)
        assertEquals(TicketStartError.SESSION_FAILED, failed.error)
        assertEquals(false, failed.moved)
        assertEquals(TicketStartStep.SESSION, failed.step)
    }

    @Test
    fun acceptsReservedOptionalFields() {
        val item = parseTicketsPage(data("list-result-reserved-fields"), projectId).items.single()
        assertTrue(item.prOpen)
        assertEquals(TicketPr(42, "https://github.com/acme/repo/pull/42"), item.pr)
        assertEquals("pass", item.checks)
        assertEquals(setOf(TicketAction.COMMENT), item.actions)
        assertTrue(item.moveTargets.isEmpty())
    }

    @Test
    fun rejectsEveryInvalidFixtureResult() {
        var results = 0
        for (entry in entries("invalid")) {
            val payload = entry.obj("payload")
            if (payload.text("type") != "result") continue
            results++
            assertTrue(entry.text("name"), runCatching { parse(payload.obj("data")) }.isFailure)
        }
        assertTrue(results >= 5)
    }

    @Test
    fun rejectsUnknownKeysAndLimits() {
        fun fails(data: JsonObject, block: (JsonObject) -> Unit = { parse(it) }) =
            assertTrue(data.toString(), runCatching { block(data) }.isFailure)
        fun with(base: JsonObject, vararg changes: Pair<String, JsonElement>) = JsonObject(base + changes)
        val detail = data("ticket-result")
        val ticket = detail.obj("ticket")
        fun detailWith(vararg changes: Pair<String, JsonElement>) = with(detail, "ticket" to with(ticket, *changes))
        fails(with(data("projects-result"), "extra" to JsonPrimitive(1)))
        fails(detailWith("title" to JsonPrimitive("x".repeat(4097))))
        fails(detailWith("url" to JsonPrimitive("x".repeat(2049))))
        fails(detailWith("description" to JsonPrimitive("x".repeat(32 * 1024 + 1))))
        fails(detailWith("description" to JsonPrimitive("ä".repeat(16 * 1024 + 1))))
        val comment = ticket.getValue("comments").jsonArray[0].jsonObject
        fails(detailWith("comments" to JsonArray(List(31) { comment })))
        fails(detailWith("comments" to JsonArray(listOf(with(comment, "text" to JsonPrimitive("x".repeat(6 * 1024 + 1)))))))
        detailWith("comments" to JsonArray(List(30) { comment })).let { parse(it) }
        fails(detailWith("ref" to JsonPrimitive("OTHER-1")))
        fails(detailWith("ref" to JsonPrimitive("DEV-1234567890")))
        fails(detailWith("moveTargets" to JsonArray(listOf(JsonPrimitive("Review")))))
        fails(detailWith("priority" to JsonPrimitive("critical")))
        fails(detailWith("pr" to Wire.objectOf("number" to 1)))
        // The page is for the project asked.
        fails(data("list-result")) { parseTicketsPage(it, "other") }
        fails(data("ticket-result")) { parseTicketDetail(it, "other") }
        fails(data("moved-result")) { parseTicketMoved(it, projectId, "DEV-13") }
        fails(data("start-failed-result")) { parseTicketStart(it, "QUJDREVGR0hJSktMTU5PUA") }
        val list = data("list-result")
        fails(with(list, "items" to JsonArray(List(101) { list.getValue("items").jsonArray[0] })))
        fails(with(list, "nextCursor" to JsonPrimitive("c".repeat(1025))))
        val projects = data("projects-result")
        val first = projects.getValue("items").jsonArray[0].jsonObject
        fails(with(projects, "items" to JsonArray(List(65) { first })))
        // writeBlock only when not writable, reason only when unavailable.
        fails(with(projects, "items" to JsonArray(listOf(with(first, "writeBlock" to JsonPrimitive("writes_off"))))))
        fails(with(projects, "items" to JsonArray(listOf(with(first, "reason" to JsonPrimitive("plane_unreachable"))))))
        // A start error only when the start failed.
        val running = data("start-running-result")
        fails(with(running, "error" to JsonPrimitive("timeout")))
        fails(with(running, "moved" to JsonPrimitive("yes")))
        fails(with(running, "step" to JsonPrimitive("later")))
    }

    /** The checks the repository makes before it sends a command; throws when the host would refuse it. */
    private fun preSend(command: JsonObject) {
        when (command.text("type")) {
            "tickets.projects" -> Unit
            "tickets.list" -> {
                requireTicketProjectId(command.text("projectId"))
                requireTicketCursor(command.optionalText("cursor"))
            }
            "tickets.get", "tickets.reload", "tickets.comment", "tickets.move", "tickets.start" -> {
                requireTicketProjectId(command.text("projectId"))
                if (command.containsKey("ref")) requireTicketRef(command.text("ref"))
                when (command.text("type")) {
                    "tickets.comment" -> requireTicketComment(command.text("text"))
                    "tickets.move" -> requireTicketMove(command.text("state"), command.optionalText("comment"))
                    "tickets.start" -> requireTicketNote(command.optionalText("note"))
                }
            }
            "tickets.start.status" -> requireStartId(command.text("startId"))
            else -> error("unknown command")
        }
        // The host's strict parser also refuses extra keys; the app never builds them.
        val known = setOf("type", "requestId", "projectId", "cursor", "ref", "text", "state", "comment", "note", "startId")
        require(command.keys.all { it in known })
    }

    @Test
    fun invalidFixtureCommandsAreRefusedBeforeSending() {
        var commands = 0
        for (entry in entries("invalid")) {
            val payload = entry.obj("payload")
            if (payload.text("type") == "result") continue
            commands++
            assertTrue(entry.text("name"), runCatching { preSend(payload) }.isFailure)
        }
        assertTrue(commands >= 7)
    }

    @Test
    fun validFixtureCommandsPassTheSendChecks() {
        for (entry in entries("valid")) {
            val payload = entry.obj("payload")
            if (payload.text("type") == "result") continue
            runCatching { preSend(payload) }.onFailure { throw AssertionError(entry.text("name"), it) }
        }
        assertEquals(8000, valid("comment-command-max-length").text("text").length)
    }

    // ---- Repository ---------------------------------------------------------------------

    private val ticketCapabilities = listOf(TICKETS_CAPABILITY)

    private fun reply(request: JsonObject): JsonObject? =
        when (request.text("type")) {
            "tickets.projects" -> data("projects-result")
            "tickets.list" -> data("list-result")
            "tickets.get" -> data("ticket-result")
            "tickets.reload" -> data("reloaded-result")
            "tickets.comment" -> data("commented-result")
            "tickets.move" -> JsonObject(data("moved-result") + ("state" to request.getValue("state")) + ("ref" to request.getValue("ref")))
            "tickets.start" -> data("start-started-result")
            else -> data("start-succeeded-result")
        }

    @Test
    fun capabilityKeptFromV2Route() = runTest {
        val transport = Transport()
        transport.configure(ticketCapabilities)
        val repository = connected(transport)
        assertTrue(TICKETS_CAPABILITY in repository.state.value.capabilities)
        assertTrue(canUseTickets(repository.state.value))
        val without = Transport()
        without.configure(emptyList())
        assertFalse(TICKETS_CAPABILITY in connected(without).state.value.capabilities)
    }

    @Test
    fun sentCommandsMatchFixture() = runTest {
        val transport = Transport()
        transport.configure(ticketCapabilities, ::reply)
        val repository = connected(transport)
        assertEquals(2, repository.ticketProjects().size)
        assertEquals(2, repository.ticketsPage(projectId, null).items.size)
        assertEquals("page-2", repository.ticketsPage(projectId, "page-2").nextCursor)
        assertEquals("DEV-12", repository.ticketDetail(projectId, "DEV-12").ticket?.ref)
        assertEquals(data("reloaded-result").long("syncedAt"), repository.reloadTickets(projectId))
        repository.commentTicket(projectId, "DEV-12", valid("comment-command").getValue("text").jsonPrimitive.content)
        val done = valid("move-command-done")
        assertFalse(
            repository.moveTicket(projectId, "DEV-12", done.text("state"), done.text("comment"))
        )
        repository.moveTicket(projectId, "TOOLING-3", "Todo", null)
        val start = valid("start-command")
        assertEquals(startId.length, repository.startTicket(projectId, "TOOLING-3", start.text("note")).length)
        assertEquals(TicketStartState.SUCCEEDED, repository.ticketStartStatus(startId).state)
        val sent = transport.tickets().map { JsonObject(it - "requestId") }
        assertEquals(
            listOf(
                command("projects-command"),
                command("list-command"),
                command("list-command-cursor"),
                command("get-command"),
                command("reload-command"),
                command("comment-command"),
                command("move-command-done"),
                command("move-command-todo-without-comment"),
                command("start-command"),
                command("start-status-command"),
            ),
            sent,
        )
    }

    @Test
    fun noTicketCommandWithoutCapability() = runTest {
        val transport = Transport()
        transport.configure(emptyList()) { error("must not be sent") }
        val repository = connected(transport)
        suspend fun unsupported(block: suspend () -> Unit) {
            val error = runCatching { block() }.exceptionOrNull()
            assertTrue(error.toString(), error?.isUnsupported == true)
        }
        unsupported { repository.ticketProjects() }
        unsupported { repository.ticketsPage(projectId, null) }
        unsupported { repository.ticketDetail(projectId, "DEV-12") }
        unsupported { repository.reloadTickets(projectId) }
        unsupported { repository.commentTicket(projectId, "DEV-12", "ok") }
        unsupported { repository.moveTicket(projectId, "DEV-12", "Done", "ok") }
        unsupported { repository.startTicket(projectId, "TOOLING-3", null) }
        unsupported { repository.ticketStartStatus(startId) }
        assertTrue(transport.tickets().isEmpty())
    }

    @Test
    fun rejectsInvalidArgumentsBeforeSending() = runTest {
        val transport = Transport()
        transport.configure(ticketCapabilities, ::reply)
        val repository = connected(transport)
        suspend fun refused(block: suspend () -> Unit) = assertTrue(runCatching { block() }.isFailure)
        refused { repository.ticketDetail(projectId, "PROD-1") }
        refused { repository.commentTicket(projectId, "DEV-12", "") }
        refused { repository.commentTicket(projectId, "DEV-12", "x".repeat(8001)) }
        refused { repository.moveTicket(projectId, "DEV-12", "Done", null) }
        refused { repository.moveTicket(projectId, "DEV-12", "Review", "ok") }
        refused { repository.moveTicket(projectId, "DEV-12", "Cancelled", "x".repeat(2001)) }
        refused { repository.startTicket(projectId, "DEV-12", "x".repeat(2001)) }
        refused { repository.ticketStartStatus("abc") }
        assertTrue(transport.tickets().isEmpty())
    }
}
