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
        /** Runs once after the next save, as if during the write. */
        var afterSave: (() -> Unit)? = null

        /** The next load found the store unreadable and reset it, as EncryptedFileStore does. */
        var reset = false

        override fun load() = hosts

        override fun save(hosts: List<PairedHost>) {
            this.hosts = hosts
            afterSave?.also { afterSave = null }?.invoke()
        }

        override fun takeReset() = reset.also { reset = false }
    }

    private class Drafts : DraftStorage {
        var values = emptyMap<DraftKey, StoredDraft>()
        var fail = false

        /** Writes made, so a test can tell one coalesced write from several. */
        var saves = 0

        override fun load() = values.toMap()

        override fun save(drafts: Map<DraftKey, StoredDraft>) {
            if (fail) error("Disk unavailable")
            saves++
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
        /** Routes whose handshake never completes, as when that Mac is asleep. */
        var unreachable: Set<String> = emptySet()
        var failureOnConnect: Int? = null
        var response: (JsonObject) -> JsonObject? = { null }
        var failure: (JsonObject) -> String? = { null }
        var failureMessage: (JsonObject) -> String? = { null }

        /** Delivers [payload] as the real transport does: receipt is reported before the payload. */
        fun deliver(payload: JsonObject) {
            listener.inbound()
            listener.message(payload)
        }

        override fun connect(host: PairedHost) {
            connects++
            route = host.routeId
            val failure = failureOnConnect
            if (failure != null) listener.failed(true, failure)
            else if (readyOnConnect && host.routeId !in unreachable) listener.ready(capabilities)
        }

        var pairing: JsonObject? = null

        override fun pair(value: JsonObject) {
            pairing = value
        }

        override fun close() {
            closes++
        }

        override fun send(payload: JsonObject) {
            sent += payload
            val code = failure(payload)
            if (code != null) {
                deliver(
                    Wire.objectOf(
                        "type" to "result",
                        "requestId" to payload.text("requestId"),
                        "ok" to false,
                        "error" to Wire.objectOf("code" to code, "message" to (failureMessage(payload) ?: code)),
                    )
                )
                return
            }
            response(payload)?.let { result(payload, it) }
        }

        fun result(request: JsonObject, data: JsonObject) {
            deliver(
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
        hosts: List<PairedHost> = listOf(host),
        pairings: Pairings = Pairings(hosts),
        navigationStorage: NavigationSnapshotStorage? = null,
        projectHint: suspend (String, String) -> String? = { _, _ -> null },
        attachmentImporter: (suspend (String, Boolean) -> LocalAttachment)? = null,
    ) =
        DefaultRemoteRepository(
            pairings,
            drafts,
            transport,
            backgroundScope,
            StandardTestDispatcher(testScheduler),
            attachmentStorage,
            attachmentImporter,
            now = { testScheduler.currentTime },
            navigationStorage = navigationStorage,
            projectHint = projectHint,
            jitter = { 0.5 },
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
            assertTrue(discovery.text("requestId").startsWith("capabilities.v2:"))
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
        transport.deliver(Wire.objectOf("type" to "event", "sessionId" to "session",
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
        transport.deliver(
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
        // notification for a session no cache knows while the chat stays connected) merges it in
        // without a reconnect.
        leaseAdvertised = true
        repository.openNotification("host", "elsewhere")
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
            transport.deliver(
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
            transport.deliver(
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
        transport.deliver(
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
        remoteId: String = "BBBBBBBBBBBBBBBBBBBBBB",
    ) {
        capable(transport)
        transport.capabilities += ATTACHMENTS_CAPABILITY
        val original = transport.response
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

        transport.deliver(
            Wire.objectOf(
                "type" to "event", "kind" to "session.title", "sessionId" to "session",
                "revision" to 1, "title" to "Changed on Mac",
            )
        )
        assertEquals("Changed on Mac", repository.state.value.session?.text("title"))
        assertEquals("Changed on Mac", repository.state.value.sessions.single().text("title"))
        transport.deliver(
            Wire.objectOf(
                "type" to "event", "kind" to "session.title", "sessionId" to "session",
                "revision" to 0, "title" to "Stale title",
            )
        )
        assertEquals("Changed on Mac", repository.state.value.session?.text("title"))
        transport.deliver(
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

        transport.deliver(
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
        transport.deliver(
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
                transport.deliver(
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
    fun mixedBatchImportsInOneRunAndIsDoneOnlyAfterEveryUriWasRead() = runTest {
        val storage = Attachments()
        val transport = Transport().also { attachmentResponses(it, localFile()) }
        val reads = mutableListOf<Pair<String, Boolean>>()
        val second = CompletableDeferred<Unit>()
        val repository =
            repository(transport, attachmentStorage = storage) { uri, photo ->
                reads += uri to photo
                if (reads.size == 2) second.await()
                val bytes = byteArrayOf(reads.size.toByte())
                val local =
                    if (photo) localFile(bytes, "C".repeat(22)).copy(kind = "image", name = "shot.jpg", mimeType = "image/jpeg")
                    else localFile(bytes, "D".repeat(22)).copy(name = "spec.pdf", mimeType = "application/pdf")
                storage.write(local, bytes)
                local
            }
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        var done = 0
        var readsWhenDone = -1
        repository.importAttachments(
            repository.state.value.selection,
            listOf(Picked("content:shot", photo = true), Picked("content:spec.pdf", photo = false)),
        ) {
            done++
            readsWhenDone = reads.size
        }
        runCurrent()
        // The second URI is still being read: the drop's grants must stay.
        assertEquals(0, done)
        assertTrue(repository.state.value.importingAttachments)
        second.complete(Unit)
        runCurrent()
        assertEquals(listOf("content:shot" to true, "content:spec.pdf" to false), reads)
        assertEquals(1, done)
        assertEquals(2, readsWhenDone)
        assertEquals(listOf("shot.jpg", "spec.pdf"), repository.state.value.attachments.map { it.name })
        assertFalse(repository.state.value.importingAttachments)
    }

    @Test
    fun batchKeepsTheAttachmentLimitAndRefusesWhileSendingButIsAlwaysDone() = runTest {
        val local = localFile(byteArrayOf(1))
        val storage = Attachments()
        val transport = Transport().also { attachmentResponses(it, local, prompt = false) }
        val reads = mutableListOf<String>()
        val repository =
            repository(transport, attachmentStorage = storage) { uri, _ ->
                reads += uri
                storage.write(local, byteArrayOf(1))
                local
            }
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        val selection = repository.state.value.selection
        var done = 0
        repository.importAttachments(selection, (1..6).map { Picked("content:$it", photo = it % 2 == 0) }) { done++ }
        runCurrent()
        assertEquals(R.string.remote_attachment_limits, repository.state.value.error)
        assertTrue(reads.isEmpty())
        assertEquals(1, done)

        repository.draft("pending")
        repository.prompt()
        runCurrent()
        assertTrue(repository.state.value.sending)
        repository.importAttachments(selection, listOf(Picked("content:late", photo = true))) { done++ }
        runCurrent()
        assertTrue(reads.isEmpty())
        assertEquals(2, done)
        // A drop or a paste reaches this while the + button is disabled: it says why.
        assertEquals(R.string.remote_attachments_busy, repository.state.value.error)

        // Another chat's batch is refused too, and given back as well.
        repository.importAttachments(selection.copy(sessionId = "other"), listOf(Picked("content:x", false))) { done++ }
        assertEquals(3, done)
    }

    @Test
    fun cancelledBatchIsDoneOnce() = runTest {
        val storage = Attachments()
        val transport = Transport().also { attachmentResponses(it, localFile()) }
        val gate = CompletableDeferred<Unit>()
        val repository =
            repository(transport, attachmentStorage = storage) { _, _ ->
                gate.await()
                localFile()
            }
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        var done = 0
        repository.importAttachments(repository.state.value.selection, listOf(Picked("content:a", false))) { done++ }
        runCurrent()
        assertEquals(0, done)
        repository.cancelAttachmentWork()
        runCurrent()
        assertEquals(1, done)
        assertTrue(repository.state.value.attachments.isEmpty())
    }

    @Test
    fun sentImageUsesUploadedBytesAfterLocalDraftCleanupWithoutDownload() = runTest {
        val bytes = byteArrayOf(1, 2, 3)
        val local = localFile(bytes).copy(kind = "image", name = "photo.jpg", mimeType = "image/jpeg")
        val remoteId = Wire.encode(ByteArray(16) { 1 })
        val storage = Attachments()
        val transport = Transport().also { attachmentResponses(it, local, remoteId = remoteId) }
        val repository = repository(transport, attachmentStorage = storage) { _, _ ->
            storage.write(local, bytes)
            local
        }
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        repository.importAttachments(repository.state.value.selection, listOf("content:photo"), true)
        runCurrent()
        repository.prompt()
        runCurrent()
        advanceTimeBy(201)
        runCurrent()
        assertTrue(transport.sent.any { it.text("type") == "session.prompt" })
        assertFalse(storage.bytes.containsKey(local.id))
        val remote = RemoteAttachment(
            remoteId, local.name, local.kind, local.mimeType,
            local.size, local.sha256, 3_600_000,
        )
        val result = repository.readAttachment("session", remote)
        assertTrue(result is AttachmentReadResult.Loaded)
        assertArrayEquals(bytes, (result as AttachmentReadResult.Loaded).bytes)
        assertFalse(transport.sent.any { it.text("type") == "session.attachments.get" })
        assertTrue(repository.readAttachment("session", remote.copy(sha256 = "0".repeat(64))) !is AttachmentReadResult.Loaded)
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
                    transport.deliver(
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
        transport.deliver(
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
        transport.deliver(
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
                    transport.deliver(
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
        transport.deliver(
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
        // The Projects page's read of the project gets no reply, so the open reads the list itself.
        transport.response = { request ->
            if (request.text("type") == "projects.list") data("projects", listOf(project)) else null
        }
        repository.activate(RemoteSelection("host"))
        runCurrent()
        val selection = async { repository.activate(RemoteSelection("host", "project")) }
        runCurrent()
        val listRequest = transport.sent.last { it.text("type") == "sessions.list" }
        fun status(revision: Long, value: String) =
            transport.deliver(
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
            if (request.text("type") == "projects.list") JsonObject(data("projects", emptyList()) + ("capabilities" to JsonArray(emptyList())))
            else original(request)
        }

        repository.setForeground(true)
        repository.setValidatedNetwork("wifi")
        runCurrent()

        assertEquals(RemoteSelection("host"), repository.state.value.selection)
        // The first discovery went v2 and, without a list, once more v1; the refresh added one.
        assertEquals(3, transport.sent.count { it.text("type") == "projects.list" })
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

    private suspend fun TestScope.offlineRepository(transport: Transport): DefaultRemoteRepository {
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        repository.setForeground(true)
        repository.setValidatedNetwork("wifi")
        runCurrent()
        transport.failureOnConnect = R.string.remote_connection_error
        return repository
    }

    @Test
    fun reconnectKeepsRetryingWithCappedBackoffWhileRecoverable() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = offlineRepository(transport)
        val start = transport.connects
        transport.listener.failed(true, R.string.remote_connection_error)

        var elapsed = 0L
        for ((index, delay) in listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 27_000L, 27_000L, 27_000L).withIndex()) {
            advanceTimeBy(delay - 1)
            runCurrent()
            assertEquals(start + index, transport.connects)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(start + index + 1, transport.connects)
            elapsed += delay
        }
        assertFalse(repository.state.value.connected)
    }

    @Test
    fun reconnectDelayIsJitteredWithinTwentyPercentAndNeverAboveTheCap() = runTest {
        val transport = Transport().also { configure(it) }
        val low = DefaultRemoteRepository(
            Pairings(listOf(host)), Drafts(), transport, backgroundScope,
            StandardTestDispatcher(testScheduler), now = { testScheduler.currentTime },
            jitter = { 0.0 },
        )
        low.activate(RemoteSelection("host", "project", "session"))
        low.setForeground(true)
        low.setValidatedNetwork("wifi")
        runCurrent()
        val start = transport.connects
        transport.failureOnConnect = R.string.remote_connection_error
        transport.listener.failed(true, R.string.remote_connection_error)
        assertEquals(testScheduler.currentTime + 800, low.state.value.reconnectAt)
        advanceTimeBy(800)
        runCurrent()
        assertEquals(start + 1, transport.connects)
    }

    @Test
    fun reconnectPausesInTheBackgroundAndResumesOnForeground() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = offlineRepository(transport)
        transport.listener.failed(true, R.string.remote_connection_error)
        repository.setForeground(false)
        assertNull(repository.state.value.reconnectAt)
        val connects = transport.connects
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(connects, transport.connects)

        transport.failureOnConnect = null
        repository.setForeground(true)
        runCurrent()
        assertTrue(repository.state.value.connected)
    }

    @Test
    fun deniedPairingStopsReconnectAndClearsTheSchedule() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = offlineRepository(transport)
        transport.listener.failed(true, R.string.remote_connection_error)
        assertNotNull(repository.state.value.reconnectAt)
        transport.listener.failed(true, R.string.remote_denied)
        assertNull(repository.state.value.reconnectAt)
        val connects = transport.connects
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(connects, transport.connects)
    }

    @Test
    fun deniedDeviceStopsReconnectEvenWhenTheTransportAsksForOne() = runTest {
        for (error in listOf(R.string.remote_device_revoked, R.string.remote_device_paused)) {
            val transport = Transport().also { configure(it) }
            val repository = offlineRepository(transport)
            transport.listener.failed(true, R.string.remote_connection_error)
            assertNotNull(repository.state.value.reconnectAt)
            transport.listener.failed(true, error)
            assertNull(repository.state.value.reconnectAt)
            assertEquals(error, repository.state.value.error)
            val connects = transport.connects
            advanceTimeBy(120_000)
            runCurrent()
            repository.setValidatedNetwork("cellular")
            runCurrent()
            assertEquals(connects, transport.connects)
            assertEquals(error, repository.state.value.error)
        }
    }

    @Test
    fun deniedDeviceKeepsItsErrorThroughActivationAndRetriesOnlyOnRefresh() = runTest {
        val full = RemoteSelection("host", "project", "session")
        for (error in listOf(R.string.remote_device_revoked, R.string.remote_device_paused)) {
            val transport = Transport().also { configure(it) }
            val repository = repository(transport)
            transport.failureOnConnect = error
            repository.activate(full)
            runCurrent()
            assertEquals(error, repository.state.value.error)
            assertEquals("host", repository.state.value.deniedRouteId)
            assertNull(repository.state.value.reconnectAt)
            val connects = transport.connects
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(connects, transport.connects)
            assertEquals(error, repository.state.value.error)

            // Navigation restores must not retry it either, even after the card is dismissed.
            repository.dismissError()
            repository.activate(full, ActivationMode.RESTORE)
            runCurrent()
            assertEquals(connects, transport.connects)

            repository.refresh()
            runCurrent()
            assertEquals(connects + 1, transport.connects)
            assertEquals(error, repository.state.value.error)

            transport.failureOnConnect = null
            repository.refresh()
            runCurrent()
            assertTrue(repository.state.value.connected)
            assertNull(repository.state.value.deniedRouteId)
            assertEquals(full, repository.state.value.selection)
        }
    }

    @Test
    fun deniedHostOpenedFromTheListIsTargetedByRouteAndRetried() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = repository(transport)
        transport.failureOnConnect = R.string.remote_device_revoked
        repository.activate(RemoteSelection("host"))
        runCurrent()
        // Nothing was selected yet, so the error card cannot rely on the selection.
        assertNull(repository.state.value.selection.routeId)
        assertEquals("host", repository.state.value.deniedRouteId)

        transport.failureOnConnect = null
        repository.refresh()
        runCurrent()
        assertTrue(repository.state.value.connected)
        assertEquals("host", repository.state.value.selection.routeId)
        assertNull(repository.state.value.deniedRouteId)
    }

    @Test
    fun restoringTheHostsListLeavesADeniedHostAndReenablesReconnect() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = repository(transport)
        repository.setForeground(true)
        repository.setValidatedNetwork("wifi")
        transport.failureOnConnect = R.string.remote_device_paused
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        repository.dismissError()

        repository.activate(RemoteSelection(), ActivationMode.RESTORE)
        runCurrent()
        assertNull(repository.state.value.selection.routeId)

        // Reconnect is enabled again, so a failure on another host schedules a retry.
        transport.listener.failed(true, R.string.remote_connection_error)
        assertNotNull(repository.state.value.reconnectAt)
    }

    @Test
    fun restoringAHealthyHostIgnoresAnotherHostsDenial() = runTest {
        val other = PairedHost("other", "https://relay.test", "device2", "secret2", "Other")
        val transport = Transport().also { configure(it) }
        val repository = repository(transport, hosts = listOf(host, other))
        repository.setForeground(true)
        repository.setValidatedNetwork("wifi")
        transport.failureOnConnect = R.string.remote_device_revoked
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        repository.dismissError()

        transport.failureOnConnect = null
        repository.activate(RemoteSelection("other", "project"), ActivationMode.RESTORE)
        runCurrent()
        assertEquals("other", transport.route)
        assertTrue(repository.state.value.connected)
        transport.failureOnConnect = R.string.remote_connection_error
        transport.listener.failed(true, R.string.remote_connection_error)
        assertNotNull(repository.state.value.reconnectAt)
    }

    @Test
    fun pausedDeviceReconnectsOnlyWhenTheUserRetries() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = offlineRepository(transport)
        transport.listener.failed(false, R.string.remote_device_paused)
        val connects = transport.connects
        advanceTimeBy(120_000)
        runCurrent()
        repository.setValidatedNetwork("cellular")
        repository.setForeground(false)
        repository.setForeground(true)
        runCurrent()
        assertEquals(connects, transport.connects)

        transport.failureOnConnect = null
        repository.refresh()
        runCurrent()
        assertTrue(transport.connects > connects)
        assertTrue(repository.state.value.connected)
    }

    @Test
    fun reconnectAtIsSetOnFailureAndClearedOnSuccess() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = offlineRepository(transport)
        val failedAt = testScheduler.currentTime
        transport.listener.failed(true, R.string.remote_connection_error)
        assertEquals(failedAt + 1_000, repository.state.value.reconnectAt)

        transport.failureOnConnect = null
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(repository.state.value.connected)
        assertNull(repository.state.value.reconnectAt)
    }

    @Test
    fun manualRefreshResetsTheBackoff() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = offlineRepository(transport)
        transport.listener.failed(true, R.string.remote_connection_error)
        advanceTimeBy(1_000 + 2_000 + 4_000 + 8_000)
        runCurrent()
        assertEquals(16_000L, repository.state.value.reconnectAt!! - testScheduler.currentTime)

        repository.refresh()
        runCurrent()
        // The refresh attempt failed again; the schedule starts over at one second.
        assertEquals(1_000L, repository.state.value.reconnectAt!! - testScheduler.currentTime)
    }

    @Test
    fun clearerUnreachableMessageAppearsAfterThreeFailuresAndRevertsOnConnect() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = offlineRepository(transport)
        transport.listener.failed(true, R.string.remote_connection_error)
        // The first failure of a streak reconnects quietly; the second one raises the card.
        assertNull(repository.state.value.error)
        assertEquals(R.string.remote_reconnecting, repository.state.value.connection)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(R.string.remote_connection_error, repository.state.value.error)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(R.string.remote_unreachable, repository.state.value.error)

        transport.failureOnConnect = null
        advanceTimeBy(4_000)
        runCurrent()
        assertTrue(repository.state.value.connected)
        assertNull(repository.state.value.error)
    }

    @Test
    fun serviceRestartCloseRetriesImmediately() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = offlineRepository(transport)
        transport.listener.failed(true, R.string.remote_connection_error)
        advanceTimeBy(1_000 + 2_000)
        runCurrent()
        val connects = transport.connects
        transport.listener.closed(1012)
        transport.listener.failed(true, R.string.remote_connection_error)
        runCurrent()
        assertEquals(connects + 1, transport.connects)
        assertNotNull(repository.state.value.reconnectAt)
    }

    @Test
    fun repeatedServiceRestartClosesRetryImmediatelyOnlyOncePerStreak() = runTest {
        val transport = Transport().also { configure(it) }
        offlineRepository(transport)
        val start = transport.connects
        repeat(3) {
            transport.listener.closed(1012)
            transport.listener.failed(true, R.string.remote_connection_error)
            runCurrent()
        }
        // The first close retried at once (and its retry failed the same way, without the flag).
        assertEquals(start + 1, transport.connects)
    }

    @Test
    fun reconnectCapSpreadsDownwardOnly() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = DefaultRemoteRepository(
            Pairings(listOf(host)), Drafts(), transport, backgroundScope,
            StandardTestDispatcher(testScheduler), now = { testScheduler.currentTime },
            jitter = { 1.0 },
        )
        repository.activate(RemoteSelection("host", "project", "session"))
        repository.setForeground(true)
        repository.setValidatedNetwork("wifi")
        runCurrent()
        transport.failureOnConnect = R.string.remote_connection_error
        transport.listener.failed(true, R.string.remote_connection_error)
        // 1.2 s, 2.4 s, 4.8 s, 9.6 s, 19.2 s, then the capped 24 s.
        for (wait in listOf(1_200L, 2_400L, 4_800L, 9_600L, 19_200L)) {
            advanceTimeBy(wait)
            runCurrent()
        }
        assertEquals(24_000L, repository.state.value.reconnectAt!! - testScheduler.currentTime)
    }

    @Test
    fun connectTimeoutGoesOfflineAndSchedulesAReconnect() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = repository(transport)
        transport.readyOnConnect = false
        repository.setForeground(true)
        repository.setValidatedNetwork("wifi")
        val activation = async { repository.activate(RemoteSelection("host", "project", "session")) }
        advanceTimeBy(CONNECT_READY_WAIT_MILLIS + 1)
        runCurrent()
        activation.await()
        assertFalse(repository.state.value.connected)
        assertNotNull(repository.state.value.reconnectAt)
    }

    @Test
    fun activateCatchKeepsTheConnectionError() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = offlineRepository(transport)
        transport.listener.failed(true, R.string.remote_connection_error)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(R.string.remote_connection_error, repository.state.value.error)
    }

    @Test
    fun networkLossAndRefreshClearReconnectAt() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = offlineRepository(transport)
        transport.listener.failed(true, R.string.remote_connection_error)
        assertNotNull(repository.state.value.reconnectAt)
        repository.setValidatedNetwork(null)
        assertNull(repository.state.value.reconnectAt)

        repository.setValidatedNetwork("cell")
        runCurrent()
        transport.listener.failed(true, R.string.remote_connection_error)
        assertNotNull(repository.state.value.reconnectAt)
        transport.failureOnConnect = null
        repository.refresh()
        assertNull(repository.state.value.reconnectAt)
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
    fun connectGivesUpJustAfterTheTransportHandshakeTimeout() = runTest {
        val transport = Transport().also { configure(it) }
        transport.readyOnConnect = false
        val repository = repository(transport)
        val selection = RemoteSelection("host", "project", "session")

        val stalled = async { repository.activate(selection) }
        runCurrent()
        advanceTimeBy(CONNECT_READY_WAIT_MILLIS - 1)
        runCurrent()
        assertFalse(stalled.isCompleted)
        assertTrue(repository.state.value.loading)

        advanceTimeBy(1)
        runCurrent()
        assertTrue(stalled.isCompleted)
        assertFalse(repository.state.value.connected)
        // Well before the 30 s the wait used to be, and only just after the transport's 20 s.
        assertEquals(HANDSHAKE_TIMEOUT_MILLIS + 2_000, currentTime)
        // The first failure is quiet; the card follows while the Mac stays unreachable.
        assertNull(repository.state.value.error)
        advanceTimeBy(QUIET_RECONNECT_MILLIS)
        runCurrent()
        assertEquals(R.string.remote_connection_error, repository.state.value.error)
    }

    /** A connected repository on the projects list, recoverable, one second after connecting. */
    private suspend fun TestScope.listingRepository(transport: Transport): DefaultRemoteRepository {
        val repository = repository(transport)
        // The Projects page's reads of each project fail, so opening the project reads its list.
        transport.failure = { if (it.text("type") == "sessions.list") "internal" else null }
        repository.activate(RemoteSelection("host"))
        repository.setForeground(true)
        repository.setValidatedNetwork("wifi")
        runCurrent()
        transport.failure = { null }
        // Every frame so far arrived before the next request goes out.
        advanceTimeBy(1_000)
        return repository
    }

    @Test
    fun aListReadWithNoReplyAndNoFrameReconnectsAtOnceAndQuietly() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = listingRepository(transport)
        val answered = transport.response
        transport.response = { if (it.text("type") == "sessions.list") null else answered(it) }
        val closes = transport.closes
        val connects = transport.connects
        val opening = async { repository.activate(RemoteSelection("host", "project")) }
        runCurrent()
        advanceTimeBy(REQUEST_TIMEOUT_MILLIS - 1)
        runCurrent()
        assertEquals(closes, transport.closes)
        assertTrue(repository.state.value.connected)

        transport.response = answered
        advanceTimeBy(1)
        runCurrent()
        opening.await()
        // The dead socket is closed and replaced without a backoff and without an error card.
        assertEquals(closes + 1, transport.closes)
        assertEquals(connects + 1, transport.connects)
        assertTrue(repository.state.value.connected)
        assertNull(repository.state.value.error)
    }

    @Test
    fun theFirstReadAfterAReconnectDetectsADeadSocket() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host"))
        repository.setForeground(true)
        repository.setValidatedNetwork("wifi")
        runCurrent()
        val answered = transport.response
        transport.response = { if (it.text("type") == "projects.list") null else answered(it) }
        val connects = transport.connects
        transport.listener.failed(true, R.string.remote_connection_error)
        // The list goes out in the same instant as the handshake's last frame.
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(connects + 1, transport.connects)
        assertTrue(repository.state.value.connected)

        transport.response = answered
        advanceTimeBy(REQUEST_TIMEOUT_MILLIS)
        runCurrent()
        assertEquals(connects + 2, transport.connects)
        assertTrue(repository.state.value.connected)
    }

    @Test
    fun aListReadThatTimesOutAfterAFrameOnlyReportsARequestError() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = listingRepository(transport)
        val answered = transport.response
        transport.response = { if (it.text("type") == "sessions.list") null else answered(it) }
        val closes = transport.closes
        val connects = transport.connects
        val opening = async { repository.activate(RemoteSelection("host", "project")) }
        runCurrent()
        advanceTimeBy(10_000)
        // Any frame proves the socket alive: the host is slow, not gone.
        transport.deliver(Wire.objectOf("type" to "relay.notice"))
        advanceTimeBy(REQUEST_TIMEOUT_MILLIS)
        runCurrent()
        opening.await()
        assertEquals(closes, transport.closes)
        assertEquals(connects, transport.connects)
        assertTrue(repository.state.value.connected)
        assertEquals(R.string.remote_request_error, repository.state.value.error)
    }

    @Test
    fun aToolOutputReadThatTimesOutNeverEndsTheConnection() = runTest {
        val (transport, repository) = toolOutputRepository { null }
        repository.activate(RemoteSelection("host", "project", "session"))
        repository.setForeground(true)
        repository.setValidatedNetwork("wifi")
        runCurrent()
        advanceTimeBy(1_000)
        val closes = transport.closes
        repository.loadToolOutput("call")
        advanceTimeBy(REQUEST_TIMEOUT_MILLIS)
        runCurrent()
        // It may queue behind other reads on the host, so its silence says nothing about the socket.
        assertEquals(closes, transport.closes)
        assertTrue(repository.state.value.connected)
        assertEquals(ToolOutputFailure.FAILED, repository.state.value.toolOutput?.failure)
    }

    @Test
    fun aReplacedNetworkReconnectsAtOnceWithoutAnError() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = offlineRepository(transport)
        transport.failureOnConnect = null
        val connects = transport.connects
        repository.setValidatedNetwork("cellular")
        assertFalse(repository.state.value.connected)
        assertNull(repository.state.value.error)
        assertEquals(R.string.remote_reconnecting, repository.state.value.connection)
        assertEquals(testScheduler.currentTime, repository.state.value.reconnectAt)
        assertTrue(repository.state.value.stale)

        runCurrent()
        assertEquals(connects + 1, transport.connects)
        assertTrue(repository.state.value.connected)
        assertNull(repository.state.value.error)
        assertFalse(repository.state.value.stale)
        assertNull(repository.state.value.staleSince)
    }

    private val pendingQuestion = Wire.objectOf("id" to "question", "kind" to "input", "title" to "Answer")

    /**
     * A chat with a pending question, configuration, commands, advisor and a finished tool output,
     * connected and recoverable.
     */
    private suspend fun TestScope.lastKnownRepository(
        transport: Transport,
        hosts: List<PairedHost> = listOf(host),
        extraCapabilities: List<String> = emptyList(),
        sessions: List<JsonObject> = listOf(session()),
        readyCapabilities: Set<String> = emptySet(),
    ): DefaultRemoteRepository {
        configure(transport, sessions = sessions)
        advertising(transport, JsonArray(fixtureCapabilities.jsonArray + extraCapabilities.map(::JsonPrimitive)))
        transport.capabilities =
            setOf(CONFIGURATION_CAPABILITY, COMMANDS_CAPABILITY, ADVISOR_CAPABILITY) + readyCapabilities
        val original = transport.response
        transport.response = { request ->
            when (request.text("type")) {
                "session.snapshot" ->
                    JsonObject(original(request)!! + ("pendingQuestions" to JsonArray(listOf(pendingQuestion))))
                "session.configuration.get" -> configurationData(request.text("sessionId"))
                "session.commands.get" -> commandData(request.text("sessionId"))
                "session.advisor.get" -> advisorData()
                "session.tool_output.get" -> toolOutputData(0, 2, "ok".encodeToByteArray())
                else -> original(request)
            }
        }
        val repository = repository(transport, hosts = hosts)
        repository.activate(RemoteSelection("host", "project", "session"))
        repository.setForeground(true)
        repository.setValidatedNetwork("wifi")
        runCurrent()
        repository.loadToolOutput("call")
        runCurrent()
        val state = repository.state.value
        assertEquals(listOf(pendingQuestion), state.questions)
        assertNotNull(state.configuration)
        assertNotNull(state.advisor)
        assertEquals(1, state.commands.size)
        assertEquals("ok", state.toolOutput?.text)
        assertTrue(TOOL_OUTPUT_CAPABILITY in state.capabilities)
        return repository
    }

    @Test
    fun aDropKeepsTheLastKnownChatStateUntilTheReconnectReadsItAgain() = runTest {
        val transport = Transport()
        val repository = lastKnownRepository(transport)
        val before = repository.state.value
        transport.failureOnConnect = R.string.remote_connection_error
        transport.listener.failed(true, R.string.remote_connection_error)

        val offline = repository.state.value
        assertFalse(offline.connected)
        assertTrue(offline.stale)
        assertEquals(testScheduler.currentTime, offline.staleSince)
        assertEquals(R.string.remote_reconnecting, offline.connection)
        assertNull(offline.error)
        assertEquals(before.questions, offline.questions)
        assertEquals(before.configuration, offline.configuration)
        assertEquals(before.advisor, offline.advisor)
        assertEquals(before.commands, offline.commands)
        assertEquals(before.status, offline.status)
        assertEquals(before.capabilities, offline.capabilities)
        assertTrue(offline.capabilitiesKnown)
        assertEquals("ok", offline.toolOutput?.text)
        assertEquals(before.messages, offline.messages)

        // The reconnect attempt fails too: now the card shows, and the chat still stays.
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(R.string.remote_connection_error, repository.state.value.error)
        assertTrue(repository.state.value.stale)
        assertEquals(before.questions, repository.state.value.questions)
        assertEquals(before.configuration, repository.state.value.configuration)
        assertEquals(offline.staleSince, repository.state.value.staleSince)

        transport.failureOnConnect = null
        advanceTimeBy(2_000)
        runCurrent()
        val back = repository.state.value
        assertTrue(back.connected)
        assertFalse(back.stale)
        assertNull(back.staleSince)
        assertNull(back.error)
        assertEquals(RemoteSelection("host", "project", "session"), back.selection)
        assertEquals(before.questions, back.questions)
        assertEquals(before.configuration, back.configuration)
        assertEquals("ok", back.toolOutput?.text)
    }

    /** Holds every configuration, commands and advisor read from now on, so what stays shows. */
    private fun holdControls(transport: Transport) {
        val original = transport.response
        transport.response = { request ->
            if (request.text("type") in setOf("session.configuration.get", "session.commands.get", "session.advisor.get")) null
            else original(request)
        }
    }

    @Test
    fun aForcedRestoreOfTheSameChatKeepsItsPanesAndControls() = runTest {
        val transport = Transport()
        val repository =
            lastKnownRepository(
                transport,
                sessions = listOf(session(), session("other")),
                readyCapabilities = setOf(SESSION_CLOSE_CAPABILITY),
            )
        val original = transport.response
        transport.response = { request ->
            if (request.text("type") == "session.close")
                Wire.objectOf("kind" to "accepted", "sessionId" to request.text("sessionId"))
            else original(request)
        }
        repository.openChanges()
        runCurrent()
        val before = repository.state.value
        assertEquals("session", before.changes?.sessionId)
        holdControls(transport)
        val snapshots = transport.sent.count { it.text("type") == "session.snapshot" }

        // Closing another chat reads the lists again with a forced restore of this one.
        repository.closeSession("other")
        runCurrent()
        assertEquals(snapshots + 1, transport.sent.count { it.text("type") == "session.snapshot" })
        val after = repository.state.value
        assertFalse(after.loading)
        assertEquals("session", after.changes?.sessionId)
        assertEquals("ok", after.toolOutput?.text)
        assertEquals(before.configuration, after.configuration)
        assertEquals(before.commands, after.commands)
        assertEquals(before.advisor, after.advisor)
        assertEquals(listOf(pendingQuestion), after.questions)
    }

    @Test
    fun anotherChatAndBackShowsItsControlsWhileTheyLoad() = runTest {
        val transport = Transport()
        val repository = lastKnownRepository(transport, sessions = listOf(session(), session("other")))
        val before = repository.state.value
        holdControls(transport)

        repository.activate(RemoteSelection("host", "project", "other"))
        runCurrent()
        assertNull(repository.state.value.configuration)
        assertTrue(repository.state.value.commands.isEmpty())
        // The first chat's tool output stays behind with it.
        assertNull(repository.state.value.toolOutput)

        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        assertEquals(before.configuration, repository.state.value.configuration)
        assertEquals(before.commands, repository.state.value.commands)
        assertEquals(before.advisor, repository.state.value.advisor)
        assertTrue(repository.state.value.configurationLoading)
    }

    @Test
    fun eventsDuringARestoreKeepTheCachedChatAndAskForNoExtraSnapshot() = runTest {
        val transport = Transport().also {
            configure(it, sessions = listOf(session(), session("other")))
            quoteMessages(it)
        }
        val quoted = transport.response
        // Revision 5, so an event of revision 3 is older than the snapshot that follows.
        transport.response = { request ->
            val response = quoted(request)
            if (request.text("type") == "session.snapshot" && response != null)
                JsonObject(response + ("revision" to JsonPrimitive(5)))
            else response
        }
        val repository = repository(transport)
        val first = RemoteSelection("host", "project", "session")
        repository.activate(first)
        val messages = repository.state.value.messages
        assertEquals(2, messages.size)
        transport.listener.failed(false, R.string.remote_connection_error)
        runCurrent()

        val normal = transport.response
        val held = mutableListOf<JsonObject>()
        transport.response = { request ->
            if (request.text("type") == "projects.list") {
                held += request
                null
            } else normal(request)
        }
        val snapshots = transport.sent.count { it.text("type") == "session.snapshot" }
        val restoring = async { repository.activate(first, ActivationMode.RESTORE) }
        runCurrent()
        assertEquals(first, repository.state.value.selection)
        assertEquals(messages, repository.state.value.messages)

        // Another chat's status, and one of this chat that arrives ahead of its snapshot.
        for ((sessionId, revision) in listOf("other" to 1, "session" to 3))
            transport.deliver(
                Wire.objectOf(
                    "type" to "event", "kind" to "session.status", "sessionId" to sessionId,
                    "revision" to revision, "status" to "running",
                )
            )
        runCurrent()
        assertEquals(messages, repository.state.value.messages)
        // The restore's own snapshot went out alongside the project list; the events ask for none.
        assertEquals(snapshots + 1, transport.sent.count { it.text("type") == "session.snapshot" })

        transport.response = normal
        transport.result(held.single(), data("projects", listOf(project)))
        assertEquals(first, restoring.await())
        runCurrent()
        assertEquals(snapshots + 1, transport.sent.count { it.text("type") == "session.snapshot" })
        assertEquals(messages.map { it.text("id") }, repository.state.value.messages.map { it.text("id") })
    }

    @Test
    fun aColdStartKeepsTheSavedChatWhenItsSnapshotFails() = runTest {
        val transport = Transport().also { configure(it) }
        val saved = listOf(
            Wire.objectOf("id" to "a", "role" to "assistant", "text" to "Saved answer", "state" to "complete"),
        )
        val storage = object : NavigationSnapshotStorage {
            override fun load() =
                mapOf("host" to NavigationSnapshot("host", listOf(project), "project", listOf(session()), "session", saved))

            override fun save(snapshots: Map<String, NavigationSnapshot>) = Unit
        }
        // The host is busy: the snapshot of the restored chat fails with the connection up.
        transport.failure = { if (it.text("type") == "session.snapshot") "busy" else null }
        val repository = repository(transport, navigationStorage = storage)
        val chat = RemoteSelection("host", "project", "session")

        assertEquals(chat, repository.activate(chat, ActivationMode.RESTORE))
        runCurrent()
        val state = repository.state.value
        assertTrue(state.connected)
        assertEquals(chat, state.selection)
        assertEquals(saved, state.messages)
        // The one read alongside the lists is refused, and asked for once more once they confirm the chat.
        assertEquals(2, transport.sent.count { it.text("type") == "session.snapshot" })
    }

    @Test
    fun aRestoreThatFailsBeforeItsSnapshotStillAsksForOneForBufferedEvents() = runTest {
        val transport = Transport().also {
            configure(it, sessions = listOf(session(), session("other")))
            quoteMessages(it)
        }
        val repository = repository(transport)
        val chat = RemoteSelection("host", "project", "session")
        repository.activate(chat)
        val messages = repository.state.value.messages
        assertEquals(2, messages.size)
        transport.listener.failed(false, R.string.remote_connection_error)
        runCurrent()

        val quoted = transport.response
        val held = mutableListOf<JsonObject>()
        val fresh = Wire.objectOf("id" to "c", "role" to "assistant", "text" to "Third answer", "state" to "complete")
        transport.response = { request ->
            when (request.text("type")) {
                "projects.list" -> {
                    held += request
                    null
                }
                "session.snapshot" -> {
                    val response = checkNotNull(quoted(request))
                    JsonObject(
                        response + ("revision" to JsonPrimitive(5)) +
                            ("messages" to JsonArray(response.getValue("messages").jsonArray + fresh))
                    )
                }
                else -> quoted(request)
            }
        }
        val snapshots = transport.sent.count { it.text("type") == "session.snapshot" }
        val restoring = async { repository.activate(chat, ActivationMode.RESTORE) }
        runCurrent()
        assertEquals(chat, repository.state.value.selection)
        // An event that skips revisions waits for the snapshot the restore set up.
        transport.deliver(
            Wire.objectOf(
                "type" to "event", "kind" to "session.status", "sessionId" to "session",
                "revision" to 3, "status" to "running",
            )
        )
        runCurrent()
        // The restore's own snapshot went out alongside the project list; the event asks for none.
        assertEquals(snapshots + 1, transport.sent.count { it.text("type") == "session.snapshot" })

        // The project list fails with the connection up, so the restore never takes its snapshot.
        transport.deliver(
            Wire.objectOf(
                "type" to "result",
                "requestId" to held.single().text("requestId"),
                "ok" to false,
                "error" to Wire.objectOf("code" to "busy", "message" to "busy"),
            )
        )
        restoring.await()
        runCurrent()
        assertTrue(repository.state.value.connected)
        // The unconfirmed one is dropped; the chat on screen asks for its own.
        assertEquals(snapshots + 2, transport.sent.count { it.text("type") == "session.snapshot" })
        assertEquals(listOf("a", "b", "c"), repository.state.value.messages.map { it.text("id") })
    }

    @Test
    fun aRunningToolOutputDownloadStaysOnlyWhileItCanFinish() = runTest {
        val transport = Transport()
        val repository =
            lastKnownRepository(
                transport,
                sessions = listOf(session(), session("other")),
                readyCapabilities = setOf(SESSION_CLOSE_CAPABILITY),
            )
        val original = transport.response
        val chunks = mutableListOf<JsonObject>()
        val snapshots = mutableListOf<JsonObject>()
        transport.response = { request ->
            when (request.text("type")) {
                "session.close" -> Wire.objectOf("kind" to "accepted", "sessionId" to request.text("sessionId"))
                "session.tool_output.get" -> {
                    chunks += request
                    null
                }
                "session.snapshot" -> {
                    snapshots += request
                    null
                }
                else -> original(request)
            }
        }
        repository.loadToolOutput("call")
        runCurrent()
        // A forced restore of the chat keeps the download that still runs, and it finishes.
        repository.closeSession("other")
        runCurrent()
        assertTrue(repository.state.value.loading)
        assertEquals(1, snapshots.size)
        assertNotNull(repository.state.value.toolOutput)
        transport.result(chunks.removeAt(0), toolOutputData(0, 2, "ok".encodeToByteArray()))
        runCurrent()
        assertEquals("ok", repository.state.value.toolOutput?.text)

        // A download that a new activation of the same chat stops goes, instead of spinning.
        repository.loadToolOutput("call")
        runCurrent()
        assertNull(repository.state.value.toolOutput?.text)
        val again = async { repository.activate(RemoteSelection("host", "project", "session"), ActivationMode.RESTORE) }
        runCurrent()
        assertEquals(RemoteSelection("host", "project", "session"), repository.state.value.selection)
        assertNull(repository.state.value.toolOutput)
        again.cancel()
    }

    @Test
    fun aRememberedChangesPaneShowsFromTheFirstFrameOfTheReturn() = runTest {
        val transport = Transport()
        val repository =
            lastKnownRepository(
                transport,
                sessions = listOf(session(), session("other")),
                readyCapabilities = setOf(GIT_CAPABILITY),
            )
        repository.openChanges()
        runCurrent()
        assertEquals("session", repository.state.value.changes?.sessionId)
        repository.activate(RemoteSelection("host", "project", "other"))
        runCurrent()
        assertNull(repository.state.value.changes)

        val original = transport.response
        val held = mutableListOf<JsonObject>()
        transport.response = { request ->
            if (request.text("type") == "session.snapshot") {
                held += request
                null
            } else original(request)
        }
        val statuses = transport.sent.count { it.text("type") == "session.git.status" }
        val returning = async { repository.activate(RemoteSelection("host", "project", "session")) }
        runCurrent()
        // On screen, loading, before the snapshot: it never opens over a composer in use.
        val placeholder = repository.state.value.changes
        assertEquals("session", placeholder?.sessionId)
        assertTrue(placeholder?.loading == true)
        assertEquals(statuses, transport.sent.count { it.text("type") == "session.git.status" })

        transport.response = original
        transport.result(held.single(), checkNotNull(original(held.single())))
        returning.await()
        runCurrent()
        assertEquals("session", repository.state.value.changes?.sessionId)
        assertEquals(statuses + 1, transport.sent.count { it.text("type") == "session.git.status" })
    }

    @Test
    fun aRememberedPaneWhoseReturnFailsBeforeTheSnapshotStopsLoading() = runTest {
        val transport = Transport()
        val repository =
            lastKnownRepository(
                transport,
                sessions = listOf(session(), session("other")),
                readyCapabilities = setOf(GIT_CAPABILITY),
            )
        repository.openChanges()
        runCurrent()
        repository.activate(RemoteSelection("host", "project", "other"))
        runCurrent()

        transport.failure = { if (it.text("type") == "session.snapshot") "busy" else null }
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        val changes = repository.state.value.changes
        assertEquals("session", changes?.sessionId)
        // Its own read ran instead: the pane does not wait for a snapshot that never comes.
        assertTrue(transport.sent.any { it.text("type") == "session.git.status" && it.text("sessionId") == "session" })
    }

    @Test
    fun creatingASessionOrRefreshingAChatKeepsOtherChatsCached() = runTest {
        val transport = Transport().also {
            configure(it, sessions = listOf(session(), session("other")), opened = session("new"))
            quoteMessages(it)
        }
        val repository = repository(transport)
        val first = RemoteSelection("host", "project", "session")
        repository.activate(first)
        val messages = repository.state.value.messages
        assertTrue(messages.isNotEmpty())
        repository.activate(RemoteSelection("host", "project", "other"))
        assertEquals(
            RemoteSelection("host", "project", "new"),
            repository.createSession("host", "project"),
        )
        runCurrent()
        repository.refresh()
        runCurrent()

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
    fun aRunningToolOutputDownloadStopsWithTheConnection() = runTest {
        val (transport, repository) = toolOutputRepository { null }
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        repository.loadToolOutput("call")
        runCurrent()
        assertNotNull(repository.state.value.toolOutput)
        transport.listener.failed(true, R.string.remote_connection_error)
        assertNull(repository.state.value.toolOutput)
    }

    @Test
    fun theErrorCardFollowsAQuietReconnectThatTakesTooLong() = runTest {
        val transport = Transport()
        val repository = lastKnownRepository(transport)
        // The next attempt reaches nobody and hangs in the handshake.
        transport.readyOnConnect = false
        transport.listener.failed(true, R.string.remote_connection_error)
        advanceTimeBy(QUIET_RECONNECT_MILLIS - 1)
        runCurrent()
        assertNull(repository.state.value.error)
        assertEquals(R.string.remote_reconnecting, repository.state.value.connection)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(R.string.remote_connection_error, repository.state.value.error)
        assertTrue(repository.state.value.stale)
        assertEquals(listOf(pendingQuestion), repository.state.value.questions)
    }

    @Test
    fun aQuietReconnectThatSucceedsInTimeNeverRaisesTheCard() = runTest {
        val transport = Transport()
        val repository = lastKnownRepository(transport)
        transport.listener.failed(true, R.string.remote_connection_error)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(repository.state.value.connected)
        advanceTimeBy(QUIET_RECONNECT_MILLIS)
        runCurrent()
        assertNull(repository.state.value.error)
    }

    @Test
    fun switchingToAnotherMacClearsTheLastKnownState() = runTest {
        val other = PairedHost("other", "https://relay.test", "device", "secret", "Other")
        val transport = Transport()
        val repository = lastKnownRepository(transport, hosts = listOf(host, other))
        transport.failureOnConnect = R.string.remote_connection_error
        transport.listener.failed(true, R.string.remote_connection_error)
        assertTrue(repository.state.value.stale)

        transport.failureOnConnect = null
        transport.readyOnConnect = false
        val switching = async { repository.activate(RemoteSelection("other"), ActivationMode.USER_OPEN) }
        runCurrent()
        val state = repository.state.value
        assertEquals("other", transport.route)
        assertFalse(state.stale)
        assertNull(state.staleSince)
        assertEquals(R.string.remote_connecting, state.connection)
        assertTrue(state.capabilities.isEmpty())
        assertFalse(state.capabilitiesKnown)
        assertTrue(state.questions.isEmpty())
        assertNull(state.configuration)
        assertNull(state.advisor)
        assertTrue(state.commands.isEmpty())
        assertNull(state.toolOutput)
        switching.cancel()
    }

    @Test
    fun aFailureThatStopsReconnectingClearsTheLastKnownState() = runTest {
        val transport = Transport()
        val repository = lastKnownRepository(transport)
        transport.listener.failed(false, R.string.remote_device_revoked)
        val state = repository.state.value
        assertEquals(R.string.remote_device_revoked, state.error)
        assertFalse(state.stale)
        assertTrue(state.capabilities.isEmpty())
        assertTrue(state.questions.isEmpty())
        assertNull(state.configuration)
    }

    @Test
    fun mutationsAreRefusedWhileDisconnected() = runTest {
        val transport = Transport()
        val repository = lastKnownRepository(transport)
        repository.draft("hello")
        runCurrent()
        transport.failureOnConnect = R.string.remote_connection_error
        transport.listener.failed(true, R.string.remote_connection_error)
        assertTrue(repository.state.value.stale)
        val sent = transport.sent.size

        repository.prompt()
        repository.followUp()
        repository.steer()
        repository.answer("question", Wire.objectOf("value" to "yes"))
        repository.abort()
        repository.setModel("provider", "other")
        repository.setThinkingLevel("max")
        repository.changeSettings(autoCompaction = false, steeringMode = null, followUpMode = null)
        repository.setAdvisor("provider", "model", "high")
        repository.compactContext()
        repository.refreshCommands()
        repository.refreshConfiguration()
        repository.refreshAdvisor()
        runCurrent()

        assertEquals(sent, transport.sent.size)
        assertEquals("hello", repository.state.value.draft)
        assertFalse(repository.state.value.sending)
        assertTrue(repository.state.value.answering.isEmpty())
        assertFalse(repository.state.value.configurationChanging)
        assertFalse(repository.state.value.advisorChanging)

        // The same answer goes out once the Mac is back.
        transport.failureOnConnect = null
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(repository.state.value.connected)
        repository.answer("question", Wire.objectOf("value" to "yes"))
        runCurrent()
        assertEquals(1, transport.sent.count { it.text("type") == "question.answer" })
    }

    @Test
    fun aReconnectWhoseHandshakeKeepsHangingKeepsTheCardAndEscalates() = runTest {
        val transport = Transport()
        val repository = lastKnownRepository(transport)
        // The relay answers, the Mac never completes the handshake (asleep).
        transport.readyOnConnect = false
        val connects = transport.connects
        transport.listener.failed(true, R.string.remote_connection_error)
        val errors = mutableListOf<Int?>()
        repeat(200) {
            advanceTimeBy(1_000)
            runCurrent()
            errors += repository.state.value.error
        }
        // Quiet for the first seconds, then the card stays up across every timed-out retry.
        assertTrue(errors.take(4).all { it == null })
        assertTrue(errors.drop(4).all { it != null })
        assertTrue(transport.connects >= connects + 3)
        // The third failure in a row says the Mac is unreachable.
        assertEquals(R.string.remote_unreachable, repository.state.value.error)
        assertTrue(repository.state.value.stale)
        assertEquals(listOf(pendingQuestion), repository.state.value.questions)
    }

    @Test
    fun disconnectingStopsTheQuietReconnectTimer() = runTest {
        val transport = Transport()
        val repository = lastKnownRepository(transport)
        transport.readyOnConnect = false
        transport.listener.failed(true, R.string.remote_connection_error)
        assertNull(repository.state.value.error)
        repository.disconnect()
        advanceTimeBy(QUIET_RECONNECT_MILLIS * 2)
        runCurrent()
        assertNull(repository.state.value.error)
    }

    @Test
    fun aSecondFailureOfAnUnprovenConnectionShowsTheCardAtOnce() = runTest {
        val transport = Transport()
        val repository = lastKnownRepository(transport)
        transport.listener.failed(true, R.string.remote_connection_error)
        assertNull(repository.state.value.error)
        // The reconnect comes up, and its socket dies before any reply proved it.
        transport.readyOnConnect = false
        advanceTimeBy(2_000)
        runCurrent()
        transport.listener.ready(transport.capabilities)
        transport.listener.failed(true, R.string.remote_connection_error)
        assertEquals(R.string.remote_connection_error, repository.state.value.error)
        assertTrue(repository.state.value.stale)
    }

    @Test
    fun fullToolOutputWhileReconnectingSaysOffline() = runTest {
        val transport = Transport()
        val repository = lastKnownRepository(transport)
        transport.failureOnConnect = R.string.remote_connection_error
        transport.listener.failed(true, R.string.remote_connection_error)
        assertTrue(TOOL_OUTPUT_CAPABILITY in repository.state.value.capabilities)
        val sent = transport.sent.size

        repository.loadToolOutput("call")
        // Offline offers a retry; the host is not too old for it.
        assertEquals(ToolOutputFailure.OFFLINE, repository.state.value.toolOutput?.failure)
        assertEquals(sent, transport.sent.size)
    }

    @Test
    fun forkingWhileReconnectingKeepsTheSourceAndTheReconnect() = runTest {
        val transport = Transport()
        val repository =
            lastKnownRepository(
                transport,
                extraCapabilities = listOf(SESSION_FORK_CAPABILITY, SESSION_TREE_CAPABILITY),
            )
        val source = RemoteSelection("host", "project", "session")
        transport.readyOnConnect = false
        val connects = transport.connects
        transport.listener.failed(true, R.string.remote_connection_error)
        assertTrue(SESSION_FORK_CAPABILITY in repository.state.value.capabilities)
        // The reconnect's activation waits for the handshake.
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(connects + 1, transport.connects)

        assertEquals(source, repository.forkSession(source, "user-1", ForkMode.RETRY))
        assertEquals(source, repository.forkSessionAtNode(source, "node"))
        assertTrue(transport.sent.none { it.text("type") in setOf("session.fork", "session.tree.fork") })

        // The activation was left running and reads the chat once the Mac answers.
        transport.listener.ready(transport.capabilities)
        runCurrent()
        assertTrue(repository.state.value.connected)
        assertFalse(repository.state.value.stale)
        assertEquals(source, repository.state.value.selection)
    }

    @Test
    fun aRefreshThatReadsTheChatEndsTheStaleStateOfAReconnect() = runTest {
        val transport = Transport()
        val repository = lastKnownRepository(transport)
        transport.failure = { if (it.text("type") == "session.snapshot") "internal" else null }
        transport.listener.failed(true, R.string.remote_connection_error)
        advanceTimeBy(2_000)
        runCurrent()
        // Connected again, but the chat on screen could not be read.
        assertTrue(repository.state.value.connected)
        assertTrue(repository.state.value.stale)

        transport.failure = { null }
        repository.refresh()
        runCurrent()
        assertTrue(repository.state.value.connected)
        assertFalse(repository.state.value.stale)
        assertNull(repository.state.value.staleSince)
    }

    @Test
    fun requestsRecordTypeDurationSizeAndTimeout() = runTest {
        Diagnostics.clear()
        Diagnostics.clock = { testScheduler.currentTime }
        try {
            val transport = Transport().also { configure(it) }
            val repository = repository(transport)
            repository.activate(RemoteSelection("host", "project", "session"))
            val normal = transport.response
            transport.response = { if (it.text("type") == "session.prompt") null else normal(it) }
            repository.draft("keep this")
            repository.prompt()
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()

            val requests = Diagnostics.report().entries.filter { it.kind == Diagnostics.Kind.REQUEST }
            val prompt = requests.single { it.label == "session.prompt" }
            assertEquals("timeout", prompt.outcome)
            assertEquals(30_000L, prompt.durationMs)
            assertTrue(prompt.bytes > 0)
            val listed = requests.single { it.label == "sessions.list" }
            assertEquals("ok", listed.outcome)
            assertEquals(0L, listed.durationMs)
            val stats = Diagnostics.report().requests.single { it.type == "session.prompt" }
            assertEquals(1, stats.timeouts)
            // Labels are protocol words only; the draft text never reaches the record.
            assertFalse(Diagnostics.export().contains("keep this"))
        } finally {
            Diagnostics.clear()
        }
    }

    @Test
    fun requestFailuresRecordTheHostErrorCode() = runTest {
        Diagnostics.clear()
        try {
            val transport = Transport().also { configure(it) }
            transport.failure = { if (it.text("type") == "sessions.list") "not_found" else null }
            val repository = repository(transport)
            repository.activate(RemoteSelection("host", "project", "session"))

            val listed = Diagnostics.report().entries.first { it.label == "sessions.list" }
            assertEquals("error.not_found", listed.outcome)
            assertTrue(Diagnostics.report().activations.any { it.label == "activate.restore" })
        } finally {
            Diagnostics.clear()
        }
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
        // Away long enough that the resume probes the chat instead of trusting it as it is.
        advanceTimeBy(RESUME_QUIET_AWAY_MILLIS + 5_000)
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
        // Away long enough that the resume probes the chat instead of trusting it as it is.
        advanceTimeBy(RESUME_QUIET_AWAY_MILLIS + 5_000)
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

    @Test
    fun aNotificationWhoseProjectTheAppKnowsOpensWithoutWalkingTheProjects() = runTest {
        val transport = Transport().also { configure(it) }
        val listed = configuredResponse(transport)
        transport.response = { request ->
            if (request.text("type") == "projects.list") projectsData(listOf(project, Wire.objectOf("id" to "other")))
            else listed(request)
        }
        // As the home snapshot or the label worker's names know it, the caches being empty.
        val repository = repository(transport, projectHint = { route, id -> "project".takeIf { route == "host" && id == "session" } })
        assertEquals(RemoteSelection("host", "project", "session"), repository.openNotification("host", "session"))
        assertEquals(listOf("project"), transport.sent.filter { it.text("type") == "sessions.list" }.map { it.text("projectId") })
        assertEquals(1, transport.sent.count { it.text("type") == "projects.list" })
    }

    @Test
    fun aNotificationForASessionInTheCachesSendsNoListAtAll() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project"))
        repository.activate(RemoteSelection("host"))
        runCurrent()
        transport.sent.clear()
        assertEquals(RemoteSelection("host", "project", "session"), repository.openNotification("host", "session"))
        val kinds = transport.sent.map { it.text("type") }
        assertFalse(kinds.toString(), "projects.list" in kinds || "sessions.list" in kinds)
        assertTrue("sessions.open" in kinds)
    }

    @Test
    fun aColdStartFindsTheNotificationsProjectInTheSavedNavigation() = runTest {
        val transport = Transport().also { configure(it) }
        val listed = configuredResponse(transport)
        transport.response = { request ->
            if (request.text("type") == "projects.list") projectsData(listOf(namedProject, Wire.objectOf("id" to "other")))
            else listed(request)
        }
        val repository = repository(transport, navigationStorage = SavedNavigation(savedHost()))
        assertEquals("project", repository.notificationProject("host", "session"))
        assertNull("not paired", repository.notificationProject("unpaired", "session"))
        assertNull("never seen", repository.notificationProject("host", "unknown"))
        assertEquals(RemoteSelection("host", "project", "session"), repository.openNotification("host", "session"))
        assertEquals(listOf("project"), transport.sent.filter { it.text("type") == "sessions.list" }.map { it.text("projectId") })
    }

    @Test
    fun aStaleProjectHintStillFindsTheSessionByWalking() = runTest {
        val transport = Transport()
        val moved = session(projectId = "other")
        configure(transport, opened = moved)
        val listed = configuredResponse(transport)
        transport.response = { request ->
            when {
                request.text("type") == "projects.list" ->
                    data("projects", listOf(project, Wire.objectOf("id" to "other")))
                request.text("type") == "sessions.list" ->
                    data("sessions", if (request.text("projectId") == "other") listOf(moved) else emptyList())
                else -> listed(request)
            }
        }
        val repository = repository(transport, projectHint = { _, _ -> "project" })
        assertEquals(RemoteSelection("host", "other", "session"), repository.openNotification("host", "session"))
    }

    @Test
    fun theNotificationWalkReadsFourListsAtATimeAndStopsAtTheFirstHit() = runTest {
        val transport = Transport()
        val opened = session(projectId = "p1")
        configure(transport, sessions = listOf(opened), opened = opened)
        val listed = configuredResponse(transport)
        val lists = mutableListOf<JsonObject>()
        transport.response = { request ->
            when (request.text("type")) {
                "projects.list" -> projectsData((0 until 6).map { Wire.objectOf("id" to "p$it") })
                "sessions.list" -> null.also { lists += request }
                else -> listed(request)
            }
        }
        val repository = repository(transport)
        val opening = async { repository.openNotification("host", "session") }
        runCurrent()
        assertEquals(listOf("p0", "p1", "p2", "p3"), lists.map { it.text("projectId") })
        transport.result(lists[0], data("sessions", emptyList()))
        transport.result(lists[1], data("sessions", listOf(opened)))
        runCurrent()
        assertEquals(RemoteSelection("host", "p1", "session"), opening.await())
        // The later projects are never read, and the open reuses the walk's lists.
        assertEquals(4, transport.sent.count { it.text("type") == "sessions.list" })
        assertEquals(1, transport.sent.count { it.text("type") == "projects.list" })
        assertTrue(transport.sent.any { it.text("type") == "sessions.open" })
    }

    @Test
    fun aNotificationRequestUsesTheAppConnectionOnlyWhileItIsHealthy() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = repository(transport)
        assertNull("not connected", repository.withHealthyConnection("host") { error("no connection") })
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        val result = repository.withHealthyConnection("host") { it.request("question.answer", "sessionId" to "session") }
        assertEquals("accepted", result?.text("kind"))
        assertEquals("question.answer", transport.sent.last().text("type"))
        assertNull("another Mac", repository.withHealthyConnection("other") { error("another Mac") })
        advanceTimeBy(HEALTHY_CONNECTION_MILLIS)
        runCurrent()
        assertNull("quiet too long", repository.withHealthyConnection("host") { error("quiet") })
    }

    @Test
    fun aSlowAppConnectionGivesUpSoonAndAHostErrorPassesThrough() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        val listed = configuredResponse(transport)
        transport.response = { request -> if (request.text("type") == "question.answer") null else listed(request) }
        val slow = async { repository.withHealthyConnection("host") { it.request("question.answer") } }
        advanceTimeBy(HEALTHY_REQUEST_TIMEOUT_MILLIS + 1)
        runCurrent()
        assertTrue(slow.isCompleted)
        assertNull(slow.await())
        transport.failure = { request -> "already_resolved".takeIf { request.text("type") == "question.answer" } }
        val error = runCatching { repository.withHealthyConnection("host") { it.request("question.answer") } }.exceptionOrNull()
        assertEquals("already_resolved", (error as RemoteCommandException).code)
    }

    @Test
    fun aKnownNotificationForAnUnreachableMacReconnectsTheMacOnScreen() = runTest {
        val other = host.copy(routeId = "other", name = "Other")
        val transport = Transport().also { configure(it) }
        val repository = repository(transport, hosts = listOf(host, other), projectHint = { _, _ -> "project" })
        val selection = RemoteSelection("host", "project", "session")
        repository.activate(selection)
        runCurrent()
        val connects = transport.connects
        transport.unreachable = setOf("other")
        val opening = async { repository.openNotification("other", "session") }
        advanceTimeBy(CONNECT_READY_WAIT_MILLIS + 1)
        runCurrent()
        assertTrue(opening.isCompleted)
        // A failed open is no selection: the navigator goes back to the screen it left.
        assertNull(opening.await())
        // Other was tried once, without a walk that would only time out again, then Host came back.
        assertEquals(connects + 2, transport.connects)
        assertEquals("host", transport.route)
        val state = repository.state.value
        assertEquals(selection, state.selection)
        assertTrue(state.connected)
        assertFalse(state.loading)
        assertEquals("session", state.session?.text("id"))
    }

    @Test
    fun aKnownNotificationOnAColdStartWithTheMacOfflineReturnsNoSelection() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = repository(transport, navigationStorage = SavedNavigation(savedHost()))
        transport.unreachable = setOf("host")
        val opening = async { repository.openNotification("host", "session") }
        advanceTimeBy(CONNECT_READY_WAIT_MILLIS + 1)
        runCurrent()
        assertTrue(opening.isCompleted)
        // Not the empty selection the activation started from, which would collapse the saved stack.
        assertNull(opening.await())
        assertEquals(1, transport.connects)
        assertFalse(repository.state.value.loading)
    }

    private fun configuredResponse(transport: Transport): (JsonObject) -> JsonObject? = transport.response

    /** A v2 project list, which no host answers twice. */
    private fun projectsData(items: List<JsonObject>) =
        JsonObject(data("projects", items) + ("capabilities" to JsonArray(emptyList())))

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
        transport.deliver(Wire.objectOf(
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
        transport.deliver(Wire.objectOf("type" to "event", "sessionId" to "session",
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
        transport.deliver(Wire.objectOf("type" to "event", "sessionId" to "session",
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
        transport.deliver(Wire.objectOf("type" to "event", "sessionId" to "session",
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
        transport.deliver(Wire.objectOf("type" to "event", "sessionId" to "session",
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
        transport.deliver(Wire.objectOf("type" to "event", "sessionId" to "session",
            "revision" to 1, "kind" to "session.status", "status" to "running"))
        repository.draft("Change direction")
        repository.steer()
        runCurrent()
        val sent = transport.sent.last { it.text("type") == "session.prompt" }
        assertEquals("steer", sent.text("delivery"))
        assertEquals("accepted", repository.state.value.followUps.single().status)
        assertEquals("steer", repository.state.value.followUps.single().delivery)
        transport.deliver(Wire.objectOf("type" to "event", "sessionId" to "session",
            "revision" to 2, "kind" to "follow_up.status", "requestId" to sent.text("requestId"),
            "status" to "delivered"))
        assertEquals("accepted", repository.state.value.followUps.single().status)
        transport.deliver(Wire.objectOf("type" to "event", "sessionId" to "session",
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
                    transport.deliver(Wire.objectOf("type" to "event", "sessionId" to "session",
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
        fun status(revision: Int, value: String) = transport.deliver(
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
        transport.deliver(Wire.objectOf("type" to "event", "sessionId" to "session",
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
                transport.deliver(Wire.objectOf("type" to "event", "sessionId" to "session",
                    "revision" to 2, "kind" to "follow_up.status",
                    "requestId" to request.text("requestId"), "status" to "uncertain"))
                Wire.objectOf("kind" to "accepted", "sessionId" to "session")
            } else normal(request)
        }
        val repository = repository(transport, drafts)
        repository.activate(RemoteSelection("host", "project", "session"))
        transport.deliver(Wire.objectOf("type" to "event", "sessionId" to "session",
            "revision" to 1, "kind" to "session.status", "status" to "running"))
        repository.draft("same text")
        repository.followUp()
        runCurrent()
        repository.flush()
        assertEquals("uncertain", drafts.values.getValue(DraftKey("host", "session")).followUps.single().status)
    }

    @Test
    fun confirmedDeliveryUpgradesUncertaintyAndCannotBeDowngraded() = runTest {
        val drafts = Drafts()
        val transport = Transport().also(::configure)
        transport.capabilities = setOf(STEER_CAPABILITY)
        val normal = transport.response
        transport.response = { request ->
            if (request.text("type") == "session.prompt")
                Wire.objectOf("kind" to "accepted", "sessionId" to "session")
            else normal(request)
        }
        val repository = repository(transport, drafts)
        repository.activate(RemoteSelection("host", "project", "session"))
        transport.deliver(Wire.objectOf("type" to "event", "sessionId" to "session",
            "revision" to 1, "kind" to "session.status", "status" to "running"))
        repository.draft("same text")
        repository.steer()
        runCurrent()
        val id = transport.sent.last { it.text("type") == "session.prompt" }.text("requestId")
        listOf("uncertain", "delivered", "accepted", "cancelled").forEachIndexed { index, status ->
            transport.deliver(Wire.objectOf("type" to "event", "sessionId" to "session",
                "revision" to index + 2, "kind" to "steer.status", "requestId" to id, "status" to status))
        }
        repository.flush()
        assertEquals("delivered", repository.state.value.followUps.single().status)
        assertEquals("delivered", drafts.values.getValue(DraftKey("host", "session")).followUps.single().status)
    }

    @Test
    fun messageIdentityBeforeAcknowledgementSurvivesLaterAcceptedReceipt() = runTest {
        val transport = Transport().also(::configure)
        transport.capabilities = setOf(FOLLOW_UP_CAPABILITY)
        val normal = transport.response
        transport.response = { request ->
            if (request.text("type") == "session.prompt") {
                transport.deliver(Wire.objectOf("type" to "event", "sessionId" to "session",
                    "revision" to 2, "kind" to "message.upsert", "message" to Wire.objectOf(
                        "id" to "consumed", "role" to "user", "text" to "same text",
                        "remoteDelivery" to Wire.objectOf("requestId" to request.text("requestId"),
                            "delivery" to "follow_up"))))
                transport.deliver(Wire.objectOf("type" to "event", "sessionId" to "session",
                    "revision" to 3, "kind" to "follow_up.status", "requestId" to request.text("requestId"),
                    "status" to "accepted"))
                Wire.objectOf("kind" to "accepted", "sessionId" to "session")
            } else normal(request)
        }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        transport.deliver(Wire.objectOf("type" to "event", "sessionId" to "session",
            "revision" to 1, "kind" to "session.status", "status" to "running"))
        repository.draft("same text")
        repository.followUp()
        runCurrent()
        assertEquals("delivered", repository.state.value.followUps.single().status)
    }

    @Test
    fun snapshotRecoversOnlyMatchingUserDeliveryIdentity() = runTest {
        val drafts = Drafts().apply {
            values = mapOf(DraftKey("host", "session") to StoredDraft(followUps = listOf(
                PendingFollowUp("first", "same text", "uncertain", "steer"),
                PendingFollowUp("second", "same text", "uncertain", "follow_up"),
                PendingFollowUp("third", "same text", "uncertain", "steer"))))
        }
        val transport = Transport().also(::configure)
        val normal = transport.response
        fun message(id: String, requestId: String?, delivery: String = "steer", role: String = "user") =
            Wire.objectOf("id" to id, "role" to role, "text" to "same text",
                "remoteDelivery" to requestId?.let {
                    Wire.objectOf("requestId" to it, "delivery" to delivery)
                })
        transport.response = { request ->
            if (request.text("type") == "session.snapshot") Wire.objectOf(
                "kind" to "snapshot", "sessionId" to "session", "revision" to 0, "status" to "idle",
                "pendingQuestions" to JsonArray(emptyList()), "messages" to JsonArray(listOf(
                    message("one", "first"), message("two", "second", "steer"),
                    message("three", "third", role = "assistant"), message("four", null))))
            else normal(request)
        }
        val repository = repository(transport, drafts)
        repository.activate(RemoteSelection("host", "project", "session"))
        repository.flush()
        assertEquals(listOf("delivered", "uncertain", "uncertain"), repository.state.value.followUps.map { it.status })
        assertEquals(listOf("delivered", "uncertain", "uncertain"),
            drafts.values.getValue(DraftKey("host", "session")).followUps.map { it.status })
    }

    @Test
    fun snapshotAfterRestartResolvesLostAcknowledgementAndPreservesEditedDraft() = runTest {
        val drafts = Drafts().apply {
            values = mapOf(DraftKey("host", "session") to StoredDraft(
                text = "new draft", mutationId = "lost-ack", submittedText = "sent text"))
        }
        val transport = Transport().also(::configure)
        val normal = transport.response
        transport.response = { request ->
            if (request.text("type") == "session.snapshot") Wire.objectOf(
                "kind" to "snapshot", "sessionId" to "session", "revision" to 0, "status" to "idle",
                "pendingQuestions" to JsonArray(emptyList()), "messages" to JsonArray(listOf(Wire.objectOf(
                    "id" to "consumed", "role" to "user", "text" to "sent text",
                    "remoteDelivery" to Wire.objectOf("requestId" to "lost-ack", "delivery" to "follow_up")))))
            else normal(request)
        }
        val repository = repository(transport, drafts)
        repository.activate(RemoteSelection("host", "project", "session"))
        repository.flush()
        assertFalse(repository.state.value.uncertain)
        assertEquals("new draft", repository.state.value.draft)
        assertEquals("delivered", repository.state.value.followUps.single().status)
        assertEquals("sent text", repository.state.value.followUps.single().text)
        assertNull(drafts.values.getValue(DraftKey("host", "session")).mutationId)
        assertTrue(transport.sent.none { it.text("type") == "session.prompt" })
    }

    @Test
    fun lostFollowUpAcknowledgementLeavesDraftUncertainWithoutReplay() = runTest {
        val drafts = Drafts()
        val transport = Transport().also(::configure)
        transport.capabilities = setOf(FOLLOW_UP_CAPABILITY)
        val repository = repository(transport, drafts)
        val selection = RemoteSelection("host", "project", "session")
        repository.activate(selection)
        transport.deliver(Wire.objectOf("type" to "event", "sessionId" to "session",
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
            transport.deliver(Wire.objectOf("type" to "event", "sessionId" to "session",
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
        transport.deliver(Wire.objectOf("type" to "event", "sessionId" to "session",
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
                transport.deliver(
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
    fun aClearedPushTokenIsRegisteredAsNullAgainAfterReconnecting() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = repository(transport)
        val selection = RemoteSelection("host", "project", "session")
        repository.activate(selection)
        repository.setPushToken(null)
        runCurrent()

        transport.listener.failed(false, R.string.remote_connection_error)
        repository.activate(selection)
        runCurrent()

        val registrations = transport.sent.filter { it.text("type") == "push.register" }
        assertEquals(2, registrations.size)
        assertTrue(registrations.all { it["token"] == JsonNull })
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
    fun aLateComposerWriteIsFiledUnderTheChatItWasTypedIn() = runTest {
        val drafts = Drafts()
        val transport = Transport().also { configure(it, sessions = listOf(session(), session("other"))) }
        val repository = repository(transport, drafts)
        val first = RemoteSelection("host", "project", "session")
        val second = first.copy(sessionId = "other")
        repository.activate(first)
        runCurrent()
        repository.draft(first, "fix the tes")
        assertEquals("fix the tes", repository.state.value.draft)
        // Back or another chat ran before the composer's flush on the way out.
        assertEquals(second, repository.activate(second))
        runCurrent()
        repository.draft(first, "fix the test")
        assertEquals("", repository.state.value.draft)
        repository.flush()
        assertEquals("fix the test", drafts.values.getValue(DraftKey("host", "session")).text)
        repository.draft(second, "second chat")
        assertEquals("second chat", repository.state.value.draft)
        repository.activate(first)
        runCurrent()
        assertEquals("fix the test", repository.state.value.draft)
        // A chat of a host that is not paired keeps nothing.
        repository.draft(RemoteSelection("gone", "project", "session"), "lost")
        repository.flush()
        assertFalse(drafts.values.containsKey(DraftKey("gone", "session")))
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
        transport.deliver(running)
        runCurrent()
        assertEquals("threshold", repository.state.value.compaction?.reason)
        val snapshots = transport.sent.count { it.text("type") == "session.snapshot" }
        transport.deliver(insight("compaction-done"))
        runCurrent()
        assertNull(repository.state.value.compaction)
        assertEquals(snapshots, transport.sent.count { it.text("type") == "session.snapshot" })

        transport.deliver(JsonObject(running + ("revision" to JsonPrimitive(17))))
        runCurrent()
        assertNotNull(repository.state.value.compaction)
        repository.activate(RemoteSelection("host", "project", "other"))
        runCurrent()
        assertNull(repository.state.value.compaction)
    }

    @Test
    fun noticesReachTheSelectedSessionOnceAndResetOnSelection() = runTest {
        val sessionId = "session"
        val transport = Transport()
        configure(transport, sessions = listOf(session(sessionId), session("other")), opened = session(sessionId))
        val original = transport.response
        transport.response = { request ->
            if (request.text("type") == "session.snapshot" && request.text("sessionId") == sessionId)
                JsonObject(original(request)!! + ("revision" to JsonPrimitive(3)))
            else original(request)
        }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", sessionId))
        runCurrent()
        fun notice(revision: Int, message: String, session: String = sessionId) =
            Wire.objectOf(
                "type" to "event",
                "sessionId" to session,
                "revision" to revision,
                "kind" to "session.notice",
                "level" to "info",
                "message" to message,
            )
        val snapshots = transport.sent.count { it.text("type") == "session.snapshot" }
        transport.deliver(notice(4, "No artifacts in this session yet."))
        runCurrent()
        val first = repository.state.value.notice
        assertEquals("No artifacts in this session yet.", first?.message)
        // The timeline takes the notice without asking for a snapshot.
        assertEquals(snapshots, transport.sent.count { it.text("type") == "session.snapshot" })
        // A replay and another session's notice change nothing.
        transport.deliver(notice(4, "No artifacts in this session yet."))
        transport.deliver(notice(5, "elsewhere", "other"))
        runCurrent()
        assertEquals(first, repository.state.value.notice)
        transport.deliver(notice(5, "second"))
        runCurrent()
        val second = repository.state.value.notice!!
        assertEquals("second", second.message)
        // Dismissing the earlier notice keeps the newer one.
        repository.dismissNotice(first!!.id)
        assertEquals(second, repository.state.value.notice)
        repository.dismissNotice(second.id)
        assertNull(repository.state.value.notice)
        transport.deliver(notice(6, "third"))
        runCurrent()
        assertNotNull(repository.state.value.notice)
        repository.activate(RemoteSelection("host", "project", "other"))
        runCurrent()
        assertNull(repository.state.value.notice)
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

    private fun treeNode(id: String, parentId: String?, kind: String = "user", forkable: Boolean = true) =
        Wire.objectOf(
            "id" to id,
            "parentId" to parentId,
            "kind" to kind,
            "preview" to "preview $id",
            "timestamp" to "2026-01-01T12:00:00.000Z",
            "children" to 1,
            "forkable" to forkable,
        )

    private fun treeResult(sessionId: String) =
        Wire.objectOf(
            "kind" to "tree",
            "sessionId" to sessionId,
            "leafId" to "a1",
            "truncated" to false,
            "canNavigate" to true,
            "nodes" to JsonArray(listOf(treeNode("u1", null), treeNode("a1", "u1", "assistant"))),
        )

    private fun treeing(transport: Transport, result: (JsonObject) -> JsonObject? = { null }) {
        configure(transport, sessions = listOf(session(), session("fork")))
        advertising(
            transport,
            JsonArray(listOf(JsonPrimitive(SESSION_FORK_CAPABILITY), JsonPrimitive(SESSION_TREE_CAPABILITY))),
        )
        val original = transport.response
        transport.response = { request ->
            if (request.text("type").startsWith("session.tree")) result(request) else original(request)
        }
    }

    @Test
    fun treeSendsNothingWithoutTheRouteCapability() = runTest {
        val transport = Transport().also(::configure)
        val repository = repository(transport)
        val source = RemoteSelection("host", "project", "session")
        repository.activate(source)
        assertEquals(TreeLoadResult.Failed(TreeFailure.UNSUPPORTED), repository.loadSessionTree("session"))
        assertEquals(
            TreeNavigateResult.Failed(TreeFailure.UNSUPPORTED),
            repository.navigateSessionTree("session", "u1", false),
        )
        assertEquals(source, repository.forkSessionAtNode(source, "u1"))
        assertTrue(transport.sent.none { it.text("type").startsWith("session.tree") })
    }

    @Test
    fun treeLoadsTheSelectedSessionsTreeAndRejectsABrokenReply() = runTest {
        val transport = Transport()
        var broken = false
        treeing(transport) { request ->
            treeResult(request.text("sessionId")).let { if (broken) JsonObject(it + ("canNavigate" to JsonPrimitive("yes"))) else it }
        }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        val loaded = repository.loadSessionTree("session") as TreeLoadResult.Loaded
        assertEquals(listOf("u1", "a1"), loaded.tree.nodes.map { it.id })
        val request = transport.sent.single { it.text("type") == "session.tree" }
        assertEquals(setOf("type", "requestId", "sessionId"), request.keys)
        assertEquals(TreeLoadResult.Failed(TreeFailure.FAILED), repository.loadSessionTree("other"))
        broken = true
        assertEquals(TreeLoadResult.Failed(TreeFailure.PROTOCOL), repository.loadSessionTree("session"))
        transport.failure = { if (it.text("type") == "session.tree") "internal" else null }
        // A read has no unknown result.
        assertEquals(TreeLoadResult.Failed(TreeFailure.FAILED), repository.loadSessionTree("session"))
        transport.failure = { if (it.text("type") == "session.tree") "not_found" else null }
        assertEquals(TreeLoadResult.Failed(TreeFailure.NOT_FOUND), repository.loadSessionTree("session"))
    }

    private fun reloading(
        transport: Transport,
        origin: String = "rpc",
        result: (JsonObject) -> JsonObject? = { null },
    ) {
        configure(transport, sessions = listOf(session(origin = origin)))
        // The command catalog is advertised at authentication, reload on the route.
        transport.capabilities = setOf(COMMANDS_CAPABILITY)
        advertising(transport, JsonArray(listOf(JsonPrimitive(RELOAD_CAPABILITY))))
        val original = transport.response
        transport.response = { request ->
            when (request.text("type")) {
                "session.reload" -> result(request)
                "session.commands.get" -> commandData(request.text("sessionId"))
                else -> original(request)
            }
        }
    }

    private fun reloaded(request: JsonObject) =
        Wire.objectOf("kind" to "reloaded", "sessionId" to request.text("sessionId"))

    @Test
    fun reloadSendsNothingWithoutTheRouteCapabilityOrForATerminalSession() = runTest {
        val transport = Transport().also(::configure)
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        assertEquals(ReloadResult.Failed(ReloadFailure.Unsupported), repository.reloadSession("session"))
        // The terminal session has the capability, so only its origin refuses the reload.
        val terminal = Transport()
        reloading(terminal, origin = "tui", result = ::reloaded)
        val other = repository(terminal)
        other.activate(RemoteSelection("host", "project", "session"))
        assertEquals(ReloadResult.Failed(ReloadFailure.Unsupported), other.reloadSession("session"))
        assertEquals(ReloadResult.Failed(ReloadFailure.Failed(null)), other.reloadSession("elsewhere"))
        assertTrue(transport.sent.none { it.text("type") == "session.reload" })
        assertTrue(terminal.sent.none { it.text("type") == "session.reload" })
        // Positive control: the same setup with a host-owned session does send it.
        val owned = Transport()
        reloading(owned, origin = "rpc", result = ::reloaded)
        val rpc = repository(owned)
        rpc.activate(RemoteSelection("host", "project", "session"))
        assertEquals(ReloadResult.Done, rpc.reloadSession("session"))
        assertEquals(1, owned.sent.count { it.text("type") == "session.reload" })
    }

    @Test
    fun reloadRefreshesTheCatalogEvenWhenARequestIsAlreadyInFlight() = runTest {
        val transport = Transport()
        reloading(transport, result = ::reloaded)
        var hold = true
        val answering = transport.response
        transport.response = { request ->
            if (request.text("type") == "session.commands.get" && hold) null else answering(request)
        }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        assertTrue(repository.state.value.commandsLoading)
        assertTrue(repository.state.value.commands.isEmpty())
        hold = false
        val catalogs = transport.sent.count { it.text("type") == "session.commands.get" }
        assertEquals(ReloadResult.Done, repository.reloadSession("session"))
        runCurrent()
        assertEquals(catalogs + 1, transport.sent.count { it.text("type") == "session.commands.get" })
        assertFalse(repository.state.value.commandsLoading)
        assertEquals(listOf("review"), repository.state.value.commands.map { it.name })
    }

    @Test
    fun reloadAsksTheHostAndRefreshesTheChatAndTheCommandCatalog() = runTest {
        val transport = Transport()
        reloading(transport, result = ::reloaded)
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        val snapshots = transport.sent.count { it.text("type") == "session.snapshot" }
        val catalogs = transport.sent.count { it.text("type") == "session.commands.get" }
        assertEquals(ReloadResult.Done, repository.reloadSession("session"))
        runCurrent()
        val request = transport.sent.single { it.text("type") == "session.reload" }
        assertEquals(setOf("type", "requestId", "sessionId"), request.keys)
        assertEquals("session", request.text("sessionId"))
        assertEquals(snapshots + 1, transport.sent.count { it.text("type") == "session.snapshot" })
        assertEquals(catalogs + 1, transport.sent.count { it.text("type") == "session.commands.get" })
    }

    @Test
    fun reloadMapsErrorsAndRefreshesOnlyWhenTheResultIsUnknown() = runTest {
        val transport = Transport()
        reloading(transport, result = ::reloaded)
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        for ((code, failure) in
            listOf(
                "busy" to ReloadFailure.Busy,
                "unsupported" to ReloadFailure.Unsupported,
                "offline" to ReloadFailure.ConnectionFailure,
                "forbidden" to ReloadFailure.Failed(null),
            )) {
            transport.failure = { if (it.text("type") == "session.reload") code else null }
            val snapshots = transport.sent.count { it.text("type") == "session.snapshot" }
            assertEquals(code, ReloadResult.Failed(failure), repository.reloadSession("session"))
            runCurrent()
            assertEquals(code, snapshots, transport.sent.count { it.text("type") == "session.snapshot" })
        }
        transport.failure = { if (it.text("type") == "session.reload") "internal" else null }
        transport.failureMessage = { "Extension failed to load" }
        assertEquals(
            ReloadResult.Failed(ReloadFailure.Failed("Extension failed to load")),
            repository.reloadSession("session"),
        )
        // The host's generic text is no detail worth repeating.
        transport.failureMessage = { "Reload failed" }
        assertEquals(ReloadResult.Failed(ReloadFailure.Failed(null)), repository.reloadSession("session"))
        transport.failureMessage = { "Reload result unknown; refresh" }
        val snapshots = transport.sent.count { it.text("type") == "session.snapshot" }
        assertEquals(ReloadResult.Failed(ReloadFailure.Unknown), repository.reloadSession("session"))
        runCurrent()
        assertEquals(snapshots + 1, transport.sent.count { it.text("type") == "session.snapshot" })
        transport.failure = { null }
        transport.failureMessage = { null }
        // A reply that breaks the protocol is no success.
        transport.response = { request ->
            if (request.text("type") == "session.reload")
                Wire.objectOf("kind" to "reloaded", "sessionId" to "other")
            else null
        }
        assertEquals(ReloadResult.Failed(ReloadFailure.Failed(null)), repository.reloadSession("session"))
    }

    @Test
    fun navigatingPutsTheTextInTheComposerAndRefreshesTheChat() = runTest {
        val quote = MessageQuote("assistant-2", "assistant", "earlier answer")
        val transport = Transport()
        treeing(transport) { request ->
            Wire.objectOf(
                "kind" to "tree.navigated",
                "sessionId" to request.text("sessionId"),
                "leafId" to "u1",
                "text" to QuoteCodec.encode("second question", quote),
            )
        }
        val drafts = Drafts()
        val repository = repository(transport, drafts)
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        val snapshots = transport.sent.count { it.text("type") == "session.snapshot" }
        val result = repository.navigateSessionTree("session", "u2", true)
        runCurrent()
        assertEquals(TreeNavigateResult.Done(TreeNavigation("u1", QuoteCodec.encode("second question", quote))), result)
        val request = transport.sent.single { it.text("type") == "session.tree.navigate" }
        assertEquals(setOf("type", "requestId", "sessionId", "nodeId", "summarize"), request.keys)
        assertEquals("u2", request.text("nodeId"))
        assertTrue(request.flag("summarize"))
        val state = repository.state.value
        assertEquals("second question", state.draft)
        assertEquals(quote, state.quote)
        assertEquals("session", state.selection.sessionId)
        assertEquals(snapshots + 1, transport.sent.count { it.text("type") == "session.snapshot" })
        repository.flush()
        assertEquals("second question", drafts.values[DraftKey("host", "session")]?.text)
    }

    @Test
    fun navigatingWithoutTextKeepsTheDraft() = runTest {
        val transport = Transport()
        treeing(transport) { request ->
            Wire.objectOf("kind" to "tree.navigated", "sessionId" to request.text("sessionId"), "leafId" to "a1")
        }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        repository.draft("keep me")
        val snapshots = transport.sent.count { it.text("type") == "session.snapshot" }
        assertEquals(TreeNavigateResult.Done(TreeNavigation("a1", null)), repository.navigateSessionTree("session", "a1", false))
        runCurrent()
        assertEquals("keep me", repository.state.value.draft)
        assertFalse(transport.sent.single { it.text("type") == "session.tree.navigate" }.flag("summarize"))
        assertEquals(snapshots + 1, transport.sent.count { it.text("type") == "session.snapshot" })
        assertEquals(
            TreeNavigateResult.Failed(TreeFailure.INVALID),
            repository.navigateSessionTree("session", "bad id", false),
        )
    }

    @Test
    fun failedNavigationsMapTheirErrorsAndRefreshWhenTheResultIsUnknown() = runTest {
        val transport = Transport()
        treeing(transport)
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        for ((code, failure) in
            listOf(
                "busy" to TreeFailure.BUSY,
                "cancelled" to TreeFailure.CANCELLED,
                "unsupported" to TreeFailure.UNSUPPORTED,
                "not_found" to TreeFailure.NOT_FOUND,
                "invalid_request" to TreeFailure.INVALID,
                "forbidden" to TreeFailure.FAILED,
            )) {
            transport.failure = { if (it.text("type") == "session.tree.navigate") code else null }
            val snapshots = transport.sent.count { it.text("type") == "session.snapshot" }
            assertEquals(code, TreeNavigateResult.Failed(failure), repository.navigateSessionTree("session", "u1", false))
            runCurrent()
            assertEquals(code, snapshots, transport.sent.count { it.text("type") == "session.snapshot" })
        }
        // Only an `internal` that says the result is unknown, and a timeout, may have moved the leaf.
        for ((code, message) in
            listOf(
                "internal" to "Navigation result unknown; refresh",
                "timeout" to "timeout",
            )) {
            transport.failure = { if (it.text("type") == "session.tree.navigate") code else null }
            transport.failureMessage = { message }
            val snapshots = transport.sent.count { it.text("type") == "session.snapshot" }
            assertEquals(
                code,
                TreeNavigateResult.Failed(TreeFailure.UNKNOWN_RESULT),
                repository.navigateSessionTree("session", "u1", false),
            )
            runCurrent()
            assertEquals(code, snapshots + 1, transport.sent.count { it.text("type") == "session.snapshot" })
        }
        transport.failure = { if (it.text("type") == "session.tree.navigate") "internal" else null }
        transport.failureMessage = { "No model available for summarization" }
        val before = transport.sent.count { it.text("type") == "session.snapshot" }
        assertEquals(TreeNavigateResult.Failed(TreeFailure.FAILED), repository.navigateSessionTree("session", "u1", true))
        runCurrent()
        assertEquals(before, transport.sent.count { it.text("type") == "session.snapshot" })
        transport.failureMessage = { null }
        transport.failure = { null }
        // A reply that breaks the protocol is no success, and leaves the draft alone.
        transport.response = { request ->
            if (request.text("type") == "session.tree.navigate")
                Wire.objectOf("kind" to "tree.navigated", "sessionId" to "other", "leafId" to null)
            else null
        }
        assertEquals(
            TreeNavigateResult.Failed(TreeFailure.PROTOCOL),
            repository.navigateSessionTree("session", "u1", false),
        )
    }

    private fun navigated(request: JsonObject, text: String? = null) =
        Wire.objectOf(
            "kind" to "tree.navigated",
            "sessionId" to request.text("sessionId"),
            "leafId" to "u1",
            *(if (text != null) arrayOf("text" to text) else emptyArray()),
        )

    @Test
    fun navigatedTextNeverReplacesATypedDraftOrQuote() = runTest {
        val transport = Transport()
        treeing(transport) { navigated(it, "step text") }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        repository.draft("typed meanwhile")
        runCurrent()
        assertTrue(repository.navigateSessionTree("session", "u2", false) is TreeNavigateResult.Done)
        runCurrent()
        assertEquals("typed meanwhile", repository.state.value.draft)
        assertEquals(R.string.remote_tree_text_not_inserted, repository.state.value.error)
    }

    @Test
    fun navigatedTextNeverReplacesAQuote() = runTest {
        val transport = Transport()
        treeing(transport) { navigated(it, "step text") }
        quoteMessages(transport)
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        repository.quote("a")
        runCurrent()
        assertNotNull(repository.state.value.quote)
        assertTrue(repository.navigateSessionTree("session", "u2", false) is TreeNavigateResult.Done)
        assertEquals("", repository.state.value.draft)
        assertNotNull(repository.state.value.quote)
        assertEquals(R.string.remote_tree_text_not_inserted, repository.state.value.error)
    }

    @Test
    fun navigatedTextFillsABlankComposerWithoutANotice() = runTest {
        val transport = Transport()
        treeing(transport) { navigated(it, "step text") }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        repository.draft("   ")
        runCurrent()
        assertTrue(repository.navigateSessionTree("session", "u2", false) is TreeNavigateResult.Done)
        assertEquals("step text", repository.state.value.draft)
        assertNull(repository.state.value.error)
    }

    @Test
    fun aLostConnectionDuringANavigationIsAnUnknownResultButNotDuringARead() = runTest {
        val transport = Transport()
        treeing(transport)
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        val move = async { repository.navigateSessionTree("session", "u1", false) }
        runCurrent()
        repository.disconnect()
        runCurrent()
        assertEquals(TreeNavigateResult.Failed(TreeFailure.UNKNOWN_RESULT), move.await())
        val reconnected = Transport()
        treeing(reconnected)
        val again = repository(reconnected)
        again.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        val read = async { again.loadSessionTree("session") }
        runCurrent()
        again.disconnect()
        runCurrent()
        assertEquals(TreeLoadResult.Failed(TreeFailure.FAILED), read.await())
    }

    @Test
    fun aNavigationWithoutAnAnswerEndsAfterTheTimeoutAndRefreshes() = runTest {
        for ((summarize, millis) in listOf(false to 45_000L, true to 195_000L)) {
            val transport = Transport()
            treeing(transport)
            val repository = repository(transport)
            repository.activate(RemoteSelection("host", "project", "session"))
            runCurrent()
            val snapshots = transport.sent.count { it.text("type") == "session.snapshot" }
            val move = async { repository.navigateSessionTree("session", "u1", summarize) }
            runCurrent()
            advanceTimeBy(millis - 1)
            assertFalse(summarize.toString(), move.isCompleted)
            advanceTimeBy(2)
            runCurrent()
            assertEquals(TreeNavigateResult.Failed(TreeFailure.UNKNOWN_RESULT), move.await())
            assertEquals(snapshots + 1, transport.sent.count { it.text("type") == "session.snapshot" })
        }
    }

    @Test
    fun aSelectionChangeDuringANavigationLeavesEveryDraftUntouched() = runTest {
        val transport = Transport()
        treeing(transport)
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        val move = async { repository.navigateSessionTree("session", "u2", false) }
        runCurrent()
        val request = transport.sent.last { it.text("type") == "session.tree.navigate" }
        repository.activate(RemoteSelection("host", "project", "fork"))
        runCurrent()
        transport.result(request, navigated(request, "step text"))
        runCurrent()
        val outcome = try { move.await() } catch (e: CancellationException) { null }
        assertFalse(outcome is TreeNavigateResult.Done)
        assertEquals("", repository.state.value.draft)
        assertEquals("fork", repository.state.value.selection.sessionId)
        repository.flush()
    }

    @Test
    fun forkAtANodeOpensTheForkWithoutADraft() = runTest {
        val transport = Transport()
        treeing(transport) { request ->
            Wire.objectOf(
                "kind" to "fork",
                "session" to session("fork"),
                "sourceSessionId" to request.text("sessionId"),
                "mode" to "at",
            )
        }
        val repository = repository(transport)
        val source = RemoteSelection("host", "project", "session")
        repository.activate(source)
        val fork = repository.forkSessionAtNode(source, "a1")
        runCurrent()
        assertEquals(RemoteSelection("host", "project", "fork"), fork)
        val request = transport.sent.single { it.text("type") == "session.tree.fork" }
        assertEquals(setOf("type", "requestId", "sessionId", "nodeId"), request.keys)
        assertEquals("a1", request.text("nodeId"))
        val state = repository.state.value
        assertEquals(fork, state.selection)
        assertEquals("", state.draft)
        assertNull(state.quote)
        assertNull(state.error)
    }

    @Test
    fun forkAtAUserNodePrefillsTheForksComposer() = runTest {
        val transport = Transport()
        treeing(transport) { request ->
            Wire.objectOf(
                "kind" to "fork",
                "session" to session("fork"),
                "sourceSessionId" to request.text("sessionId"),
                "mode" to "edit",
                "text" to "second question",
            )
        }
        val repository = repository(transport)
        val source = RemoteSelection("host", "project", "session")
        repository.activate(source)
        val fork = repository.forkSessionAtNode(source, "u2")
        runCurrent()
        assertEquals(RemoteSelection("host", "project", "fork"), fork)
        assertEquals("second question", repository.state.value.draft)
    }

    @Test
    fun refusedOrBrokenForkAtANodeStaysInTheSourceChat() = runTest {
        val transport = Transport()
        treeing(transport) { request ->
            // `retry` is not a result of a tree fork.
            Wire.objectOf(
                "kind" to "fork",
                "session" to session("fork"),
                "sourceSessionId" to request.text("sessionId"),
                "mode" to "retry",
                "resend" to "accepted",
            )
        }
        val repository = repository(transport)
        val source = RemoteSelection("host", "project", "session")
        repository.activate(source)
        assertEquals(source, repository.forkSessionAtNode(source, "a1"))
        assertEquals(source, repository.state.value.selection)
        assertEquals(R.string.remote_request_error, repository.state.value.error)
        assertFalse(repository.state.value.loading)
        transport.failure = { if (it.text("type") == "session.tree.fork") "invalid_request" else null }
        assertEquals(source, repository.forkSessionAtNode(source, "t1"))
        assertEquals(R.string.remote_tree_fork_invalid, repository.state.value.error)
        transport.failure = { if (it.text("type") == "session.tree.fork") "busy" else null }
        assertEquals(source, repository.forkSessionAtNode(source, "a1"))
        assertEquals(R.string.remote_fork_busy, repository.state.value.error)
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
        transport.deliver(
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
                transport.deliver(Wire.objectOf("type" to "event", "sessionId" to "child",
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
        transport.deliver(Wire.objectOf("type" to "event", "sessionId" to "child",
            "revision" to 2, "kind" to "session.status", "status" to "idle"))
        assertEquals(R.string.remote_child_stopped_by_you, childControlStatus(repository.state.value))
        assertTrue(offersChildResume(repository.state.value))

        answer = controlResult("refused", "reason" to "Reviewer diff changed")
        transport.deliver(Wire.objectOf("type" to "event", "sessionId" to "child",
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
        transport.deliver(Wire.objectOf("type" to "event", "sessionId" to "child",
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
        transport.deliver(Wire.objectOf("type" to "event", "sessionId" to "child",
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
        transport.deliver(Wire.objectOf("type" to "event", "sessionId" to "child",
            "revision" to 1, "kind" to "session.status", "status" to "idle"))
        assertEquals(ChildControlPhase.NOT_FOUND, childControl(repository.state.value)?.phase)
        transport.deliver(Wire.objectOf("type" to "event", "sessionId" to "child",
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

        transport.deliver(Wire.objectOf("type" to "event", "sessionId" to "child",
            "revision" to 1, "kind" to "session.status", "status" to "running"))
        assertEquals(ChildControl(null, agentId = "agent-2"), childControl(repository.state.value))
        assertNull(childControlStatus(repository.state.value))
        assertFalse(offersChildResume(repository.state.value))
        transport.deliver(Wire.objectOf("type" to "event", "sessionId" to "child",
            "revision" to 2, "kind" to "session.status", "status" to "idle"))
        // The UI reads the neutral entry like no entry at all.
        assertEquals(R.string.remote_child_idle, childControlStatus(repository.state.value))
        assertTrue(offersChildResume(repository.state.value))

        answer = controlResult("accepted")
        repository.resumeChild("Continue again")
        runCurrent()
        assertEquals("agent-2", transport.sent.last { it.text("type") == "session.subagent.resume" }.text("agentId"))
    }

    // ---- Forward compatibility, typed errors, proven-connection backoff, pairing timeout ----

    private suspend fun TestScope.chatRepository(transport: Transport): DefaultRemoteRepository {
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        repository.setForeground(true)
        repository.setValidatedNetwork("wifi")
        runCurrent()
        return repository
    }

    private fun event(revision: Int, kind: String, vararg fields: Pair<String, Any?>) =
        Wire.objectOf("type" to "event", "sessionId" to "session", "revision" to revision, "kind" to kind, *fields)

    private fun Transport.snapshotRequests() = sent.count { it.text("type") == "session.snapshot" }

    @Test
    fun unknownPayloadTypeAndEventKindKeepTheConnectionAndResyncAtMostEveryTenSeconds() = runTest {
        val transport = Transport().also { configure(it) }
        val snapshotTimes = mutableListOf<Long>()
        val original = transport.response
        transport.response = { request ->
            if (request.text("type") == "session.snapshot") snapshotTimes += testScheduler.currentTime
            original(request)
        }
        val repository = chatRepository(transport)
        val closes = transport.closes
        snapshotTimes.clear()

        transport.deliver(Wire.objectOf("type" to "host.future", "anything" to 1))
        runCurrent()
        assertTrue(repository.state.value.connected)

        // A newer host sends a kind this app does not know, once a second for 25 s.
        val start = testScheduler.currentTime
        for (revision in 1..25) {
            transport.deliver(event(revision, "future.kind"))
            runCurrent()
            advanceTimeBy(1_000)
        }
        runCurrent()

        assertTrue(repository.state.value.connected)
        assertEquals(closes, transport.closes)
        assertNull(repository.state.value.error)
        // One snapshot right away, then one at the end of each ten-second window.
        assertEquals(listOf(start, start + 10_000, start + 20_000), snapshotTimes)

        // The revision stayed in step, so the next known event applies without a snapshot.
        transport.deliver(event(26, "session.status", "status" to "running"))
        runCurrent()
        assertEquals("running", repository.state.value.status)
        assertEquals(3, snapshotTimes.size)
    }

    @Test
    fun malformedTimelineEventRequestsASnapshotInsteadOfGoingOffline() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = chatRepository(transport)
        val snapshots = transport.snapshotRequests()

        // An upsert without its message.
        transport.deliver(event(1, "message.upsert"))
        runCurrent()

        assertTrue(repository.state.value.connected)
        assertNull(repository.state.value.error)
        assertNull(repository.state.value.reconnectAt)
        assertEquals(snapshots + 1, transport.snapshotRequests())
    }

    @Test
    fun unknownSessionStatusKeepsTheLastStatusAndRequestsOneSnapshot() = runTest {
        val transport = Transport().also { configure(it) }
        val original = transport.response
        val repository = chatRepository(transport)
        transport.deliver(event(1, "session.status", "status" to "running"))
        runCurrent()
        assertEquals("running", repository.state.value.status)
        // The resync stays unanswered, so the state shows what the event left behind.
        transport.response = { request -> if (request.text("type") == "session.snapshot") null else original(request) }
        val snapshots = transport.snapshotRequests()

        transport.deliver(event(2, "session.status", "status" to "retrying"))
        runCurrent()

        assertEquals("running", repository.state.value.status)
        assertTrue(repository.state.value.connected)
        assertNull(repository.state.value.error)
        assertEquals(snapshots + 1, transport.snapshotRequests())
    }

    @Test
    fun advisorStatusWithTheTimelineRevisionCostsNoSnapshot() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = chatRepository(transport)
        val snapshots = transport.snapshotRequests()

        transport.deliver(event(1, "advisor.status", "advisor" to Wire.objectOf()))
        runCurrent()
        transport.deliver(event(2, "session.status", "status" to "running"))
        runCurrent()

        assertTrue(repository.state.value.connected)
        assertEquals("running", repository.state.value.status)
        assertEquals(snapshots, transport.snapshotRequests())
    }

    @Test
    fun malformedFollowUpReceiptIsDroppedAndTheTimelineStaysInStep() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = chatRepository(transport)
        val snapshots = transport.snapshotRequests()

        transport.deliver(event(1, "follow_up.status", "requestId" to "request", "status" to "bogus"))
        runCurrent()
        transport.deliver(event(2, "session.status", "status" to "running"))
        runCurrent()

        assertTrue(repository.state.value.connected)
        assertNull(repository.state.value.error)
        assertEquals("running", repository.state.value.status)
        assertEquals(snapshots, transport.snapshotRequests())
    }

    @Test
    fun malformedResultFailsOnlyItsRequest() = runTest {
        val titled = JsonObject(session() + ("title" to JsonPrimitive("Old")))
        val transport = Transport().also { configure(it, sessions = listOf(titled), opened = titled) }
        transport.capabilities = setOf(RENAME_CAPABILITY)
        val repository = chatRepository(transport)
        val normal = transport.response
        transport.response = { request ->
            if (request.text("type") == "session.rename") {
                transport.deliver(
                    Wire.objectOf("type" to "result", "requestId" to request.text("requestId"), "ok" to "yes")
                )
                null
            } else normal(request)
        }

        repository.renameSession("session", "Renamed")
        runCurrent()

        assertTrue(repository.state.value.connected)
        assertEquals(R.string.remote_request_error, repository.state.value.error)
    }

    @Test
    fun unreadableFrameReconnectsLikeALostConnection() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = chatRepository(transport)

        transport.deliver(Wire.objectOf("requestId" to "no type"))

        assertFalse(repository.state.value.connected)
        assertNotNull(repository.state.value.reconnectAt)
    }

    @Test
    fun requestErrorsAreTypedByKind() = runTest {
        val transport = Transport()
        reloading(transport)
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()

        // No reply: the reload may have run.
        val timedOut = async { repository.reloadSession("session") }
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals(ReloadResult.Failed(ReloadFailure.Unknown), timedOut.await())

        val lost = async { repository.reloadSession("session") }
        runCurrent()
        transport.listener.failed(true, R.string.remote_connection_error)
        runCurrent()
        assertEquals(ReloadResult.Failed(ReloadFailure.ConnectionFailure), lost.await())
    }

    @Test
    fun connectionsDroppedRightAfterReadyGrowTheBackoffAndStopAfterFive() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = chatRepository(transport)
        val working = transport.response
        // The Mac accepts each connection and drops it before answering anything.
        transport.response = { null }
        transport.listener.failed(true, R.string.remote_connection_error)

        val waits = mutableListOf<Long>()
        repeat(MAX_SHORT_LIVED_CONNECTIONS) {
            val wait = repository.state.value.reconnectAt!! - testScheduler.currentTime
            waits += wait
            val connects = transport.connects
            advanceTimeBy(wait)
            runCurrent()
            assertEquals(connects + 1, transport.connects)
            assertTrue(repository.state.value.connected)
            advanceTimeBy(500)
            transport.listener.failed(true, R.string.remote_connection_error)
        }

        // The ready connections did not reset the backoff.
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L), waits)
        assertNull(repository.state.value.reconnectAt)
        assertEquals(R.string.remote_unreachable, repository.state.value.error)
        val connects = transport.connects
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(connects, transport.connects)

        // Retry still connects, and the first answered request resets the backoff.
        transport.response = working
        repository.refresh()
        runCurrent()
        assertTrue(repository.state.value.connected)
        assertNull(repository.state.value.error)
        transport.listener.failed(true, R.string.remote_connection_error)
        assertEquals(1_000L, repository.state.value.reconnectAt!! - testScheduler.currentTime)
    }

    @Test
    fun stoppedReconnectsResumeOnTheNextForegroundOrNetworkSignal() = runTest {
        for (signal in listOf("foreground", "network")) {
            val transport = Transport().also { configure(it) }
            val repository = chatRepository(transport)
            transport.response = { null }
            transport.listener.failed(true, R.string.remote_connection_error)
            repeat(MAX_SHORT_LIVED_CONNECTIONS) {
                advanceTimeBy(repository.state.value.reconnectAt!! - testScheduler.currentTime)
                runCurrent()
                transport.listener.failed(true, R.string.remote_connection_error)
            }
            assertNull(signal, repository.state.value.reconnectAt)
            val connects = transport.connects

            if (signal == "foreground") {
                repository.setForeground(false)
                repository.setForeground(true)
            } else repository.setValidatedNetwork("cellular")
            // A network change reconnects after the first, reset step of the backoff.
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(signal, connects + 1, transport.connects)
            assertTrue(signal, repository.state.value.connected)
        }
    }

    @Test
    fun connectionsBrokenAfterTheirFirstAnswerGrowTheBackoffAndStopAfterFive() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = chatRepository(transport)
        assertTrue(repository.state.value.connected)

        // Every connection answers its requests and then gets a frame this app cannot read.
        fun breakConnection() {
            transport.listener.broken()
            transport.listener.failed(true, R.string.remote_connection_error)
        }
        breakConnection()
        val waits = mutableListOf<Long>()
        repeat(MAX_BROKEN_CONNECTIONS - 1) {
            val wait = repository.state.value.reconnectAt!! - testScheduler.currentTime
            waits += wait
            val connects = transport.connects
            val sent = transport.sent.size
            advanceTimeBy(wait)
            runCurrent()
            assertEquals(connects + 1, transport.connects)
            assertTrue(repository.state.value.connected)
            // The reconnect answered requests, which proves it, but it breaks again.
            assertTrue(transport.sent.size > sent)
            advanceTimeBy(500)
            breakConnection()
        }

        // The answered requests did not reset the backoff of the broken connections.
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), waits)
        assertNull(repository.state.value.reconnectAt)
        assertEquals(R.string.remote_unreachable, repository.state.value.error)
        val connects = transport.connects
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(connects, transport.connects)

        // Retry still connects; a connection that stays up ends the run of broken ones.
        repository.refresh()
        runCurrent()
        assertTrue(repository.state.value.connected)
        advanceTimeBy(CONNECTION_PROVEN_MILLIS)
        runCurrent()
        breakConnection()
        assertEquals(1_000L, repository.state.value.reconnectAt!! - testScheduler.currentTime)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(repository.state.value.connected)
        breakConnection()
        assertEquals(2_000L, repository.state.value.reconnectAt!! - testScheduler.currentTime)

        // A plain drop after an answer still starts the backoff from the beginning.
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(repository.state.value.connected)
        transport.listener.failed(true, R.string.remote_connection_error)
        assertEquals(1_000L, repository.state.value.reconnectAt!! - testScheduler.currentTime)
    }

    @Test
    fun backoffResetsOnlyAfterAnAnsweredRequestOrThirtySecondsUp() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = chatRepository(transport)
        val working = transport.response
        transport.response = { null }
        transport.listener.failed(true, R.string.remote_connection_error)
        assertEquals(1_000L, repository.state.value.reconnectAt!! - testScheduler.currentTime)

        // Up for six seconds without an answer: no longer short-lived, but not proven either.
        advanceTimeBy(1_000)
        runCurrent()
        advanceTimeBy(6_000)
        transport.listener.failed(true, R.string.remote_connection_error)
        assertEquals(2_000L, repository.state.value.reconnectAt!! - testScheduler.currentTime)

        // Up for thirty seconds proves the connection even without an answer.
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(repository.state.value.connected)
        // A frame after the reads went out: they are slow, not on a dead socket.
        transport.deliver(Wire.objectOf("type" to "relay.notice"))
        advanceTimeBy(CONNECTION_PROVEN_MILLIS)
        runCurrent()
        transport.listener.failed(true, R.string.remote_connection_error)
        assertEquals(1_000L, repository.state.value.reconnectAt!! - testScheduler.currentTime)

        // An answered request proves it at once, even if the connection drops right after.
        advanceTimeBy(1_000)
        runCurrent()
        transport.listener.failed(true, R.string.remote_connection_error)
        assertEquals(2_000L, repository.state.value.reconnectAt!! - testScheduler.currentTime)
        transport.response = working
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(repository.state.value.connected)
        transport.listener.failed(true, R.string.remote_connection_error)
        assertEquals(1_000L, repository.state.value.reconnectAt!! - testScheduler.currentTime)
    }

    private fun pairingCode() =
        "pi-remote://pair#" + Wire.encode(
            Wire.objectOf(
                "v" to 1,
                "relay" to "https://relay.test",
                "routeId" to Wire.random(),
                "pairId" to Wire.random(),
                "secret" to Wire.random(32),
                "expiresAt" to 1,
            ).toString().toByteArray()
        )

    private suspend fun TestScope.pairingRepository(
        transport: Transport,
        pairings: Pairings = Pairings(emptyList()),
    ): DefaultRemoteRepository {
        Diagnostics.clear()
        Diagnostics.clock = { testScheduler.currentTime }
        val repository = repository(transport, hosts = emptyList(), pairings = pairings)
        repository.setForeground(true)
        repository.setValidatedNetwork("wifi")
        runCurrent()
        return repository
    }

    @Test
    fun pairingNotApprovedInTimeSaysSoAndClearsTheDeadline() = runTest {
        try {
            val transport = Transport().also { configure(it) }
            val repository = pairingRepository(transport)
            val started = testScheduler.currentTime
            val pairing = async { repository.pair(pairingCode()) }
            runCurrent()
            assertTrue(repository.state.value.loading)
            assertNull(repository.state.value.pairingDeadline)

            advanceTimeBy(2_000)
            transport.listener.approval()
            assertEquals(R.string.remote_approval, repository.state.value.connection)
            assertEquals(started + PAIRING_TIMEOUT_MILLIS, repository.state.value.pairingDeadline)

            advanceTimeBy(PAIRING_TIMEOUT_MILLIS)
            runCurrent()
            assertNull(pairing.await())
            assertEquals(R.string.remote_pairing_timeout, repository.state.value.error)
            assertNull(repository.state.value.pairingDeadline)
            assertFalse(repository.state.value.loading)
        } finally {
            Diagnostics.clear()
        }
    }

    @Test
    fun pairingTimeoutReportedByTheTransportKeepsItsText() = runTest {
        try {
            val transport = Transport().also { configure(it) }
            val repository = pairingRepository(transport)
            val pairing = async { repository.pair(pairingCode()) }
            runCurrent()
            transport.listener.approval()
            transport.listener.failed(false, R.string.remote_pairing_timeout)
            runCurrent()
            assertNull(pairing.await())
            assertEquals(R.string.remote_pairing_timeout, repository.state.value.error)
            assertNull(repository.state.value.pairingDeadline)
        } finally {
            Diagnostics.clear()
        }
    }

    @Test
    fun approvedPairingClearsTheDeadline() = runTest {
        try {
            val transport = Transport().also { configure(it) }
            val repository = pairingRepository(transport)
            val pairing = async { repository.pair(pairingCode()) }
            runCurrent()
            transport.listener.approval()
            assertNotNull(repository.state.value.pairingDeadline)

            val paired = PairedHost(transport.pairing!!.text("routeId"), "https://relay.test", "device", "secret", "Mac")
            transport.listener.persistPairing(paired)
            transport.listener.paired(paired)
            runCurrent()

            assertEquals(RemoteSelection(paired.routeId), pairing.await())
            assertNull(repository.state.value.pairingDeadline)
            assertTrue(repository.state.value.connected)
        } finally {
            Diagnostics.clear()
        }
    }

    @Test
    fun cancelPairingEndsThePairingWithoutAnError() = runTest {
        try {
            val transport = Transport().also { configure(it) }
            val repository = pairingRepository(transport)
            val pairing = async { repository.pair(pairingCode()) }
            runCurrent()
            transport.listener.approval()
            val closes = transport.closes

            repository.cancelPairing()
            runCurrent()

            assertNull(pairing.await())
            assertTrue(transport.closes > closes)
            assertFalse(repository.state.value.loading)
            assertNull(repository.state.value.pairingDeadline)
            assertNull(repository.state.value.error)
            assertEquals(R.string.remote_offline, repository.state.value.connection)
        } finally {
            Diagnostics.clear()
        }
    }

    @Test
    fun pairingCancelledDuringItsWriteDoesNotStayPaired() = runTest {
        try {
            val transport = Transport().also { configure(it) }
            val pairings = Pairings(emptyList())
            val repository = pairingRepository(transport, pairings)
            val pairing = async { repository.pair(pairingCode()) }
            runCurrent()
            transport.listener.approval()
            val paired = PairedHost(transport.pairing!!.text("routeId"), "https://relay.test", "device", "secret", "Mac")
            pairings.afterSave = { repository.cancelPairing() }

            // The transport then never acknowledges the pairing to the Mac.
            assertTrue(runCatching { transport.listener.persistPairing(paired) }.isFailure)
            runCurrent()

            assertNull(pairing.await())
            assertEquals(emptyList<PairedHost>(), pairings.hosts)
            assertEquals(emptyList<PairedHost>(), repository.state.value.hosts)
            assertFalse(repository.state.value.loading)
        } finally {
            Diagnostics.clear()
        }
    }

    @Test
    fun failedPairingsNeverReconnectAutomatically() = runTest {
        try {
            for (error in listOf(R.string.remote_connection_error, R.string.remote_storage_error, R.string.remote_denied)) {
                val transport = Transport().also { configure(it) }
                val repository = pairingRepository(transport)
                val pairing = async { repository.pair(pairingCode()) }
                runCurrent()
                transport.listener.approval()
                // Even when asked to reconnect, a failed pairing has no host to return to.
                transport.listener.failed(true, error)
                runCurrent()
                assertNull(pairing.await())
                assertEquals(error, repository.state.value.error)
                assertNull(repository.state.value.reconnectAt)
                assertNull(repository.state.value.pairingDeadline)
                val connects = transport.connects
                advanceTimeBy(120_000)
                runCurrent()
                assertEquals(connects, transport.connects)
            }
        } finally {
            Diagnostics.clear()
        }
    }

    // ---- Tiered resume and network signals ----

    private fun Transport.typeCount(type: String) = sent.count { it.text("type") == type }

    @Test
    fun aZombieSocketAfterALongBackgroundReconnectsQuietly() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = chatRepository(transport)
        repository.setForeground(false)
        advanceTimeBy(120_000)
        runCurrent()
        val closes = transport.closes
        val connects = transport.connects
        transport.readyOnConnect = false

        repository.setForeground(true)
        runCurrent()

        assertEquals(closes + 1, transport.closes)
        assertEquals(connects + 1, transport.connects)
        assertNull(repository.state.value.error)
        assertEquals(R.string.remote_reconnecting, repository.state.value.connection)
        // The chat stays on screen while it reconnects.
        assertTrue(repository.state.value.stale)
        assertEquals(RemoteSelection("host", "project", "session"), repository.state.value.selection)

        transport.listener.ready(emptySet())
        runCurrent()
        assertTrue(repository.state.value.connected)
        assertNull(repository.state.value.error)
        assertFalse(repository.state.value.stale)
    }

    @Test
    fun aProbeWithoutAnswerReconnectsAtOnceAndSaysItChecksOnlyAfterASecond() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = chatRepository(transport)
        repository.setForeground(false)
        advanceTimeBy(20_000)
        val original = transport.response
        transport.response = { if (it.text("type") == "session.snapshot") null else original(it) }
        val snapshots = transport.typeCount("session.snapshot")
        val connects = transport.connects

        repository.setForeground(true)
        runCurrent()
        assertEquals(snapshots + 1, transport.typeCount("session.snapshot"))
        assertFalse(repository.state.value.loading)
        assertEquals(R.string.remote_connected, repository.state.value.connection)
        advanceTimeBy(RESUME_PROBE_HINT_MILLIS - 1)
        runCurrent()
        assertEquals(R.string.remote_connected, repository.state.value.connection)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(R.string.remote_checking_connection, repository.state.value.connection)

        transport.response = original
        advanceTimeBy(RESUME_PROBE_TIMEOUT_MILLIS - RESUME_PROBE_HINT_MILLIS)
        runCurrent()
        // No backoff: the reconnect went out in the same instant the probe gave up.
        assertEquals(connects + 1, transport.connects)
        assertTrue(repository.state.value.connected)
        assertNull(repository.state.value.error)
        assertEquals(R.string.remote_connected, repository.state.value.connection)
    }

    @Test
    fun aProbeThatAnswersClearsTheCheckingLine() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = chatRepository(transport)
        repository.setForeground(false)
        advanceTimeBy(20_000)
        val original = transport.response
        transport.response = { if (it.text("type") == "session.snapshot") null else original(it) }
        repository.setForeground(true)
        runCurrent()
        advanceTimeBy(RESUME_PROBE_HINT_MILLIS)
        runCurrent()
        assertEquals(R.string.remote_checking_connection, repository.state.value.connection)

        val probe = transport.sent.last { it.text("type") == "session.snapshot" }
        transport.result(probe, original(probe)!!)
        runCurrent()
        assertEquals(R.string.remote_connected, repository.state.value.connection)
        assertTrue(repository.state.value.connected)
    }

    @Test
    fun aShortSwitchWithEventsFlowingSendsNothing() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = chatRepository(transport)
        repository.setForeground(false)
        advanceTimeBy(2_000)
        transport.deliver(event(1, "session.status", "status" to "running"))
        advanceTimeBy(1_000)
        runCurrent()
        val sent = transport.sent.size
        val messages = repository.state.value.messages

        repository.setForeground(true)
        runCurrent()

        assertEquals(sent, transport.sent.size)
        assertSame(messages, repository.state.value.messages)
        assertFalse(repository.state.value.loading)
    }

    @Test
    fun aShortSwitchProbesInPlaceWithoutLoadingOrReselecting() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = chatRepository(transport)
        repository.setForeground(false)
        advanceTimeBy(20_000)
        runCurrent()
        val loading = mutableListOf<Boolean>()
        val watcher = backgroundScope.launch { repository.state.collect { loading += it.loading } }
        runCurrent()
        val projects = transport.typeCount("projects.list")
        val sessions = transport.typeCount("sessions.list")
        val snapshots = transport.typeCount("session.snapshot")

        repository.setForeground(true)
        runCurrent()
        watcher.cancel()

        assertEquals(snapshots + 1, transport.typeCount("session.snapshot"))
        assertEquals(projects + 1, transport.typeCount("projects.list"))
        assertEquals(sessions + 1, transport.typeCount("sessions.list"))
        assertFalse(transport.sent.any { it.text("type") == "sessions.open" })
        assertFalse(loading.any { it })
        assertTrue(repository.state.value.sessionsFresh)
        assertEquals(RemoteSelection("host", "project", "session"), repository.state.value.selection)
    }

    @Test
    fun aSocketKeptAliveInTheBackgroundIsProbedNotReconnected() = runTest {
        try {
            Diagnostics.clear()
            Diagnostics.clock = { testScheduler.currentTime }
            val transport = Transport().also { configure(it) }
            val repository = chatRepository(transport)
            repository.setForeground(false)
            for (revision in 1..10) {
                advanceTimeBy(30_000)
                transport.deliver(
                    event(revision, "session.status", "status" to if (revision % 2 == 0) "idle" else "running")
                )
            }
            runCurrent()
            val connects = transport.connects
            val closes = transport.closes
            val snapshots = transport.typeCount("session.snapshot")

            repository.setForeground(true)
            runCurrent()

            assertEquals(connects, transport.connects)
            assertEquals(closes, transport.closes)
            assertEquals(snapshots + 1, transport.typeCount("session.snapshot"))
            assertTrue(repository.state.value.connected)
            assertTrue(Diagnostics.export().contains("recovery recover.fresh_foreground probe"))
        } finally {
            Diagnostics.clear()
        }
    }

    @Test
    fun aReplacedNetworkWhileLoadingReconnectsAtOnce() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = chatRepository(transport)
        repository.activate(RemoteSelection("host", "project"))
        runCurrent()
        val original = transport.response
        transport.response = { if (it.text("type") == "sessions.open") null else original(it) }
        val opening = async { repository.activate(RemoteSelection("host", "project", "session"), ActivationMode.USER_OPEN) }
        runCurrent()
        assertTrue(repository.state.value.loading)
        val connects = transport.connects
        transport.response = original

        repository.setValidatedNetwork("cellular")
        runCurrent()

        assertEquals(connects + 1, transport.connects)
        assertTrue(repository.state.value.connected)
        assertNull(repository.state.value.error)
        // The open did not wait out its request timeout.
        assertTrue(opening.isCompleted)
        assertTrue(testScheduler.currentTime < REQUEST_TIMEOUT_MILLIS)
    }

    @Test
    fun aForegroundReturnDuringAnActivationRunsOnceItEnds() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = chatRepository(transport)
        repository.activate(RemoteSelection("host", "project"))
        runCurrent()
        val original = transport.response
        transport.response = { if (it.text("type") == "sessions.open") null else original(it) }
        repository.setForeground(false)
        // A restore that started in the background is not abandoned when the app returns.
        val opening = async { repository.activate(RemoteSelection("host", "project", "session"), ActivationMode.USER_OPEN) }
        runCurrent()
        advanceTimeBy(20_000)
        runCurrent()
        val snapshots = transport.typeCount("session.snapshot")

        repository.setForeground(true)
        runCurrent()
        assertTrue(repository.state.value.loading)
        assertEquals(snapshots, transport.typeCount("session.snapshot"))

        val open = transport.sent.last { it.text("type") == "sessions.open" }
        transport.response = original
        transport.result(open, original(open)!!)
        runCurrent()
        opening.await()

        assertEquals(RemoteSelection("host", "project", "session"), repository.state.value.selection)
        // The activation's snapshot went out with the open; then the deferred resume's probe.
        assertEquals(snapshots + 1, transport.typeCount("session.snapshot"))
        assertFalse(repository.state.value.loading)
    }

    @Test
    fun anOpenFromBeforeALongBackgroundOnAQuietSocketEndsWithItsPreviousSelection() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = chatRepository(transport)
        repository.activate(RemoteSelection("host", "project"))
        runCurrent()
        val original = transport.response
        transport.response = { if (it.text("type") == "sessions.open") null else original(it) }
        val opening = async { repository.activate(RemoteSelection("host", "project", "session"), ActivationMode.USER_OPEN) }
        runCurrent()
        repository.setForeground(false)
        // Away longer than a short switch, the socket quiet for longer than a live one would be,
        // and the open's request not yet timed out.
        advanceTimeBy(RESUME_LIVE_SOCKET_MILLIS + 1_000)
        runCurrent()
        assertTrue(repository.state.value.loading)
        transport.response = original
        val connects = transport.connects
        val closes = transport.closes

        repository.setForeground(true)
        runCurrent()

        // The open ended through its error path, not by cancellation, so the caller can put its
        // navigation back in order.
        assertTrue(opening.isCompleted)
        assertFalse(opening.isCancelled)
        assertEquals(RemoteSelection("host", "project"), opening.await())
        assertEquals(closes + 1, transport.closes)
        assertEquals(connects + 1, transport.connects)
        assertTrue(repository.state.value.connected)
        assertFalse(repository.state.value.loading)
        assertNull(repository.state.value.error)
        assertEquals(RemoteSelection("host", "project"), repository.state.value.selection)
        assertTrue(testScheduler.currentTime < REQUEST_TIMEOUT_MILLIS)
    }

    @Test
    fun anOpenFromBeforeABackgroundOnALiveSocketRunsOnAndTheResumeFollows() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = chatRepository(transport)
        repository.activate(RemoteSelection("host", "project"))
        runCurrent()
        val original = transport.response
        transport.response = { if (it.text("type") == "sessions.open") null else original(it) }
        val opening = async { repository.activate(RemoteSelection("host", "project", "session"), ActivationMode.USER_OPEN) }
        runCurrent()
        repository.setForeground(false)
        advanceTimeBy(RESUME_QUIET_AWAY_MILLIS + 2_000)
        // The socket still delivers frames.
        transport.deliver(Wire.objectOf("type" to "ping"))
        advanceTimeBy(2_000)
        runCurrent()
        val connects = transport.connects
        val snapshots = transport.typeCount("session.snapshot")

        repository.setForeground(true)
        runCurrent()
        assertFalse(opening.isCompleted)
        assertTrue(repository.state.value.loading)
        assertEquals(connects, transport.connects)

        val open = transport.sent.last { it.text("type") == "sessions.open" }
        transport.response = original
        transport.result(open, original(open)!!)
        runCurrent()

        assertEquals(RemoteSelection("host", "project", "session"), opening.await())
        assertEquals(RemoteSelection("host", "project", "session"), repository.state.value.selection)
        assertEquals(connects, transport.connects)
        // The activation's snapshot went out with the open; then the deferred resume's probe.
        assertEquals(snapshots + 1, transport.typeCount("session.snapshot"))
    }

    @Test
    fun aNetworkUnblockedAfterTheReturnStillResumesByTier() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = chatRepository(transport)
        // A long lock: Android blocks the backgrounded app's network and unblocks it only after
        // the app is back.
        repository.setForeground(false)
        repository.setNetwork(null, false)
        advanceTimeBy(15 * 60_000L)
        runCurrent()
        val closes = transport.closes
        val connects = transport.connects
        repository.setForeground(true)
        runCurrent()
        assertEquals(connects, transport.connects)

        repository.setNetwork("wifi", true)
        runCurrent()
        // The zombie socket is replaced at once rather than probed first.
        assertEquals(closes + 1, transport.closes)
        assertEquals(connects + 1, transport.connects)
        assertTrue(repository.state.value.connected)
        assertNull(repository.state.value.error)
    }

    @Test
    fun aNetworkUnblockedAfterAShortSwitchSendsNothing() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = chatRepository(transport)
        repository.setForeground(false)
        repository.setNetwork(null, false)
        advanceTimeBy(2_000)
        transport.deliver(event(1, "session.status", "status" to "running"))
        advanceTimeBy(1_000)
        runCurrent()
        val sent = transport.sent.size
        repository.setForeground(true)
        repository.setNetwork("wifi", true)
        runCurrent()

        assertEquals(sent, transport.sent.size)
        assertTrue(repository.state.value.connected)
    }

    @Test
    fun aReturnWhileAlreadyOfflineReconnectsWithoutAnotherFailure() = runTest {
        try {
            Diagnostics.clear()
            Diagnostics.clock = { testScheduler.currentTime }
            val transport = Transport().also { configure(it) }
            val repository = chatRepository(transport)
            transport.failureOnConnect = R.string.remote_connection_error
            transport.listener.failed(true, R.string.remote_connection_error)
            advanceTimeBy(1)
            runCurrent()
            assertFalse(repository.state.value.connected)
            repository.setForeground(false)
            advanceTimeBy(120_000)
            runCurrent()
            transport.failureOnConnect = null
            val connects = transport.connects
            val closes = transport.closes

            repository.setForeground(true)
            runCurrent()

            assertEquals(connects + 1, transport.connects)
            assertEquals(closes, transport.closes)
            assertTrue(repository.state.value.connected)
            assertFalse(Diagnostics.export().contains("stale.retry"))
        } finally {
            Diagnostics.clear()
        }
    }

    @Test
    fun aMovedRouteWhileOfflineKeepsTheBackoff() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        repository.setForeground(true)
        repository.setNetwork("wifi", true, "wlan0:192.168.1.5")
        runCurrent()
        transport.failureOnConnect = R.string.remote_connection_error
        transport.listener.failed(true, R.string.remote_connection_error)
        advanceTimeBy(repository.state.value.reconnectAt!! - testScheduler.currentTime)
        runCurrent()
        val reconnectAt = repository.state.value.reconnectAt
        assertNotNull(reconnectAt)
        val connects = transport.connects

        repository.setNetwork("wifi", true, "wlan0:192.168.1.6")
        runCurrent()

        assertEquals(connects, transport.connects)
        assertEquals(reconnectAt, repository.state.value.reconnectAt)
    }

    @Test
    fun aForegroundReturnListsTheJobsOnce() = runTest {
        val transport = Transport()
        val repository = jobsRepository(transport, lease = true)
        repository.activate(RemoteSelection("host", "project", "session"))
        repository.setForeground(true)
        repository.setValidatedNetwork("wifi")
        runCurrent()
        repository.refreshJobs()
        runCurrent()

        // A probe: the controller lists once on its own.
        var lists = transport.jobsListRequests().size
        repository.setForeground(false)
        advanceTimeBy(20_000)
        repository.setForeground(true)
        runCurrent()
        assertEquals(lists + 1, transport.jobsListRequests().size)

        // A reconnect: nothing goes out over the dead socket. The chat lists the jobs once the
        // reconnect's activation has ended, as after any activation.
        lists = transport.jobsListRequests().size
        repository.setForeground(false)
        advanceTimeBy(120_000)
        runCurrent()
        val connects = transport.connects
        repository.setForeground(true)
        runCurrent()
        assertEquals(connects + 1, transport.connects)
        assertEquals(lists, transport.jobsListRequests().size)
    }

    @Test
    fun aSocketFailingWithoutANetworkWaitsForOneAndReconnectsWhenItReturns() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = chatRepository(transport)
        repository.setValidatedNetwork(null)
        assertTrue(repository.state.value.connected)

        transport.listener.failed(true, R.string.remote_connection_error)
        assertEquals(R.string.remote_waiting_for_network, repository.state.value.connection)
        assertNull(repository.state.value.error)
        assertNull(repository.state.value.reconnectAt)
        val connects = transport.connects
        advanceTimeBy(QUIET_RECONNECT_MILLIS + 60_000)
        runCurrent()
        assertNull(repository.state.value.error)
        assertEquals(connects, transport.connects)

        repository.setValidatedNetwork("wifi")
        runCurrent()
        assertEquals(connects + 1, transport.connects)
        assertTrue(repository.state.value.connected)
        assertNull(repository.state.value.error)
    }

    @Test
    fun aNetworkLostAfterTheSocketFailedWaitsForItInsteadOfTheCard() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = chatRepository(transport)
        transport.failureOnConnect = R.string.remote_connection_error
        transport.listener.failed(true, R.string.remote_connection_error)
        assertEquals(R.string.remote_reconnecting, repository.state.value.connection)

        repository.setValidatedNetwork(null)
        assertEquals(R.string.remote_waiting_for_network, repository.state.value.connection)
        advanceTimeBy(QUIET_RECONNECT_MILLIS + 1_000)
        runCurrent()
        assertNull(repository.state.value.error)
        assertNull(repository.state.value.reconnectAt)

        transport.failureOnConnect = null
        repository.setValidatedNetwork("wifi")
        runCurrent()
        assertTrue(repository.state.value.connected)
    }

    @Test
    fun theSameNetworkReturningProbesTheSocketWithoutATeardown() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = chatRepository(transport)
        val closes = transport.closes
        val snapshots = transport.typeCount("session.snapshot")

        repository.setValidatedNetwork(null)
        repository.setValidatedNetwork("wifi")
        runCurrent()

        assertEquals(closes, transport.closes)
        assertEquals(snapshots + 1, transport.typeCount("session.snapshot"))
        assertTrue(repository.state.value.connected)
    }

    @Test
    fun validationOfTheNetworkInUseRetriesAWaitingReconnectAtOnce() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", "session"))
        repository.setForeground(true)
        repository.setNetwork("wifi", validated = false)
        runCurrent()
        assertTrue(repository.state.value.connected)
        transport.failureOnConnect = R.string.remote_connection_error
        transport.listener.failed(true, R.string.remote_connection_error)
        advanceTimeBy(repository.state.value.reconnectAt!! - testScheduler.currentTime)
        runCurrent()
        // The captive network still lets the app try, on its backoff.
        assertNotNull(repository.state.value.reconnectAt)
        val connects = transport.connects

        transport.failureOnConnect = null
        repository.setNetwork("wifi", validated = true)
        runCurrent()
        assertEquals(connects + 1, transport.connects)
        assertTrue(repository.state.value.connected)
    }

    // ---- Cold start ------------------------------------------------------------------------

    private class SavedNavigation(val snapshots: Map<String, NavigationSnapshot>) : NavigationSnapshotStorage {
        override fun load() = snapshots

        override fun save(snapshots: Map<String, NavigationSnapshot>) = Unit
    }

    private val namedProject = Wire.objectOf("id" to "project", "name" to "Saved project")

    /** [configure], with projects that carry the names quick chats show. */
    private fun configureNamed(transport: Transport, sessions: List<JsonObject> = listOf(session())) {
        configure(transport, sessions)
        val original = transport.response
        transport.response = { request ->
            if (request.text("type") == "projects.list") data("projects", listOf(namedProject))
            else original(request)
        }
    }

    private fun savedHost(
        projectId: String? = null,
        sessions: List<JsonObject> = emptyList(),
    ) =
        mapOf(
            "host" to
                NavigationSnapshot(
                    "host",
                    listOf(namedProject),
                    projectId = projectId,
                    sessions = sessions,
                    projectChats = listOf(
                        Wire.objectOf("projectId" to "project", "projectName" to "Saved project", "session" to session()),
                    ),
                )
        )

    /** Runs the test dispatcher until [condition] holds, while real IO threads make progress. */
    private fun TestScope.runUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        runCurrent()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out" }
            Thread.sleep(5)
            runCurrent()
        }
    }

    @Test
    fun aRestoreConnectsWhileTheSavedNavigationStillLoads() = runTest {
        val transport = Transport().also { configureNamed(it) }
        // Holds the load on its IO thread, like a large snapshot that decrypts slowly.
        val gate = java.util.concurrent.CountDownLatch(1)
        val storage = object : NavigationSnapshotStorage {
            override fun load(): Map<String, NavigationSnapshot> {
                gate.await(10, java.util.concurrent.TimeUnit.SECONDS)
                return savedHost()
            }

            override fun save(snapshots: Map<String, NavigationSnapshot>) = Unit
        }
        val io = java.util.concurrent.Executors.newFixedThreadPool(2).asCoroutineDispatcher()
        try {
            val repository =
                DefaultRemoteRepository(
                    Pairings(listOf(host)),
                    Drafts(),
                    transport,
                    backgroundScope,
                    io,
                    now = { testScheduler.currentTime },
                    navigationStorage = storage,
                    jitter = { 0.5 },
                )
            repository.setPushToken("token")
            val restoring = async { repository.activate(RemoteSelection("host"), ActivationMode.RESTORE) }
            runUntil { transport.connects == 1 }
            // The navigation snapshot still loads, so the activation itself has not started.
            assertTrue(repository.state.value.hostsLoaded)
            assertEquals(RemoteSelection(), repository.state.value.selection)
            assertTrue(transport.sent.none { it.text("type") == "projects.list" })
            // A request sent now would be cancelled by the activation's selection change.
            assertTrue(transport.sent.none { it.text("type") == "push.register" })

            gate.countDown()
            runUntil { restoring.isCompleted }
            assertEquals(RemoteSelection("host"), restoring.await())
            assertEquals(1, transport.connects)
            assertEquals(1, transport.sent.count { it.text("type") == "push.register" })
            // The saved list showed first, but the connection reads its own.
            assertTrue(transport.sent.any { it.text("type") == "projects.list" })
            assertEquals(listOf(namedProject), repository.state.value.projects)
        } finally {
            gate.countDown()
            io.close()
        }
    }

    @Test
    fun aNotificationThatReplacesTheRestoreConnectsOnlyToItsOwnMac() = runTest {
        val other = host.copy(routeId = "other", name = "Other")
        val transport = Transport().also { configureNamed(it) }
        // Holds the pairings, so the restore still waits to connect when the notification comes.
        val gate = java.util.concurrent.CountDownLatch(1)
        val pairings = object : PairingStorage {
            override fun load(): List<PairedHost> {
                gate.await(10, java.util.concurrent.TimeUnit.SECONDS)
                return listOf(host, other)
            }

            override fun save(hosts: List<PairedHost>) = Unit
        }
        val io = java.util.concurrent.Executors.newFixedThreadPool(2).asCoroutineDispatcher()
        try {
            val repository =
                DefaultRemoteRepository(
                    pairings,
                    Drafts(),
                    transport,
                    backgroundScope,
                    io,
                    now = { testScheduler.currentTime },
                    navigationStorage = SavedNavigation(savedHost()),
                    jitter = { 0.5 },
                )
            val restoring = launch { repository.activate(RemoteSelection("host"), ActivationMode.RESTORE) }
            runCurrent()
            // What the navigator does when a tapped notification replaces the restore.
            restoring.cancel()
            repository.cancelSelection()
            val opening = async { repository.openNotification("other", "session") }
            gate.countDown()
            runUntil { opening.isCompleted }
            assertEquals(1, transport.connects)
            assertEquals("other", transport.route)
        } finally {
            gate.countDown()
            io.close()
        }
    }

    @Test
    fun aProcessThatNothingRestoresNeverConnects() = runTest {
        // As when FCM, a worker or the widget starts the process: the repository loads, the push
        // token arrives, and no screen asks for a Mac.
        val transport = Transport().also { configure(it) }
        val repository = repository(transport, navigationStorage = SavedNavigation(savedHost()))
        repository.setPushToken("token")
        runCurrent()
        assertEquals(0, transport.connects)
        assertTrue(transport.sent.isEmpty())
        assertTrue(repository.state.value.hostsLoaded)
        assertEquals(listOf(host), repository.state.value.hosts)
    }

    @Test
    fun hostsStayUnloadedWhileTheirReadFails() = runTest {
        val transport = Transport()
        val pairings = object : PairingStorage {
            override fun load(): List<PairedHost> = error("Keystore unavailable")

            override fun save(hosts: List<PairedHost>) = Unit
        }
        val repository =
            DefaultRemoteRepository(
                pairings,
                Drafts(),
                transport,
                backgroundScope,
                StandardTestDispatcher(testScheduler),
                now = { testScheduler.currentTime },
                jitter = { 0.5 },
            )
        assertFalse(repository.state.value.hostsLoaded)
        assertFalse(repository.state.value.hostsUnreadable)
        runCurrent()
        assertFalse(repository.state.value.hostsLoaded)
        // The Hosts screen says the read failed rather than loading for good.
        assertTrue(repository.state.value.hostsUnreadable)
        assertEquals(R.string.remote_storage_error, repository.state.value.error)
        repository.disconnect()
        assertTrue(repository.state.value.hostsUnreadable)
    }

    @Test
    fun aResetPairingStoreSaysSoOnceAndPairingWorksAgain() = runTest {
        try {
            val transport = Transport().also { configure(it) }
            val pairings = Pairings(emptyList()).apply { reset = true }
            val repository = repository(transport, hosts = emptyList(), pairings = pairings)
            repository.setForeground(true)
            repository.setValidatedNetwork("wifi")
            runCurrent()
            assertEquals(R.string.remote_storage_pairings_reset, repository.state.value.error)
            assertTrue(repository.state.value.hostsLoaded)
            assertFalse(repository.state.value.hostsUnreadable)

            val pairing = async { repository.pair(pairingCode()) }
            runCurrent()
            transport.listener.approval()
            val paired = PairedHost(transport.pairing!!.text("routeId"), "https://relay.test", "device", "secret", "Mac")
            transport.listener.persistPairing(paired)
            transport.listener.paired(paired)
            runCurrent()
            assertEquals(RemoteSelection(paired.routeId), pairing.await())
            assertEquals(listOf(paired), pairings.hosts)
            assertNotEquals(R.string.remote_storage_pairings_reset, repository.state.value.error)
        } finally {
            Diagnostics.clear()
        }
    }

    @Test
    fun aResetDraftStoreRemovesOrphanedAttachmentsAndKeepsTyping() = runTest {
        val transport = Transport().also { configure(it) }
        val orphan = localFile(id = "OOOOOOOOOOOOOOOOOOOOOO")
        val attachments = Attachments().apply { bytes[orphan.id] = ByteArray(1) }
        val drafts = object : DraftStorage {
            var values = emptyMap<DraftKey, StoredDraft>()
            private var reset = true

            override fun load() = values

            override fun save(drafts: Map<DraftKey, StoredDraft>) {
                values = drafts.toMap()
            }

            override fun takeReset() = reset.also { reset = false }
        }
        val repository =
            DefaultRemoteRepository(
                Pairings(listOf(host)),
                drafts,
                transport,
                backgroundScope,
                StandardTestDispatcher(testScheduler),
                attachmentStorage = attachments,
                now = { testScheduler.currentTime },
                jitter = { 0.5 },
            )
        runCurrent()
        assertEquals(R.string.remote_storage_drafts_reset, repository.state.value.error)
        assertEquals(listOf(orphan.id), attachments.removed)
        assertTrue(attachments.bytes.isEmpty())

        repository.activate(RemoteSelection("host", "project", "session"), ActivationMode.USER_OPEN)
        runCurrent()
        repository.draft("new text")
        runCurrent()
        repository.flush()
        assertEquals("new text", drafts.values.values.single().text)
    }

    @Test
    fun aReadableDraftStoreKeepsTheAttachmentsItsDraftsReference() = runTest {
        val transport = Transport().also { configure(it) }
        val referenced = localFile(id = "RRRRRRRRRRRRRRRRRRRRRR")
        val orphan = localFile(id = "OOOOOOOOOOOOOOOOOOOOOO")
        val attachments = Attachments().apply {
            bytes[referenced.id] = ByteArray(1)
            bytes[orphan.id] = ByteArray(1)
        }
        val drafts = Drafts().apply {
            values = mapOf(DraftKey("host", "session") to StoredDraft(attachments = listOf(referenced)))
        }
        val repository = repository(transport, drafts = drafts, attachmentStorage = attachments)
        runCurrent()
        assertNull(repository.state.value.error)
        assertEquals(setOf(referenced.id), attachments.bytes.keys)
    }

    @Test
    fun aDisconnectKeepsTheHostsLoaded() = runTest {
        val transport = Transport().also { configure(it) }
        val repository = repository(transport)
        runCurrent()
        assertTrue(repository.state.value.hostsLoaded)
        repository.disconnect()
        assertTrue(repository.state.value.hostsLoaded)
        assertEquals(listOf(host), repository.state.value.hosts)
    }

    @Test
    fun aUserOpenOfAMacShowsItsSavedListsUnverifiedUntilTheMacAnswers() = runTest {
        val transport = Transport().also {
            configureNamed(it)
            it.readyOnConnect = false
        }
        val repository = repository(transport, navigationStorage = SavedNavigation(savedHost()))
        runCurrent()
        val opening = async { repository.activate(RemoteSelection("host"), ActivationMode.USER_OPEN) }
        runCurrent()
        val shown = repository.state.value
        assertEquals(RemoteSelection("host"), shown.selection)
        assertTrue(shown.loading)
        assertFalse(shown.connected)
        assertEquals(listOf("Saved project"), shown.projects.map { it.text("name") })
        assertEquals(listOf("session"), shown.projectChats.map { it.session.text("id") })
        assertFalse(shown.projectChats.single().verified)

        transport.listener.ready(emptySet())
        assertEquals(RemoteSelection("host"), opening.await())
        runCurrent()
        assertEquals(listOf(namedProject), repository.state.value.projects)
        assertTrue(repository.state.value.projectChats.single().verified)
    }

    @Test
    fun aUserOpenOfAnUnreachableMacStaysOnItsSavedList() = runTest {
        val transport = Transport().also {
            configureNamed(it)
            it.failureOnConnect = R.string.remote_connection_error
        }
        val repository = repository(transport, navigationStorage = SavedNavigation(savedHost()))
        runCurrent()
        assertEquals(RemoteSelection("host"), repository.activate(RemoteSelection("host"), ActivationMode.USER_OPEN))
        val state = repository.state.value
        assertEquals(RemoteSelection("host"), state.selection)
        assertFalse(state.connected)
        assertEquals(listOf("Saved project"), state.projects.map { it.text("name") })
    }

    @Test
    fun aUserOpenOfAProjectShowsItsSavedSessions() = runTest {
        val transport = Transport().also {
            configureNamed(it, sessions = listOf(session(), session("fresh")))
            it.readyOnConnect = false
        }
        val saved = savedHost(projectId = "project", sessions = listOf(session()))
        val repository = repository(transport, navigationStorage = SavedNavigation(saved))
        runCurrent()
        val project = RemoteSelection("host", "project")
        val opening = async { repository.activate(project, ActivationMode.USER_OPEN) }
        runCurrent()
        assertEquals(project, repository.state.value.selection)
        assertEquals(listOf("session"), repository.state.value.sessions.map { it.text("id") })
        assertFalse(repository.state.value.sessionsFresh)

        transport.listener.ready(emptySet())
        assertEquals(project, opening.await())
        assertEquals(listOf("session", "fresh"), repository.state.value.sessions.map { it.text("id") })
        assertTrue(repository.state.value.sessionsFresh)
    }

    @Test
    fun aUserOpenOfAChatStillWaitsForItsCanonicalSession() = runTest {
        val transport = Transport().also {
            configureNamed(it)
            it.readyOnConnect = false
        }
        val saved = savedHost(projectId = "project", sessions = listOf(session()))
        val repository = repository(transport, navigationStorage = SavedNavigation(saved))
        runCurrent()
        val chat = RemoteSelection("host", "project", "session")
        val opening = async { repository.activate(chat, ActivationMode.USER_OPEN) }
        runCurrent()
        // An open may fork a history session, so nothing is selected before sessions.open.
        assertEquals(RemoteSelection(), repository.state.value.selection)
        transport.listener.ready(emptySet())
        assertEquals(chat, opening.await())
    }

    @Test
    fun attachmentCleanupDoesNotHoldBackTheFirstActivation() = runTest {
        val transport = Transport().also { configure(it) }
        var connectsAtCleanup: Int? = null
        val attachments = object : AttachmentStorage {
            override fun write(attachment: LocalAttachment, bytes: ByteArray) = Unit

            override fun read(attachment: LocalAttachment): ByteArray = ByteArray(0)

            override fun remove(id: String) = Unit

            override fun cleanup(keepIds: Set<String>) {
                connectsAtCleanup = transport.connects
            }
        }
        val repository = repository(transport, attachmentStorage = attachments)
        val opening = async { repository.activate(RemoteSelection("host"), ActivationMode.USER_OPEN) }
        runCurrent()
        assertEquals(RemoteSelection("host"), opening.await())
        // The activation got past initialization before the directory IO ran.
        assertEquals(1, connectsAtCleanup)
    }

    /**
     * A repository whose saved navigation loads on a real IO thread and waits for [gate], like a
     * large snapshot that decrypts slowly; the test closes [io].
     */
    private fun TestScope.gatedRepository(
        transport: Transport,
        gate: java.util.concurrent.CountDownLatch,
        snapshots: Map<String, NavigationSnapshot>,
        io: CoroutineDispatcher,
    ): DefaultRemoteRepository {
        val storage = object : NavigationSnapshotStorage {
            override fun load(): Map<String, NavigationSnapshot> {
                gate.await(10, java.util.concurrent.TimeUnit.SECONDS)
                return snapshots
            }

            override fun save(snapshots: Map<String, NavigationSnapshot>) = Unit
        }
        return DefaultRemoteRepository(
            Pairings(listOf(host)),
            Drafts(),
            transport,
            backgroundScope,
            io,
            now = { testScheduler.currentTime },
            navigationStorage = storage,
            jitter = { 0.5 },
        )
    }

    @Test
    fun aUserOpenOfAProjectAfterItsMacReadsTheSavedSessionListAgain() = runTest {
        val transport = Transport().also { configureNamed(it, sessions = listOf(session(), session("fresh"))) }
        val saved = savedHost(projectId = "project", sessions = listOf(session()))
        val repository = repository(transport, navigationStorage = SavedNavigation(saved))
        runCurrent()
        // The quick chats' reads of each project fail, so only the saved list is at hand.
        transport.failure = { if (it.text("type") == "sessions.list") "internal" else null }
        assertEquals(RemoteSelection("host"), repository.activate(RemoteSelection("host"), ActivationMode.USER_OPEN))
        runCurrent()
        transport.failure = { null }
        val lists = transport.sent.count { it.text("type") == "sessions.list" }
        val project = RemoteSelection("host", "project")
        assertEquals(project, repository.activate(project, ActivationMode.USER_OPEN))
        // The saved list showed the Mac's projects first; this connection never read it.
        assertTrue(transport.sent.count { it.text("type") == "sessions.list" } > lists)
        assertEquals(listOf("session", "fresh"), repository.state.value.sessions.map { it.text("id") })
    }

    @Test
    fun aListReadOnTheConnectionIsReusedOnTheNextOpen() = runTest {
        val transport = Transport().also { configureNamed(it) }
        val repository = repository(transport, navigationStorage = SavedNavigation(savedHost()))
        runCurrent()
        val project = RemoteSelection("host", "project")
        assertEquals(project, repository.activate(project, ActivationMode.USER_OPEN))
        assertEquals(RemoteSelection("host"), repository.activate(RemoteSelection("host"), ActivationMode.USER_OPEN))
        val projects = transport.sent.count { it.text("type") == "projects.list" }
        val sessions = transport.sent.count { it.text("type") == "sessions.list" }
        assertEquals(project, repository.activate(project, ActivationMode.USER_OPEN))
        assertEquals(projects, transport.sent.count { it.text("type") == "projects.list" })
        assertEquals(sessions, transport.sent.count { it.text("type") == "sessions.list" })
    }

    @Test
    fun aProjectOpenedAfterAnEarlyRestoreReadsItsSessionsAgain() = runTest {
        val transport = Transport().also { configureNamed(it, sessions = listOf(session(), session("fresh"))) }
        val gate = java.util.concurrent.CountDownLatch(1)
        val io = java.util.concurrent.Executors.newFixedThreadPool(2).asCoroutineDispatcher()
        try {
            val saved = savedHost(projectId = "project", sessions = listOf(session()))
            val repository = gatedRepository(transport, gate, saved, io)
            // The quick chats' reads of each project fail, so only the saved list is at hand.
            transport.failure = { if (it.text("type") == "sessions.list") "internal" else null }
            val restoring = async { repository.activate(RemoteSelection("host"), ActivationMode.RESTORE) }
            runUntil { transport.connects == 1 }
            gate.countDown()
            runUntil { restoring.isCompleted }
            assertEquals(RemoteSelection("host"), restoring.await())
            runCurrent()
            transport.failure = { null }
            val lists = transport.sent.count { it.text("type") == "sessions.list" }

            val project = RemoteSelection("host", "project")
            val opening = async { repository.activate(project, ActivationMode.USER_OPEN) }
            runUntil { opening.isCompleted }
            assertEquals(project, opening.await())
            assertTrue(transport.sent.count { it.text("type") == "sessions.list" } > lists)
            assertEquals(listOf("session", "fresh"), repository.state.value.sessions.map { it.text("id") })
            assertEquals(1, transport.connects)
        } finally {
            gate.countDown()
            io.close()
        }
    }

    @Test
    fun anEarlyConnectionThatFailsLeavesTheReconnectToTheRestore() = runTest {
        val transport = Transport().also {
            configureNamed(it)
            it.failureOnConnect = R.string.remote_connection_error
        }
        val gate = java.util.concurrent.CountDownLatch(1)
        val io = java.util.concurrent.Executors.newFixedThreadPool(2).asCoroutineDispatcher()
        try {
            val repository = gatedRepository(transport, gate, savedHost(), io)
            repository.setNetwork("wifi", true)
            repository.setForeground(true)
            val stack = mutableListOf<androidx.navigation3.runtime.NavKey>(RemoteNavKey.Hosts, RemoteNavKey.Projects("host"))
            val navigator = RemoteNavigator(repository, stack, backgroundScope)
            navigator.restore()
            runUntil { transport.connects == 1 }
            transport.failureOnConnect = null
            // Longer than any backoff, while the saved navigation still loads.
            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(1, transport.connects)
            assertNull(repository.state.value.reconnectAt)
            assertEquals(RemoteSelection(), repository.state.value.selection)

            gate.countDown()
            runUntil { repository.state.value.selection == RemoteSelection("host") && !repository.state.value.loading }
            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(RemoteSelection("host"), repository.state.value.selection)
            assertTrue(repository.state.value.connected)
            assertEquals(2, transport.connects)
            navigator.reconcile(repository.state.value)
            assertEquals(RemoteNavKey.Projects("host"), stack.last())
        } finally {
            gate.countDown()
            io.close()
        }
    }

    @Test
    fun aRestoreDoesNotRetryADeviceItsEarlyConnectionFoundPaused() = runTest {
        val transport = Transport().also {
            configureNamed(it)
            it.failureOnConnect = R.string.remote_device_paused
        }
        val gate = java.util.concurrent.CountDownLatch(1)
        val io = java.util.concurrent.Executors.newFixedThreadPool(2).asCoroutineDispatcher()
        try {
            val repository = gatedRepository(transport, gate, savedHost(), io)
            val restoring = async { repository.activate(RemoteSelection("host"), ActivationMode.RESTORE) }
            runUntil { transport.connects == 1 }
            assertEquals("host", repository.state.value.deniedRouteId)
            gate.countDown()
            runUntil { restoring.isCompleted }
            restoring.await()
            runCurrent()
            assertEquals(1, transport.connects)
            assertEquals("host", repository.state.value.deniedRouteId)
            assertEquals(R.string.remote_device_paused, repository.state.value.error)
        } finally {
            gate.countDown()
            io.close()
        }
    }

    @Test
    fun aRestoreReadsItsSnapshotAlongsideTheListsAndTheControlsBeforeItsReply() = runTest {
        val transport =
            Transport().apply { capabilities = setOf(CONFIGURATION_CAPABILITY, ADVISOR_CAPABILITY, COMMANDS_CAPABILITY) }
        configure(transport)
        quoteMessages(transport)
        val answer = transport.response
        val held = mutableMapOf<String, JsonObject>()
        transport.response = { request ->
            when (val type = request.text("type")) {
                "projects.list", "sessions.list", "session.snapshot" -> {
                    held[type] = request
                    null
                }
                "session.configuration.get" -> configurationData()
                "session.commands.get" -> commandData()
                "session.advisor.get" -> advisorData()
                else -> answer(request)
            }
        }
        val repository = repository(transport)
        val chat = RemoteSelection("host", "project", "session")
        val restoring = async { repository.activate(chat, ActivationMode.RESTORE) }
        runCurrent()
        // The snapshot and the session list go out with the projects, before any reply.
        assertEquals(
            listOf("session.snapshot", "sessions.list", "projects.list"),
            transport.sent.map { it.text("type") }.filter { it in held },
        )
        assertEquals(setOf("session.snapshot", "sessions.list", "projects.list"), held.keys)

        transport.result(
            held.getValue("projects.list"),
            JsonObject(data("projects", listOf(project)) + ("capabilities" to JsonArray(emptyList()))),
        )
        runCurrent()
        // Nothing is selected before the lists confirm the chat.
        assertNull(repository.state.value.selection.sessionId)
        transport.result(held.getValue("sessions.list"), data("sessions", listOf(session())))
        runCurrent()
        assertEquals(chat, repository.state.value.selection)
        assertTrue(repository.state.value.loading)
        // The controls are read while the snapshot still is.
        val sent = transport.sent.map { it.text("type") }
        for (type in listOf("session.configuration.get", "session.advisor.get", "session.commands.get"))
            assertTrue(type, type in sent)
        assertEquals(1, sent.count { it == "session.snapshot" })

        val snapshot = held.getValue("session.snapshot")
        transport.result(snapshot, checkNotNull(answer(snapshot)))
        assertEquals(chat, restoring.await())
        runCurrent()
        val state = repository.state.value
        assertFalse(state.loading)
        assertEquals(listOf("a", "b"), state.messages.map { it.text("id") })
        assertEquals("model", state.configuration?.model?.id)
        assertEquals(listOf("review"), state.commands.map { it.name })
        assertNotNull(state.advisor)
        assertEquals(1, transport.sent.count { it.text("type") == "session.snapshot" })
    }

    @Test
    fun aRestoreOfAMissingSessionDropsItsHeldSnapshotAndLandsOnTheProject() = runTest {
        val transport = Transport()
        configure(transport, sessions = listOf(session("other")))
        quoteMessages(transport)
        val answer = transport.response
        val snapshots = mutableListOf<JsonObject>()
        transport.response = { request ->
            if (request.text("type") == "session.snapshot") {
                snapshots += request
                null
            } else answer(request)
        }
        val repository = repository(transport)
        val parent = RemoteSelection("host", "project")
        assertEquals(parent, repository.activate(RemoteSelection("host", "project", "session"), ActivationMode.RESTORE))
        runCurrent()
        // It went out alongside the lists and was dropped with the chat.
        assertEquals(1, snapshots.size)
        assertEquals(parent, repository.state.value.selection)

        // Its late reply, and an event of the gone chat, change nothing.
        transport.result(snapshots.single(), checkNotNull(answer(snapshots.single())))
        transport.deliver(
            Wire.objectOf(
                "type" to "event", "kind" to "session.status", "sessionId" to "session",
                "revision" to 1, "status" to "running",
            )
        )
        runCurrent()
        val state = repository.state.value
        assertEquals(parent, state.selection)
        assertNull(state.session)
        assertTrue(state.messages.isEmpty())
        assertFalse(state.loading)
        assertEquals(listOf("other"), state.sessions.map { it.text("id") })
        assertEquals(1, transport.sent.count { it.text("type") == "session.snapshot" })
    }

    @Test
    fun aLiveOpenWhoseIdChangesDropsTheSnapshotReadAlongside() = runTest {
        val transport = Transport()
        configure(transport, sessions = listOf(session()), opened = session("moved"))
        quoteMessages(transport)
        val answer = transport.response
        val held = mutableListOf<JsonObject>()
        transport.response = { request ->
            if (request.text("type") == "session.snapshot" && request.text("sessionId") == "session") {
                held += request
                null
            } else answer(request)
        }
        val repository = repository(transport)
        val moved = RemoteSelection("host", "project", "moved")
        assertEquals(moved, repository.activate(RemoteSelection("host", "project", "session"), ActivationMode.USER_OPEN))
        runCurrent()
        // The open goes out first, the snapshot of the live chat right behind it; the open's ID wins.
        val sent = transport.sent.map { it.text("type") }
        assertTrue(sent.indexOf("sessions.open") < sent.indexOf("session.snapshot"))
        assertEquals(
            listOf("session", "moved"),
            transport.sent.filter { it.text("type") == "session.snapshot" }.map { it.text("sessionId") },
        )
        assertEquals(moved, repository.state.value.selection)
        assertEquals(listOf("a", "b"), repository.state.value.messages.map { it.text("id") })

        transport.result(held.single(), JsonObject(checkNotNull(answer(held.single())) + ("messages" to JsonArray(emptyList()))))
        runCurrent()
        assertEquals(moved, repository.state.value.selection)
        assertEquals(listOf("a", "b"), repository.state.value.messages.map { it.text("id") })
    }

    @Test
    fun aHistoryOpenWaitsForTheForkBeforeItsSnapshot() = runTest {
        val transport = Transport()
        configure(transport, sessions = listOf(session("old", origin = "history")), opened = session("fork"))
        val answer = transport.response
        val opens = mutableListOf<JsonObject>()
        transport.response = { request ->
            if (request.text("type") == "sessions.open") {
                opens += request
                null
            } else answer(request)
        }
        val repository = repository(transport)
        val opening = async { repository.activate(RemoteSelection("host", "project", "old"), ActivationMode.USER_OPEN) }
        runCurrent()
        assertEquals(1, opens.size)
        assertEquals(0, transport.sent.count { it.text("type") == "session.snapshot" })
        transport.result(opens.single(), checkNotNull(answer(opens.single())))
        assertEquals(RemoteSelection("host", "project", "fork"), opening.await())
        assertEquals(
            listOf("fork"),
            transport.sent.filter { it.text("type") == "session.snapshot" }.map { it.text("sessionId") },
        )
    }

    @Test
    fun aRevisionGapAfterAConcurrentOpenAsksForOneResync() = runTest {
        val transport = Transport()
        configure(transport)
        quoteMessages(transport)
        val quoted = transport.response
        var revision = 5
        val opens = mutableListOf<JsonObject>()
        transport.response = { request ->
            when (request.text("type")) {
                "sessions.open" -> {
                    opens += request
                    null
                }
                "session.snapshot" -> JsonObject(checkNotNull(quoted(request)) + ("revision" to JsonPrimitive(revision)))
                else -> quoted(request)
            }
        }
        val repository = repository(transport)
        val chat = RemoteSelection("host", "project", "session")
        val opening = async { repository.activate(chat, ActivationMode.USER_OPEN) }
        runCurrent()
        // The snapshot answered before the open did; nothing is selected yet.
        assertEquals(1, transport.sent.count { it.text("type") == "session.snapshot" })
        assertNull(repository.state.value.selection.sessionId)
        // Revision 7 of the chat arrives meanwhile: 6 never does.
        revision = 7
        transport.deliver(
            Wire.objectOf(
                "type" to "event", "kind" to "session.status", "sessionId" to "session",
                "revision" to 7, "status" to "running",
            )
        )
        runCurrent()
        assertEquals(1, transport.sent.count { it.text("type") == "session.snapshot" })

        transport.result(opens.single(), checkNotNull(quoted(opens.single())))
        assertEquals(chat, opening.await())
        runCurrent()
        // The held snapshot (revision 5) is applied, and the gap to 7 asks for exactly one more.
        assertEquals(2, transport.sent.count { it.text("type") == "session.snapshot" })
        runCurrent()
        assertEquals(2, transport.sent.count { it.text("type") == "session.snapshot" })
        assertEquals(listOf("a", "b"), repository.state.value.messages.map { it.text("id") })
        assertFalse(repository.state.value.loading)
    }

    /** A snapshot at [revision] with messages a and b; the requests of [held] types get no reply. */
    private fun heldOpenTransport(revision: () -> Int, vararg held: String): Pair<Transport, MutableList<JsonObject>> {
        val transport = Transport()
        configure(transport)
        quoteMessages(transport)
        val quoted = transport.response
        val waiting = mutableListOf<JsonObject>()
        transport.response = { request ->
            when (request.text("type")) {
                in held -> {
                    waiting += request
                    null
                }
                "session.snapshot" -> JsonObject(checkNotNull(quoted(request)) + ("revision" to JsonPrimitive(revision())))
                else -> quoted(request)
            }
        }
        return transport to waiting
    }

    private fun upsert(revision: Int, id: String) =
        Wire.objectOf(
            "type" to "event", "kind" to "message.upsert", "sessionId" to "session", "revision" to revision,
            "message" to Wire.objectOf("id" to id, "role" to "assistant", "text" to "Answer $id", "state" to "complete"),
        )

    private fun running(revision: Int) =
        Wire.objectOf(
            "type" to "event", "kind" to "session.status", "sessionId" to "session",
            "revision" to revision, "status" to "running",
        )

    private class NavigationSaves : NavigationSnapshotStorage {
        val saves = mutableListOf<Map<String, NavigationSnapshot>>()

        override fun load() = emptyMap<String, NavigationSnapshot>()

        override fun save(snapshots: Map<String, NavigationSnapshot>) {
            saves += snapshots
        }

        fun lastText() = saves.last().getValue("host").messages.lastOrNull()?.optionalText("text")
    }

    /** A chat open and running, with the writes its open caused done. */
    private suspend fun TestScope.streamingChat(
        storage: NavigationSaves,
    ): Pair<Transport, DefaultRemoteRepository> {
        val transport = Transport().also { configure(it, sessions = listOf(session(), session("other"))) }
        val repository = repository(transport, navigationStorage = storage)
        repository.activate(RemoteSelection("host", "project", "session"))
        repository.setForeground(true)
        transport.deliver(running(1))
        advanceTimeBy(5_000)
        runCurrent()
        return transport to repository
    }

    @Test
    fun streamedUpsertsWriteTheNavigationSnapshotAtMostOncePerFifteenSeconds() = runTest {
        val storage = NavigationSaves()
        val (transport, _) = streamingChat(storage)
        val before = storage.saves.size
        assertTrue(before > 0)

        // 100 upserts spread over 15 s.
        for (index in 1..100) {
            transport.deliver(upsert(1 + index, "m$index"))
            advanceTimeBy(NAVIGATION_DIRTY_WRITE_MILLIS / 100)
        }
        runCurrent()
        assertTrue("${storage.saves.size - before} writes", storage.saves.size - before <= 1)

        // The tail reaches storage within the interval of the last event.
        advanceTimeBy(NAVIGATION_DIRTY_WRITE_MILLIS)
        runCurrent()
        assertTrue(storage.saves.size - before <= 2)
        assertEquals("Answer m100", storage.lastText())
        // Nothing streamed since: no further writes.
        val settled = storage.saves.size
        advanceTimeBy(NAVIGATION_DIRTY_WRITE_MILLIS * 4)
        runCurrent()
        assertEquals(settled, storage.saves.size)
    }

    @Test
    fun goingToTheBackgroundWritesTheStreamedTailAtOnce() = runTest {
        val storage = NavigationSaves()
        val (transport, repository) = streamingChat(storage)
        transport.deliver(upsert(2, "tail"))
        runCurrent()
        val before = storage.saves.size

        repository.setForeground(false)
        runCurrent()

        assertEquals(before + 1, storage.saves.size)
        assertEquals("Answer tail", storage.lastText())
    }

    @Test
    fun theEndOfARunWritesTheNavigationSnapshot() = runTest {
        val storage = NavigationSaves()
        val (transport, _) = streamingChat(storage)
        transport.deliver(upsert(2, "last"))
        runCurrent()
        val before = storage.saves.size

        transport.deliver(
            Wire.objectOf(
                "type" to "event", "kind" to "session.status", "sessionId" to "session",
                "revision" to 3, "status" to "idle",
            )
        )
        // Within the write queue's one second, far sooner than the streaming interval.
        advanceTimeBy(1_100)
        runCurrent()

        assertEquals(before + 1, storage.saves.size)
        assertEquals("Answer last", storage.lastText())
        assertEquals("idle", storage.saves.last().getValue("host").sessions.first().text("status"))
    }

    @Test
    fun anotherSessionsRepeatedStatusWritesNothingButItsTransitionDoes() = runTest {
        val storage = NavigationSaves()
        val (transport, _) = streamingChat(storage)
        val before = storage.saves.size
        fun other(revision: Int, status: String) =
            Wire.objectOf(
                "type" to "event", "kind" to "session.status", "sessionId" to "other",
                "revision" to revision, "status" to status,
            )

        transport.deliver(other(1, "idle"))
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(before, storage.saves.size)

        transport.deliver(other(2, "running"))
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(before + 1, storage.saves.size)
        assertEquals(
            "running",
            storage.saves.last().getValue("host").sessions.single { it.text("id") == "other" }.text("status"),
        )
    }

    @Test
    fun eventsThatArriveWhileARestoreHoldsItsSnapshotReachTheChat() = runTest {
        val (transport, held) = heldOpenTransport({ 5 }, "projects.list")
        val repository = repository(transport)
        val chat = RemoteSelection("host", "project", "session")
        val restoring = async { repository.activate(chat, ActivationMode.RESTORE) }
        runCurrent()
        // The snapshot (revision 5) is in; the project list is not, so no Timeline exists yet.
        assertEquals(1, transport.sent.count { it.text("type") == "session.snapshot" })
        assertNull(repository.state.value.selection.sessionId)
        transport.deliver(upsert(6, "c"))
        transport.deliver(running(7))
        runCurrent()

        transport.result(held.single(), JsonObject(data("projects", listOf(project)) + ("capabilities" to JsonArray(emptyList()))))
        assertEquals(chat, restoring.await())
        runCurrent()
        val state = repository.state.value
        assertEquals(listOf("a", "b", "c"), state.messages.map { it.text("id") })
        assertEquals("running", state.status)
        assertFalse(state.loading)
        assertEquals(1, transport.sent.count { it.text("type") == "session.snapshot" })
    }

    @Test
    fun eventsThatArriveWhileALiveOpenRunsReachTheChat() = runTest {
        val (transport, held) = heldOpenTransport({ 5 }, "sessions.open")
        val repository = repository(transport)
        val chat = RemoteSelection("host", "project", "session")
        val opening = async { repository.activate(chat, ActivationMode.USER_OPEN) }
        runCurrent()
        assertEquals(1, transport.sent.count { it.text("type") == "session.snapshot" })
        assertNull(repository.state.value.selection.sessionId)
        transport.deliver(upsert(6, "c"))
        transport.deliver(running(7))
        runCurrent()

        transport.result(held.single(), Wire.objectOf("kind" to "session", "session" to session()))
        assertEquals(chat, opening.await())
        runCurrent()
        val state = repository.state.value
        assertEquals(listOf("a", "b", "c"), state.messages.map { it.text("id") })
        assertEquals("running", state.status)
        assertEquals(1, transport.sent.count { it.text("type") == "session.snapshot" })
    }

    @Test
    fun moreEventsThanAHeldSnapshotKeepsAskForOneResync() = runTest {
        var revision = 5
        val (transport, held) = heldOpenTransport({ revision }, "projects.list")
        val repository = repository(transport)
        val chat = RemoteSelection("host", "project", "session")
        val restoring = async { repository.activate(chat, ActivationMode.RESTORE) }
        runCurrent()
        assertEquals(1, transport.sent.count { it.text("type") == "session.snapshot" })
        // Revisions 6 to 205: the oldest ones no longer fit, so the rest leaves a gap.
        for (next in 6..205) transport.deliver(running(next))
        revision = 205
        runCurrent()

        transport.result(held.single(), JsonObject(data("projects", listOf(project)) + ("capabilities" to JsonArray(emptyList()))))
        assertEquals(chat, restoring.await())
        runCurrent()
        assertEquals(2, transport.sent.count { it.text("type") == "session.snapshot" })
        assertEquals(listOf("a", "b"), repository.state.value.messages.map { it.text("id") })
        assertFalse(repository.state.value.loading)
    }

    @Test
    fun aHeldSnapshotRefusedByTheHostIsAskedForOnceMore() = runTest {
        val transport = Transport()
        configure(transport)
        quoteMessages(transport)
        var refusals = 1
        transport.failure = { if (it.text("type") == "session.snapshot" && refusals-- > 0) "not_found" else null }
        val repository = repository(transport)
        val chat = RemoteSelection("host", "project", "session")
        assertEquals(chat, repository.activate(chat, ActivationMode.USER_OPEN))
        runCurrent()
        // The one read alongside the open was refused before the open revived the chat.
        assertEquals(2, transport.sent.count { it.text("type") == "session.snapshot" })
        assertEquals(chat, repository.state.value.selection)
        assertEquals(listOf("a", "b"), repository.state.value.messages.map { it.text("id") })
        assertNull(repository.state.value.error)
    }

    @Test
    fun aCachedChatTheHostRefusesShowsOnlyTheSnapshotsError() = runTest {
        val transport =
            Transport().apply { capabilities = setOf(CONFIGURATION_CAPABILITY, ADVISOR_CAPABILITY, COMMANDS_CAPABILITY) }
        configure(transport, sessions = listOf(session(), session("other")))
        quoteMessages(transport)
        val answer = transport.response
        transport.response = { request ->
            when (request.text("type")) {
                "session.configuration.get" -> configurationData(request.text("sessionId"))
                "session.commands.get" -> commandData(request.text("sessionId"))
                "session.advisor.get" -> JsonObject(advisorData() + ("sessionId" to JsonPrimitive(request.text("sessionId"))))
                else -> answer(request)
            }
        }
        val repository = repository(transport)
        val chat = RemoteSelection("host", "project", "session")
        repository.activate(chat)
        repository.activate(RemoteSelection("host", "project", "other"))
        runCurrent()
        assertNull(repository.state.value.error)

        // The chat ended meanwhile: the host refuses every read of it.
        transport.failure = {
            if (it.optionalText("sessionId") == "session" && it.text("type") != "sessions.open") "not_found" else null
        }
        assertEquals(chat, repository.activate(chat, ActivationMode.USER_OPEN))
        runCurrent()
        val state = repository.state.value
        assertEquals(chat, state.selection)
        assertEquals(listOf("a", "b"), state.messages.map { it.text("id") })
        assertEquals(R.string.remote_request_error, state.error)
        assertFalse(state.configurationLoading)
        assertFalse(state.advisorLoading)
        assertFalse(state.commandsLoading)
    }

    @Test
    fun aChatLeftBehindIsPreviewedWithItsDraft() = runTest {
        val transport = Transport()
        configure(transport, sessions = listOf(session(), session("other"), session("old", origin = "history")))
        quoteMessages(transport)
        val repository = repository(transport)
        val chat = RemoteSelection("host", "project", "session")
        assertNull(repository.peekCachedChat(chat))
        repository.activate(chat)
        runCurrent()
        repository.draft("half typed")
        repository.activate(RemoteSelection("host", "project", "other"))
        runCurrent()
        val preview = checkNotNull(repository.peekCachedChat(chat))
        assertEquals(listOf("a", "b"), preview.messages.map { it.text("id") })
        assertEquals("half typed", preview.draft)
        assertEquals("session", preview.session?.text("id"))
        // A history session known only from its row previews without messages, to say it opens as a copy.
        val history = checkNotNull(repository.peekCachedChat(RemoteSelection("host", "project", "old")))
        assertTrue(history.messages.isEmpty())
        assertEquals("history", history.session?.text("origin"))
        assertNull(repository.peekCachedChat(RemoteSelection("host", "project", "unknown")))
        // Peeking never touches the repository's own state.
        assertFalse(repository.state.value.preview)
        assertEquals(RemoteSelection("host", "project", "other"), repository.state.value.selection)
    }

    /**
     * [configure], with the named projects of [lists] and each one's sessions, read from [lists] at
     * each request. A sessions.list that [hold] picks gets no reply; it waits in the returned list
     * for the test to answer.
     */
    private fun configureProjects(
        transport: Transport,
        lists: Map<String, List<JsonObject>>,
        hold: (JsonObject) -> Boolean = { false },
        opened: JsonObject = session(),
    ): MutableList<JsonObject> {
        configure(transport, opened = opened)
        val held = mutableListOf<JsonObject>()
        val original = transport.response
        transport.response = { request ->
            when (request.text("type")) {
                "projects.list" ->
                    data("projects", lists.keys.map { Wire.objectOf("id" to it, "name" to "Project $it") })
                "sessions.list" ->
                    if (hold(request)) {
                        held += request
                        null
                    } else data("sessions", lists[request.text("projectId")].orEmpty())
                else -> original(request)
            }
        }
        return held
    }

    private fun Transport.lists(projectId: String? = null) =
        sent.count { it.text("type") == "sessions.list" && (projectId == null || it.text("projectId") == projectId) }

    private fun updated(session: JsonObject, at: Long) = JsonObject(session + ("updatedAt" to JsonPrimitive(at)))

    @Test
    fun returningToProjectsWithinThirtySecondsReadsNoListsUntilAReconnect() = runTest {
        val transport = Transport()
        configureProjects(
            transport,
            mapOf("a" to listOf(session("one", projectId = "a")), "b" to listOf(session("two", projectId = "b"))),
        )
        val repository = repository(transport)
        val root = RemoteSelection("host")
        val project = RemoteSelection("host", "a")
        assertEquals(root, repository.activate(root, ActivationMode.USER_OPEN))
        repository.setForeground(true)
        repository.setValidatedNetwork("wifi")
        runCurrent()
        assertEquals(2, transport.lists())

        // Into a project and back, twice: the lists the Projects page read are still fresh.
        repeat(2) {
            assertEquals(project, repository.activate(project, ActivationMode.USER_OPEN))
            assertEquals(root, repository.activate(root))
            runCurrent()
            advanceTimeBy(10_000)
        }
        assertEquals(2, transport.lists())

        // Once they are 30 s old, a return reads them again.
        advanceTimeBy(PROJECT_LISTS_REFRESH_MILLIS)
        assertEquals(project, repository.activate(project, ActivationMode.USER_OPEN))
        assertEquals(root, repository.activate(root))
        runCurrent()
        assertEquals(4, transport.lists())

        // A reconnect reads them at once.
        val connects = transport.connects
        transport.listener.failed(true, R.string.remote_connection_error)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(connects + 1, transport.connects)
        assertEquals(6, transport.lists())
    }

    @Test
    fun aProjectOpenedWhileTheProjectsPageReadsKeepsTheLateRepliesForTheNextOpen() = runTest {
        val transport = Transport()
        val held = configureProjects(
            transport,
            mapOf(
                "a" to listOf(session("one", projectId = "a")),
                "b" to listOf(session("two", projectId = "b"), session("three", projectId = "b")),
            ),
            // The Projects page's read of b is still on its way.
            hold = { it.text("projectId") == "b" && transport.lists("b") == 1 },
        )
        val repository = repository(transport)
        val root = RemoteSelection("host")
        assertEquals(root, repository.activate(root, ActivationMode.USER_OPEN))
        runCurrent()
        assertEquals(1, held.size)

        // Opening a project the Projects page already read asks the Mac for nothing.
        val a = RemoteSelection("host", "a")
        assertEquals(a, repository.activate(a, ActivationMode.USER_OPEN))
        assertEquals(1, transport.lists("a"))
        assertEquals(listOf("one"), repository.state.value.sessions.map { it.text("id") })

        // The reply that comes after the open is kept, not dropped with the old selection.
        transport.result(
            held.single(),
            data("sessions", listOf(session("two", projectId = "b"), session("three", projectId = "b"))),
        )
        runCurrent()
        assertEquals(
            listOf("two", "three"),
            repository.state.value.projectSessions.getValue("b").sessions.map { it.text("id") },
        )
        val b = RemoteSelection("host", "b")
        assertEquals(b, repository.activate(b, ActivationMode.USER_OPEN))
        assertEquals(1, transport.lists("b"))
        assertEquals(listOf("two", "three"), repository.state.value.sessions.map { it.text("id") })
    }

    @Test
    fun featuredChatsStayVerifiedUntilTheirProjectsReplyReplacesThem() = runTest {
        val transport = Transport()
        val lists =
            mutableMapOf(
                "a" to listOf(session("waits", status = "waiting", projectId = "a")),
                "b" to listOf(session("runs", status = "running", projectId = "b")),
            )
        var holding = false
        val held = configureProjects(transport, lists, hold = { holding })
        val repository = repository(transport)
        val root = RemoteSelection("host")
        repository.activate(root, ActivationMode.USER_OPEN)
        runCurrent()
        assertEquals(listOf("waits", "runs"), repository.state.value.projectChats.map { it.session.text("id") })
        assertTrue(repository.state.value.projectChats.all { it.verified })

        advanceTimeBy(PROJECT_LISTS_REFRESH_MILLIS)
        holding = true
        repository.activate(RemoteSelection("host", "a"), ActivationMode.USER_OPEN)
        repository.activate(root)
        runCurrent()
        assertEquals(2, held.size)
        // Waiting and running chats keep their place while the lists come in.
        assertEquals(listOf("waits", "runs"), repository.state.value.projectChats.map { it.session.text("id") })
        assertTrue(repository.state.value.projectChats.all { it.verified })

        transport.result(
            held.first { it.text("projectId") == "a" },
            data("sessions", listOf(session("waits", projectId = "a"))),
        )
        runCurrent()
        // Only a's reply replaced its row: it now waits for nothing and drops behind b's running chat.
        assertEquals(listOf("runs", "waits"), repository.state.value.projectChats.map { it.session.text("id") })
        assertTrue(repository.state.value.projectChats.all { it.verified })
    }

    @Test
    fun projectSessionsKeepEveryListedSessionWithItsCounts() = runTest {
        val transport = Transport()
        val many =
            List(2) { session("waiting$it", status = "waiting", projectId = "a") } +
                List(3) { session("running$it", status = "running", projectId = "a") } +
                List(10) { session("idle$it", projectId = "a") }
        configureProjects(transport, mapOf("a" to many, "b" to listOf(session("other", projectId = "b"))))
        val repository = repository(transport)
        repository.activate(RemoteSelection("host"), ActivationMode.USER_OPEN)
        runCurrent()
        val state = repository.state.value
        // The Projects page features 12 chats; the aggregate keeps all 16.
        assertEquals(12, state.projectChats.size)
        val a = state.projectSessions.getValue("a")
        assertEquals(15, a.sessions.size)
        assertTrue(a.verified)
        assertEquals(SessionCounts(waiting = 2, running = 3), a.counts)
        assertEquals("Project a", a.projectName)
        assertEquals(SessionCounts(), state.projectSessions.getValue("b").counts)

        // Live status events patch the rows and the counts.
        fun status(sessionId: String, value: String) =
            transport.deliver(
                Wire.objectOf(
                    "type" to "event", "sessionId" to sessionId, "revision" to 1,
                    "kind" to "session.status", "status" to value,
                )
            )
        status("idle0", "running")
        status("waiting0", "idle")
        val patched = repository.state.value.projectSessions.getValue("a")
        assertEquals(SessionCounts(waiting = 1, running = 4), patched.counts)
        assertEquals("running", patched.sessions.first { it.text("id") == "idle0" }.text("status"))

        // A dropped connection leaves the rows unconfirmed and without counts.
        transport.listener.failed(true, R.string.remote_connection_error)
        val dropped = repository.state.value.projectSessions.getValue("a")
        assertFalse(dropped.verified)
        assertNull(dropped.counts)
        assertEquals(15, dropped.sessions.size)
    }

    @Test
    fun projectSessionsRestoredFromTheSavedNavigationCarryNoCounts() = runTest {
        val transport = Transport().also {
            configureNamed(it, sessions = listOf(session(), session("fresh", status = "waiting")))
            it.readyOnConnect = false
        }
        val saved = savedHost(projectId = "project", sessions = listOf(session(status = "waiting")))
        val repository = repository(transport, navigationStorage = SavedNavigation(saved))
        runCurrent()
        val opening = async { repository.activate(RemoteSelection("host"), ActivationMode.USER_OPEN) }
        runCurrent()
        val restored = repository.state.value.projectSessions.getValue("project")
        assertEquals(listOf("session"), restored.sessions.map { it.text("id") })
        assertFalse(restored.verified)
        assertNull(restored.counts)

        transport.listener.ready(emptySet())
        opening.await()
        runCurrent()
        val read = repository.state.value.projectSessions.getValue("project")
        assertEquals(listOf("session", "fresh"), read.sessions.map { it.text("id") })
        assertTrue(read.verified)
        assertEquals(SessionCounts(waiting = 1), read.counts)
    }

    @Test
    fun anUnknownSessionWhileAChatIsOpenReadsOnlyTheProjectThatHoldsIt() = runTest {
        val transport = Transport()
        val lists =
            mutableMapOf(
                "a" to listOf(session("chat", projectId = "a")),
                "b" to listOf(updated(session("old", projectId = "b"), 100)),
                "c" to listOf(updated(session("older", projectId = "c"), 50)),
            )
        configureProjects(transport, lists)
        val repository = repository(transport)
        repository.activate(RemoteSelection("host"), ActivationMode.USER_OPEN)
        runCurrent()
        val chat = RemoteSelection("host", "a", "chat")
        assertEquals(chat, repository.activate(chat))
        runCurrent()
        assertEquals(3, transport.lists())
        fun status(sessionId: String, revision: Long = 1) =
            transport.deliver(
                Wire.objectOf(
                    "type" to "event", "sessionId" to sessionId, "revision" to revision,
                    "kind" to "session.status", "status" to "running",
                )
            )

        // Within 30 s of the lists, a session none holds waits for them to age, with no second report.
        lists["b"] = lists.getValue("b") + updated(session("new", status = "running", projectId = "b"), 200)
        status("new")
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(3, transport.lists())

        // Started in b, the project active most lately, which is read first and alone.
        advanceTimeBy(PROJECT_LISTS_REFRESH_MILLIS)
        runCurrent()
        assertEquals(4, transport.lists())
        assertEquals(2, transport.lists("b"))
        val b = repository.state.value.projectSessions.getValue("b")
        assertEquals(listOf("old", "new"), b.sessions.map { it.text("id") })
        assertEquals(SessionCounts(running = 1), b.counts)
        assertEquals(chat, repository.state.value.selection)
    }

    private fun Transport.status(sessionId: String, value: String = "running", revision: Long = 1) =
        deliver(
            Wire.objectOf(
                "type" to "event", "sessionId" to sessionId, "revision" to revision,
                "kind" to "session.status", "status" to value,
            )
        )

    @Test
    fun aChatCreatedInAProjectTheProjectsPageReadShowsInItsListOnTheWayBack() = runTest {
        val transport = Transport()
        val lists = mutableMapOf("a" to listOf(session("old", projectId = "a")), "b" to listOf(session("other", projectId = "b")))
        configureProjects(transport, lists, opened = session("new", projectId = "a"))
        val repository = repository(transport)
        val root = RemoteSelection("host")
        val a = RemoteSelection("host", "a")
        assertEquals(root, repository.activate(root, ActivationMode.USER_OPEN))
        runCurrent()
        assertEquals(a, repository.activate(a, ActivationMode.USER_OPEN))
        assertEquals(1, transport.lists("a"))

        lists["a"] = listOf(session("new", projectId = "a")) + lists.getValue("a")
        assertEquals(RemoteSelection("host", "a", "new"), repository.createSession("host", "a"))
        runCurrent()
        // Back to the project: the Projects page's read of it no longer stands in for one.
        repository.cancelSelection()
        assertEquals(a, repository.activate(a))
        runCurrent()
        assertEquals(2, transport.lists("a"))
        assertEquals(listOf("new", "old"), repository.state.value.sessions.map { it.text("id") })
        assertEquals(
            listOf("new", "old"),
            repository.state.value.projectSessions.getValue("a").sessions.map { it.text("id") },
        )
    }

    @Test
    fun anUnknownSessionReadForTheProjectOnScreenShowsInItsList() = runTest {
        val transport = Transport()
        val lists = mutableMapOf("a" to listOf(session("old", projectId = "a")), "b" to listOf(session("other", projectId = "b")))
        configureProjects(transport, lists)
        val repository = repository(transport)
        val root = RemoteSelection("host")
        val a = RemoteSelection("host", "a")
        assertEquals(root, repository.activate(root, ActivationMode.USER_OPEN))
        runCurrent()
        assertEquals(a, repository.activate(a, ActivationMode.USER_OPEN))
        runCurrent()

        // A chat started on the Mac in the project on screen.
        lists["a"] = lists.getValue("a") + session("started", status = "running", projectId = "a")
        transport.status("started")
        advanceTimeBy(PROJECT_LISTS_REFRESH_MILLIS + 2_000)
        runCurrent()
        assertEquals(2, transport.lists("a"))
        assertEquals(listOf("old", "started"), repository.state.value.sessions.map { it.text("id") })
        assertTrue(repository.state.value.sessionsFresh)

        // The cache holds the list on screen, so the next open needs no read and shows it.
        repository.cancelSelection()
        assertEquals(a, repository.activate(a, ActivationMode.USER_OPEN))
        runCurrent()
        assertEquals(2, transport.lists("a"))
        assertEquals(listOf("old", "started"), repository.state.value.sessions.map { it.text("id") })
    }

    @Test
    fun aCachedSessionsListIsReadOnceBehindItAndNotAgainWithinFiveSeconds() = runTest {
        val transport = Transport()
        val lists = mutableMapOf("a" to listOf(session("old", projectId = "a")), "b" to listOf(session("other", projectId = "b")))
        configureProjects(transport, lists)
        val repository = repository(transport)
        val root = RemoteSelection("host")
        val a = RemoteSelection("host", "a")
        assertEquals(root, repository.activate(root, ActivationMode.USER_OPEN))
        runCurrent()
        assertEquals(1, transport.lists("a"))

        // A chat started on the Mac since the Projects page read the list.
        advanceTimeBy(SESSION_REFRESH_INTERVAL_MILLIS)
        lists["a"] = listOf(session("started", projectId = "a")) + lists.getValue("a")
        assertEquals(a, repository.activate(a, ActivationMode.USER_OPEN))
        // The cached list shows at once; its read follows.
        assertEquals(listOf("old"), repository.state.value.sessions.map { it.text("id") })
        runCurrent()
        assertEquals(2, transport.lists("a"))
        assertEquals(listOf("started", "old"), repository.state.value.sessions.map { it.text("id") })
        assertTrue(repository.state.value.sessionsFresh)

        // Back and in again within five seconds: the list just read is shown without a read.
        assertEquals(root, repository.activate(root))
        runCurrent()
        advanceTimeBy(SESSION_REFRESH_INTERVAL_MILLIS - 1_000)
        assertEquals(a, repository.activate(a, ActivationMode.USER_OPEN))
        runCurrent()
        assertEquals(2, transport.lists("a"))
        assertEquals(listOf("started", "old"), repository.state.value.sessions.map { it.text("id") })

        // Another project the Projects page read long enough ago is read too, right after.
        val b = RemoteSelection("host", "b")
        assertEquals(b, repository.activate(b, ActivationMode.USER_OPEN))
        runCurrent()
        assertEquals(2, transport.lists("b"))
    }

    @Test
    fun aNewlySharedProjectAppearsWithoutAReconnect() = runTest {
        val transport = Transport()
        val lists = mutableMapOf("a" to listOf(session("one", projectId = "a")))
        configureProjects(transport, lists)
        val repository = repository(transport)
        val root = RemoteSelection("host")
        val a = RemoteSelection("host", "a")
        assertEquals(root, repository.activate(root, ActivationMode.USER_OPEN))
        runCurrent()
        val connects = transport.connects
        fun projectLists() = transport.sent.count { it.text("type") == "projects.list" }
        val read = projectLists()

        // Within ten seconds, the reused list is not read again.
        assertEquals(a, repository.activate(a, ActivationMode.USER_OPEN))
        assertEquals(root, repository.activate(root))
        runCurrent()
        assertEquals(read, projectLists())

        lists["c"] = listOf(session("shared", status = "waiting", projectId = "c"))
        advanceTimeBy(PROJECTS_REVALIDATE_MILLIS)
        assertEquals(a, repository.activate(a, ActivationMode.USER_OPEN))
        assertEquals(root, repository.activate(root))
        runCurrent()
        assertEquals(read + 1, projectLists())
        assertEquals(listOf("a", "c"), repository.state.value.projects.map { it.text("id") })
        // Its sessions are read along with it, though the other lists are not 30 s old.
        assertEquals(1, transport.lists("c"))
        assertEquals(1, transport.lists("a"))
        assertEquals(listOf("shared"), repository.state.value.projectSessions.getValue("c").sessions.map { it.text("id") })
        assertEquals(connects, transport.connects)

        // An unchanged list changes nothing on screen.
        advanceTimeBy(PROJECTS_REVALIDATE_MILLIS)
        val projects = repository.state.value.projects
        assertEquals(a, repository.activate(a, ActivationMode.USER_OPEN))
        runCurrent()
        assertEquals(read + 2, projectLists())
        assertSame(projects, repository.state.value.projects)
    }

    @Test
    fun aProjectListThatArrivesDuringAnOpenIsNotLostToIt() = runTest {
        val transport = Transport()
        var holdB = true
        val lists = mutableMapOf("a" to listOf(session("one", projectId = "a")), "b" to listOf(session("two", projectId = "b")))
        val heldSessions = configureProjects(transport, lists, hold = { holdB && it.text("projectId") == "b" })
        var holdProjects = false
        val heldProjects = mutableListOf<JsonObject>()
        val answer = transport.response
        transport.response = { request ->
            if (holdProjects && request.text("type") == "projects.list") null.also { heldProjects += request }
            else answer(request)
        }
        fun projects(ids: Collection<String>) = data("projects", ids.map { Wire.objectOf("id" to it, "name" to "Project $it") })
        val repository = repository(transport)
        val root = RemoteSelection("host")
        val a = RemoteSelection("host", "a")
        val b = RemoteSelection("host", "b")
        assertEquals(root, repository.activate(root, ActivationMode.USER_OPEN))
        runCurrent()

        // Opening a project reads the reused project list behind it; that reply waits.
        advanceTimeBy(PROJECTS_REVALIDATE_MILLIS)
        holdProjects = true
        assertEquals(a, repository.activate(a, ActivationMode.USER_OPEN))
        runCurrent()
        val revalidation = heldProjects.single()

        // Another project, whose list the Projects page never got, is opened and waits for it.
        val opening = async { repository.activate(b, ActivationMode.USER_OPEN) }
        runCurrent()
        val bList = heldSessions.last()
        // A project shared on the Mac meanwhile comes with the reply, while the open still runs.
        lists["c"] = listOf(session("three", projectId = "c"))
        holdProjects = false
        transport.result(revalidation, projects(lists.keys))
        runCurrent()
        holdB = false
        transport.result(bList, data("sessions", lists.getValue("b")))
        assertEquals(b, opening.await())
        runCurrent()

        // Back on Projects at once, the new project is there.
        assertEquals(root, repository.activate(root))
        runCurrent()
        assertEquals(listOf("a", "b", "c"), repository.state.value.projects.map { it.text("id") })
    }

    @Test
    fun anUnknownSessionOnTheSessionsListReadsThatListAtOnce() = runTest {
        val transport = Transport()
        val lists = mutableMapOf("a" to listOf(session("old", projectId = "a")), "b" to listOf(session("other", projectId = "b")))
        configureProjects(transport, lists)
        val repository = repository(transport)
        val root = RemoteSelection("host")
        val a = RemoteSelection("host", "a")
        assertEquals(root, repository.activate(root, ActivationMode.USER_OPEN))
        runCurrent()
        assertEquals(a, repository.activate(a, ActivationMode.USER_OPEN))
        runCurrent()
        assertEquals(1, transport.lists("a"))

        // Started on the Mac once the list is a few seconds old: read at once.
        advanceTimeBy(SESSION_REFRESH_INTERVAL_MILLIS)
        lists["a"] = lists.getValue("a") + session("started", status = "running", projectId = "a")
        transport.status("started")
        runCurrent()
        assertEquals(2, transport.lists("a"))
        assertEquals(listOf("old", "started"), repository.state.value.sessions.map { it.text("id") })
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(1, transport.lists("b"))

        // Reported just after a read, the list is read again once it is five seconds old.
        lists["a"] = lists.getValue("a") + session("another", status = "running", projectId = "a")
        transport.status("another")
        runCurrent()
        assertEquals(2, transport.lists("a"))
        advanceTimeBy(SESSION_REFRESH_INTERVAL_MILLIS)
        runCurrent()
        assertEquals(3, transport.lists("a"))
        assertEquals(listOf("old", "started", "another"), repository.state.value.sessions.map { it.text("id") })
        assertEquals(1, transport.lists("b"))
    }

    @Test
    fun pullToRefreshReadsTheListsOnScreenAgain() = runTest {
        val transport = Transport()
        val lists = mutableMapOf("a" to listOf(session("one", projectId = "a")), "b" to listOf(session("two", projectId = "b")))
        configureProjects(transport, lists)
        val repository = repository(transport)
        val root = RemoteSelection("host")
        assertEquals(root, repository.activate(root, ActivationMode.USER_OPEN))
        runCurrent()
        fun projectLists() = transport.sent.count { it.text("type") == "projects.list" }
        val read = projectLists()

        // On Projects: the projects and every project's list, however fresh.
        repository.revalidateLists()
        runCurrent()
        assertEquals(read + 1, projectLists())
        assertEquals(4, transport.lists())

        // On a project's Sessions list: the projects and that list.
        val a = RemoteSelection("host", "a")
        assertEquals(a, repository.activate(a, ActivationMode.USER_OPEN))
        runCurrent()
        repository.revalidateLists()
        runCurrent()
        assertEquals(read + 2, projectLists())
        assertEquals(3, transport.lists("a"))
        assertEquals(2, transport.lists("b"))
    }

    @Test
    fun aSessionNoProjectHoldsIsLookedForOnceAWhile() = runTest {
        val transport = Transport()
        val lists = mapOf("a" to listOf(session("chat", projectId = "a")), "b" to listOf(session("other", projectId = "b")))
        configureProjects(transport, lists)
        val repository = repository(transport)
        repository.activate(RemoteSelection("host"), ActivationMode.USER_OPEN)
        runCurrent()
        val chat = RemoteSelection("host", "a", "chat")
        assertEquals(chat, repository.activate(chat))
        runCurrent()
        assertEquals(2, transport.lists())

        // A subagent child that never registers, say, reports one status after another.
        transport.status("child")
        advanceTimeBy(PROJECT_LISTS_REFRESH_MILLIS + 2_000)
        runCurrent()
        assertEquals(4, transport.lists())
        assertEquals(2, transport.lists("a"))
        transport.status("child", value = "idle", revision = 2)
        advanceTimeBy(PROJECT_LISTS_REFRESH_MILLIS + 2_000)
        runCurrent()
        transport.status("child", value = "running", revision = 3)
        advanceTimeBy(PROJECT_LISTS_REFRESH_MILLIS + 2_000)
        runCurrent()
        assertEquals(4, transport.lists())

        // A while later it is looked for again.
        advanceTimeBy(MISSING_SESSION_RETRY_MILLIS)
        transport.status("child", value = "idle", revision = 4)
        advanceTimeBy(PROJECT_LISTS_REFRESH_MILLIS + 2_000)
        runCurrent()
        assertEquals(6, transport.lists())
    }

    @Test
    fun aProjectNoLongerSharedLeavesTheProjectsPageWithinTheThrottle() = runTest {
        val transport = Transport()
        val lists = mutableMapOf(
            "a" to listOf(session("one", status = "waiting", projectId = "a")),
            "b" to listOf(session("two", status = "waiting", projectId = "b")),
        )
        configureProjects(transport, lists)
        val repository = repository(transport)
        val root = RemoteSelection("host")
        repository.activate(root, ActivationMode.USER_OPEN)
        repository.setForeground(true)
        repository.setValidatedNetwork("wifi")
        runCurrent()
        assertEquals(setOf("a", "b"), repository.state.value.projectSessions.keys)
        assertEquals(2, transport.lists())

        // The resume reads the projects again, within the lists' 30 s: b is gone, a not read again.
        lists.remove("b")
        val projectLists = transport.sent.count { it.text("type") == "projects.list" }
        repository.setForeground(false)
        advanceTimeBy(RESUME_QUIET_AWAY_MILLIS + 1_000)
        repository.setForeground(true)
        runCurrent()
        assertTrue(transport.sent.count { it.text("type") == "projects.list" } > projectLists)
        assertEquals(2, transport.lists())
        assertEquals(listOf("one"), repository.state.value.projectChats.map { it.session.text("id") })
        assertEquals(setOf("a"), repository.state.value.projectSessions.keys)
    }

    private fun userMessage(revision: Int, id: String, text: String) =
        Wire.objectOf(
            "type" to "event", "kind" to "message.upsert", "sessionId" to "session", "revision" to revision,
            "message" to Wire.objectOf("id" to id, "role" to "user", "text" to text, "state" to "complete"),
        )

    /** A plain prompt whose request stays unanswered, so its send is still in flight. */
    private suspend fun TestScope.inFlightPrompt(transport: Transport, drafts: Drafts, text: String): RemoteRepository {
        val repository = repository(transport, drafts)
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        repository.draft(text)
        val normal = transport.response
        transport.response = { if (it.text("type") == "session.prompt") null else normal(it) }
        repository.prompt()
        runCurrent()
        return repository
    }

    @Test
    fun keystrokesBecomeOneDelayedDraftWrite() = runTest {
        val drafts = Drafts()
        val repository = repository(Transport().also(::configure), drafts)
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        val saves = drafts.saves
        val typed = "A longer draft, typed one letter at a time"
        for (length in 1..typed.length) repository.draft(typed.take(length))
        runCurrent()
        assertEquals(saves, drafts.saves)
        advanceTimeBy(299)
        runCurrent()
        assertEquals(saves, drafts.saves)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(saves + 1, drafts.saves)
        assertEquals(typed, drafts.values.getValue(DraftKey("host", "session")).text)
    }

    @Test
    fun sendJournalIsWrittenAtOnceAndFlushWritesTypingStillWaitingForItsDelay() = runTest {
        val drafts = Drafts()
        val transport = Transport().also(::configure)
        val repository = inFlightPrompt(transport, drafts, "Journal me")
        val key = DraftKey("host", "session")
        // The clock did not move: the journal is stored before the request is answered.
        assertNotNull(drafts.values.getValue(key).mutationId)
        assertEquals("Journal me", drafts.values.getValue(key).submittedText)
        repository.draft("Typed after")
        val saves = drafts.saves
        repository.flush()
        assertEquals("Typed after", drafts.values.getValue(key).text)
        assertEquals(saves + 1, drafts.saves)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(saves + 1, drafts.saves)
    }

    @Test
    fun plainPromptLeavesTheComposerAndShowsPendingUntilAcknowledgedAndInTheTimeline() = runTest {
        val drafts = Drafts()
        val transport = Transport().also(::configure)
        val repository = inFlightPrompt(transport, drafts, "Hello pi")
        assertEquals("", repository.state.value.draft)
        assertEquals(PendingSendState.SENDING, repository.state.value.pendingSend?.state)
        assertEquals("Hello pi", repository.state.value.pendingSend?.text)
        val request = transport.sent.last { it.text("type") == "session.prompt" }
        transport.result(request, Wire.objectOf("kind" to "accepted"))
        runCurrent()
        assertEquals(PendingSendState.CONFIRMED, repository.state.value.pendingSend?.state)
        transport.deliver(userMessage(1, "user-1", "Hello pi"))
        runCurrent()
        assertNull(repository.state.value.pendingSend)
        assertNull(drafts.values.getValue(DraftKey("host", "session")).mutationId)
    }

    @Test
    fun timelineMessageBeforeTheAcknowledgementClearsThePendingSendOnceAcknowledged() = runTest {
        val transport = Transport().also(::configure)
        val repository = inFlightPrompt(transport, Drafts(), "Hello pi")
        transport.deliver(userMessage(1, "user-1", "Hello pi"))
        runCurrent()
        assertEquals(PendingSendState.SENDING, repository.state.value.pendingSend?.state)
        transport.result(transport.sent.last { it.text("type") == "session.prompt" }, Wire.objectOf("kind" to "accepted"))
        runCurrent()
        assertNull(repository.state.value.pendingSend)
    }

    @Test
    fun unconfirmedPromptIsNotConfirmedAndItsTextReturnsToTheComposer() = runTest {
        val drafts = Drafts()
        val transport = Transport().also(::configure)
        transport.failure = { if (it.text("type") == "session.prompt") "internal" else null }
        val repository = repository(transport, drafts)
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        repository.draft("Keep me")
        repository.prompt()
        runCurrent()
        assertEquals(PendingSendState.NOT_CONFIRMED, repository.state.value.pendingSend?.state)
        assertEquals("Keep me", repository.state.value.draft)
        assertTrue(repository.state.value.uncertain)
        advanceTimeBy(301)
        runCurrent()
        assertEquals("Keep me", drafts.values.getValue(DraftKey("host", "session")).text)
    }

    @Test
    fun resendOfAnUnconfirmedPromptReusesItsMutationId() = runTest {
        val transport = Transport().also(::configure)
        var failing = true
        transport.failure = { if (failing && it.text("type") == "session.prompt") "internal" else null }
        val repository = repository(transport, Drafts())
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        repository.draft("Once only")
        repository.prompt()
        runCurrent()
        failing = false
        repository.prompt()
        runCurrent()
        val requests = transport.sent.filter { it.text("type") == "session.prompt" }
        assertEquals(2, requests.size)
        assertEquals(requests[0].text("requestId"), requests[1].text("requestId"))
        assertEquals("Once only", QuoteCodec.decode(requests[1].text("text")).body)
        assertEquals(PendingSendState.CONFIRMED, repository.state.value.pendingSend?.state)
    }

    @Test
    fun processDeathRightAfterTappingSendKeepsTheJournalAndOffersTheUncertainActions() = runTest {
        val drafts = Drafts()
        val transport = Transport().also(::configure)
        inFlightPrompt(transport, drafts, "Lost in flight")
        val key = DraftKey("host", "session")
        // The composer is already clear, so the journal alone holds the prompt.
        assertEquals("", drafts.values.getValue(key).text)
        val mutationId = drafts.values.getValue(key).mutationId
        assertNotNull(mutationId)
        // A new process reads only what the first one stored.
        val reopened = repository(Transport().also(::configure), Drafts().apply { values = drafts.values })
        reopened.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        assertEquals("Lost in flight", reopened.state.value.draft)
        assertTrue(reopened.state.value.uncertain)
        assertNull(reopened.state.value.pendingSend)
    }

    @Test
    fun flushOnStopWaitsForTheTypingStillInItsDelayAndIsNotDelayedByIt() = runTest {
        val drafts = Drafts()
        val repository = repository(Transport().also(::configure), drafts)
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        repository.draft("Typed just before stop")
        val key = DraftKey("host", "session")
        assertNotEquals("Typed just before stop", drafts.values[key]?.text)
        // No clock advance: the flush itself writes, it does not wait for the delay.
        repository.flush()
        assertEquals("Typed just before stop", drafts.values.getValue(key).text)
    }

    @Test
    fun unresolvedPromptReturnsItsTextToTheComposerAfterRestart() = runTest {
        val drafts = Drafts().apply {
            values = mapOf(DraftKey("host", "session") to StoredDraft(mutationId = "lost", submittedText = "Lost text"))
        }
        val transport = Transport().also(::configure)
        val repository = repository(transport, drafts)
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        assertEquals("Lost text", repository.state.value.draft)
        assertTrue(repository.state.value.uncertain)
        assertNull(repository.state.value.pendingSend)
        assertTrue(transport.sent.none { it.text("type") == "session.prompt" })
    }
}
