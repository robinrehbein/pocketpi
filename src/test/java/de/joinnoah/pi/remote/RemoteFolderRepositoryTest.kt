package de.joinnoah.pi.remote

import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RemoteFolderRepositoryTest {
    private val host = PairedHost("host", "https://relay.test", "device", "secret", "Host")
    private val other = PairedHost("other", "https://relay.test", "device", "secret", "Other")
    private val folderTypes =
        setOf("fs.browse", "fs.mkdir", "project.clone", "project.clone.status", "project.open")

    private class Pairings(var hosts: List<PairedHost>) : PairingStorage {
        override fun load() = hosts

        override fun save(hosts: List<PairedHost>) {
            this.hosts = hosts
        }
    }

    private class Drafts : DraftStorage {
        override fun load() = emptyMap<DraftKey, StoredDraft>()

        override fun save(drafts: Map<DraftKey, StoredDraft>) {}
    }

    private class Transport : RemoteTransport {
        override lateinit var listener: RemoteTransport.Listener
        val sent = mutableListOf<JsonObject>()
        var advertised = listOf(PROJECT_OPEN_CAPABILITY)
        var projects = listOf(Wire.objectOf("id" to "project", "name" to "project"))
        var sessions = listOf(session("session", "project"))
        val failures = mutableMapOf<String, JsonObject>()
        val replies = mutableMapOf<String, (JsonObject) -> JsonObject?>()

        override fun connect(host: PairedHost) {
            listener.ready(emptySet())
        }

        override fun pair(value: JsonObject) {}

        override fun close() {}

        override fun send(payload: JsonObject) {
            sent += payload
            val type = payload.text("type")
            failures.remove(type)?.let { error ->
                listener.message(
                    Wire.objectOf(
                        "type" to "result",
                        "requestId" to payload.text("requestId"),
                        "ok" to false,
                        "error" to error,
                    )
                )
                return
            }
            val data =
                replies[type]?.invoke(payload)
                    ?: when (type) {
                        "projects.list" ->
                            Wire.objectOf(
                                "kind" to "projects",
                                "items" to JsonArray(projects),
                                "capabilities" to JsonArray(advertised.map(::JsonPrimitive)),
                            )
                        "sessions.list" -> Wire.objectOf("kind" to "sessions", "items" to JsonArray(sessions))
                        "session.snapshot" ->
                            Wire.objectOf(
                                "kind" to "snapshot",
                                "sessionId" to payload.text("sessionId"),
                                "revision" to 0,
                                "status" to "idle",
                                "messages" to JsonArray(emptyList()),
                                "pendingQuestions" to JsonArray(emptyList()),
                            )
                        else -> null
                    }
                    ?: return
            listener.message(
                Wire.objectOf(
                    "type" to "result",
                    "requestId" to payload.text("requestId"),
                    "ok" to true,
                    "data" to data,
                )
            )
        }

        fun event(vararg fields: Pair<String, Any?>) =
            listener.message(Wire.objectOf("type" to "host.event", *fields))

        fun of(type: String) = sent.filter { it.text("type") == type }
    }

    companion object {
        fun session(id: String, projectId: String) =
            Wire.objectOf("id" to id, "origin" to "rpc", "status" to "idle", "projectId" to projectId)

        fun entry(name: String, trusted: Boolean = true, piConfig: Boolean = false) =
            Wire.objectOf(
                "name" to name,
                "git" to true,
                "piConfig" to piConfig,
                "shared" to false,
                "trusted" to trusted,
            )

        fun listing(path: String, vararg entries: JsonObject, truncated: Boolean = false) =
            Wire.objectOf(
                "kind" to "fs.listing",
                "root" to "~/Code",
                "path" to path,
                "entries" to JsonArray(entries.toList()),
                "truncated" to truncated,
            )
    }

    private fun TestScope.repository(transport: Transport) =
        DefaultRemoteRepository(
            Pairings(listOf(host, other)),
            Drafts(),
            transport,
            backgroundScope,
            StandardTestDispatcher(testScheduler),
            now = { testScheduler.currentTime },
        )

    /** Begins and runs an open the way the navigator does; null when begin refuses. */
    private suspend fun DefaultRemoteRepository.open(
        routeId: String,
        path: String,
        confirmed: FolderTrustPrompt? = null,
    ) = beginOpenFolder(routeId, path, confirmed)?.let { openFolder(routeId, path, it) }

    /** The host's next project.open answer is this error. */
    private fun Transport.answerOpen(code: String, details: JsonObject? = null) {
        failures["project.open"] =
            if (details == null) Wire.objectOf("code" to code, "message" to code)
            else Wire.objectOf("code" to code, "message" to code, "details" to details)
    }

    private fun JsonObject.openFlags() = keys - setOf("type", "requestId", "path")

    private fun Transport.result(request: JsonObject, data: JsonObject) =
        listener.message(
            Wire.objectOf("type" to "result", "requestId" to request.text("requestId"), "ok" to true, "data" to data)
        )

    private fun started(cloneId: String, path: String) =
        Wire.objectOf("kind" to "clone.started", "cloneId" to cloneId, "path" to path)

    private fun opened(transport: Transport) {
        transport.projects = transport.projects + Wire.objectOf("id" to "opened", "name" to "app")
        transport.sessions = listOf(session("fresh", "opened"))
        transport.replies["project.open"] = {
            Wire.objectOf(
                "kind" to "project.opened",
                "project" to Wire.objectOf("id" to "opened", "name" to "app"),
                "session" to session("fresh", "opened"),
            )
        }
    }

    private suspend fun TestScope.connected(transport: Transport): DefaultRemoteRepository {
        val repository = repository(transport)
        repository.activate(RemoteSelection("host"))
        runCurrent()
        return repository
    }

    @Test
    fun routeCapabilitiesNowMergeFourEntries() = runTest {
        val transport = Transport()
        transport.advertised =
            listOf(STEER_CAPABILITY, FOLLOW_UP_CAPABILITY, TOOL_OUTPUT_CAPABILITY, PROJECT_OPEN_CAPABILITY)
        val repository = connected(transport)
        assertEquals(transport.advertised.toSet(), repository.state.value.capabilities)
        assertTrue(canOpenFolders(repository.state.value))
    }

    @Test
    fun withoutCapabilityNoFolderCommandIsSent() = runTest {
        val transport = Transport()
        transport.advertised = emptyList()
        val repository = connected(transport)
        repository.browseFolder("host", "")
        repository.createFolder("new")
        repository.cloneRepository("https://github.com/org/repo.git", null)
        assertNull(repository.beginOpenFolder("host", "Code"))
        assertNull(repository.openFolder("host", "Code", 1))
        runCurrent()
        assertTrue(transport.sent.none { it.text("type") in folderTypes })
        // The path still moves, so the browser reloads once the capability arrives.
        assertEquals("host", repository.state.value.folders.routeId)
        assertFalse(repository.state.value.folders.loading)
    }

    @Test
    fun browseSendsPathAndPublishesListing() = runTest {
        val transport = Transport()
        transport.replies["fs.browse"] = { listing(it.text("path"), entry("app"), entry("lib"), truncated = true) }
        val repository = connected(transport)
        repository.browseFolder("host", "Code")
        runCurrent()
        val request = transport.of("fs.browse").single()
        assertEquals(setOf("type", "requestId", "path"), request.keys)
        assertEquals("Code", request.text("path"))
        val folders = repository.state.value.folders
        assertEquals("~/Code", folders.root)
        assertEquals(listOf("app", "lib"), folders.entries.map { it.name })
        assertTrue(folders.truncated && folders.loaded && !folders.loading)
        repository.browseFolder("host", "../etc")
        runCurrent()
        assertEquals(1, transport.of("fs.browse").size)
    }

    @Test
    fun browseErrorsMapToMessagesAndBusyOffersRetry() = runTest {
        val transport = Transport()
        val repository = connected(transport)
        transport.failures["fs.browse"] = Wire.objectOf("code" to "busy", "message" to "busy")
        repository.browseFolder("host", "")
        runCurrent()
        assertEquals(R.string.remote_folders_error_browse_busy, repository.state.value.folders.error)
        transport.failures["fs.browse"] = Wire.objectOf("code" to "root_missing", "message" to "gone")
        repository.browseFolder("host", "")
        runCurrent()
        assertEquals(R.string.remote_folders_error_root_missing, repository.state.value.folders.error)
        assertTrue(repository.state.value.connected)
    }

    @Test
    fun errorCodesMapToLocalizedMessages() {
        val expected =
            mapOf(
                "exists" to R.string.remote_folders_error_exists,
                "outside_root" to R.string.remote_folders_error_outside_root,
                "root_missing" to R.string.remote_folders_error_root_missing,
                "invalid_path" to R.string.remote_folders_error_invalid_path,
                "invalid_name" to R.string.remote_folders_error_invalid_name,
                "invalid_url" to R.string.remote_folders_error_invalid_url,
                "not_found" to R.string.remote_folders_error_not_found,
                "offline" to R.string.remote_folders_error_offline,
                "forbidden" to R.string.remote_folders_error_forbidden,
                "trust_required" to R.string.remote_folders_error_trust_required,
                "trust_scope" to R.string.remote_folders_error_trust_scope,
                "trust_denied" to R.string.remote_folders_error_trust_denied,
                "something_new" to R.string.remote_request_error,
            )
        for (command in FolderCommand.entries)
            expected.forEach { (code, message) ->
                assertEquals(code, message, folderErrorMessage(RemoteRequestException(code), command))
            }
        val busy = RemoteRequestException("busy")
        assertEquals(R.string.remote_folders_error_browse_busy, folderErrorMessage(busy, FolderCommand.BROWSE))
        assertEquals(R.string.remote_folders_error_busy, folderErrorMessage(busy, FolderCommand.CLONE))
        assertEquals(R.string.remote_folders_error_host_busy, folderErrorMessage(busy, FolderCommand.OPEN))
        assertEquals(R.string.remote_folders_error_host_busy, folderErrorMessage(busy, FolderCommand.MKDIR))
        val internal = RemoteRequestException("internal")
        assertEquals(R.string.remote_folders_error_internal, folderErrorMessage(internal, FolderCommand.OPEN))
        assertEquals(R.string.remote_request_error, folderErrorMessage(internal, FolderCommand.BROWSE))
        CloneFailure.entries.forEach { assertNotEquals(0, cloneFailureMessage(it)) }
    }

    @Test
    fun mkdirSendsParentAndNameThenEntersNewFolder() = runTest {
        val transport = Transport()
        transport.replies["fs.browse"] = { listing(it.text("path")) }
        transport.replies["fs.mkdir"] = { Wire.objectOf("kind" to "fs.created", "path" to "Code/new") }
        val repository = connected(transport)
        repository.browseFolder("host", "Code")
        runCurrent()
        repository.createFolder(".hidden")
        runCurrent()
        assertTrue(transport.of("fs.mkdir").isEmpty())
        repository.createFolder("new")
        runCurrent()
        val request = transport.of("fs.mkdir").single()
        assertEquals("Code", request.text("parent"))
        assertEquals("new", request.text("name"))
        assertEquals("Code/new", repository.state.value.folders.path)
        transport.failures["fs.mkdir"] = Wire.objectOf("code" to "exists", "message" to "exists")
        repository.createFolder("again")
        runCurrent()
        assertEquals(R.string.remote_folders_error_exists, repository.state.value.folders.notice)
    }

    @Test
    fun cloneTracksHostEventsAndReloadsParentOnSuccess() = runTest {
        val transport = Transport()
        transport.replies["fs.browse"] = { listing(it.text("path")) }
        transport.replies["project.clone"] = {
            Wire.objectOf("kind" to "clone.started", "cloneId" to "clone-1", "path" to "Code/repo")
        }
        val repository = connected(transport)
        repository.browseFolder("host", "Code")
        runCurrent()
        repository.cloneRepository("https://github.com/org/repo.git", null)
        runCurrent()
        val request = transport.of("project.clone").single()
        assertEquals(setOf("type", "requestId", "parent", "url"), request.keys)
        assertEquals("Code", request.text("parent"))
        // An event for a clone this device does not know is ignored.
        transport.event("kind" to "clone.progress", "cloneId" to "other", "phase" to "counting")
        transport.event("kind" to "clone.progress", "cloneId" to "clone-1", "phase" to "receiving", "percent" to 42)
        runCurrent()
        var clone = checkNotNull(repository.state.value.folders.clone)
        assertEquals(CloneStage.RUNNING, clone.stage)
        assertEquals(ClonePhase.RECEIVING, clone.phase)
        assertEquals(42, clone.percent)
        val listings = transport.of("fs.browse").size
        transport.event("kind" to "clone.finished", "cloneId" to "clone-1", "state" to "succeeded", "path" to "Code/repo")
        runCurrent()
        clone = checkNotNull(repository.state.value.folders.clone)
        assertEquals(CloneStage.SUCCEEDED, clone.stage)
        assertEquals("Code/repo", clone.path)
        assertEquals(listings + 1, transport.of("fs.browse").size)
        assertTrue(repository.state.value.connected)
    }

    @Test
    fun cloneSendsOptionalNameAndMapsFailure() = runTest {
        val transport = Transport()
        transport.replies["fs.browse"] = { listing(it.text("path")) }
        transport.replies["project.clone"] = {
            Wire.objectOf("kind" to "clone.started", "cloneId" to "clone-2", "path" to "mine")
        }
        val repository = connected(transport)
        repository.browseFolder("host", "")
        runCurrent()
        repository.cloneRepository("file:///etc", null)
        repository.cloneRepository("git@github.com:org/repo.git", "mine")
        runCurrent()
        val request = transport.of("project.clone").single()
        assertEquals("mine", request.text("name"))
        assertEquals("git@github.com:org/repo.git", request.text("url"))
        transport.event("kind" to "clone.finished", "cloneId" to "clone-2", "state" to "failed", "error" to "auth_failed")
        runCurrent()
        assertEquals(CloneFailure.AUTH_FAILED, repository.state.value.folders.clone?.failure)
        repository.dismissClone()
        assertNull(repository.state.value.folders.clone)
    }

    @Test
    fun malformedHostEventClosesTheChannel() = runTest {
        val transport = Transport()
        val repository = connected(transport)
        transport.event("kind" to "clone.progress", "cloneId" to "x", "phase" to "receiving", "percent" to 101)
        runCurrent()
        assertFalse(repository.state.value.connected)
    }

    @Test
    fun runningCloneIsPolledAfterReconnect() = runTest {
        val transport = Transport()
        transport.replies["fs.browse"] = { listing(it.text("path")) }
        transport.replies["project.clone"] = {
            Wire.objectOf("kind" to "clone.started", "cloneId" to "clone-3", "path" to "repo")
        }
        transport.replies["project.clone.status"] = {
            Wire.objectOf("kind" to "clone", "cloneId" to it.text("cloneId"), "path" to "repo", "state" to "succeeded")
        }
        val repository = connected(transport)
        repository.browseFolder("host", "")
        runCurrent()
        repository.cloneRepository("https://example.com/repo", null)
        runCurrent()
        assertTrue(transport.of("project.clone.status").isEmpty())
        transport.listener.failed(false, R.string.remote_connection_error)
        runCurrent()
        assertEquals(CloneStage.RUNNING, repository.state.value.folders.clone?.stage)
        repository.activate(RemoteSelection("host"))
        runCurrent()
        assertEquals("clone-3", transport.of("project.clone.status").single().text("cloneId"))
        assertEquals(CloneStage.SUCCEEDED, repository.state.value.folders.clone?.stage)
    }

    @Test
    fun succeededCloneStaysWithItsHost() = runTest {
        val transport = Transport()
        transport.replies["fs.browse"] = { listing(it.text("path")) }
        transport.replies["project.clone"] = { started("clone-a", "repo") }
        val repository = connected(transport)
        repository.browseFolder("host", "")
        runCurrent()
        repository.cloneRepository("https://example.com/repo", null)
        runCurrent()
        transport.event("kind" to "clone.finished", "cloneId" to "clone-a", "state" to "succeeded", "path" to "repo")
        runCurrent()
        assertEquals(CloneStage.SUCCEEDED, repository.state.value.folders.clone?.stage)

        repository.activate(RemoteSelection("other"))
        repository.browseFolder("other", "")
        runCurrent()
        assertNull(repository.state.value.folders.clone)
        assertNull(repository.beginOpenFolder("other", "repo"))
        assertNull(repository.beginOpenFolder("host", "repo"))
        assertNull(repository.openFolder("other", "repo", 1))
        assertTrue(transport.of("project.open").isEmpty())
        repository.dismissClone()
        assertEquals("clone-a", repository.state.value.folders.clones["host"]?.cloneId)
    }

    @Test
    fun runningCloneDoesNotBlockOrShowOnAnotherHost() = runTest {
        val transport = Transport()
        transport.replies["fs.browse"] = { listing(it.text("path")) }
        var next = 0
        transport.replies["project.clone"] = { started("clone-${next++}", "repo") }
        val repository = connected(transport)
        repository.browseFolder("host", "")
        runCurrent()
        repository.cloneRepository("https://example.com/repo", null)
        runCurrent()
        repository.activate(RemoteSelection("other"))
        repository.browseFolder("other", "")
        runCurrent()
        assertNull(repository.state.value.folders.clone)
        // The other host's clone event does not touch this host's clone.
        transport.event("kind" to "clone.progress", "cloneId" to "clone-0", "phase" to "receiving")
        runCurrent()
        assertEquals(CloneStage.RUNNING, repository.state.value.folders.clones["host"]?.stage)
        assertNull(repository.state.value.folders.clones["host"]?.phase)
        repository.cloneRepository("https://example.com/repo", null)
        runCurrent()
        assertEquals(2, transport.of("project.clone").size)
        assertEquals("clone-1", repository.state.value.folders.clone?.cloneId)
        repository.dismissClone()
        assertNull(repository.state.value.folders.clone)
        assertEquals("clone-0", repository.state.value.folders.clones["host"]?.cloneId)
    }

    @Test
    fun statusPollRetriesWithBackoffAndRunningCardCanBeHidden() = runTest {
        val transport = Transport()
        transport.replies["fs.browse"] = { listing(it.text("path")) }
        transport.replies["project.clone"] = { started("clone-r", "repo") }
        transport.replies["project.clone.status"] = {
            Wire.objectOf("kind" to "clone", "cloneId" to "clone-r", "path" to "repo", "state" to "running",
                "phase" to "checkout", "percent" to 90)
        }
        val repository = connected(transport)
        repository.browseFolder("host", "")
        runCurrent()
        repository.cloneRepository("https://example.com/repo", null)
        runCurrent()
        transport.failures["project.clone.status"] = Wire.objectOf("code" to "busy", "message" to "busy")
        repository.refreshClone()
        runCurrent()
        assertEquals(1, transport.of("project.clone.status").size)
        advanceTimeBy(2001)
        runCurrent()
        assertEquals(2, transport.of("project.clone.status").size)
        val clone = checkNotNull(repository.state.value.folders.clone)
        assertEquals(ClonePhase.CHECKOUT, clone.phase)
        assertEquals(90, clone.percent)
        repository.dismissClone()
        assertNull(repository.state.value.folders.clone)
    }

    @Test
    fun leavingTheBrowserKeepsAnInFlightClone() = runTest {
        val transport = Transport()
        transport.replies["fs.browse"] = { listing(it.text("path")) }
        val repository = connected(transport)
        repository.browseFolder("host", "")
        runCurrent()
        repository.cloneRepository("https://example.com/repo", null)
        runCurrent()
        // Back while project.clone is in flight: the navigator invalidates the selection.
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts, RemoteNavKey.Projects("host"), RemoteNavKey.FolderBrowser("host"))
        RemoteNavigator(repository, stack, backgroundScope).back()
        runCurrent()
        assertEquals(RemoteNavKey.Projects("host"), stack.last())
        transport.result(transport.of("project.clone").single(), started("clone-k", "repo"))
        runCurrent()
        assertEquals("clone-k", repository.state.value.folders.clones["host"]?.cloneId)
        transport.event("kind" to "clone.finished", "cloneId" to "clone-k", "state" to "succeeded", "path" to "repo")
        runCurrent()
        assertEquals(CloneStage.SUCCEEDED, repository.state.value.folders.clones["host"]?.stage)
    }

    @Test
    fun doubleTapSendsOneOpen() = runTest {
        val transport = Transport()
        transport.replies["fs.browse"] = { listing(it.text("path"), entry("app")) }
        opened(transport)
        val repository = connected(transport)
        repository.browseFolder("host", "")
        runCurrent()
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts, RemoteNavKey.Projects("host"), RemoteNavKey.FolderBrowser("host"))
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.openFolder("host", "app")
        navigation.openFolder("host", "app")
        runCurrent()
        assertEquals(1, transport.of("project.open").size)
        assertTrue(transport.of("project.open").single().openFlags().isEmpty())
        assertEquals(RemoteNavKey.Chat("host", "opened", "fresh"), stack.last())
    }

    @Test
    fun duplicateListingNamesBecomeAnError() = runTest {
        val transport = Transport()
        transport.replies["fs.browse"] = { listing(it.text("path"), entry("a"), entry("a")) }
        val repository = connected(transport)
        repository.browseFolder("host", "")
        runCurrent()
        assertEquals(R.string.remote_request_error, repository.state.value.folders.error)
        assertTrue(repository.state.value.connected)
    }

    @Test
    fun switchingHostsDropsTheOldListingRequest() = runTest {
        val transport = Transport()
        transport.replies["fs.browse"] = { null }
        val repository = connected(transport)
        repository.browseFolder("host", "")
        runCurrent()
        assertTrue(repository.state.value.folders.loading)
        repository.activate(RemoteSelection("other"))
        runCurrent()
        val folders = repository.state.value.folders
        assertFalse(folders.loading || folders.working)
        assertNull(folders.error)
        assertNull(folders.trust)
    }

    @Test
    fun unknownFinishedStateCountsAsFailed() = runTest {
        val transport = Transport()
        transport.replies["fs.browse"] = { listing(it.text("path")) }
        transport.replies["project.clone"] = { started("clone-s", "repo") }
        val repository = connected(transport)
        repository.browseFolder("host", "")
        runCurrent()
        repository.cloneRepository("https://example.com/repo", null)
        runCurrent()
        transport.event("kind" to "clone.finished", "cloneId" to "clone-s", "state" to "paused")
        runCurrent()
        assertTrue(repository.state.value.connected)
        val clone = checkNotNull(repository.state.value.folders.clone)
        assertEquals(CloneStage.FAILED, clone.stage)
        assertEquals(CloneFailure.FAILED, clone.failure)
    }

    @Test
    fun anOpenCancelledBeforeItRanLeavesNothingWorking() = runTest {
        val transport = Transport()
        transport.replies["fs.browse"] = { listing(it.text("path"), entry("app")) }
        opened(transport)
        val repository = connected(transport)
        repository.browseFolder("host", "")
        runCurrent()
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts, RemoteNavKey.Projects("host"), RemoteNavKey.FolderBrowser("host"))
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        navigation.openFolder("host", "app")
        assertTrue(repository.state.value.folders.working)
        // Back before the open job was dispatched cancels it.
        navigation.back()
        runCurrent()
        assertFalse(repository.state.value.folders.working)
        assertTrue(transport.of("project.open").isEmpty())
        assertEquals(RemoteNavKey.Projects("host"), stack.last())
    }

    @Test
    fun unknownClonePhaseAndErrorDoNotBreakTheChannel() = runTest {
        val transport = Transport()
        transport.replies["fs.browse"] = { listing(it.text("path")) }
        transport.replies["project.clone"] = { started("clone-u", "repo") }
        transport.replies["project.clone.status"] = {
            Wire.objectOf("kind" to "clone", "cloneId" to "clone-u", "path" to "repo", "state" to "running", "phase" to "lfs")
        }
        val repository = connected(transport)
        repository.browseFolder("host", "")
        runCurrent()
        repository.cloneRepository("https://example.com/repo", null)
        runCurrent()
        transport.event("kind" to "clone.progress", "cloneId" to "clone-u", "phase" to "receiving")
        transport.event("kind" to "clone.progress", "cloneId" to "clone-u", "phase" to "compressing", "percent" to 5)
        runCurrent()
        assertTrue(repository.state.value.connected)
        assertNull(repository.state.value.folders.clone?.phase)
        assertEquals(5, repository.state.value.folders.clone?.percent)
        repository.refreshClone()
        runCurrent()
        assertNull(repository.state.value.folders.clone?.phase)
        transport.event("kind" to "clone.finished", "cloneId" to "clone-u", "state" to "failed", "error" to "lfs_quota")
        runCurrent()
        assertTrue(repository.state.value.connected)
        assertEquals(CloneFailure.FAILED, repository.state.value.folders.clone?.failure)
    }

    @Test
    fun openSendsAPlainRequestAndOnlyAShownPromptAddsFlags() = runTest {
        val transport = Transport()
        transport.replies["fs.browse"] = { listing(it.text("path"), entry("app"), entry("other")) }
        opened(transport)
        val repository = connected(transport)
        repository.browseFolder("host", "")
        runCurrent()
        // A prompt that was never shown cannot add trust.
        val forged = FolderTrustPrompt("host", "app", piConfig = true)
        assertNull(repository.beginOpenFolder("host", "app", forged))
        assertNull(repository.beginOpenFolder("host", "", null))
        transport.answerOpen("trust_required", Wire.objectOf("piConfig" to false))
        assertNull(repository.open("host", "app"))
        val shown = checkNotNull(repository.state.value.folders.trust)
        assertEquals(FolderTrustPrompt("host", "app", piConfig = false), shown)
        // The shown prompt is for app, not for other.
        assertNull(repository.beginOpenFolder("host", "other", shown))
        assertNull(repository.beginOpenFolder("host", "app", shown.copy(piConfig = true)))
        val sent = transport.of("project.open")
        assertEquals(1, sent.size)
        assertTrue(sent.single().openFlags().isEmpty())
        assertEquals(RemoteSelection("host", "opened", "fresh"), repository.open("host", "app", shown))
        val retry = transport.of("project.open").last()
        assertEquals(setOf("trust"), retry.openFlags())
        assertNotEquals(sent.single().text("requestId"), retry.text("requestId"))
    }

    @Test
    fun piConfigTrustPromptWarnsAndResendsWithTrustOnly() = runTest {
        val transport = Transport()
        transport.replies["fs.browse"] = { listing(it.text("path"), entry("app")) }
        opened(transport)
        val repository = connected(transport)
        repository.browseFolder("host", "")
        runCurrent()
        transport.answerOpen("trust_required", Wire.objectOf("piConfig" to true))
        assertNull(repository.open("host", "app"))
        val prompt = checkNotNull(repository.state.value.folders.trust)
        assertEquals(FolderTrustPrompt("host", "app", piConfig = true), prompt)
        assertFalse(repository.state.value.folders.working)
        assertEquals(RemoteSelection("host", "opened", "fresh"), repository.open("host", "app", prompt))
        val (first, retry) = transport.of("project.open")
        assertTrue(first.openFlags().isEmpty())
        assertEquals(setOf("trust"), retry.openFlags())
        assertNotEquals(first.text("requestId"), retry.text("requestId"))
    }

    @Test
    fun malformedPromptDetailsErrTowardTheWarning() = runTest {
        val transport = Transport()
        transport.replies["fs.browse"] = { listing(it.text("path"), entry("app")) }
        val repository = connected(transport)
        repository.browseFolder("host", "")
        runCurrent()
        for (details in listOf<JsonObject?>(null, Wire.objectOf(), Wire.objectOf("piConfig" to "no"))) {
            transport.answerOpen("trust_required", details)
            assertNull(repository.open("host", "app"))
            assertEquals(FolderTrustPrompt("host", "app", piConfig = true), repository.state.value.folders.trust)
            repository.cancelFolderTrust()
        }
        assertTrue(repository.state.value.connected)
    }

    @Test
    fun cloningAndOtherOpenErrorsBecomeNotices() = runTest {
        val transport = Transport()
        transport.replies["fs.browse"] = { listing(it.text("path"), entry("repo")) }
        val repository = connected(transport)
        repository.browseFolder("host", "")
        runCurrent()
        for ((code, notice) in listOf(
            "cloning" to R.string.remote_folders_clone_in_progress,
            "trust_denied" to R.string.remote_folders_error_trust_denied,
            "trust_scope" to R.string.remote_folders_error_trust_scope,
        )) {
            transport.answerOpen(code)
            assertNull(repository.open("host", "repo"))
            assertEquals(notice, repository.state.value.folders.notice)
            assertNull(repository.state.value.folders.trust)
            assertFalse(repository.state.value.folders.working)
        }
        assertTrue(transport.of("project.open").all { it.openFlags().isEmpty() })
    }

    @Test
    fun aHostSwitchDuringAnOpenSendsNothingFurther() = runTest {
        val transport = Transport()
        transport.replies["fs.browse"] = { listing(it.text("path"), entry("app")) }
        val repository = connected(transport)
        repository.browseFolder("host", "")
        runCurrent()
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts, RemoteNavKey.Projects("host"), RemoteNavKey.FolderBrowser("host"))
        val navigation = RemoteNavigator(repository, stack, backgroundScope)
        // No reply: the open is still in flight when the user switches hosts.
        navigation.openFolder("host", "app")
        runCurrent()
        assertEquals(1, transport.of("project.open").size)
        val sent = transport.sent.size
        repository.activate(RemoteSelection("other"))
        runCurrent()
        transport.result(transport.of("project.open").single(), Wire.objectOf("kind" to "accepted"))
        runCurrent()
        assertEquals(1, transport.of("project.open").size)
        assertTrue(transport.sent.drop(sent).none { it.text("type") in folderTypes + "session.snapshot" })
        val folders = repository.state.value.folders
        assertFalse(folders.working)
        assertNull(folders.trust)
        assertNull(folders.notice)
    }

    @Test
    fun aStaleOpenIdCannotEndANewerOpen() = runTest {
        val transport = Transport()
        transport.replies["fs.browse"] = { listing(it.text("path"), entry("app")) }
        val repository = connected(transport)
        repository.browseFolder("host", "")
        runCurrent()
        val first = checkNotNull(repository.beginOpenFolder("host", "app"))
        assertNull(repository.beginOpenFolder("host", "app"))
        repository.endOpenFolder(first)
        val second = checkNotNull(repository.beginOpenFolder("host", "app"))
        repository.endOpenFolder(first)
        assertTrue(repository.state.value.folders.working)
        assertNull(repository.openFolder("host", "app", first))
        assertTrue(repository.state.value.folders.working)
        assertTrue(transport.of("project.open").isEmpty())
        repository.endOpenFolder(second)
        assertFalse(repository.state.value.folders.working)
    }

    @Test
    fun lostConnectionClearsPromptsAndTheOpen() = runTest {
        val transport = Transport()
        var answer = true
        transport.replies["fs.browse"] = { if (answer) listing(it.text("path"), entry("wild")) else null }
        val repository = connected(transport)
        repository.browseFolder("host", "")
        runCurrent()
        transport.answerOpen("trust_required", Wire.objectOf("piConfig" to false))
        assertNull(repository.open("host", "wild"))
        assertNotNull(repository.state.value.folders.trust)
        // A path change drops the stale prompt.
        repository.browseFolder("host", "wild")
        runCurrent()
        assertNull(repository.state.value.folders.trust)
        repository.browseFolder("host", "")
        runCurrent()
        val openId = checkNotNull(repository.beginOpenFolder("host", "wild"))
        answer = false
        transport.listener.failed(false, R.string.remote_connection_error)
        runCurrent()
        val folders = repository.state.value.folders
        assertFalse(folders.loading || folders.working)
        assertNull(folders.error)
        assertNull(folders.trust)
        assertNull(folders.notice)
        // The open begun before the loss is gone.
        assertNull(repository.openFolder("host", "wild", openId))
    }

    @Test
    fun aLostMkdirReplyWritesNothing() = runTest {
        val transport = Transport()
        transport.replies["fs.browse"] = { listing(it.text("path")) }
        transport.replies["fs.mkdir"] = { null }
        val repository = connected(transport)
        repository.browseFolder("host", "")
        runCurrent()
        repository.createFolder("new")
        runCurrent()
        assertTrue(repository.state.value.folders.working)
        repository.activate(RemoteSelection("other"))
        repository.browseFolder("other", "")
        runCurrent()
        val folders = repository.state.value.folders
        assertEquals("other", folders.routeId)
        assertNull(folders.notice)
        assertFalse(folders.working)
    }

    @Test
    fun validationMirrorsTheHostRules() {
        assertTrue(validFolderName("my-app"))
        assertTrue(validFolderName("Ünïcode"))
        assertFalse(validFolderName(""))
        assertFalse(validFolderName(".git"))
        assertFalse(validFolderName("a/b"))
        assertFalse(validFolderName("a\\b"))
        assertFalse(validFolderName("a\u0007"))
        assertFalse(validFolderName("x".repeat(101)))
        assertFalse(validFolderName("ü".repeat(51)))
        for (url in listOf(
            "https://github.com/org/repo.git",
            "ssh://git@github.com:22/org/repo.git",
            "git@github.com:org/repo.git",
        )) assertTrue(url, validCloneUrl(url))
        for (url in listOf(
            "",
            "http://github.com/org/repo",
            "git://github.com/org/repo",
            "file:///tmp/repo",
            "ext::sh -c touch",
            "-uhttps://x/y",
            "https://user:secret@github.com/org/repo",
            "https://github.com/org/repo?x=1",
            "https://github.com/org/repo#frag",
            "https://github.com/",
            "/local/path",
            "git@github.com:-oProxy",
        )) assertFalse(url, validCloneUrl(url))
        assertEquals("repo", cloneNameFromUrl("https://github.com/org/repo.git"))
        assertEquals("my repo", cloneNameFromUrl("https://github.com/org/my%20repo"))
        assertEquals("repo", cloneNameFromUrl("git@github.com:org/repo.git"))
        assertEquals(listOf("a", "b"), relativePathSegments("a/b"))
        assertNull(relativePathSegments("a/../b"))
        assertNull(relativePathSegments("/abs"))
        assertEquals("a", parentFolderPath("a/b"))
        assertEquals("", parentFolderPath("a"))
    }
}
