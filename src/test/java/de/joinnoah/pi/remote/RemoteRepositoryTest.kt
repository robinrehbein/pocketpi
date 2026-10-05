package de.joinnoah.pi.remote

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RemoteRepositoryTest {
    private val host = PairedHost("host", "https://relay.test", "device", "secret", "Host")
    private val project = Wire.objectOf("id" to "project")

    private fun session(
        id: String = "session",
        origin: String = "rpc",
        status: String = "idle",
        projectId: String = "project",
    ) = Wire.objectOf("id" to id, "origin" to origin, "status" to status, "projectId" to projectId)

    private class Pairings(var hosts: List<PairedHost>) : PairingStorage {
        override fun load() = hosts

        override fun save(hosts: List<PairedHost>) {
            this.hosts = hosts
        }
    }

    private class Drafts : DraftStorage {
        var values = emptyMap<DraftKey, StoredDraft>()
        var fail = false

        override fun load() = values.toMap()

        override fun save(drafts: Map<DraftKey, StoredDraft>) {
            if (fail) error("Disk unavailable")
            values = drafts.toMap()
        }
    }

    private class Transport : RemoteTransport {
        override lateinit var listener: RemoteTransport.Listener
        val sent = mutableListOf<JsonObject>()
        var capabilities: Set<String> = emptySet()
        var connects = 0
        var route: String? = null
        var closes = 0
        var readyOnConnect = true
        var failureOnConnect: Int? = null
        var response: (JsonObject) -> JsonObject? = { null }
        var failure: (JsonObject) -> String? = { null }

        override fun connect(host: PairedHost) {
            connects++
            route = host.routeId
            val failure = failureOnConnect
            if (failure != null) listener.failed(true, failure)
            else if (readyOnConnect) listener.ready(capabilities)
        }

        override fun pair(value: JsonObject) {}

        override fun close() {
            closes++
        }

        override fun send(payload: JsonObject) {
            sent += payload
            val code = failure(payload)
            if (code != null) {
                listener.message(
                    Wire.objectOf(
                        "type" to "result",
                        "requestId" to payload.text("requestId"),
                        "ok" to false,
                        "error" to Wire.objectOf("code" to code, "message" to code),
                    )
                )
                return
            }
            response(payload)?.let { result(payload, it) }
        }

        fun result(request: JsonObject, data: JsonObject) {
            listener.message(
                Wire.objectOf(
                    "type" to "result",
                    "requestId" to request.text("requestId"),
                    "ok" to true,
                    "data" to data,
                )
            )
        }
    }

    private fun data(kind: String, items: List<JsonObject>) =
        Wire.objectOf("kind" to kind, "items" to JsonArray(items))

    private fun configure(
        transport: Transport,
        sessions: List<JsonObject> = listOf(session()),
        opened: JsonObject = session(),
    ) {
        transport.response = { request ->
            when (request.text("type")) {
                "projects.list" -> data("projects", listOf(project))
                "sessions.list" -> data("sessions", sessions)
                "sessions.open",
                "sessions.create" -> Wire.objectOf("kind" to "session", "session" to opened)
                "session.snapshot" ->
                    Wire.objectOf(
                        "kind" to "snapshot",
                        "sessionId" to request.text("sessionId"),
                        "revision" to 0,
                        "status" to "idle",
                        "messages" to JsonArray(emptyList()),
                        "pendingQuestions" to JsonArray(emptyList()),
                    )
                else -> Wire.objectOf("kind" to "accepted")
            }
        }
    }

    private fun TestScope.repository(
        transport: Transport,
        drafts: Drafts = Drafts(),
        attachmentStorage: AttachmentStorage? = null,
        attachmentImporter: (suspend (String, Boolean) -> LocalAttachment)? = null,
    ) =
        DefaultRemoteRepository(
            Pairings(listOf(host)),
            drafts,
            transport,
            backgroundScope,
            StandardTestDispatcher(testScheduler),
            attachmentStorage,
            attachmentImporter,
            now = { testScheduler.currentTime },
        )

    private fun modelData(id: String = "model") =
        Wire.objectOf(
            "provider" to "provider",
            "id" to id,
            "name" to id,
            "input" to JsonArray(listOf(JsonPrimitive("text"), JsonPrimitive("image"))),
        )

    private fun settingsData(auto: Boolean = true, steering: String = "one-at-a-time", followUp: String = "one-at-a-time") =
        Wire.objectOf("autoCompaction" to auto, "steeringMode" to steering, "followUpMode" to followUp)

    private fun configurationData(
        sessionId: String = "session",
        model: String = "model",
        settings: JsonObject? = null,
    ) =
        Wire.objectOf(
            "kind" to "configuration",
            "sessionId" to sessionId,
            "model" to modelData(model),
            "thinkingLevel" to "high",
            "thinkingLevels" to JsonArray(listOf(JsonPrimitive("high"), JsonPrimitive("max"))),
            "models" to JsonArray(listOf(modelData(), modelData("other"))),
            "modelsTruncated" to false,
            *(if (settings == null) emptyArray() else arrayOf("settings" to settings)),
        )

    private fun commandData(sessionId: String = "session") =
        Wire.objectOf(
            "kind" to "commands",
            "sessionId" to sessionId,
            "items" to JsonArray(listOf(Wire.objectOf("name" to "review", "source" to "skill"))),
            "truncated" to false,
        )

    private fun advisorData(enabled: Boolean = false) = Wire.objectOf(
        "kind" to "advisor", "sessionId" to "session", "enabled" to enabled,
        "model" to if (enabled) JsonPrimitive("provider/model") else JsonNull,
        "reasoning" to "high", "pending" to false, "contextShared" to enabled,
        "attempts" to 0, "maxAttempts" to 3, "remainingAttempts" to 3, "error" to JsonNull,
        "choices" to JsonArray(listOf(Wire.objectOf(
            "provider" to "provider", "id" to "model", "name" to "Advisor model",
            "levels" to JsonArray(listOf(JsonPrimitive("high"))),
        ))),
    )

    @Test
    fun advisorCanRecoverFromUnsupportedAndBeEnabledFromTheApp() = runTest {
        val transport = Transport().apply { capabilities = setOf(ADVISOR_CAPABILITY) }
        configure(transport)
        val original = transport.response
        var supported = false
        var enabled = false
        transport.failure = { request ->
            if (request.text("type") == "session.advisor.get" && !supported) "unsupported" else null
        }
        transport.response = { request ->
            when (request.text("type")) {
                "session.advisor.get" -> advisorData(enabled)
                "session.command" -> {
                    enabled = request.text("text") != "/advisor off"
                    Wire.objectOf("kind" to "accepted")
                }
                else -> original(request)
            }
        }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        assertTrue(ADVISOR_CAPABILITY in repository.state.value.unavailableCapabilities)
        repository.setAdvisor("provider", "model", "high")
        runCurrent()
        assertFalse(transport.sent.any { it.text("type") == "session.command" })

        supported = true
        repository.refreshAdvisor()
        runCurrent()
        assertTrue(advisorControlAvailable(repository.state.value))
        assertEquals(false, repository.state.value.advisor?.enabled)
        repository.setAdvisor("provider", "model", "high")
        runCurrent()
        assertEquals("/advisor provider/model --thinking high",
            transport.sent.last { it.text("type") == "session.command" }.text("text"))
        advanceTimeBy(1200)
        runCurrent()
        assertEquals(true, repository.state.value.advisor?.enabled)

        repository.setAdvisor(null, null, null)
        runCurrent()
        advanceTimeBy(1200)
        runCurrent()
        assertEquals(false, repository.state.value.advisor?.enabled)
        assertTrue(advisorControlAvailable(repository.state.value))
    }

    @Test
    fun advisorRefreshDoesNotSendToAnUnadvertisedHost() = runTest {
        val transport = Transport()
        configure(transport)
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        repository.refreshAdvisor()
        runCurrent()
        assertFalse(transport.sent.any { it.text("type") == "session.advisor.get" })
    }

    @Test
    fun projectsDiscoveryOptsInWithoutBreakingAnOlderHost() = runTest {
        for (supportsSteer in listOf(false, true)) {
            val transport = Transport()
            transport.capabilities = setOf(FOLLOW_UP_CAPABILITY)
            configure(transport)
            val legacyResponse = transport.response
            transport.response = { request ->
                val result = legacyResponse(request)
                if (supportsSteer && request.text("type") == "projects.list" && result != null)
                    JsonObject(result + ("capabilities" to JsonArray(listOf(JsonPrimitive(STEER_CAPABILITY)))))
                else result
            }
            val repository = repository(transport)
            repository.activate(RemoteSelection("host", "project", "session"))
            val discovery = transport.sent.first { it.text("type") == "projects.list" }
            assertTrue(discovery.text("requestId").startsWith("capabilities.v1:"))
            assertEquals(supportsSteer, canSteer(repository.state.value))
            assertTrue(canFollowUp(repository.state.value))
            assertTrue(repository.state.value.connected)
        }
    }

    @Test
    fun contextUsageRequiresCapabilityAndClearsOnSessionSwitch() = runTest {
        val transport = Transport()
        configure(transport, sessions = listOf(session(), session("other")))
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        repository.refreshContextUsage()
        runCurrent()
        assertFalse(transport.sent.any { it.text("type") == "session.context.get" })

        transport.capabilities = setOf(CONTEXT_CAPABILITY)
        val original = transport.response
        transport.response = { request ->
            if (request.text("type") == "session.context.get")
                Wire.objectOf(
                    "kind" to "context",
                    "sessionId" to request.text("sessionId"),
                    "usedTokens" to 50000,
                    "contextWindow" to 200000,
                    "percent" to 25.0,
                )
            else original(request)
        }
        repository.disconnect()
        repository.activate(RemoteSelection("host", "project", "session"))
        repository.refreshContextUsage()
        runCurrent()
        assertEquals(50000L, repository.state.value.contextUsage?.usedTokens)
        repository.activate(RemoteSelection("host", "project", "other"))
        runCurrent()
        assertNull(repository.state.value.contextUsage)
    }

    private fun capable(transport: Transport) {
        configure(transport)
        transport.capabilities = setOf(CONFIGURATION_CAPABILITY, COMMANDS_CAPABILITY)
        val original = transport.response
        transport.response = {
            when (it.text("type")) {
                "session.configuration.get" -> configurationData(it.text("sessionId"))
                "session.commands.get" -> commandData(it.text("sessionId"))
                else -> original(it)
            }
        }
    }

    @Test
    fun closeDispatchesOnlyTheConfirmedRpcSession() = runTest {
        val transport = Transport().also(::configure)
        transport.capabilities = setOf("session.close.v1")
        val original = transport.response
        transport.response = { request ->
            if (request.text("type") == "session.close")
                Wire.objectOf("kind" to "accepted", "sessionId" to request.text("sessionId"))
            else original(request)
        }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project"))
        runCurrent()

        repository.closeSession("session")
        repository.closeSession("session")
        runCurrent()

        val close = transport.sent.single { it.text("type") == "session.close" }
        assertEquals("project", close.text("projectId"))
        assertEquals("session", close.text("sessionId"))
        assertTrue(close.text("requestId").isNotBlank())
    }

    private fun TestScope.jobsRepository(transport: Transport, lease: Boolean = false): DefaultRemoteRepository {
        val tui = session(origin = "tui")
        configure(transport, sessions = listOf(tui), opened = tui)
        transport.capabilities =
            setOf(BACKGROUND_JOBS_CAPABILITY) + if (lease) setOf(BACKGROUND_JOBS_LIST_LEASE_CAPABILITY) else emptySet()
        return repository(transport)
    }

    private fun Transport.jobsListRequests() = sent.filter { it.text("type") == "session.jobs.list" }

    private fun JsonObject.hasLease() = "lease" in this && flag("lease")

    @Test
    fun aSessionStatusChangeRefreshesTheJobList() = runTest {
        val transport = Transport()
        val repository = jobsRepository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        fun lists() = transport.sent.count { it.text("type") == "session.jobs.list" }
        val before = lists()
        transport.listener.message(Wire.objectOf("type" to "event", "sessionId" to "session",
            "revision" to 1, "kind" to "session.status", "status" to "running"))
        runCurrent()
        assertEquals(before + 1, lists())
    }

    @Test
    fun aReconnectToTheSameSessionKeepsTheJobsView() = runTest {
        val transport = Transport()
        val repository = jobsRepository(transport)
        val selection = RemoteSelection("host", "project", "session")
        repository.activate(selection)
        runCurrent()
        repository.openJobs()
        runCurrent()
        assertTrue(repository.state.value.jobs!!.listOpen)
        val connects = transport.connects
        transport.listener.failed(false, R.string.remote_connection_error)
        repository.activate(selection)
        runCurrent()
        assertTrue(transport.connects > connects)
        assertTrue(repository.state.value.connected)
        assertEquals("session", repository.state.value.jobs?.sessionId)
        assertTrue(repository.state.value.jobs!!.listOpen)
    }

    @Test
    fun listsWithALeaseOnlyWhenTheHostAdvertisesTheCapability() = runTest {
        val transport = Transport()
        val repository = jobsRepository(transport, lease = false)
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        repository.refreshJobs()
        runCurrent()
        val unleased = transport.jobsListRequests()
        assertTrue(unleased.isNotEmpty())
        assertTrue(unleased.none { "lease" in it })

        val leasedTransport = Transport()
        val leasedRepository = jobsRepository(leasedTransport, lease = true)
        leasedRepository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        leasedRepository.refreshJobs()
        runCurrent()
        val leased = leasedTransport.jobsListRequests()
        assertTrue(leased.isNotEmpty())
        assertTrue(leased.all { it.hasLease() })
    }

    @Test
    fun renewsTheListLeaseEveryThirtySecondsWhileTheChatStaysOpenAndConnected() = runTest {
        val transport = Transport()
        val repository = jobsRepository(transport, lease = true)
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        repository.refreshJobs()
        runCurrent()
        fun leaseLists() = transport.jobsListRequests().count { it.hasLease() }
        val before = leaseLists()

        advanceTimeBy(JOBS_LIST_LEASE_RENEW_MILLIS - 1)
        runCurrent()
        assertEquals(before, leaseLists())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(before + 1, leaseLists())
        advanceTimeBy(JOBS_LIST_LEASE_RENEW_MILLIS)
        runCurrent()
        assertEquals(before + 2, leaseLists())
    }

    @Test
    fun leaseRenewalStopsWhenTheChatClosesOrTheSessionChanges() = runTest {
        val transport = Transport()
        val repository = jobsRepository(transport, lease = true)
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        repository.refreshJobs()
        runCurrent()
        fun leaseLists() = transport.jobsListRequests().count { it.hasLease() }
        val before = leaseLists()

        // Closing the chat (no session selected) stops the renewal loop.
        repository.activate(RemoteSelection("host", "project"))
        runCurrent()
        advanceTimeBy(3 * JOBS_LIST_LEASE_RENEW_MILLIS)
        runCurrent()
        assertEquals(before, leaseLists())
    }

    @Test
    fun leaseRenewalStopsInTheBackgroundAndRelistsOnReturningToTheForeground() = runTest {
        val transport = Transport()
        val repository = jobsRepository(transport, lease = true)
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        repository.refreshJobs()
        runCurrent()
        fun leaseLists() = transport.jobsListRequests().count { it.hasLease() }
        val before = leaseLists()

        repository.setForeground(false)
        advanceTimeBy(3 * JOBS_LIST_LEASE_RENEW_MILLIS)
        runCurrent()
        assertEquals(before, leaseLists())

        // Returning to the foreground lists again right away, without waiting out the interval.
        repository.setForeground(true)
        runCurrent()
        assertEquals(before + 1, leaseLists())
    }

    @Test
    fun aReconnectRelistsWithTheLeaseRightAway() = runTest {
        val transport = Transport()
        val repository = jobsRepository(transport, lease = true)
        val selection = RemoteSelection("host", "project", "session")
        repository.activate(selection)
        runCurrent()
        repository.refreshJobs()
        runCurrent()
        fun leaseLists() = transport.jobsListRequests().count { it.hasLease() }
        val before = leaseLists()

        transport.listener.failed(false, R.string.remote_connection_error)
        repository.activate(selection)
        runCurrent()
        // The chat is still open after the reconnect; the app relists to renew the lease.
        repository.refreshJobs()
        runCurrent()
        assertEquals(before + 1, leaseLists())
    }

    @Test
    fun switchingSessionsScopesLeaseRenewalToTheNewSessionOnly() = runTest {
        val transport = Transport()
        val first = session(id = "session", origin = "tui")
        val second = session(id = "other", origin = "tui")
        val sessions = listOf(first, second)
        transport.response = { request ->
            when (request.text("type")) {
                "projects.list" -> data("projects", listOf(project))
                "sessions.list" -> data("sessions", sessions)
                "sessions.open" ->
                    Wire.objectOf(
                        "kind" to "session",
                        "session" to sessions.first { it.text("id") == request.text("sessionId") },
                    )
                "session.snapshot" ->
                    Wire.objectOf(
                        "kind" to "snapshot",
                        "sessionId" to request.text("sessionId"),
                        "revision" to 0,
                        "status" to "idle",
                        "messages" to JsonArray(emptyList()),
                        "pendingQuestions" to JsonArray(emptyList()),
                    )
                else -> Wire.objectOf("kind" to "accepted")
            }
        }
        transport.capabilities = setOf(BACKGROUND_JOBS_CAPABILITY, BACKGROUND_JOBS_LIST_LEASE_CAPABILITY)
        val repository = repository(transport)

        fun leaseListsFor(id: String) =
            transport.jobsListRequests().count { it.hasLease() && it.text("sessionId") == id }

        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        repository.refreshJobs()
        runCurrent()
        assertTrue(leaseListsFor("session") > 0)

        repository.activate(RemoteSelection("host", "project", "other"))
        runCurrent()
        repository.refreshJobs()
        runCurrent()
        val firstAfterSwitch = leaseListsFor("session")
        assertTrue(leaseListsFor("other") > 0)

        advanceTimeBy(3 * JOBS_LIST_LEASE_RENEW_MILLIS)
        runCurrent()
        // The session left behind gets no more renewals; the newly selected one keeps renewing.
        assertEquals(firstAfterSwitch, leaseListsFor("session"))
        assertTrue(leaseListsFor("other") > 1)
    }

    @Test
    fun aReconnectToAHostWithoutTheCapabilityStopsLeasingAndRestoresTheHeuristics() = runTest {
        val transport = Transport()
        val repository = jobsRepository(transport, lease = true)
        val selection = RemoteSelection("host", "project", "session")
        repository.activate(selection)
        runCurrent()
        repository.refreshJobs()
        runCurrent()
        fun leaseLists() = transport.jobsListRequests().count { it.hasLease() }
        fun allLists() = transport.jobsListRequests().size
        assertTrue(leaseLists() > 0)

        // The host reconnected to no longer advertises the list-lease capability (an older host).
        transport.capabilities = setOf(BACKGROUND_JOBS_CAPABILITY)
        transport.listener.failed(false, R.string.remote_connection_error)
        repository.activate(selection)
        runCurrent()
        repository.refreshJobs()
        runCurrent()
        assertFalse(canLeaseJobsList(repository.state.value))
        assertFalse(transport.jobsListRequests().last().hasLease())
        val leaseListsAfterReconnect = leaseLists()

        advanceTimeBy(3 * JOBS_LIST_LEASE_RENEW_MILLIS)
        runCurrent()
        assertEquals(leaseListsAfterReconnect, leaseLists())

        // The re-list heuristic is active again for this older host.
        val before = allLists()
        transport.listener.message(
            Wire.objectOf(
                "type" to "event", "sessionId" to "session",
                "revision" to 1, "kind" to "session.status", "status" to "running",
            )
        )
        runCurrent()
        assertEquals(before + 1, allLists())
    }

    @Test
    fun aCapabilityThatArrivesMidConnectionStartsLeasingAtOnce() = runTest {
        val transport = Transport()
        val tui = session(origin = "tui")
        configure(transport, sessions = listOf(tui), opened = tui)
        transport.capabilities = setOf(BACKGROUND_JOBS_CAPABILITY)
        var leaseAdvertised = false
        val original = transport.response
        transport.response = { request ->
            val result = original(request)
            if (request.text("type") == "projects.list" && leaseAdvertised && result != null)
                JsonObject(
                    result + ("capabilities" to JsonArray(listOf(JsonPrimitive(BACKGROUND_JOBS_LIST_LEASE_CAPABILITY))))
                )
            else result
        }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        repository.refreshJobs()
        runCurrent()
        fun leaseLists() = transport.jobsListRequests().count { it.hasLease() }
        assertFalse(canLeaseJobsList(repository.state.value))
        assertEquals(0, leaseLists())

        // The host now advertises the lease capability; a later projects.list (here, resolving a
        // notification while the chat stays connected) merges it in without a reconnect.
        leaseAdvertised = true
        repository.openNotification("host", "session")
        runCurrent()

        assertTrue(canLeaseJobsList(repository.state.value))
        assertEquals(1, leaseLists())
    }

    @Test
    fun theStatusChangeHeuristicIsSkippedOnlyWhenTheListLeaseCapabilityIsPresent() = runTest {
        for (lease in listOf(false, true)) {
            val transport = Transport()
            val repository = jobsRepository(transport, lease = lease)
            repository.activate(RemoteSelection("host", "project", "session"))
            runCurrent()
            val before = transport.jobsListRequests().size
            transport.listener.message(
                Wire.objectOf(
                    "type" to "event", "sessionId" to "session",
                    "revision" to 1, "kind" to "session.status", "status" to "running",
                )
            )
            runCurrent()
            assertEquals("lease=$lease", if (lease) before else before + 1, transport.jobsListRequests().size)
        }
    }

    @Test
    fun theBashBgHeuristicIsSkippedOnlyWhenTheListLeaseCapabilityIsPresent() = runTest {
        for (lease in listOf(false, true)) {
            val transport = Transport()
            val repository = jobsRepository(transport, lease = lease)
            repository.activate(RemoteSelection("host", "project", "session"))
            runCurrent()
            val before = transport.jobsListRequests().size
            transport.listener.message(
                Wire.objectOf(
                    "type" to "event",
                    "sessionId" to "session",
                    "revision" to 1,
                    "kind" to "message.upsert",
                    "message" to
                        Wire.objectOf("id" to "tool-message", "role" to "tool", "toolName" to "bash_bg"),
                )
            )
            runCurrent()
            assertEquals("lease=$lease", if (lease) before else before + 1, transport.jobsListRequests().size)
        }
    }

    @Test
    fun closeDoesNotDispatchForTuiOrHistorySessions() = runTest {
        for (origin in listOf("tui", "history")) {
            val transport = Transport()
            configure(transport, sessions = listOf(session(origin = origin)))
            transport.capabilities = setOf("session.close.v1")
            val repository = repository(transport)
            repository.activate(RemoteSelection("host", "project"))
            runCurrent()

            repository.closeSession("session")
            runCurrent()

            assertTrue(transport.sent.none { it.text("type") == "session.close" })
        }
    }

    @Test
    fun closeDoesNotDispatchWithoutSupportOrForOfflineRpcSessions() = runTest {
        val cases =
            listOf(
                "unsupported" to Pair("idle", emptySet()),
                "offline" to Pair("offline", setOf(SESSION_CLOSE_CAPABILITY)),
            )
        for ((_, condition) in cases) {
            val (status, capabilities) = condition
            val transport = Transport()
            configure(transport, sessions = listOf(session(status = status)))
            transport.capabilities = capabilities
            val repository = repository(transport)
            repository.activate(RemoteSelection("host", "project"))
            runCurrent()

            repository.closeSession("session")
            runCurrent()

            assertTrue(transport.sent.none { it.text("type") == "session.close" })
        }
    }

    @Test
    fun unshareDispatchesOnlyTheSelectedProjectAndKeepsTheOtherListed() = runTest {
        val transport = Transport()
        transport.capabilities = setOf(PROJECT_UNSHARE_CAPABILITY)
        val projects = mutableListOf(project, Wire.objectOf("id" to "second"))
        transport.response = { request ->
            when (request.text("type")) {
                "projects.list" -> data("projects", projects.toList())
                "project.unshare" -> {
                    projects.removeAll { it.text("id") == request.text("projectId") }
                    Wire.objectOf("kind" to "accepted")
                }
                else -> Wire.objectOf("kind" to "accepted")
            }
        }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host"))
        runCurrent()

        repository.unshareProject("second")
        repository.unshareProject("second")
        runCurrent()

        val unshare = transport.sent.single { it.text("type") == "project.unshare" }
        assertEquals("second", unshare.text("projectId"))
        assertEquals(listOf("project"), repository.state.value.projects.map { it.text("id") })
    }

    @Test
    fun unshareRequiresCurrentProjectAndHostCapability() = runTest {
        val transport = Transport()
        configure(transport)
        val repository = repository(transport)
        repository.activate(RemoteSelection("host"))
        runCurrent()

        repository.unshareProject("project")
        repository.unshareProject("unknown")
        runCurrent()

        assertTrue(transport.sent.none { it.text("type") == "project.unshare" })
    }

    @Test
    fun failedUnshareKeepsTheProjectListed() = runTest {
        val transport = Transport()
        transport.capabilities = setOf(PROJECT_UNSHARE_CAPABILITY)
        configure(transport)
        val original = transport.response
        transport.response = { request ->
            if (request.text("type") == "project.unshare") null else original(request)
        }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host"))
        runCurrent()

        repository.unshareProject("project")
        runCurrent()
        val request = transport.sent.single { it.text("type") == "project.unshare" }
        transport.listener.message(
            Wire.objectOf(
                "type" to "result",
                "requestId" to request.text("requestId"),
                "ok" to false,
                "error" to Wire.objectOf("code" to "internal", "message" to "failed"),
            )
        )
        runCurrent()

        assertEquals(listOf("project"), repository.state.value.projects.map { it.text("id") })
        assertNotNull(repository.state.value.error)
    }

    private class Attachments : AttachmentStorage {
        val bytes = mutableMapOf<String, ByteArray>()
        val removed = mutableListOf<String>()

        override fun write(attachment: LocalAttachment, bytes: ByteArray) {
            this.bytes[attachment.id] = bytes.copyOf()
        }

        override fun read(attachment: LocalAttachment): ByteArray =
            checkNotNull(bytes[attachment.id])

        override fun remove(id: String) {
            bytes.remove(id)
            removed += id
        }

        override fun cleanup(keepIds: Set<String>) {
            bytes.keys.toList().filterNot { it in keepIds }.forEach(::remove)
        }
    }

    private fun localFile(
        bytes: ByteArray = ByteArray(130 * 1024) { (it % 251).toByte() },
        id: String = "AAAAAAAAAAAAAAAAAAAAAA",
    ) =
        LocalAttachment(
            id,
            "file.bin",
            "file",
            "application/octet-stream",
            bytes.size.toLong(),
            AttachmentImportRules.sha256(bytes),
        )

    private fun attachmentResponses(
        transport: Transport,
        local: LocalAttachment,
        prompt: Boolean = true,
    ) {
        capable(transport)
        transport.capabilities += ATTACHMENTS_CAPABILITY
        val original = transport.response
        val remoteId = "BBBBBBBBBBBBBBBBBBBBBB"
        transport.response = { request ->
            when (request.text("type")) {
                "session.attachments.begin" ->
                    Wire.objectOf(
                        "kind" to "attachment.upload",
                        "sessionId" to "session",
                        "attachmentId" to remoteId,
                        "offset" to 0,
                    )
                "session.attachments.chunk" ->
                    Wire.objectOf(
                        "kind" to "attachment.upload",
                        "sessionId" to "session",
                        "attachmentId" to remoteId,
                        "offset" to
                            request.long("offset") +
                                java.util.Base64.getUrlDecoder().decode(request.text("data")).size,
                    )
                "session.attachments.commit" ->
                    Wire.objectOf(
                        "kind" to "attachment",
                        "sessionId" to "session",
                        "attachment" to
                            RemoteAttachment(
                                    remoteId,
                                    local.name,
                                    local.kind,
                                    local.mimeType,
                                    local.size,
                                    local.sha256,
                                    3_600_000,
                                )
                                .json(),
                    )
                "session.attachments.cancel" -> Wire.objectOf("kind" to "accepted")
                "session.prompt" -> if (prompt) Wire.objectOf("kind" to "accepted") else null
                else -> original(request)
            }
        }
    }

    @Test
    fun renameUpdatesTheActiveSessionAndAcceptsLaterMacTitleEvents() = runTest {
        val originalSession = JsonObject(session() + ("title" to JsonPrimitive("Old name")))
        val transport = Transport()
        configure(transport, listOf(originalSession), originalSession)
        transport.capabilities = setOf(RENAME_CAPABILITY)
        val originalResponse = transport.response
        transport.response = { request ->
            if (request.text("type") == "session.rename")
                Wire.objectOf(
                    "kind" to "session",
                    "session" to JsonObject(originalSession + ("title" to JsonPrimitive("New name"))),
                )
            else originalResponse(request)
        }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()

        repository.renameSession("session", "  New name  ")
        runCurrent()

        assertEquals("New name", repository.state.value.session?.text("title"))
        assertEquals("New name", repository.state.value.sessions.single().text("title"))
        assertEquals(
            "New name",
            transport.sent.last { it.text("type") == "session.rename" }.text("title"),
        )

        transport.listener.message(
            Wire.objectOf(
                "type" to "event", "kind" to "session.title", "sessionId" to "session",
                "revision" to 1, "title" to "Changed on Mac",
            )
        )
        assertEquals("Changed on Mac", repository.state.value.session?.text("title"))
        assertEquals("Changed on Mac", repository.state.value.sessions.single().text("title"))
        transport.listener.message(
            Wire.objectOf(
                "type" to "event", "kind" to "session.title", "sessionId" to "session",
                "revision" to 0, "title" to "Stale title",
            )
        )
        assertEquals("Changed on Mac", repository.state.value.session?.text("title"))
        transport.listener.message(
            Wire.objectOf(
                "type" to "event", "kind" to "session.title", "sessionId" to "session",
                "revision" to 2, "title" to "",
            )
        )
        assertEquals("", repository.state.value.session?.text("title"))
    }

    @Test
    fun renameDoesNotSendForOfflineHistorySessions() = runTest {
        val history = JsonObject(session(origin = "history", status = "offline") +
            ("title" to JsonPrimitive("Original")))
        val transport = Transport()
        configure(transport, listOf(history), history)
        transport.capabilities = setOf(RENAME_CAPABILITY)
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project"))
        runCurrent()

        repository.renameSession("session", "Changed")
        runCurrent()

        assertFalse(transport.sent.any { it.text("type") == "session.rename" })
        assertEquals("Original", repository.state.value.sessions.single().text("title"))
    }

    @Test
    fun titleEventWinsOverOlderSessionListResponse() = runTest {
        val oldSession = JsonObject(session() + ("title" to JsonPrimitive("Old")))
        val transport = Transport()
        configure(transport, listOf(oldSession))
        val originalResponse = transport.response
        transport.response = { request ->
            if (request.text("type") == "sessions.list") null else originalResponse(request)
        }
        val repository = repository(transport)
        val activation = backgroundScope.async {
            repository.activate(RemoteSelection("host", "project"))
        }
        runCurrent()
        val listRequest = transport.sent.single { it.text("type") == "sessions.list" }

        transport.listener.message(
            Wire.objectOf(
                "type" to "event", "kind" to "session.title", "sessionId" to "session",
                "revision" to 7, "title" to "New",
            )
        )
        transport.result(listRequest, data("sessions", listOf(oldSession)))
        runCurrent()

        assertTrue(activation.isCompleted)
        assertEquals("New", repository.state.value.sessions.single().text("title"))
    }

    @Test
    fun aFreshSessionListReplacesAnEarlierTitleEvent() = runTest {
        val oldSession = JsonObject(session() + ("title" to JsonPrimitive("Old")))
        var listed = oldSession
        val transport = Transport()
        configure(transport, listOf(oldSession))
        val originalResponse = transport.response
        transport.response = { request ->
            if (request.text("type") == "sessions.list") data("sessions", listOf(listed))
            else originalResponse(request)
        }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project"))
        runCurrent()
        transport.listener.message(
            Wire.objectOf(
                "type" to "event", "kind" to "session.title", "sessionId" to "session",
                "revision" to 7, "title" to "A",
            )
        )
        assertEquals("A", repository.state.value.sessions.single().text("title"))

        listed = JsonObject(oldSession + ("title" to JsonPrimitive("B")))
        repository.refresh()
        runCurrent()

        assertEquals("B", repository.state.value.sessions.single().text("title"))
    }

    @Test
    fun failedRemovalAndAckWritesKeepBlobsReferencedByDurableJournal() = runTest {
        for (ack in listOf(false, true)) {
            val bytes = byteArrayOf(1)
            val local = localFile(bytes)
            val storage = Attachments()
            val drafts = Drafts()
            val transport = Transport().also { attachmentResponses(it, local, prompt = false) }
            val repository =
                repository(transport, drafts, storage) { _, _ ->
                    storage.write(local, bytes)
                    local
                }
            repository.activate(RemoteSelection("host", "project", "session"))
            runCurrent()
            repository.importAttachments(
                repository.state.value.selection,
                listOf("content:file"),
                false,
            )
            runCurrent()
            if (ack) {
                repository.prompt()
                runCurrent()
                advanceTimeBy(201)
                runCurrent()
                val request = transport.sent.last { it.text("type") == "session.prompt" }
                drafts.fail = true
                transport.result(request, Wire.objectOf("kind" to "accepted"))
                runCurrent()
            } else {
                drafts.fail = true
                repository.removeAttachment(local.id)
                runCurrent()
            }
            assertArrayEquals(bytes, storage.read(local))
            drafts.fail = false
            val fresh =
                repository(Transport().also { attachmentResponses(it, local) }, drafts, storage)
            fresh.activate(RemoteSelection("host", "project", "session"))
            runCurrent()
            assertEquals(listOf(local), fresh.state.value.attachments)
            assertArrayEquals(bytes, storage.read(fresh.state.value.attachments.single()))
        }
    }

    @Test
    fun unsupportedTuiControlsDoNotHideCapabilitiesForLaterRpcSession() = runTest {
        val transport = Transport().also(::capable)
        val original = transport.response
        transport.response = { request ->
            if (
                request.text("type") in
                    setOf("session.configuration.get", "session.commands.get") &&
                    request.text("sessionId") == "tui"
            ) {
                transport.listener.message(
                    Wire.objectOf(
                        "type" to "result",
                        "requestId" to request.text("requestId"),
                        "ok" to false,
                        "error" to Wire.objectOf("code" to "unsupported"),
                    )
                )
                null
            } else if (request.text("type") == "sessions.list")
                data("sessions", listOf(session("tui", "tui"), session("rpc")))
            else original(request)
        }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "tui"))
        runCurrent()
        assertEquals(
            setOf(CONFIGURATION_CAPABILITY, COMMANDS_CAPABILITY),
            repository.state.value.capabilities,
        )
        assertEquals(
            setOf(CONFIGURATION_CAPABILITY, COMMANDS_CAPABILITY),
            repository.state.value.unavailableCapabilities,
        )
        repository.activate(RemoteSelection("host", "project"))
        repository.activate(RemoteSelection("host", "project", "rpc"))
        runCurrent()
        assertEquals(1, transport.connects)
        assertNotNull(repository.state.value.configuration)
        assertTrue(repository.state.value.commands.isNotEmpty())
        assertTrue(repository.state.value.unavailableCapabilities.isEmpty())
    }

    @Test
    fun attachmentOnlyPromptWaitsForPacedVerifiedUploadAndDurableMarker() = runTest {
        val bytes = ByteArray(130 * 1024) { (it % 251).toByte() }
        val local = localFile(bytes)
        val storage = Attachments()
        val drafts = Drafts()
        val transport = Transport().also { attachmentResponses(it, local) }
        val timestamps = mutableListOf<Long>()
        val original = transport.response
        transport.response = { request ->
            if (request.text("type") == "session.attachments.chunk")
                timestamps += testScheduler.currentTime
            if (request.text("type") == "session.prompt")
                assertEquals(listOf(local), drafts.values.values.single().submittedAttachments)
            original(request)
        }
        val repository =
            repository(transport, drafts, storage) { _, _ ->
                storage.write(local, bytes)
                local
            }
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        repository.importAttachments(
            repository.state.value.selection,
            listOf("content:file"),
            false,
        )
        runCurrent()
        repository.prompt()
        runCurrent()
        assertFalse(transport.sent.any { it.text("type") == "session.prompt" })
        advanceTimeBy(601)
        runCurrent()
        val chunks = transport.sent.filter { it.text("type") == "session.attachments.chunk" }
        assertEquals(3, chunks.size)
        assertTrue(timestamps.zipWithNext().all { (a, b) -> b - a >= 200 })
        assertArrayEquals(
            bytes,
            chunks
                .flatMap { java.util.Base64.getUrlDecoder().decode(it.text("data")).toList() }
                .toByteArray(),
        )
        val prompt = transport.sent.single { it.text("type") == "session.prompt" }
        assertEquals("", prompt.text("text"))
        assertEquals(
            "BBBBBBBBBBBBBBBBBBBBBB",
            prompt.getValue("attachmentIds").jsonArray.single().jsonPrimitive.content,
        )
        assertTrue(repository.state.value.attachments.isEmpty())
        assertFalse(repository.state.value.uncertain)
        assertFalse(storage.bytes.containsKey(local.id))
    }

    @Test
    fun changedAttachmentDraftSurvivesAckAndUncertainUploadsAreReusedWithoutReplay() = runTest {
        val bytes = byteArrayOf(1, 2, 3)
        val local = localFile(bytes)
        val storage = Attachments()
        val drafts = Drafts()
        val transport = Transport().also { attachmentResponses(it, local, prompt = false) }
        val repository =
            repository(transport, drafts, storage) { _, _ ->
                storage.write(local, bytes)
                local
            }
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        repository.importAttachments(
            repository.state.value.selection,
            listOf("content:file"),
            false,
        )
        runCurrent()
        repository.draft("Original")
        repository.prompt()
        runCurrent()
        advanceTimeBy(201)
        runCurrent()
        repository.draft("Changed while sending")
        advanceTimeBy(30_001)
        runCurrent()
        assertTrue(repository.state.value.uncertain)
        assertTrue(storage.bytes.containsKey(local.id))
        val freshTransport = Transport().also { attachmentResponses(it, local) }
        val fresh = repository(freshTransport, drafts, storage)
        fresh.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        assertEquals("Changed while sending", fresh.state.value.draft)
        assertFalse(freshTransport.sent.any { it.text("type") == "session.prompt" })
        fresh.prompt()
        runCurrent()
        assertTrue(freshTransport.sent.any { it.text("type") == "session.prompt" })
        assertFalse(freshTransport.sent.any { it.text("type") == "session.attachments.begin" })
    }

    @Test
    fun lateImportedFileIsDeletedAfterSessionChanges() = runTest {
        val local = localFile(byteArrayOf(1))
        val storage = Attachments()
        val gate = CompletableDeferred<Unit>()
        val transport = Transport().also { attachmentResponses(it, local) }
        val repository =
            repository(transport, attachmentStorage = storage) { _, _ ->
                withContext(NonCancellable) {
                    gate.await()
                    storage.write(local, byteArrayOf(1))
                    local
                }
            }
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        repository.importAttachments(
            repository.state.value.selection,
            listOf("content:file"),
            false,
        )
        runCurrent()
        repository.activate(RemoteSelection())
        gate.complete(Unit)
        runCurrent()
        assertTrue(repository.state.value.attachments.isEmpty())
        assertFalse(storage.bytes.containsKey(local.id))
        assertTrue(local.id in storage.removed)
    }

    @Test
    fun removingUnsubmittedAttachmentCancelsRemoteUploadAndReleasesBlob() = runTest {
        val bytes = byteArrayOf(1)
        val local = localFile(bytes)
        val storage = Attachments().also { it.write(local, bytes) }
        val remote =
            RemoteAttachment(
                "BBBBBBBBBBBBBBBBBBBBBB",
                local.name,
                local.kind,
                local.mimeType,
                local.size,
                local.sha256,
                3_600_000,
            )
        val drafts =
            Drafts().also {
                it.values =
                    mapOf(
                        DraftKey("host", "session") to
                            StoredDraft(
                                attachments = listOf(local),
                                uploads =
                                    listOf(
                                        AttachmentUpload(local.id, remote.id, "project", remote)
                                    ),
                            )
                    )
            }
        val transport = Transport().also { attachmentResponses(it, local) }
        val repository = repository(transport, drafts, storage)
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        repository.removeAttachment(local.id)
        runCurrent()
        assertTrue(
            transport.sent.any {
                it.text("type") == "session.attachments.cancel" &&
                    it.text("attachmentId") == remote.id
            }
        )
        assertTrue(drafts.values.values.single().uploads.isEmpty())
        assertFalse(storage.bytes.containsKey(local.id))
    }

    @Test
    fun missingPendingAndExpiredFinalizedUploadsRecoverOnExplicitSend() = runTest {
        for (expired in listOf(false, true)) {
            val bytes = byteArrayOf(1)
            val local = localFile(bytes)
            val storage = Attachments().also { it.write(local, bytes) }
            val oldId = "CCCCCCCCCCCCCCCCCCCCCC"
            val remote =
                if (expired)
                    RemoteAttachment(
                        oldId,
                        local.name,
                        local.kind,
                        local.mimeType,
                        local.size,
                        local.sha256,
                        0,
                    )
                else null
            val drafts =
                Drafts().also {
                    it.values =
                        mapOf(
                            DraftKey("host", "session") to
                                StoredDraft(
                                    attachments = listOf(local),
                                    uploads =
                                        listOf(
                                            AttachmentUpload(local.id, oldId, "project", remote)
                                        ),
                                )
                        )
                }
            val transport = Transport().also { attachmentResponses(it, local) }
            val original = transport.response
            transport.response = { request ->
                if (request.text("type") == "session.attachments.cancel") {
                    transport.listener.message(
                        Wire.objectOf(
                            "type" to "result",
                            "requestId" to request.text("requestId"),
                            "ok" to false,
                            "error" to
                                Wire.objectOf(
                                    "code" to if (expired) "invalid_request" else "forbidden"
                                ),
                        )
                    )
                    null
                } else original(request)
            }
            val repository = repository(transport, drafts, storage)
            repository.activate(RemoteSelection("host", "project", "session"))
            runCurrent()
            assertFalse(transport.sent.any { it.text("type") == "session.prompt" })
            repository.prompt()
            runCurrent()
            advanceTimeBy(201)
            runCurrent()
            assertEquals(1, transport.sent.count { it.text("type") == "session.attachments.begin" })
            assertEquals(1, transport.sent.count { it.text("type") == "session.prompt" })
            assertFalse(repository.state.value.uncertain)
        }
    }

    @Test
    fun imageModelAndSlashGuardsKeepAttachmentsWithoutSending() = runTest {
        val bytes = byteArrayOf(1)
        val local =
            localFile(bytes).copy(kind = "image", name = "photo.jpg", mimeType = "image/jpeg")
        val storage = Attachments()
        val transport = Transport().also { attachmentResponses(it, local) }
        val repository =
            repository(transport, attachmentStorage = storage) { _, _ ->
                storage.write(local, bytes)
                local
            }
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        repository.importAttachments(
            repository.state.value.selection,
            listOf("content:photo"),
            true,
        )
        runCurrent()
        repository.draft("/review")
        repository.prompt()
        runCurrent()
        assertEquals(R.string.remote_command_remove_context, repository.state.value.error)
        assertFalse(transport.sent.any { it.text("type") == "session.command" })
        val original = transport.response
        transport.response = { request ->
            if (request.text("type") == "session.configuration.get") {
                val config = configurationData()
                JsonObject(
                    config +
                        ("model" to
                            JsonObject(
                                config.obj("model") +
                                    ("input" to JsonArray(listOf(JsonPrimitive("text"))))
                            ))
                )
            } else original(request)
        }
        repository.refreshConfiguration()
        runCurrent()
        repository.draft("")
        repository.prompt()
        runCurrent()
        assertEquals(R.string.remote_attachment_model_error, repository.state.value.error)
        assertEquals(listOf(local), repository.state.value.attachments)
        assertFalse(
            transport.sent.any {
                it.text("type") == "session.prompt" ||
                    it.text("type") == "session.attachments.begin"
            }
        )
    }

    @Test
    fun corruptBlobAndFailedDraftWriteNeverSendPrompt() = runTest {
        val local = localFile(byteArrayOf(1))
        val storage = Attachments()
        val drafts = Drafts()
        val transport = Transport().also { attachmentResponses(it, local) }
        val repository =
            repository(transport, drafts, storage) { _, _ ->
                storage.write(local, byteArrayOf(1))
                local
            }
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        repository.importAttachments(
            repository.state.value.selection,
            listOf("content:file"),
            false,
        )
        runCurrent()
        storage.bytes[local.id] = byteArrayOf(2)
        repository.prompt()
        runCurrent()
        assertFalse(
            transport.sent.any {
                it.text("type") == "session.attachments.begin" ||
                    it.text("type") == "session.prompt"
            }
        )
        storage.bytes[local.id] = byteArrayOf(1)
        drafts.fail = true
        repository.prompt()
        runCurrent()
        advanceTimeBy(201)
        runCurrent()
        assertFalse(transport.sent.any { it.text("type") == "session.prompt" })
        assertEquals(listOf(local), repository.state.value.attachments)
    }

    @Test
    fun legacyHostIsNeverProbedAndSlashIsNeverPrompted() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        repository.refreshConfiguration()
        repository.refreshCommands()
        runCurrent()
        repository.draft("/unknown  args")
        repository.prompt()
        runCurrent()
        assertFalse(
            transport.sent.any {
                it.text("type").startsWith("session.configuration") ||
                    it.text("type").startsWith("session.commands") ||
                    it.text("type") == "session.prompt"
            }
        )
        assertEquals("/unknown  args", repository.state.value.draft)
        assertEquals(R.string.remote_command_unknown, repository.state.value.error)
    }

    @Test
    fun modelChangeWaitsForAckAndThinkingUsesConfirmedModel() = runTest {
        val transport = Transport().also(::capable)
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        val original = transport.response
        transport.response = {
            if (it.text("type") == "session.configuration.set") null else original(it)
        }
        repository.draft("keep me")
        repository.setModel("provider", "other")
        runCurrent()
        val change = transport.sent.last()
        assertEquals("model", repository.state.value.configuration?.model?.id)
        assertTrue(repository.state.value.configurationChanging)
        repository.setThinkingLevel("max")
        assertEquals(change, transport.sent.last())
        transport.result(change, configurationData(model = "other"))
        runCurrent()
        repository.setThinkingLevel("max")
        runCurrent()
        assertEquals("other", transport.sent.last().obj("change").obj("expectedModel").text("id"))
        assertEquals("keep me", repository.state.value.draft)
    }

    @Test
    fun lateConfigurationCannotChangeAnotherSessionAndBusyCannotSet() = runTest {
        val transport = Transport().also(::capable)
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        transport.listener.message(
            Wire.objectOf(
                "type" to "event",
                "sessionId" to "session",
                "revision" to 1,
                "kind" to "session.status",
                "status" to "running",
            )
        )
        val count = transport.sent.size
        repository.setModel("provider", "other")
        runCurrent()
        assertEquals(count, transport.sent.size)
        transport.response = { null }
        repository.refreshConfiguration()
        runCurrent()
        val late = transport.sent.last()
        repository.activate(RemoteSelection())
        transport.result(late, configurationData(model = "other"))
        runCurrent()
        assertNull(repository.state.value.configuration)
    }

    @Test
    fun commandKeepsArgumentsAndChangedDraftAndReceiptDoesNotResnapshot() = runTest {
        val transport = Transport().also(::capable)
        val drafts = Drafts()
        val repository = repository(transport, drafts)
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        val original = transport.response
        transport.response = { if (it.text("type") == "session.command") null else original(it) }
        repository.draft("/review  file.kt\n  detail")
        repository.prompt()
        runCurrent()
        val request = transport.sent.last()
        assertEquals("session.command", request.text("type"))
        assertEquals("/review  file.kt\n  detail", request.text("text"))
        assertNotNull(drafts.values.values.single().mutationId)
        repository.draft("new draft")
        val snapshots = transport.sent.count { it.text("type") == "session.snapshot" }
        transport.listener.message(
            Wire.objectOf(
                "type" to "event",
                "sessionId" to "session",
                "revision" to 1,
                "kind" to "command.status",
                "requestId" to request.text("requestId"),
                "status" to "accepted",
            )
        )
        transport.result(
            request,
            Wire.objectOf("kind" to "command", "sessionId" to "session", "status" to "dispatched"),
        )
        runCurrent()
        assertEquals("new draft", repository.state.value.draft)
        assertEquals(R.string.remote_command_accepted, repository.state.value.commandNotice)
        assertEquals(snapshots, transport.sent.count { it.text("type") == "session.snapshot" })
        repository.cancelSelection()
        runCurrent()
        val recreated = repository(Transport().also(::capable), drafts)
        recreated.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        assertEquals("new draft", recreated.state.value.draft)
    }

    @Test
    fun uncertainConfigurationIsReadBeforeAnotherSet() = runTest {
        val transport = Transport().also(::capable)
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        val original = transport.response
        transport.response = {
            if (it.text("type") == "session.configuration.set") null else original(it)
        }
        repository.setModel("provider", "other")
        runCurrent()
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals("model", repository.state.value.configuration?.model?.id)
        assertEquals(R.string.remote_configuration_error, repository.state.value.error)
        val before = transport.sent.count { it.text("type") == "session.configuration.set" }
        repository.setModel("provider", "other")
        runCurrent()
        assertEquals("session.configuration.get", transport.sent.last().text("type"))
        assertEquals(
            before,
            transport.sent.count { it.text("type") == "session.configuration.set" },
        )
    }

    @Test
    fun settingsChangeSendsOnlyTheGivenFieldsAndTakesTheRefreshedConfiguration() = runTest {
        val transport = Transport().also(::capable)
        transport.capabilities += SETTINGS_CAPABILITY
        val original = transport.response
        transport.response = {
            when (it.text("type")) {
                "session.configuration.get" -> configurationData(it.text("sessionId"), settings = settingsData())
                "session.configuration.set" ->
                    configurationData(it.text("sessionId"), settings = settingsData(auto = false, steering = "all"))
                else -> original(it)
            }
        }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        assertEquals(true, repository.state.value.configuration?.settings?.autoCompaction)
        repository.changeSettings()
        repository.changeSettings(steeringMode = "sometimes")
        runCurrent()
        assertEquals(0, transport.sent.count { it.text("type") == "session.configuration.set" })
        repository.changeSettings(autoCompaction = false, steeringMode = "all")
        runCurrent()
        val change = transport.sent.last { it.text("type") == "session.configuration.set" }.obj("change")
        assertEquals(setOf("kind", "autoCompaction", "steeringMode"), change.keys)
        assertEquals("settings", change.text("kind"))
        assertEquals(SessionSettings(false, "all", "one-at-a-time"), repository.state.value.configuration?.settings)
        assertFalse(repository.state.value.configurationChanging)
    }

    @Test
    fun settingsChangeIsIgnoredWithoutTheCapabilityOrReportedSettings() = runTest {
        val transport = Transport().also(::capable)
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        repository.changeSettings(autoCompaction = true)
        runCurrent()
        assertEquals(0, transport.sent.count { it.text("type") == "session.configuration.set" })
    }

    @Test
    fun unsupportedSettingsChangeOnlyWithdrawsTheSettingsCapability() = runTest {
        val transport = Transport().also(::capable)
        transport.capabilities += SETTINGS_CAPABILITY
        val original = transport.response
        transport.response = {
            when (it.text("type")) {
                "session.configuration.get" -> configurationData(it.text("sessionId"), settings = settingsData())
                "session.configuration.set" -> {
                    transport.listener.message(
                        Wire.objectOf(
                            "type" to "result",
                            "requestId" to it.text("requestId"),
                            "ok" to false,
                            "error" to Wire.objectOf("code" to "unsupported"),
                        )
                    )
                    null
                }
                else -> original(it)
            }
        }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        repository.changeSettings(autoCompaction = false)
        runCurrent()
        val state = repository.state.value
        assertEquals(setOf(SETTINGS_CAPABILITY), state.unavailableCapabilities)
        assertNotNull(state.configuration)
    }

    @Test
    fun failedSettingsChangeReadsTheConfigurationAgain() = runTest {
        val transport = Transport().also(::capable)
        transport.capabilities += SETTINGS_CAPABILITY
        val original = transport.response
        var held = settingsData()
        transport.response = {
            when (it.text("type")) {
                "session.configuration.get" -> configurationData(it.text("sessionId"), settings = held)
                "session.configuration.set" -> null
                else -> original(it)
            }
        }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        held = settingsData(auto = false)
        repository.changeSettings(autoCompaction = false)
        runCurrent()
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(R.string.remote_configuration_error, repository.state.value.error)
        assertEquals("session.configuration.get", transport.sent.last().text("type"))
        assertEquals(false, repository.state.value.configuration?.settings?.autoCompaction)
        assertFalse(repository.state.value.configurationChanging)
    }

    @Test
    fun uncertainCommandSurvivesRecreationWithoutReplay() = runTest {
        val transport = Transport().also(::capable)
        val drafts = Drafts()
        val repository = repository(transport, drafts)
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        transport.response = { null }
        repository.draft("/review  keep spaces")
        repository.prompt()
        runCurrent()
        advanceTimeBy(30_001)
        runCurrent()
        assertTrue(repository.state.value.uncertain)
        val freshTransport = Transport().also(::capable)
        val fresh = repository(freshTransport, drafts)
        fresh.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        assertTrue(fresh.state.value.uncertain)
        assertEquals("/review  keep spaces", fresh.state.value.draft)
        assertFalse(
            freshTransport.sent.any {
                it.text("type") in listOf("session.command", "session.prompt")
            }
        )
    }

    @Test
    fun quotedCommandIsRetainedAndNotDispatched() = runTest {
        val transport = Transport().also(::capable)
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        transport.listener.message(
            Wire.objectOf(
                "type" to "event",
                "sessionId" to "session",
                "revision" to 1,
                "kind" to "message.upsert",
                "message" to
                    Wire.objectOf(
                        "id" to "message",
                        "role" to "assistant",
                        "text" to "quote this",
                        "state" to "complete",
                    ),
            )
        )
        repository.quote("message")
        repository.draft("/review")
        repository.prompt()
        runCurrent()
        assertNotNull(repository.state.value.quote)
        assertEquals(R.string.remote_command_remove_context, repository.state.value.error)
        assertFalse(
            transport.sent.any { it.text("type") in listOf("session.prompt", "session.command") }
        )
    }

    @Test
    fun sessionStatusUpdatesListWithoutOpeningChatAndWinsListResponseRace() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host"))
        transport.response = { request ->
            if (request.text("type") == "projects.list") data("projects", listOf(project)) else null
        }
        val selection = async { repository.activate(RemoteSelection("host", "project")) }
        runCurrent()
        val listRequest = transport.sent.last { it.text("type") == "sessions.list" }
        fun status(revision: Long, value: String) =
            transport.listener.message(
                Wire.objectOf(
                    "type" to "event",
                    "sessionId" to "session",
                    "revision" to revision,
                    "kind" to "session.status",
                    "status" to value,
                )
            )
        status(2, "running")
        transport.result(listRequest, data("sessions", listOf(session(status = "idle"))))
        selection.await()
        assertEquals("running", repository.state.value.sessions.single().text("status"))
        status(3, "waiting")
        assertEquals("waiting", repository.state.value.sessions.single().text("status"))
        status(1, "idle")
        assertEquals("waiting", repository.state.value.sessions.single().text("status"))
        assertNull(repository.state.value.selection.sessionId)
        assertFalse(transport.sent.any { it.text("type") == "sessions.open" })
    }

    @Test
    fun restoreUsesSnapshotWithoutOpeningAndIsIdempotent() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = repository(transport)
        val target = RemoteSelection("host", "project", "session")
        assertEquals(target, repository.activate(target))
        val count = transport.sent.size
        assertEquals(target, repository.activate(target))
        assertEquals(count, transport.sent.size)
        assertFalse(transport.sent.any { it.text("type") == "sessions.open" })
        assertTrue(transport.sent.any { it.text("type") == "session.snapshot" })
    }

    @Test
    fun sessionsFreshOnlyWhenTheListWasActuallyFetchedThisTime() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        assertTrue(
            "The list was just fetched on this connection",
            repository.state.value.sessionsFresh,
        )
        val listRequests = transport.sent.count { it.text("type") == "sessions.list" }
        // Same connection, same project: the cached list is reused rather than refetched.
        repository.activate(RemoteSelection("host", "project"))
        assertEquals(listRequests, transport.sent.count { it.text("type") == "sessions.list" })
        assertFalse(
            "A reused cached list must not be trusted to declare a session closed",
            repository.state.value.sessionsFresh,
        )
    }

    @Test
    fun foregroundWithValidatedNetworkResynchronizesSavedSelectionWithoutOpeningIt() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = repository(transport)
        val target = RemoteSelection("host", "project", "session")
        repository.activate(target)
        val initialProjects = transport.sent.count { it.text("type") == "projects.list" }
        val initialSnapshots = transport.sent.count { it.text("type") == "session.snapshot" }

        repository.setForeground(true)
        repository.setValidatedNetwork("wifi")
        runCurrent()

        assertEquals(initialProjects + 1, transport.sent.count { it.text("type") == "projects.list" })
        assertEquals(initialSnapshots + 1, transport.sent.count { it.text("type") == "session.snapshot" })
        assertFalse(transport.sent.any { it.text("type") == "sessions.open" })
    }

    @Test
    fun foregroundRecoveryRefreshesCachedProjectsAndSessions() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = repository(transport)
        val target = RemoteSelection("host", "project", "session")
        repository.activate(target)
        val original = transport.response
        transport.response = { request ->
            if (request.text("type") == "projects.list") data("projects", emptyList())
            else original(request)
        }

        repository.setForeground(true)
        repository.setValidatedNetwork("wifi")
        runCurrent()

        assertEquals(RemoteSelection("host"), repository.state.value.selection)
        assertEquals(2, transport.sent.count { it.text("type") == "projects.list" })
        assertFalse(transport.sent.any { it.text("type") == "sessions.open" })
    }

    @Test
    fun backgroundStopsAutomaticReconnectRetries() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        repository.setForeground(true)
        repository.setValidatedNetwork("wifi")
        runCurrent()
        transport.listener.failed(true, R.string.remote_connection_error)
        repository.setForeground(false)
        val connects = transport.connects

        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(connects, transport.connects)
    }

    @Test
    fun networkReplacementInvalidatesConnectionBeforeRestoringSelection() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        repository.setForeground(true)
        repository.setValidatedNetwork("wifi")
        runCurrent()
        val closes = transport.closes

        repository.setValidatedNetwork("cellular")

        assertEquals(closes + 1, transport.closes)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(RemoteSelection("host", "project", "session"), repository.state.value.selection)
        assertFalse(transport.sent.any { it.text("type") == "sessions.open" })
    }

    @Test
    fun networkLossThenRecoveryInvalidatesTheStaleConnection() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        repository.setForeground(true)
        repository.setValidatedNetwork("wifi")
        runCurrent()
        val closes = transport.closes

        repository.setValidatedNetwork(null)
        repository.setValidatedNetwork("cellular")

        assertEquals(closes + 1, transport.closes)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(repository.state.value.connected)
    }

    @Test
    fun recoveryDoesNotRestoreAnOlderSelectionDuringUserNavigation() = runTest {
        val first = session("first")
        val second = session("second")
        val transport = Transport().also { configure(it, listOf(first, second), second) }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "first"))
        val firstSnapshots =
            transport.sent.count {
                it.text("type") == "session.snapshot" && it.text("sessionId") == "first"
            }

        repository.setForeground(true)
        repository.setValidatedNetwork("wifi")
        val selected =
            repository.activate(
                RemoteSelection("host", "project", "second"),
                ActivationMode.USER_OPEN,
            )
        runCurrent()

        assertEquals(RemoteSelection("host", "project", "second"), selected)
        assertEquals(RemoteSelection("host", "project", "second"), repository.state.value.selection)
        assertEquals(
            firstSnapshots,
            transport.sent.count {
                it.text("type") == "session.snapshot" && it.text("sessionId") == "first"
            },
        )
    }

    @Test
    fun foregroundRecoveryReconnectsAfterAHandshakeTimesOut() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = repository(transport)
        val selection = RemoteSelection("host", "project", "session")
        repository.activate(selection)
        transport.listener.failed(false, R.string.remote_connection_error)
        transport.readyOnConnect = false
        val stalled = async { repository.activate(selection) }
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        stalled.await()
        assertFalse(repository.state.value.connected)
        assertTrue(transport.closes > 0)

        transport.readyOnConnect = true
        val connects = transport.connects
        repository.setForeground(true)
        repository.setValidatedNetwork("wifi")
        runCurrent()

        assertEquals(connects + 1, transport.connects)
        assertTrue(repository.state.value.connected)
    }

    @Test
    fun recoveryWaitsForAPendingPromptBeforeResynchronizing() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        repository.setForeground(true)
        repository.setValidatedNetwork("wifi")
        runCurrent()
        val snapshots = transport.sent.count { it.text("type") == "session.snapshot" }
        val original = transport.response
        transport.response = { if (it.text("type") == "session.prompt") null else original(it) }
        repository.draft("pending")
        repository.prompt()
        runCurrent()
        val prompt = transport.sent.last { it.text("type") == "session.prompt" }

        repository.setForeground(false)
        repository.setForeground(true)
        runCurrent()

        assertTrue(repository.state.value.sending)
        assertEquals(snapshots, transport.sent.count { it.text("type") == "session.snapshot" })
        transport.result(prompt, Wire.objectOf("kind" to "accepted"))
        runCurrent()

        assertFalse(repository.state.value.sending)
        assertEquals(snapshots + 1, transport.sent.count { it.text("type") == "session.snapshot" })
    }

    @Test
    fun recoveryWaitsForAPendingConfigurationChangeBeforeResynchronizing() = runTest {
        val transport = Transport().also(::capable)
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        repository.setForeground(true)
        repository.setValidatedNetwork("wifi")
        runCurrent()
        val snapshots = transport.sent.count { it.text("type") == "session.snapshot" }
        val original = transport.response
        var configuredModel = "model"
        transport.response = {
            when (it.text("type")) {
                "session.configuration.set" -> null
                "session.configuration.get" -> configurationData(it.text("sessionId"), configuredModel)
                else -> original(it)
            }
        }
        repository.setModel("provider", "other")
        runCurrent()
        val change = transport.sent.last { it.text("type") == "session.configuration.set" }

        repository.setForeground(false)
        repository.setForeground(true)
        runCurrent()

        assertTrue(repository.state.value.configurationChanging)
        assertEquals(snapshots, transport.sent.count { it.text("type") == "session.snapshot" })
        configuredModel = "other"
        transport.result(change, configurationData(model = "other"))
        runCurrent()

        assertFalse(repository.state.value.configurationChanging)
        assertEquals("other", repository.state.value.configuration?.model?.id)
        assertEquals(snapshots + 1, transport.sent.count { it.text("type") == "session.snapshot" })
    }

    @Test
    fun explicitRefreshRestartsRetriesAfterTheAutomaticBudgetIsExhausted() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        repository.setForeground(true)
        repository.setValidatedNetwork("wifi")
        runCurrent()
        transport.failureOnConnect = R.string.remote_connection_error
        transport.listener.failed(true, R.string.remote_connection_error)
        advanceTimeBy(31_000)
        runCurrent()
        assertFalse(repository.state.value.connected)

        repository.refresh()
        runCurrent()
        transport.failureOnConnect = null
        advanceTimeBy(1_000)
        runCurrent()

        assertTrue(repository.state.value.connected)
    }

    @Test
    fun explicitDisconnectAndDeniedAuthenticationDoNotResumeAutomatically() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        repository.setForeground(true)
        repository.setValidatedNetwork("wifi")
        runCurrent()
        repository.disconnect()
        val connectsAfterDisconnect = transport.connects

        repository.setForeground(false)
        repository.setForeground(true)
        runCurrent()
        assertEquals(connectsAfterDisconnect, transport.connects)

        repository.activate(RemoteSelection("host", "project", "session"))
        transport.listener.failed(false, R.string.remote_denied)
        val connectsAfterDenied = transport.connects
        repository.setValidatedNetwork("cellular")
        runCurrent()
        assertEquals(connectsAfterDenied, transport.connects)
    }

    @Test
    fun restoreHistoricalAndOfflineSessionsReturnsParentWithoutFork() = runTest {
        for (item in listOf(session(origin = "history"), session(status = "offline"))) {
            val transport = Transport().also { configure(it, listOf(item)) }
            val repository = repository(transport)
            assertEquals(
                RemoteSelection("host", "project"),
                repository.activate(RemoteSelection("host", "project", "session")),
            )
            assertFalse(transport.sent.any { it.text("type") == "sessions.open" })
        }
    }

    @Test
    fun explicitHistoricalOpenUsesCanonicalReturnedIds() = runTest {
        val transport =
            Transport().also {
                configure(
                    it,
                    listOf(session(origin = "history")),
                    session("fork", projectId = "project"),
                )
            }
        val repository = repository(transport)
        assertEquals(
            RemoteSelection("host", "project", "fork"),
            repository.activate(
                RemoteSelection("host", "project", "session"),
                ActivationMode.USER_OPEN,
            ),
        )
        assertEquals("fork", repository.state.value.session?.text("id"))
    }

    @Test
    fun cancellationRejectsLateOpenWithoutClosingTransport() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project"))
        val normal = transport.response
        transport.response = { if (it.text("type") == "sessions.open") null else normal(it) }
        val opening = async {
            repository.activate(
                RemoteSelection("host", "project", "session"),
                ActivationMode.USER_OPEN,
            )
        }
        runCurrent()
        val request = transport.sent.last { it.text("type") == "sessions.open" }
        assertEquals(RemoteSelection("host", "project"), repository.state.value.selection)
        repository.cancelSelection()
        transport.result(request, Wire.objectOf("kind" to "session", "session" to session("late")))
        runCurrent()
        assertEquals(RemoteSelection("host", "project"), repository.state.value.selection)
        assertEquals(0, transport.closes)
        assertTrue(opening.isCancelled)
    }

    @Test
    fun newerActivationRejectsOlderResponse() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project"))
        val normal = transport.response
        transport.response = { if (it.text("type") == "sessions.open") null else normal(it) }
        val opening = async {
            repository.activate(
                RemoteSelection("host", "project", "session"),
                ActivationMode.USER_OPEN,
            )
        }
        runCurrent()
        val request = transport.sent.last { it.text("type") == "sessions.open" }
        repository.activate(RemoteSelection("host"))
        transport.result(request, Wire.objectOf("kind" to "session", "session" to session("late")))
        runCurrent()
        assertEquals(RemoteSelection("host"), repository.state.value.selection)
        assertTrue(opening.isCancelled)
    }

    @Test
    fun requestTimeoutIsVisibleAndRetainsUncertainDraftWithoutReplay() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        val normal = transport.response
        transport.response = { if (it.text("type") == "session.prompt") null else normal(it) }
        repository.draft("keep this")
        repository.prompt()
        runCurrent()
        advanceTimeBy(30000)
        runCurrent()
        assertEquals(R.string.remote_request_error, repository.state.value.error)
        assertFalse(repository.state.value.sending)
        assertTrue(repository.state.value.uncertain)
        assertEquals("keep this", repository.state.value.draft)
        assertEquals(1, transport.sent.count { it.text("type") == "session.prompt" })
    }

    @Test
    fun connectionFailureReturnsPriorSelectionInsteadOfCancellingNavigation() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = repository(transport)
        val parent = RemoteSelection("host", "project")
        repository.activate(parent)
        val normal = transport.response
        transport.response = { if (it.text("type") == "sessions.open") null else normal(it) }
        val stack =
            mutableListOf<androidx.navigation3.runtime.NavKey>().apply { addAll(parent.keys()) }
        val navigator = RemoteNavigator(repository, stack, backgroundScope)
        navigator.open(RemoteNavKey.Chat("host", "project", "session"))
        runCurrent()
        assertEquals(parent, repository.state.value.selection)
        transport.listener.failed(false, R.string.remote_connection_error)
        runCurrent()
        assertEquals(parent.keys(), stack)
        assertEquals(parent, repository.state.value.selection)
        assertFalse(repository.state.value.connected)
        assertNotNull(repository.state.value.error)
    }

    @Test
    fun concurrentHostRemovalDoesNotResurrectPairings() = runTest {
        val pairings = Pairings(listOf(host, host.copy(routeId = "second")))
        val transport = Transport().also { configure(it) }
        val repository =
            DefaultRemoteRepository(
                pairings,
                Drafts(),
                transport,
                backgroundScope,
                StandardTestDispatcher(testScheduler),
            )
        repository.activate(RemoteSelection())
        repository.remove("host")
        repository.remove("second")
        runCurrent()
        repository.flush()
        assertTrue(repository.state.value.hosts.isEmpty())
        assertTrue(pairings.hosts.isEmpty())
    }

    @Test
    fun unknownNotificationDoesNotChangeSelection() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = repository(transport)
        val target = RemoteSelection("host", "project", "session")
        repository.activate(target)
        assertNull(repository.openNotification("unpaired", "session"))
        assertNull(repository.openNotification("host", "missing"))
        assertEquals(target, repository.state.value.selection)
        assertFalse(transport.sent.any { it.text("type") == "sessions.open" })
    }

    @Test
    fun notificationResolvesAuthorizedProjectBeforeOpening() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = repository(transport)
        assertEquals(
            RemoteSelection("host", "project", "session"),
            repository.openNotification("host", "session"),
        )
        val kinds = transport.sent.map { it.text("type") }
        assertTrue(kinds.indexOf("sessions.list") < kinds.indexOf("sessions.open"))
    }

    private fun quoteMessages(transport: Transport) {
        val normal = transport.response
        transport.response = { request ->
            val response = normal(request)
            if (request.text("type") == "session.snapshot" && response != null)
                JsonObject(
                    response +
                        ("messages" to
                            JsonArray(
                                listOf(
                                    Wire.objectOf(
                                        "id" to "a",
                                        "role" to "assistant",
                                        "text" to "First answer",
                                        "state" to "complete",
                                    ),
                                    Wire.objectOf(
                                        "id" to "b",
                                        "role" to "assistant",
                                        "text" to "Second answer",
                                        "state" to "complete",
                                    ),
                                )
                            ))
                )
            else response
        }
    }

    @Test
    fun statusEventsUpdatePreviouslyVisitedProjectCache() = runTest {
        val transport = Transport().also { configure(it) }
        val normal = transport.response
        transport.response = { request ->
            when (request.text("type")) {
                "projects.list" -> data("projects", listOf(project, Wire.objectOf("id" to "other-project")))
                "sessions.list" -> if (request.text("projectId") == "project")
                    data("sessions", listOf(session(status = "offline")))
                else data("sessions", listOf(session("other", projectId = "other-project")))
                else -> normal(request)
            }
        }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project"))
        repository.activate(RemoteSelection("host", "other-project"))
        transport.listener.message(Wire.objectOf(
            "type" to "event", "kind" to "session.status", "sessionId" to "session",
            "revision" to 1, "status" to "idle",
        ))
        val target = RemoteSelection("host", "project", "session")
        assertEquals(target, repository.activate(target, ActivationMode.RESTORE))
        assertEquals("idle", repository.state.value.session?.text("status"))
    }

    @Test
    fun returningToEarlierChatShowsCachedMessagesBeforeSnapshotCompletes() = runTest {
        val transport = Transport().also {
            configure(it, sessions = listOf(session(), session("other")))
            quoteMessages(it)
        }
        val repository = repository(transport)
        val first = RemoteSelection("host", "project", "session")
        repository.activate(first)
        val messages = repository.state.value.messages
        assertTrue(messages.isNotEmpty())
        repository.activate(RemoteSelection("host", "project", "other"))
        val normal = transport.response
        transport.response = { request ->
            if (request.text("type") == "session.snapshot") null else normal(request)
        }
        val returning = async { repository.activate(first) }
        runCurrent()
        assertEquals(first, repository.state.value.selection)
        assertEquals(messages, repository.state.value.messages)
        assertTrue(repository.state.value.loading)
        returning.cancel()
    }

    @Test
    fun unchangedQuoteAndBodyClearTogetherAfterAcknowledgment() = runTest {
        val drafts = Drafts()
        val transport =
            Transport().also {
                configure(it)
                quoteMessages(it)
            }
        val repository = repository(transport, drafts)
        repository.activate(RemoteSelection("host", "project", "session"))
        repository.draft("Follow up")
        repository.quote("a")
        repository.prompt()
        runCurrent()
        repository.flush()
        assertEquals("", repository.state.value.draft)
        assertNull(repository.state.value.quote)
        assertEquals(StoredDraft(), drafts.values.getValue(DraftKey("host", "session")))
    }

    @Test
    fun quoteChangedDuringSendKeepsBothBodyAndNewQuoteOnAcknowledgment() = runTest {
        val drafts = Drafts()
        val transport =
            Transport().also {
                configure(it)
                quoteMessages(it)
            }
        val repository = repository(transport, drafts)
        repository.activate(RemoteSelection("host", "project", "session"))
        repository.draft("Follow up")
        repository.quote("a")
        val normal = transport.response
        transport.response = { if (it.text("type") == "session.prompt") null else normal(it) }
        repository.prompt()
        runCurrent()
        val request = transport.sent.last { it.text("type") == "session.prompt" }
        assertEquals("a", QuoteCodec.decode(request.text("text")).quote?.messageId)
        assertEquals(
            "a",
            drafts.values.getValue(DraftKey("host", "session")).submittedQuote?.messageId,
        )
        repository.quote("b")
        transport.result(request, Wire.objectOf("kind" to "accepted"))
        runCurrent()
        repository.flush()
        assertEquals("Follow up", repository.state.value.draft)
        assertEquals("b", repository.state.value.quote?.messageId)
        assertNull(drafts.values.getValue(DraftKey("host", "session")).mutationId)
    }

    @Test
    fun quoteSurvivesSessionSwitchAndRecreationWithoutAutomaticReplay() = runTest {
        val drafts = Drafts()
        val transport =
            Transport().also {
                configure(it)
                quoteMessages(it)
            }
        val repository = repository(transport, drafts)
        val target = RemoteSelection("host", "project", "session")
        repository.activate(target)
        repository.draft("Keep body")
        repository.quote("a")
        val normal = transport.response
        transport.response = { if (it.text("type") == "session.prompt") null else normal(it) }
        repository.prompt()
        runCurrent()
        repository.activate(RemoteSelection("host", "project"))
        assertNull(repository.state.value.quote)
        repository.flush()
        val freshTransport =
            Transport().also {
                configure(it)
                quoteMessages(it)
            }
        val fresh = repository(freshTransport, drafts)
        fresh.activate(target)
        assertEquals("a", fresh.state.value.quote?.messageId)
        assertEquals("Keep body", fresh.state.value.draft)
        assertTrue(fresh.state.value.uncertain)
        assertFalse(freshTransport.sent.any { it.text("type") == "session.prompt" })
    }

    @Test
    fun quotedPromptSizeLimitAndFailedStorageNeverSendOrDiscardBody() = runTest {
        val drafts = Drafts()
        val transport =
            Transport().also {
                configure(it)
                quoteMessages(it)
            }
        val repository = repository(transport, drafts)
        repository.activate(RemoteSelection("host", "project", "session"))
        repository.quote("a")
        repository.draft("x".repeat(128 * 1024))
        repository.prompt()
        runCurrent()
        assertEquals(R.string.remote_prompt_too_long, repository.state.value.error)
        assertEquals(128 * 1024, repository.state.value.draft.length)
        assertFalse(transport.sent.any { it.text("type") == "session.prompt" })
        repository.draft("short")
        repository.flush()
        drafts.fail = true
        repository.prompt()
        runCurrent()
        assertFalse(transport.sent.any { it.text("type") == "session.prompt" })
        assertEquals("a", repository.state.value.quote?.messageId)
    }

    @Test
    fun switchingHostsPersistsUncertainFollowUpsWithoutReplay() = runTest {
        val other = host.copy(routeId = "other")
        val drafts = Drafts()
        val transport = Transport().also(::configure)
        transport.capabilities = setOf(FOLLOW_UP_CAPABILITY)
        val normal = transport.response
        transport.response = { request ->
            if (request.text("type") == "session.prompt")
                Wire.objectOf("kind" to "accepted", "sessionId" to "session")
            else normal(request)
        }
        val repository = DefaultRemoteRepository(
            Pairings(listOf(host, other)), drafts, transport, backgroundScope,
            StandardTestDispatcher(testScheduler), now = { testScheduler.currentTime },
        )
        val original = RemoteSelection("host", "project", "session")
        repository.activate(original)
        transport.listener.message(Wire.objectOf("type" to "event", "sessionId" to "session",
            "revision" to 1, "kind" to "session.status", "status" to "running"))
        repository.draft("queued")
        repository.followUp()
        runCurrent()
        assertEquals("accepted", repository.state.value.followUps.single().status)
        repository.activate(RemoteSelection("other", "project", "session"))
        repository.flush()
        assertEquals("uncertain", drafts.values.getValue(DraftKey("host", "session")).followUps.single().status)
        val sends = transport.sent.count { it.text("type") == "session.prompt" }
        repository.activate(original)
        assertEquals("uncertain", repository.state.value.followUps.single().status)
        assertEquals(sends, transport.sent.count { it.text("type") == "session.prompt" })
    }

    @Test
    fun fullFollowUpJournalRetainsPendingEntriesAndDismissesOnlyResolvedEntries() = runTest {
        val drafts = Drafts()
        val transport = Transport().also(::configure)
        transport.capabilities = setOf(FOLLOW_UP_CAPABILITY)
        val normal = transport.response
        transport.response = { request ->
            if (request.text("type") == "session.prompt")
                Wire.objectOf("kind" to "accepted", "sessionId" to "session")
            else normal(request)
        }
        val repository = repository(transport, drafts)
        repository.activate(RemoteSelection("host", "project", "session"))
        transport.listener.message(Wire.objectOf("type" to "event", "sessionId" to "session",
            "revision" to 1, "kind" to "session.status", "status" to "running"))
        repeat(64) { index ->
            repository.draft("queued $index")
            repository.followUp()
            runCurrent()
        }
        val oldest = repository.state.value.followUps.first().requestId
        assertEquals(64, repository.state.value.followUps.size)
        repository.draft("blocked")
        repository.followUp()
        runCurrent()
        assertEquals(64, transport.sent.count { it.text("type") == "session.prompt" })
        repository.dismissFollowUp(oldest)
        assertEquals(64, repository.state.value.followUps.size)
        transport.listener.message(Wire.objectOf("type" to "event", "sessionId" to "session",
            "revision" to 2, "kind" to "follow_up.status", "requestId" to oldest,
            "status" to "delivered"))
        repository.followUp()
        runCurrent()
        assertEquals(64, transport.sent.count { it.text("type") == "session.prompt" })
        assertEquals("delivered", repository.state.value.followUps.first().status)
        repository.dismissFollowUp(oldest)
        repository.followUp()
        runCurrent()
        repository.flush()
        assertEquals(65, transport.sent.count { it.text("type") == "session.prompt" })
        assertEquals(64, repository.state.value.followUps.size)
        assertTrue(repository.state.value.followUps.none { it.requestId == oldest })
        assertEquals(64, repository.state.value.followUps.count { it.status == "accepted" })
        val last = repository.state.value.followUps.last().requestId
        repository.dismissFollowUp(last)
        assertEquals(64, repository.state.value.followUps.size)
        transport.listener.message(Wire.objectOf("type" to "event", "sessionId" to "session",
            "revision" to 3, "kind" to "follow_up.status", "requestId" to last,
            "status" to "uncertain"))
        repository.dismissFollowUp(last)
        repository.flush()
        assertEquals(63, drafts.values.getValue(DraftKey("host", "session")).followUps.size)
        assertTrue(repository.state.value.followUps.none { it.requestId == last })
    }

    @Test
    fun steerUsesSeparateDeliveryAndReceiptsWithoutClaimingDelivery() = runTest {
        val transport = Transport().also(::configure)
        transport.capabilities = setOf(STEER_CAPABILITY, FOLLOW_UP_CAPABILITY)
        val normal = transport.response
        transport.response = { request ->
            if (request.text("type") == "session.prompt")
                Wire.objectOf("kind" to "accepted", "sessionId" to "session")
            else normal(request)
        }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        transport.listener.message(Wire.objectOf("type" to "event", "sessionId" to "session",
            "revision" to 1, "kind" to "session.status", "status" to "running"))
        repository.draft("Change direction")
        repository.steer()
        runCurrent()
        val sent = transport.sent.last { it.text("type") == "session.prompt" }
        assertEquals("steer", sent.text("delivery"))
        assertEquals("accepted", repository.state.value.followUps.single().status)
        assertEquals("steer", repository.state.value.followUps.single().delivery)
        transport.listener.message(Wire.objectOf("type" to "event", "sessionId" to "session",
            "revision" to 2, "kind" to "follow_up.status", "requestId" to sent.text("requestId"),
            "status" to "delivered"))
        assertEquals("accepted", repository.state.value.followUps.single().status)
        transport.listener.message(Wire.objectOf("type" to "event", "sessionId" to "session",
            "revision" to 3, "kind" to "steer.status", "requestId" to sent.text("requestId"),
            "status" to "uncertain"))
        assertEquals("uncertain", repository.state.value.followUps.single().status)
    }

    @Test
    fun followUpStopUsesHostReceiptsBeforeAcknowledgement() = runTest {
        val drafts = Drafts()
        val transport = Transport().also(::configure)
        transport.capabilities = setOf(FOLLOW_UP_CAPABILITY)
        val normal = transport.response
        transport.response = { request ->
            when (request.text("type")) {
                "session.prompt" -> Wire.objectOf("kind" to "accepted", "sessionId" to "session")
                "session.abort" -> {
                    transport.listener.message(Wire.objectOf("type" to "event", "sessionId" to "session",
                        "revision" to 3, "kind" to "follow_up.status",
                        "requestId" to transport.sent.last { it.text("type") == "session.prompt" }.text("requestId"),
                        "status" to "uncertain"))
                    normal(request)
                }
                else -> normal(request)
            }
        }
        val repository = repository(transport, drafts)
        repository.activate(RemoteSelection("host", "project", "session"))
        fun status(revision: Int, value: String) = transport.listener.message(
            Wire.objectOf("type" to "event", "sessionId" to "session", "revision" to revision,
                "kind" to "session.status", "status" to value)
        )
        status(1, "running")
        repository.draft("first")
        repository.prompt()
        runCurrent()
        assertTrue(transport.sent.none { it.text("type") == "session.prompt" })
        repository.followUp()
        runCurrent()
        val first = transport.sent.last { it.text("type") == "session.prompt" }
        assertEquals("follow_up", first.text("delivery"))
        assertEquals("accepted", repository.state.value.followUps.single().status)
        repository.draft("second")
        repository.followUp()
        runCurrent()
        assertEquals(listOf("first", "second"), repository.state.value.followUps.map { it.text })
        transport.listener.message(Wire.objectOf("type" to "event", "sessionId" to "session",
            "revision" to 2, "kind" to "follow_up.status", "requestId" to first.text("requestId"),
            "status" to "delivered"))
        assertEquals("delivered", repository.state.value.followUps.first().status)
        repository.abort()
        runCurrent()
        repository.flush()
        assertEquals(listOf("delivered", "uncertain"), drafts.values.getValue(DraftKey("host", "session")).followUps.map { it.status })
    }

    @Test
    fun followUpReceiptBeforeAcknowledgementIsPersisted() = runTest {
        val drafts = Drafts()
        val transport = Transport().also(::configure)
        transport.capabilities = setOf(FOLLOW_UP_CAPABILITY)
        val normal = transport.response
        transport.response = { request ->
            if (request.text("type") == "session.prompt") {
                transport.listener.message(Wire.objectOf("type" to "event", "sessionId" to "session",
                    "revision" to 2, "kind" to "follow_up.status",
                    "requestId" to request.text("requestId"), "status" to "uncertain"))
                Wire.objectOf("kind" to "accepted", "sessionId" to "session")
            } else normal(request)
        }
        val repository = repository(transport, drafts)
        repository.activate(RemoteSelection("host", "project", "session"))
        transport.listener.message(Wire.objectOf("type" to "event", "sessionId" to "session",
            "revision" to 1, "kind" to "session.status", "status" to "running"))
        repository.draft("same text")
        repository.followUp()
        runCurrent()
        repository.flush()
        assertEquals("uncertain", drafts.values.getValue(DraftKey("host", "session")).followUps.single().status)
    }

    @Test
    fun lostFollowUpAcknowledgementLeavesDraftUncertainWithoutReplay() = runTest {
        val drafts = Drafts()
        val transport = Transport().also(::configure)
        transport.capabilities = setOf(FOLLOW_UP_CAPABILITY)
        val repository = repository(transport, drafts)
        val selection = RemoteSelection("host", "project", "session")
        repository.activate(selection)
        transport.listener.message(Wire.objectOf("type" to "event", "sessionId" to "session",
            "revision" to 1, "kind" to "session.status", "status" to "running"))
        val normal = transport.response
        transport.response = { request ->
            if (request.text("type") == "session.prompt") null else normal(request)
        }
        repository.draft("queued once")
        repository.followUp()
        runCurrent()
        repository.flush()
        assertTrue(repository.state.value.uncertain)
        assertEquals("queued once", drafts.values.getValue(DraftKey("host", "session")).submittedText)
        val restoredTransport = Transport().also(::configure)
        val restored = repository(restoredTransport, drafts)
        restored.activate(selection)
        assertTrue(restored.state.value.uncertain)
        assertTrue(restoredTransport.sent.none { it.text("type") == "session.prompt" })
    }

    @Test
    fun oldHostAndOldTerminalKeepRunningChatIdleOnly() = runTest {
        for (capabilities in listOf(emptySet(), setOf(FOLLOW_UP_CAPABILITY), setOf(STEER_CAPABILITY))) {
            val transport = Transport()
            configure(transport, sessions = listOf(session(origin = "tui")))
            transport.capabilities = capabilities
            val repository = repository(transport)
            repository.activate(RemoteSelection("host", "project", "session"))
            transport.listener.message(Wire.objectOf("type" to "event", "sessionId" to "session",
                "revision" to 1, "kind" to "session.status", "status" to "running"))
            repository.draft("next")
            repository.followUp()
            repository.steer()
            runCurrent()
            assertTrue(transport.sent.none { it.text("type") == "session.prompt" })
        }
    }

    @Test
    fun disconnectMarksPendingFollowUpsUncertainBeforeHostIsCleared() = runTest {
        val drafts = Drafts()
        val transport = Transport().also(::configure)
        transport.capabilities = setOf(FOLLOW_UP_CAPABILITY)
        val normal = transport.response
        transport.response = { request ->
            if (request.text("type") == "session.prompt")
                Wire.objectOf("kind" to "accepted", "sessionId" to "session")
            else normal(request)
        }
        val repository = repository(transport, drafts)
        val selection = RemoteSelection("host", "project", "session")
        repository.activate(selection)
        transport.listener.message(Wire.objectOf("type" to "event", "sessionId" to "session",
            "revision" to 1, "kind" to "session.status", "status" to "running"))
        repository.draft("next")
        repository.followUp()
        runCurrent()
        assertEquals("accepted", repository.state.value.followUps.single().status)
        repository.disconnect()
        repository.flush()
        assertEquals("uncertain", drafts.values.getValue(DraftKey("host", "session")).followUps.single().status)
        repository.activate(selection)
        assertEquals("uncertain", repository.state.value.followUps.single().status)
        assertEquals(1, transport.sent.count { it.text("type") == "session.prompt" })
    }

    @Test
    fun restoredPendingFollowUpIsUncertainAndNeverReplayed() = runTest {
        val drafts = Drafts()
        val id = Wire.random()
        drafts.values = mapOf(DraftKey("host", "session") to StoredDraft(
            followUps = listOf(PendingFollowUp(id, "queued"))))
        val transport = Transport().also(::configure)
        val repository = repository(transport, drafts)
        repository.activate(RemoteSelection("host", "project", "session"))
        assertEquals("uncertain", repository.state.value.followUps.single().status)
        assertTrue(transport.sent.none { it.text("type") == "session.prompt" })
    }

    @Test
    fun promptIsDurableBeforeSendAndAcknowledgementRetainsNewEdit() = runTest {
        val drafts = Drafts()
        val transport = Transport().also { configure(it) }
        val repository = repository(transport, drafts)
        repository.activate(RemoteSelection("host", "project", "session"))
        val normal = transport.response
        transport.response = {
            if (it.text("type") == "session.prompt") {
                assertEquals(
                    "first",
                    drafts.values.getValue(DraftKey("host", "session")).submittedText,
                )
                null
            } else normal(it)
        }
        repository.draft("first")
        repository.prompt()
        runCurrent()
        val request = transport.sent.last { it.text("type") == "session.prompt" }
        repository.draft("second")
        transport.result(request, Wire.objectOf("kind" to "accepted"))
        runCurrent()
        repository.flush()
        assertEquals("second", repository.state.value.draft)
        assertEquals(StoredDraft("second"), drafts.values[DraftKey("host", "session")])
    }

    @Test
    fun unresolvedDraftSurvivesRecreationAndNeverReplays() = runTest {
        val drafts = Drafts()
        val firstTransport = Transport().also { configure(it) }
        val first = repository(firstTransport, drafts)
        val selection = RemoteSelection("host", "project", "session")
        first.activate(selection)
        val normal = firstTransport.response
        firstTransport.response = { if (it.text("type") == "session.prompt") null else normal(it) }
        first.draft("do work")
        first.prompt()
        runCurrent()
        first.flush()
        val transport = Transport().also { configure(it) }
        val recreated = repository(transport, drafts)
        recreated.activate(selection)
        assertEquals("do work", recreated.state.value.draft)
        assertTrue(recreated.state.value.uncertain)
        assertFalse(transport.sent.any { it.text("type") == "session.prompt" })
    }

    @Test
    fun failedDurableMarkerPreventsNetworkPrompt() = runTest {
        val drafts = Drafts()
        val transport = Transport().also { configure(it) }
        val repository = repository(transport, drafts)
        repository.activate(RemoteSelection("host", "project", "session"))
        drafts.fail = true
        repository.draft("save first")
        repository.prompt()
        runCurrent()
        assertFalse(transport.sent.any { it.text("type") == "session.prompt" })
        assertEquals(R.string.remote_storage_error, repository.state.value.error)
    }

    @Test
    fun sessionDisappearingBetweenListAndSnapshotReturnsParentWithoutFork() = runTest {
        val transport = Transport().also { configure(it) }
        val normal = transport.response
        transport.response = { request ->
            if (request.text("type") == "session.snapshot") {
                transport.listener.message(
                    Wire.objectOf(
                        "type" to "result",
                        "requestId" to request.text("requestId"),
                        "ok" to false,
                        "error" to Wire.objectOf("code" to "session_not_found"),
                    )
                )
                null
            } else normal(request)
        }
        val repository = repository(transport)
        assertEquals(
            RemoteSelection("host", "project"),
            repository.activate(RemoteSelection("host", "project", "session")),
        )
        assertNull(repository.state.value.session)
        assertFalse(transport.sent.any { it.text("type") == "sessions.open" })
    }

    @Test
    fun reconnectRestoresLiveSnapshotWithoutOpeningOrReplayingPrompt() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = repository(transport)
        val selection = RemoteSelection("host", "project", "session")
        repository.activate(selection)
        repository.setForeground(true)
        repository.setValidatedNetwork("wifi")
        runCurrent()
        val snapshotsBeforeFailure = transport.sent.count { it.text("type") == "session.snapshot" }
        transport.listener.failed(true, R.string.remote_connection_error)
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(selection, repository.state.value.selection)
        assertTrue(repository.state.value.connected)
        assertEquals(
            snapshotsBeforeFailure + 1,
            transport.sent.count { it.text("type") == "session.snapshot" },
        )
        assertFalse(
            transport.sent.any { it.text("type") in setOf("sessions.open", "session.prompt") }
        )
    }

    @Test
    fun unknownPushTokenIsNotRegisteredWhenTheConnectionBecomesReady() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = repository(transport)

        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()

        assertTrue(transport.sent.none { it.text("type") == "push.register" })
    }

    @Test
    fun arrivingPushTokenIsRegisteredOnTheCurrentConnection() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))

        repository.setPushToken("token")
        runCurrent()

        val registration = transport.sent.single { it.text("type") == "push.register" }
        assertEquals("token", registration.text("token"))
    }

    @Test
    fun pushTokenIsRegisteredAgainAfterReconnecting() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = repository(transport)
        val selection = RemoteSelection("host", "project", "session")
        repository.activate(selection)
        repository.setPushToken("token")
        runCurrent()

        transport.listener.failed(false, R.string.remote_connection_error)
        repository.activate(selection)
        runCurrent()

        assertEquals(2, transport.sent.count { it.text("type") == "push.register" })
    }

    @Test
    fun clearingPushTokenRegistersAnExplicitNull() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        repository.setPushToken("token")
        runCurrent()

        repository.setPushToken(null)
        runCurrent()

        val registration = transport.sent.last { it.text("type") == "push.register" }
        assertEquals(JsonNull, registration["token"])
    }

    @Test
    fun removePurgesHostDraftsAndSerializedWritesRetainLatestEdit() = runTest {
        val drafts = Drafts()
        val transport = Transport().also { configure(it) }
        val repository = repository(transport, drafts)
        repository.activate(RemoteSelection("host", "project", "session"))
        repeat(20) { repository.draft("edit $it") }
        repository.flush()
        assertEquals("edit 19", drafts.values.getValue(DraftKey("host", "session")).text)
        repository.remove("host")
        runCurrent()
        repository.flush()
        assertTrue(drafts.values.isEmpty())
        assertTrue(repository.state.value.hosts.isEmpty())
    }

    @Test
    fun draftLimitCountsUtf8Bytes() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        repository.draft("a".repeat(128 * 1024))
        repository.draft("ä".repeat(128 * 1024))
        assertEquals("a".repeat(128 * 1024), repository.state.value.draft)
    }

    @Test
    fun unknownCrossHostNotificationRestoresOriginalTransportAndSelection() = runTest {
        val transport = Transport().also { configure(it) }
        val repository =
            DefaultRemoteRepository(
                Pairings(listOf(host, host.copy(routeId = "other"))),
                Drafts(),
                transport,
                backgroundScope,
                StandardTestDispatcher(testScheduler),
            )
        val selection = RemoteSelection("host", "project", "session")
        repository.activate(selection)
        assertNull(repository.openNotification("other", "missing"))
        runCurrent()
        assertEquals("host", transport.route)
        assertEquals(selection, repository.state.value.selection)
        assertEquals(selection, repository.activate(selection))
        assertFalse(transport.sent.any { it.text("type") == "sessions.open" })
    }

    private fun insight(name: String): JsonObject =
        Wire.json.parseToJsonElement(javaClass.getResource("/insights-v1.json")!!.readText())
            .jsonObject.getValue("valid").jsonArray
            .map { it.jsonObject }
            .single { it.text("name") == name }
            .obj("payload")

    private val fixtureCapabilities =
        insight("projects-capabilities").obj("data").getValue("capabilities")

    private fun advertising(transport: Transport, capabilities: JsonElement = fixtureCapabilities) {
        val original = transport.response
        transport.response = { request ->
            val result = original(request)
            if (request.text("type") == "projects.list" && result != null)
                JsonObject(result + ("capabilities" to capabilities))
            else result
        }
    }

    private fun toolOutputData(
        offset: Long,
        total: Long,
        bytes: ByteArray,
        toolCallId: String = "call",
        sessionId: String = "session",
    ) = Wire.objectOf(
        "kind" to "tool_output",
        "sessionId" to sessionId,
        "toolCallId" to toolCallId,
        "offset" to offset,
        "totalBytes" to total,
        "data" to Wire.encode(bytes),
        "truncated" to false,
        "isError" to false,
    )

    private fun TestScope.toolOutputRepository(
        chunk: (JsonObject) -> JsonObject?,
    ): Pair<Transport, DefaultRemoteRepository> {
        val transport = Transport().also(::configure)
        advertising(transport)
        val original = transport.response
        transport.response = { request ->
            if (request.text("type") == "session.tool_output.get") chunk(request) else original(request)
        }
        val repository = repository(transport)
        return transport to repository
    }

    @Test
    fun routeCapabilitiesMergeSteerFollowUpAndToolOutput() = runTest {
        val transport = Transport().also(::configure)
        advertising(transport)
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        val capabilities = repository.state.value.capabilities
        assertTrue(STEER_CAPABILITY in capabilities)
        assertTrue(FOLLOW_UP_CAPABILITY in capabilities)
        assertTrue(TOOL_OUTPUT_CAPABILITY in capabilities)
        assertTrue(canFollowUp(repository.state.value))
        assertTrue(canSteer(repository.state.value))
    }

    @Test
    fun routeCapabilitiesEnableSubagentControl() = runTest {
        val transport = Transport().also(::configure)
        advertising(transport, JsonArray(listOf(JsonPrimitive(SUBAGENT_CONTROL_CAPABILITY))))
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        assertTrue(SUBAGENT_CONTROL_CAPABILITY in repository.state.value.capabilities)
    }

    @Test
    fun routeCapabilitiesIgnoreUnknownAndOversizedLists() = runTest {
        val transport = Transport().also(::configure)
        advertising(
            transport,
            JsonArray((listOf("project.unshare.v1") + (1..16).map { "x.$it" }).map(::JsonPrimitive)),
        )
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        assertFalse(PROJECT_UNSHARE_CAPABILITY in repository.state.value.capabilities)
        assertFalse(TOOL_OUTPUT_CAPABILITY in repository.state.value.capabilities)
    }

    @Test
    fun nineDeviceCapabilitiesKeepFollowUpEndToEnd() = runTest {
        val transport = Transport().also(::configure)
        transport.capabilities =
            (listOf(FOLLOW_UP_CAPABILITY) + (1..8).map { "session.feature.$it" }).toSet()
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        assertTrue(repository.state.value.connected)
        assertEquals(9, repository.state.value.capabilities.size)
        assertTrue(canFollowUp(repository.state.value))
        assertFalse(TOOL_OUTPUT_CAPABILITY in repository.state.value.capabilities)
    }

    @Test
    fun toolOutputAssemblesSharedFixtureChunks() = runTest {
        val first = insight("tool-output-chunk").obj("data")
        val last = insight("tool-output-last").obj("data")
        val sessionId = first.text("sessionId")
        val toolCallId = first.text("toolCallId")
        val transport = Transport()
        configure(transport, sessions = listOf(session(sessionId)), opened = session(sessionId))
        advertising(transport)
        val original = transport.response
        transport.response = { request ->
            if (request.text("type") == "session.tool_output.get")
                if (request.long("offset") == 0L) first else last
            else original(request)
        }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", sessionId))
        repository.loadToolOutput(toolCallId)
        runCurrent()
        val requests = transport.sent.filter { it.text("type") == "session.tool_output.get" }
        assertEquals(listOf(0L, 12L), requests.map { it.long("offset") })
        assertTrue(requests.all { it.keys == setOf("type", "requestId", "sessionId", "toolCallId", "offset") })
        val download = checkNotNull(repository.state.value.toolOutput)
        assertEquals(37L, download.totalBytes)
        assertEquals(37L, download.loadedBytes)
        assertEquals("Build OK\nall 42 tests passed \u2014 \u2713\n", download.text)
        assertNull(download.failure)
    }

    @Test
    fun toolOutputDecodesCodePointsSplitAcrossChunks() = runTest {
        val bytes = "a\u00e4\u20ac\ud83d\ude00z".encodeToByteArray()
        val split = 4 // inside the three-byte euro sign
        val (transport, repository) = toolOutputRepository { request ->
            val offset = request.long("offset").toInt()
            val end = if (offset == 0) split else bytes.size
            toolOutputData(offset.toLong(), bytes.size.toLong(), bytes.copyOfRange(offset, end))
        }
        repository.activate(RemoteSelection("host", "project", "session"))
        repository.loadToolOutput("call")
        runCurrent()
        assertEquals(2, transport.sent.count { it.text("type") == "session.tool_output.get" })
        val download = checkNotNull(repository.state.value.toolOutput)
        assertEquals("a\u00e4\u20ac\ud83d\ude00z", download.text)
        assertEquals(bytes.size.toLong(), download.loadedBytes)
    }

    @Test
    fun emptyToolOutputCompletesInOneRequest() = runTest {
        val empty = insight("tool-output-empty").obj("data")
        val sessionId = empty.text("sessionId")
        val transport = Transport()
        configure(transport, sessions = listOf(session(sessionId)), opened = session(sessionId))
        advertising(transport)
        val original = transport.response
        transport.response = { request ->
            if (request.text("type") == "session.tool_output.get") empty else original(request)
        }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", sessionId))
        repository.loadToolOutput(empty.text("toolCallId"))
        runCurrent()
        assertEquals(1, transport.sent.count { it.text("type") == "session.tool_output.get" })
        assertEquals("", repository.state.value.toolOutput?.text)
        assertEquals(0L, repository.state.value.toolOutput?.totalBytes)
    }

    @Test
    fun mismatchedOrShiftingToolOutputFails() = runTest {
        val bytes = "hello world".encodeToByteArray()
        val cases = listOf<(JsonObject) -> JsonObject>(
            { toolOutputData(0, bytes.size.toLong(), bytes, toolCallId = "other") },
            { toolOutputData(0, bytes.size.toLong(), bytes, sessionId = "other") },
            { request ->
                val offset = request.long("offset").toInt()
                if (offset == 0) toolOutputData(0, bytes.size.toLong(), bytes.copyOfRange(0, 4))
                else toolOutputData(offset.toLong(), bytes.size + 1L, bytes.copyOfRange(offset, bytes.size))
            },
            { toolOutputData(0, bytes.size.toLong(), ByteArray(0)) },
            { toolOutputData(0, 4, bytes) },
            { toolOutputData(1, bytes.size.toLong(), bytes.copyOfRange(0, 4)) },
            { toolOutputData(0, 2_000_000, bytes) },
            { toolOutputData(0, 60_000, ByteArray(49_153)) },
            { JsonObject(toolOutputData(0, bytes.size.toLong(), bytes) + ("kind" to JsonPrimitive("accepted"))) },
        )
        for (case in cases) {
            val (_, repository) = toolOutputRepository(case)
            repository.activate(RemoteSelection("host", "project", "session"))
            repository.loadToolOutput("call")
            runCurrent()
            val download = checkNotNull(repository.state.value.toolOutput)
            assertEquals(ToolOutputFailure.FAILED, download.failure)
            assertNull(download.text)
        }
    }

    @Test
    fun toolOutputErrorCodesMapToFailures() = runTest {
        for ((code, expected) in listOf(
            "not_found" to ToolOutputFailure.NOT_FOUND,
            "offline" to ToolOutputFailure.OFFLINE,
            "forbidden" to ToolOutputFailure.FAILED,
            "unsupported" to ToolOutputFailure.UNSUPPORTED,
        )) {
            val (transport, repository) = toolOutputRepository { null }
            transport.failure = { if (it.text("type") == "session.tool_output.get") code else null }
            repository.activate(RemoteSelection("host", "project", "session"))
            repository.loadToolOutput("call")
            runCurrent()
            assertEquals(expected, repository.state.value.toolOutput?.failure)
            assertEquals(
                code == "unsupported",
                TOOL_OUTPUT_CAPABILITY in repository.state.value.unavailableCapabilities,
            )
            assertTrue(repository.state.value.connected)
        }
    }

    @Test
    fun unsupportedToolOutputIsNotRequestedAgain() = runTest {
        val (transport, repository) = toolOutputRepository { null }
        transport.failure = { if (it.text("type") == "session.tool_output.get") "unsupported" else null }
        repository.activate(RemoteSelection("host", "project", "session"))
        repository.loadToolOutput("call")
        runCurrent()
        repository.loadToolOutput("call")
        runCurrent()
        assertEquals(1, transport.sent.count { it.text("type") == "session.tool_output.get" })
        assertEquals(ToolOutputFailure.UNSUPPORTED, repository.state.value.toolOutput?.failure)
    }

    @Test
    fun toolOutputIsNeverRequestedWithoutTheCapability() = runTest {
        val transport = Transport().also(::configure)
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        repository.loadToolOutput("call")
        runCurrent()
        assertTrue(transport.sent.none { it.text("type") == "session.tool_output.get" })
        assertEquals(ToolOutputFailure.UNSUPPORTED, repository.state.value.toolOutput?.failure)
        assertTrue(repository.state.value.connected)

        repository.cancelToolOutput()
        assertNull(repository.state.value.toolOutput)
    }

    @Test
    fun toolOutputIsDroppedWhenTheSelectionChanges() = runTest {
        val transport = Transport()
        configure(transport, sessions = listOf(session(), session("other")))
        advertising(transport)
        val original = transport.response
        transport.response = { request ->
            if (request.text("type") == "session.tool_output.get") null else original(request)
        }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        repository.loadToolOutput("call")
        runCurrent()
        val pending = transport.sent.last { it.text("type") == "session.tool_output.get" }
        repository.activate(RemoteSelection("host", "project", "other"))
        runCurrent()
        transport.result(pending, toolOutputData(0, 2, "hi".encodeToByteArray()))
        runCurrent()
        assertNull(repository.state.value.toolOutput)
        assertTrue(repository.state.value.connected)
    }

    @Test
    fun failedCrossHostNotificationDoesNotRestoreAStuckDownload() = runTest {
        val transport = Transport()
        configure(transport)
        advertising(transport)
        val original = transport.response
        transport.response = { request ->
            if (request.text("type") == "session.tool_output.get") null else original(request)
        }
        val repository =
            DefaultRemoteRepository(
                Pairings(listOf(host, host.copy(routeId = "other"))),
                Drafts(),
                transport,
                backgroundScope,
                StandardTestDispatcher(testScheduler),
            )
        val selection = RemoteSelection("host", "project", "session")
        repository.activate(selection)
        repository.loadToolOutput("call")
        runCurrent()
        val pending = transport.sent.last { it.text("type") == "session.tool_output.get" }
        val loading = checkNotNull(repository.state.value.toolOutput)
        assertNull(loading.text)
        assertNull(loading.failure)

        assertNull(repository.openNotification("other", "missing"))
        runCurrent()
        assertEquals(selection, repository.state.value.selection)
        assertNull(repository.state.value.toolOutput)

        transport.result(pending, toolOutputData(0, 2, "hi".encodeToByteArray()))
        runCurrent()
        assertNull(repository.state.value.toolOutput)
        assertTrue(repository.state.value.connected)
    }

    private fun child(id: String, parentId: String, status: String) =
        JsonObject(session(id, origin = "tui", status = status) + ("parentSessionId" to JsonPrimitive(parentId)))

    @Test
    fun abortSessionTargetsOnlyARunningChildOfTheSelection() = runTest {
        val transport = Transport()
        configure(
            transport,
            sessions = listOf(
                session(status = "running"),
                child("child", "session", "running"),
                child("idle-child", "session", "idle"),
                child("stranger", "elsewhere", "running"),
                session("sibling", status = "running"),
            ),
            opened = session(status = "running"),
        )
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        repository.abortSession("stranger")
        repository.abortSession("sibling")
        repository.abortSession("idle-child")
        repository.abortSession("session")
        repository.abortSession("child")
        runCurrent()
        val aborts = transport.sent.filter { it.text("type") == "session.abort" }
        assertEquals(listOf("child"), aborts.map { it.text("sessionId") })
    }

    @Test
    fun refreshSessionsIsRateLimited() = runTest {
        val transport = Transport()
        var sessions = listOf(session(), child("child", "session", "running"))
        configure(transport)
        val original = transport.response
        transport.response = { request ->
            if (request.text("type") == "sessions.list") data("sessions", sessions) else original(request)
        }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        val before = transport.sent.count { it.text("type") == "sessions.list" }

        sessions = listOf(session(), child("child", "session", "idle"))
        repository.refreshSessions()
        runCurrent()
        repository.refreshSessions()
        runCurrent()
        assertEquals(before + 1, transport.sent.count { it.text("type") == "sessions.list" })
        assertEquals(
            "idle",
            repository.state.value.sessions.single { it.text("id") == "child" }.text("status"),
        )

        advanceTimeBy(5_001)
        repository.refreshSessions()
        runCurrent()
        assertEquals(before + 2, transport.sent.count { it.text("type") == "sessions.list" })
    }

    @Test
    fun compactionEventsReachStateAndResetOnSelection() = runTest {
        val running = insight("compaction-running")
        val sessionId = running.text("sessionId")
        val transport = Transport()
        configure(
            transport,
            sessions = listOf(session(sessionId), session("other")),
            opened = session(sessionId),
        )
        val original = transport.response
        transport.response = { request ->
            if (request.text("type") == "session.snapshot" && request.text("sessionId") == sessionId)
                JsonObject(original(request)!! + ("revision" to JsonPrimitive(14)))
            else original(request)
        }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", sessionId))
        transport.listener.message(running)
        runCurrent()
        assertEquals("threshold", repository.state.value.compaction?.reason)
        val snapshots = transport.sent.count { it.text("type") == "session.snapshot" }
        transport.listener.message(insight("compaction-done"))
        runCurrent()
        assertNull(repository.state.value.compaction)
        assertEquals(snapshots, transport.sent.count { it.text("type") == "session.snapshot" })

        transport.listener.message(JsonObject(running + ("revision" to JsonPrimitive(17))))
        runCurrent()
        assertNotNull(repository.state.value.compaction)
        repository.activate(RemoteSelection("host", "project", "other"))
        runCurrent()
        assertNull(repository.state.value.compaction)
    }

    @Test
    fun manualCompactionDispatchesWithoutChangingTheDraft() = runTest {
        val transport = Transport()
        configure(transport)
        transport.capabilities = setOf(COMPACT_CAPABILITY)
        val original = transport.response
        transport.response = { request ->
            if (request.text("type") == "session.compact") null else original(request)
        }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        repository.draft("Keep this draft")
        repository.compactContext()
        runCurrent()
        assertEquals("Keep this draft", repository.state.value.draft)
        assertEquals(1, transport.sent.count { it.text("type") == "session.compact" })
        assertTrue(repository.state.value.compactionRequesting)
        repository.compactContext()
        runCurrent()
        assertEquals(1, transport.sent.count { it.text("type") == "session.compact" })
        val compact = transport.sent.last { it.text("type") == "session.compact" }
        transport.result(compact, Wire.objectOf("kind" to "accepted", "sessionId" to "session"))
        runCurrent()
        assertFalse(repository.state.value.compactionRequesting)
    }

    private fun forking(
        transport: Transport,
        result: (JsonObject) -> JsonObject? = { request ->
            Wire.objectOf(
                "kind" to "fork",
                "session" to session("fork"),
                "sourceSessionId" to request.text("sessionId"),
                "mode" to request.text("mode"),
                if (request.text("mode") == "edit") ("text" to "edited") else ("resend" to "accepted"),
            )
        },
    ) {
        configure(transport, sessions = listOf(session(), session("fork")))
        advertising(transport, JsonArray(listOf(JsonPrimitive(SESSION_FORK_CAPABILITY))))
        val original = transport.response
        transport.response = { request ->
            if (request.text("type") == "session.fork") result(request) else original(request)
        }
    }

    @Test
    fun forkNeedsTheRouteCapabilityAndSendsNothingWithoutIt() = runTest {
        val transport = Transport().also(::configure)
        val repository = repository(transport)
        val source = RemoteSelection("host", "project", "session")
        repository.activate(source)
        assertEquals(source, repository.forkSession(source, "user-1", ForkMode.RETRY))
        assertTrue(transport.sent.none { it.text("type") == "session.fork" })
    }

    @Test
    fun editForkSelectsTheForkAndPrefillsItsComposerWithTextAndQuote() = runTest {
        val quote = MessageQuote("assistant-2", "assistant", "earlier answer")
        val transport = Transport()
        forking(transport) { request ->
            Wire.objectOf(
                "kind" to "fork",
                "session" to session("fork"),
                "sourceSessionId" to request.text("sessionId"),
                "mode" to "edit",
                "text" to QuoteCodec.encode("second question", quote),
            )
        }
        val drafts = Drafts()
        val repository = repository(transport, drafts)
        val source = RemoteSelection("host", "project", "session")
        repository.activate(source)
        val fork = repository.forkSession(source, "user-3", ForkMode.EDIT)
        runCurrent()
        assertEquals(RemoteSelection("host", "project", "fork"), fork)
        val request = transport.sent.single { it.text("type") == "session.fork" }
        assertEquals(
            setOf("type", "requestId", "sessionId", "messageId", "mode"),
            request.keys,
        )
        assertEquals("session", request.text("sessionId"))
        assertEquals("user-3", request.text("messageId"))
        assertEquals("edit", request.text("mode"))
        val state = repository.state.value
        assertEquals(fork, state.selection)
        assertEquals("fork", state.session?.text("id"))
        assertEquals("second question", state.draft)
        assertEquals(quote, state.quote)
        assertNull(state.error)
        repository.flush()
        assertEquals("second question", drafts.values[DraftKey("host", "fork")]?.text)
        assertNull(drafts.values[DraftKey("host", "session")]?.text?.ifEmpty { null })
    }

    @Test
    fun retryForkReportsAFailedResendButKeepsTheFork() = runTest {
        val transport = Transport()
        forking(transport) { request ->
            Wire.objectOf(
                "kind" to "fork",
                "session" to session("fork"),
                "sourceSessionId" to request.text("sessionId"),
                "mode" to "retry",
                "resend" to "failed",
            )
        }
        val repository = repository(transport)
        val source = RemoteSelection("host", "project", "session")
        repository.activate(source)
        val fork = repository.forkSession(source, "user-1", ForkMode.RETRY)
        assertEquals(RemoteSelection("host", "project", "fork"), fork)
        assertEquals("retry", transport.sent.single { it.text("type") == "session.fork" }.text("mode"))
        assertEquals(R.string.remote_fork_resend_failed, repository.state.value.error)
        assertEquals("", repository.state.value.draft)
    }

    @Test
    fun refusedForkReturnsToTheSourceChatWithTheBusyHint() = runTest {
        val transport = Transport()
        forking(transport)
        transport.failure = { if (it.text("type") == "session.fork") "busy" else null }
        val repository = repository(transport)
        val source = RemoteSelection("host", "project", "session")
        repository.activate(source)
        assertEquals(source, repository.forkSession(source, "user-1", ForkMode.EDIT))
        assertEquals(source, repository.state.value.selection)
        assertEquals("session", repository.state.value.session?.text("id"))
        assertEquals(R.string.remote_fork_busy, repository.state.value.error)
        assertFalse(repository.state.value.loading)
    }

    @Test
    fun failedForkOfAnOfflineTerminalChatNeverLeavesTheSource() = runTest {
        var listed = session(origin = "tui")
        val transport = Transport()
        forking(transport)
        val original = transport.response
        transport.response = { request ->
            when (request.text("type")) {
                "sessions.list" -> data("sessions", listOf(listed))
                else -> original(request)
            }
        }
        transport.failure = { if (it.text("type") == "session.fork") "not_found" else null }
        val repository = repository(transport)
        val selection = RemoteSelection("host", "project", "session")
        assertEquals(selection, repository.activate(selection))
        // The terminal quits while the chat is open; restoring it now would return the parent.
        listed = session(origin = "tui", status = "offline")
        transport.listener.message(
            Wire.objectOf(
                "type" to "event",
                "sessionId" to "session",
                "revision" to 1,
                "kind" to "session.status",
                "status" to "offline",
            )
        )
        runCurrent()
        assertEquals("offline", repository.state.value.status)
        val before = transport.sent.size
        assertEquals(selection, repository.forkSession(selection, "user-1", ForkMode.EDIT))
        runCurrent()
        // The fork goes out first, from the chat; afterwards only the chat's own work restarts.
        val sent = transport.sent.drop(before).map { it.text("type") }
        assertEquals("session.fork", sent.first())
        assertTrue(sent.drop(1).all { it in setOf("session.snapshot", "sessions.list") })
        assertEquals(selection, repository.state.value.selection)
        assertEquals("session", repository.state.value.session?.text("id"))
        assertEquals(R.string.remote_request_error, repository.state.value.error)
        assertFalse(repository.state.value.loading)
    }

    @Test
    fun uncertainRetryShowsTheNeutralNotice() = runTest {
        val transport = Transport()
        forking(transport) { request ->
            Wire.objectOf(
                "kind" to "fork",
                "session" to session("fork"),
                "sourceSessionId" to request.text("sessionId"),
                "mode" to "retry",
                "resend" to "uncertain",
            )
        }
        val repository = repository(transport)
        val source = RemoteSelection("host", "project", "session")
        repository.activate(source)
        assertEquals(
            RemoteSelection("host", "project", "fork"),
            repository.forkSession(source, "user-1", ForkMode.RETRY),
        )
        assertEquals(R.string.remote_fork_resend_uncertain, repository.state.value.error)
    }

    @Test
    fun refusedForkReloadsTheConfigurationItsCancellationDropped() = runTest {
        val transport = Transport().also(::capable)
        transport.capabilities = transport.capabilities + SESSION_FORK_CAPABILITY
        val original = transport.response
        var answerConfiguration = false
        transport.response = { request ->
            when (request.text("type")) {
                "session.configuration.get" ->
                    if (answerConfiguration) original(request) else null
                "session.fork" -> null
                else -> original(request)
            }
        }
        transport.failure = { if (it.text("type") == "session.fork") "busy" else null }
        val repository = repository(transport)
        val source = RemoteSelection("host", "project", "session")
        repository.activate(source)
        runCurrent()
        assertTrue(repository.state.value.configurationLoading)
        assertNull(repository.state.value.configuration)
        answerConfiguration = true
        val before = transport.sent.count { it.text("type") == "session.configuration.get" }
        assertEquals(source, repository.forkSession(source, "user-1", ForkMode.EDIT))
        runCurrent()
        assertEquals(
            before + 1,
            transport.sent.count { it.text("type") == "session.configuration.get" },
        )
        assertEquals("model", repository.state.value.configuration?.model?.id)
        assertFalse(repository.state.value.configurationLoading)
        assertEquals(R.string.remote_fork_busy, repository.state.value.error)
        assertEquals(source, repository.state.value.selection)
    }

    private fun child(status: String = "idle") =
        JsonObject(session("child", origin = "tui", status = status) + ("parentSessionId" to JsonPrimitive("parent")))

    private suspend fun TestScope.childRepository(
        transport: Transport,
        status: String = "idle",
        capabilities: Set<String> = setOf(SUBAGENT_CONTROL_CAPABILITY),
        drafts: Drafts = Drafts(),
        control: (JsonObject) -> JsonObject? = { null },
    ): DefaultRemoteRepository {
        configure(transport, listOf(session("parent"), child(status)), child(status))
        transport.capabilities = capabilities
        val normal = transport.response
        transport.response = { request -> control(request) ?: normal(request) }
        return repository(transport, drafts).also {
            it.activate(RemoteSelection("host", "project", "child"))
            if (status == "running")
                transport.listener.message(Wire.objectOf("type" to "event", "sessionId" to "child",
                    "revision" to 1, "kind" to "session.status", "status" to "running"))
        }
    }

    private fun controlResult(status: String, vararg extra: Pair<String, Any?>) =
        Wire.objectOf("kind" to "subagent.control", "sessionId" to "child", "status" to status, *extra)

    @Test
    fun runningChildTakesSteerAndFollowUpAndOffersResumeWhenNotRunning() = runTest {
        val transport = Transport()
        val drafts = Drafts()
        val repository = childRepository(transport, "running", drafts = drafts) { request ->
            if (request.text("type") == "session.prompt")
                Wire.objectOf("kind" to "accepted", "sessionId" to "child")
            else null
        }
        assertTrue(canSteer(repository.state.value) && canFollowUp(repository.state.value))
        assertFalse(offersChildResume(repository.state.value))
        repository.draft("Look at the tests first")
        repository.steer()
        runCurrent()
        val steer = transport.sent.last { it.text("type") == "session.prompt" }
        assertEquals("child", steer.text("sessionId"))
        assertEquals("steer", steer.text("delivery"))
        assertEquals("accepted", repository.state.value.followUps.single().status)

        transport.failure = { if (it.text("type") == "session.prompt") "not_running" else null }
        repository.draft("Also check the docs")
        repository.followUp()
        runCurrent()
        val followUp = transport.sent.last { it.text("type") == "session.prompt" }
        assertEquals("follow_up", followUp.text("delivery"))
        val state = repository.state.value
        assertNull(state.error)
        assertFalse(state.uncertain)
        assertEquals("Also check the docs", state.draft)
        assertEquals(ChildControlPhase.NOT_RUNNING, childControl(state)?.phase)
        assertTrue(offersChildResume(state))
        assertEquals(R.string.remote_child_not_running, childControlStatus(state))
        val stored = drafts.values.getValue(DraftKey("host", "child"))
        assertEquals("Also check the docs", stored.text)
        assertNull(stored.mutationId)
        assertNull(stored.submittedText)
    }

    @Test
    fun childWithoutCapabilityKeepsMacLocalBehaviour() = runTest {
        val transport = Transport()
        val repository = childRepository(transport, "running", capabilities = setOf(STEER_CAPABILITY))
        assertFalse(canSteer(repository.state.value))
        repository.draft("Steer")
        repository.steer()
        repository.stopChild()
        runCurrent()
        assertTrue(transport.sent.none { it.text("type") in setOf("session.prompt", "session.subagent.stop") })
    }

    @Test
    fun stopChildSendsStopAndMarksStoppedByYou() = runTest {
        val transport = Transport()
        var answer = controlResult("accepted", "agentId" to "a1")
        val repository = childRepository(transport, "running") { request ->
            if (request.text("type") == "session.subagent.stop") answer else null
        }
        repository.stopChild()
        assertEquals(ChildControl(ChildControlPhase.STOPPING), childControl(repository.state.value))
        runCurrent()
        val stop = transport.sent.single { it.text("type") == "session.subagent.stop" }
        assertEquals(setOf("type", "requestId", "sessionId"), stop.keys)
        assertEquals("child", stop.text("sessionId"))
        assertEquals(ChildControl(ChildControlPhase.STOPPED_BY_YOU, agentId = "a1"),
            childControl(repository.state.value))
        assertNull(childControlStatus(repository.state.value))
        transport.listener.message(Wire.objectOf("type" to "event", "sessionId" to "child",
            "revision" to 2, "kind" to "session.status", "status" to "idle"))
        assertEquals(R.string.remote_child_stopped_by_you, childControlStatus(repository.state.value))
        assertTrue(offersChildResume(repository.state.value))

        answer = controlResult("refused", "reason" to "Reviewer diff changed")
        transport.listener.message(Wire.objectOf("type" to "event", "sessionId" to "child",
            "revision" to 3, "kind" to "session.status", "status" to "running"))
        repository.stopChild()
        assertEquals(ChildControl(ChildControlPhase.STOPPING, agentId = "a1"),
            childControl(repository.state.value))
        runCurrent()
        // A refusal keeps the agent the accepted stop named.
        assertEquals(ChildControl(ChildControlPhase.REFUSED, "Reviewer diff changed", "a1"),
            childControl(repository.state.value))
    }

    @Test
    fun stopConfirmedAfterTheChildWentIdleSaysNothingWasStopped() = runTest {
        val transport = Transport()
        val repository = childRepository(transport, "running")
        transport.listener.message(Wire.objectOf("type" to "event", "sessionId" to "child",
            "revision" to 2, "kind" to "session.status", "status" to "idle"))
        val sent = transport.sent.size
        repository.stopChild()
        runCurrent()
        assertEquals(sent, transport.sent.size)
        assertEquals(R.string.remote_child_stop_not_running, repository.state.value.error)
        assertNull(childControl(repository.state.value))
    }

    @Test
    fun stopConfirmedAfterTheChildWentOfflineSaysItIsOffline() = runTest {
        val transport = Transport()
        val repository = childRepository(transport, "running")
        transport.listener.message(Wire.objectOf("type" to "event", "sessionId" to "child",
            "revision" to 2, "kind" to "session.status", "status" to "offline"))
        val sent = transport.sent.size
        repository.stopChild()
        runCurrent()
        assertEquals(sent, transport.sent.size)
        assertEquals(R.string.remote_child_stop_child_offline, repository.state.value.error)
    }

    @Test
    fun stopConfirmedAfterTheParentTurnedOutTooOldSaysNothingWasStopped() = runTest {
        val transport = Transport()
        val repository = childRepository(transport, "running")
        // Another stop already met the old parent while this stop's dialog was still open.
        transport.failure = { if (it.text("type") == "session.subagent.stop") "unsupported" else null }
        repository.stopChild()
        runCurrent()
        assertFalse(childControlsAvailable(repository.state.value))
        repository.dismissError()
        val sent = transport.sent.size
        repository.stopChild()
        runCurrent()
        assertEquals(sent, transport.sent.size)
        assertEquals(R.string.remote_child_stop_unavailable, repository.state.value.error)
    }

    @Test
    fun resumeChildSendsMessageAndHandlesEachOutcome() = runTest {
        val transport = Transport()
        var answer: JsonObject? = controlResult("refused", "reason" to "Outside the child's folder")
        val repository = childRepository(transport) { request ->
            if (request.text("type") == "session.subagent.resume") answer else null
        }
        assertTrue(offersChildResume(repository.state.value))
        assertEquals(R.string.remote_child_idle, childControlStatus(repository.state.value))
        repository.draft("Continue with step two")
        repository.resumeChild("Continue with step two")
        assertEquals(ChildControlPhase.RESUMING, childControl(repository.state.value)?.phase)
        runCurrent()
        val resume = transport.sent.last { it.text("type") == "session.subagent.resume" }
        assertEquals(setOf("type", "requestId", "sessionId", "message"), resume.keys)
        assertEquals("child", resume.text("sessionId"))
        assertEquals("Continue with step two", resume.text("message"))
        assertEquals(ChildControl(ChildControlPhase.REFUSED, "Outside the child's folder"),
            childControl(repository.state.value))
        assertEquals("Continue with step two", repository.state.value.draft)

        answer = controlResult("not_found")
        repository.resumeChild("Continue with step two")
        runCurrent()
        assertEquals(ChildControlPhase.NOT_FOUND, childControl(repository.state.value)?.phase)
        assertEquals("Continue with step two", repository.state.value.draft)

        answer = null
        transport.failure = { if (it.text("type") == "session.subagent.resume") "internal" else null }
        repository.resumeChild("Continue with step two")
        runCurrent()
        assertEquals(ChildControlPhase.UNCERTAIN, childControl(repository.state.value)?.phase)
        assertNull(repository.state.value.error)

        transport.failure = { if (it.text("type") == "session.subagent.resume") "invalid_request" else null }
        repository.resumeChild("Continue with step two")
        runCurrent()
        assertEquals(R.string.remote_request_error, repository.state.value.error)
        assertEquals(ChildControlPhase.UNCERTAIN, childControl(repository.state.value)?.phase)
        repository.dismissError()

        transport.failure = { null }
        answer = controlResult("accepted", "agentId" to "agent-2")
        repository.resumeChild("Continue with step two")
        runCurrent()
        assertEquals(ChildControl(ChildControlPhase.RESUMED, agentId = "agent-2"),
            childControl(repository.state.value))
        assertEquals("", repository.state.value.draft)
        assertNull(repository.state.value.error)

        // The next resume names the agent the accepted one started.
        repository.draft("And step three")
        repository.resumeChild("And step three")
        runCurrent()
        assertEquals("agent-2", transport.sent.last { it.text("type") == "session.subagent.resume" }.text("agentId"))

        // A parent whose extension predates subagent control: the child loses its phone controls.
        transport.failure = { if (it.text("type") == "session.subagent.resume") "unsupported" else null }
        repository.draft("And step four")
        repository.resumeChild("And step four")
        runCurrent()
        assertEquals(R.string.remote_child_unsupported, repository.state.value.error)
        assertFalse(childControlsAvailable(repository.state.value))
        assertFalse(offersChildResume(repository.state.value))
    }

    @Test
    fun resumeChildReportsWhatItCannotSend() = runTest {
        val transport = Transport()
        val repository = childRepository(transport)
        repository.draft("/review")
        repository.resumeChild("/review")
        assertEquals(R.string.remote_child_command_unsupported, repository.state.value.error)
        repository.dismissError()
        val long = "a".repeat(CHILD_RESUME_MAX_BYTES + 1)
        repository.draft(long)
        repository.resumeChild(long)
        assertEquals(R.string.remote_prompt_too_long, repository.state.value.error)
        runCurrent()
        assertTrue(transport.sent.none { it.text("type") == "session.subagent.resume" })
    }

    @Test
    fun childControlWithoutAReplyAfterSendingIsUncertain() = runTest {
        val transport = Transport()
        var answer: JsonObject? = null
        val repository = childRepository(transport, "running") { request ->
            if (request.text("type") == "session.subagent.stop") answer else null
        }
        // Timed out.
        repository.stopChild()
        runCurrent()
        advanceTimeBy(31_000)
        runCurrent()
        assertEquals(ChildControlPhase.UNCERTAIN, childControl(repository.state.value)?.phase)
        assertNull(repository.state.value.error)

        // A malformed reply.
        answer = Wire.objectOf("kind" to "accepted", "sessionId" to "child")
        repository.stopChild()
        runCurrent()
        assertEquals(ChildControlPhase.UNCERTAIN, childControl(repository.state.value)?.phase)
        assertNull(repository.state.value.error)

        // A definite host error keeps the earlier state.
        transport.failure = { if (it.text("type") == "session.subagent.stop") "invalid_request" else null }
        repository.stopChild()
        runCurrent()
        assertEquals(R.string.remote_request_error, repository.state.value.error)
        assertEquals(ChildControlPhase.UNCERTAIN, childControl(repository.state.value)?.phase)
        repository.dismissError()

        // The selection changes while the stop is out.
        transport.failure = { null }
        answer = null
        repository.stopChild()
        runCurrent()
        repository.activate(RemoteSelection("host", "project", "parent"))
        runCurrent()
        advanceTimeBy(31_000)
        runCurrent()
        assertEquals(ChildControlPhase.UNCERTAIN, repository.state.value.childControls["child"]?.phase)
    }

    @Test
    fun childStatusChangeDropsAStaleOutcome() = runTest {
        val transport = Transport()
        val repository = childRepository(transport) { request ->
            if (request.text("type") == "session.subagent.resume") controlResult("not_found") else null
        }
        repository.draft("Continue")
        repository.resumeChild("Continue")
        runCurrent()
        assertEquals(ChildControlPhase.NOT_FOUND, childControl(repository.state.value)?.phase)
        transport.listener.message(Wire.objectOf("type" to "event", "sessionId" to "child",
            "revision" to 1, "kind" to "session.status", "status" to "idle"))
        assertEquals(ChildControlPhase.NOT_FOUND, childControl(repository.state.value)?.phase)
        transport.listener.message(Wire.objectOf("type" to "event", "sessionId" to "child",
            "revision" to 2, "kind" to "session.status", "status" to "running"))
        assertNull(childControl(repository.state.value))
        assertNull(childControlStatus(repository.state.value))
    }

    @Test
    fun stopWithTheParentOfflineReportsItAndSendsNoAbort() = runTest {
        val transport = Transport()
        val repository = childRepository(transport, "running")
        transport.failure = { if (it.text("type") == "session.subagent.stop") "offline" else null }
        repository.stopChild()
        runCurrent()
        assertEquals("session.subagent.stop", transport.sent.last().text("type"))
        assertTrue(transport.sent.none { it.text("type") == "session.abort" })
        // The message says nothing was stopped and to try again once the parent is back.
        assertEquals(R.string.remote_child_stop_parent_offline, repository.state.value.error)
        assertNull(childControl(repository.state.value))
        assertTrue(childControlsAvailable(repository.state.value))
    }

    @Test
    fun stopFallsBackToAbortWhenTheParentIsTooOld() = runTest {
        val transport = Transport()
        val repository = childRepository(transport, "running") { request ->
            if (request.text("type") == "session.abort") Wire.objectOf("kind" to "accepted") else null
        }
        transport.failure = { if (it.text("type") == "session.subagent.stop") "unsupported" else null }
        repository.stopChild()
        runCurrent()
        assertEquals("child", transport.sent.last().text("sessionId"))
        assertEquals("session.abort", transport.sent.last().text("type"))
        // The abort stopped this run only; the parent may restart the subagent.
        assertEquals(R.string.remote_child_stopped_run_only, repository.state.value.error)
        assertNull(childControl(repository.state.value))
        assertFalse(childControlsAvailable(repository.state.value))
        assertFalse(canSteer(repository.state.value))
        assertTrue(canAbortRemoteRun(repository.state.value, "host"))
    }

    @Test
    fun stopReportsTheOldParentWhenItsAbortFailsToo() = runTest {
        val transport = Transport()
        val repository = childRepository(transport, "running")
        transport.failure = {
            when (it.text("type")) {
                "session.subagent.stop" -> "unsupported"
                "session.abort" -> "internal"
                else -> null
            }
        }
        repository.stopChild()
        runCurrent()
        assertEquals("session.abort", transport.sent.last().text("type"))
        assertEquals(R.string.remote_child_unsupported, repository.state.value.error)
    }

    @Test
    fun anOldParentVerdictLastsOnlyUntilTheSelectionOrConnectionChanges() = runTest {
        val transport = Transport()
        val repository = childRepository(transport, "running")
        transport.failure = { if (it.text("type") == "session.subagent.stop") "unsupported" else null }
        repository.stopChild()
        runCurrent()
        assertFalse(childControlsAvailable(repository.state.value))
        transport.failure = { null }

        repository.activate(RemoteSelection("host", "project", "parent"))
        runCurrent()
        repository.activate(RemoteSelection("host", "project", "child"))
        runCurrent()
        assertTrue(repository.state.value.childControlUnsupported.isEmpty())
        assertTrue(childControlsAvailable(repository.state.value))
        // The child is idle after the reselection: an old parent answers the resume the same way.
        transport.failure = { if (it.text("type") == "session.subagent.resume") "unsupported" else null }
        repository.draft("Continue")
        repository.resumeChild("Continue")
        runCurrent()
        assertFalse(childControlsAvailable(repository.state.value))
        transport.failure = { null }

        repository.setForeground(true)
        repository.setValidatedNetwork("wifi")
        runCurrent()
        transport.listener.failed(true, R.string.remote_connection_error)
        assertTrue(repository.state.value.childControlUnsupported.isEmpty())
        advanceTimeBy(1000)
        runCurrent()
        assertTrue(repository.state.value.connected)
        assertTrue(childControlsAvailable(repository.state.value))
    }

    @Test
    fun aStaleOutcomeKeepsTheResumedAgentForTheNextResume() = runTest {
        val transport = Transport()
        var answer = controlResult("accepted", "agentId" to "agent-2")
        val repository = childRepository(transport) { request ->
            if (request.text("type") == "session.subagent.resume") answer else null
        }
        repository.draft("Continue")
        repository.resumeChild("Continue")
        runCurrent()
        answer = controlResult("refused", "reason" to "Busy")
        repository.draft("Continue again")
        repository.resumeChild("Continue again")
        runCurrent()
        assertEquals(ChildControl(ChildControlPhase.REFUSED, "Busy", "agent-2"), childControl(repository.state.value))

        transport.listener.message(Wire.objectOf("type" to "event", "sessionId" to "child",
            "revision" to 1, "kind" to "session.status", "status" to "running"))
        assertEquals(ChildControl(null, agentId = "agent-2"), childControl(repository.state.value))
        assertNull(childControlStatus(repository.state.value))
        assertFalse(offersChildResume(repository.state.value))
        transport.listener.message(Wire.objectOf("type" to "event", "sessionId" to "child",
            "revision" to 2, "kind" to "session.status", "status" to "idle"))
        // The UI reads the neutral entry like no entry at all.
        assertEquals(R.string.remote_child_idle, childControlStatus(repository.state.value))
        assertTrue(offersChildResume(repository.state.value))

        answer = controlResult("accepted")
        repository.resumeChild("Continue again")
        runCurrent()
        assertEquals("agent-2", transport.sent.last { it.text("type") == "session.subagent.resume" }.text("agentId"))
    }
}
