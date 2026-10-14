package de.joinnoah.pi.remote

import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GitChangesTest {
    private val host = PairedHost("host", "https://relay.test", "device", "secret", "Host")
    private val sessionId = "019a2f3c-7d41-7b1e-9c55-3e8f1a2b4c6d"
    private val snapshotId = "Q2hhbmdlc1NuYXBzaG90MQ"

    private val fixture: JsonObject by lazy {
        Wire.json.parseToJsonElement(javaClass.getResource("/git-v1.json")!!.readText()).jsonObject
    }

    private fun entries(group: String) =
        fixture.getValue(group).jsonArray.map { it.jsonObject }

    private fun valid(name: String): JsonObject =
        entries("valid").single { it.text("name") == name }.obj("payload")

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
        var failure: (JsonObject) -> String? = { null }

        override fun connect(host: PairedHost) = listener.ready(emptySet())

        override fun pair(value: JsonObject) {}

        override fun close() {}

        override fun send(payload: JsonObject) {
            sent += payload
            val code = failure(payload)
            val reply =
                if (code != null)
                    Wire.objectOf(
                        "type" to "result",
                        "requestId" to payload.text("requestId"),
                        "ok" to false,
                        "error" to Wire.objectOf("code" to code, "message" to code),
                    )
                else
                    response(payload)?.let {
                        Wire.objectOf("type" to "result", "requestId" to payload.text("requestId"), "ok" to true, "data" to it)
                    }
            reply?.let(listener::message)
        }

        fun result(request: JsonObject, data: JsonObject) =
            listener.message(
                Wire.objectOf("type" to "result", "requestId" to request.text("requestId"), "ok" to true, "data" to data)
            )

        fun git() = sent.filter { it.text("type").startsWith("session.git.") }
    }

    private fun Transport.configure(
        capabilities: List<String>,
        others: List<String> = emptyList(),
        git: (JsonObject) -> JsonObject?,
    ) {
        fun session(id: String) = Wire.objectOf("id" to id, "origin" to "rpc", "status" to "idle", "projectId" to "project")
        val sessions = (listOf(sessionId) + others).map(::session)
        response = { request ->
            when (request.text("type")) {
                "projects.list" ->
                    Wire.objectOf(
                        "kind" to "projects",
                        "items" to JsonArray(listOf(Wire.objectOf("id" to "project"))),
                        "capabilities" to JsonArray(capabilities.map(::JsonPrimitive)),
                    )
                "sessions.list" -> Wire.objectOf("kind" to "sessions", "items" to JsonArray(sessions))
                "sessions.open" -> Wire.objectOf("kind" to "session", "session" to session(request.text("sessionId")))
                "session.snapshot" ->
                    Wire.objectOf(
                        "kind" to "snapshot",
                        "sessionId" to request.text("sessionId"),
                        "revision" to 0,
                        "status" to "idle",
                        "messages" to JsonArray(emptyList()),
                        "pendingQuestions" to JsonArray(emptyList()),
                    )
                else -> if (request.text("type").startsWith("session.git.")) git(request) else Wire.objectOf("kind" to "accepted")
            }
        }
    }

    private val gitCapabilities =
        valid("projects-git-capability").obj("data").getValue("capabilities").jsonArray
            .map { it.jsonPrimitive.content }
            .filter { it != PROJECT_OPEN_CAPABILITY }

    private fun TestScope.repository(transport: Transport, drafts: Drafts = Drafts()) =
        DefaultRemoteRepository(
            Pairings(host),
            drafts,
            transport,
            backgroundScope,
            StandardTestDispatcher(testScheduler),
            now = { testScheduler.currentTime },
        )

    private fun withRequest(payload: JsonObject, request: JsonObject): JsonObject =
        JsonObject(payload.obj("data") + ("sessionId" to JsonPrimitive(request.text("sessionId"))))

    private fun command(name: String): JsonObject =
        JsonObject(valid(name) - "requestId")

    // ---- Wire parsing -------------------------------------------------------------------

    @Test
    fun parsesEveryValidFixtureResult() {
        for (entry in entries("valid")) {
            val payload = entry.obj("payload")
            if (payload.text("type") != "result") continue
            val data = payload.obj("data")
            when (data.text("kind")) {
                "git.status" -> parseGitStatus(data, sessionId, GitBase.entries.single { it.wire == data.text("base") })
                "git.diff" -> parseGitDiff(data, sessionId, snapshotId, data.text("path"))
                "git.log" -> parseGitLog(data, sessionId, snapshotId)
            }
        }
        val status = parseGitStatus(valid("status-session-start").obj("data"), sessionId, GitBase.SESSION)
        assertEquals(5, status.files.size)
        assertTrue(status.files.single { it.path == "assets/logo.png" }.binary)
        assertEquals(GitOmitted.TOO_LARGE, status.files.single { it.path == "data/dump.sql" }.omitted)
        val gone = parseGitStatus(valid("status-omitted-base-unavailable").obj("data"), sessionId, GitBase.SESSION)
        assertEquals(GitOmitted.BASE_UNAVAILABLE, gone.files.single().omitted)
        assertNull(gone.files.single().additions)
        assertEquals(GitSince(false, 1790000000000), status.since)
        val first = parseGitStatus(valid("status-first-contact").obj("data"), sessionId, GitBase.SESSION)
        assertTrue(checkNotNull(first.since).firstContact)
        val unavailable = parseGitStatus(valid("status-unavailable").obj("data"), sessionId, GitBase.DEV)
        assertEquals(GitUnavailable.BASE_UNAVAILABLE, unavailable.unavailable)
    }

    @Test
    fun parsesTheDevBaseOfEveryStatus() {
        val dev = parseGitStatus(valid("status-dev").obj("data"), sessionId, GitBase.DEV)
        assertEquals(GitDevBase("origin/dev"), dev.devBase)
        assertEquals("origin/dev", dev.devRef)
        val main = parseGitStatus(valid("status-dev-default-branch").obj("data"), sessionId, GitBase.DEV)
        assertEquals(GitDevBase("origin/main"), main.devBase)
        assertEquals(null, main.devRef)
        assertEquals("main", devBaseName(main.devBase))
        val none = parseGitStatus(valid("status-head-without-dev-base").obj("data"), sessionId, GitBase.HEAD)
        assertEquals(GitDevBase(null), none.devBase)
        assertEquals(null, parseGitStatus(valid("status-session-start").obj("data"), sessionId, GitBase.SESSION).devBase)
        assertEquals("dev", devBaseName(null))
    }

    @Test
    fun rejectsEveryInvalidFixtureResult() {
        for (entry in entries("invalid")) {
            val payload = entry.obj("payload")
            if (payload.text("type") != "result") continue
            val data = payload.obj("data")
            val result = runCatching {
                when (data.text("kind")) {
                    "git.status" ->
                        parseGitStatus(data, sessionId, GitBase.entries.firstOrNull { it.wire == data.text("base") } ?: GitBase.HEAD)
                    "git.diff" -> parseGitDiff(data, sessionId, snapshotId, data.text("path"))
                    else -> parseGitLog(data, sessionId, snapshotId)
                }
            }
            assertTrue(entry.text("name"), result.isFailure)
        }
    }

    @Test
    fun rejectsResultsForAnotherRequest() {
        val data = valid("status-session-start").obj("data")
        assertThrows(IllegalArgumentException::class.java) { parseGitStatus(data, "other", GitBase.SESSION) }
        assertThrows(IllegalArgumentException::class.java) { parseGitStatus(data, sessionId, GitBase.HEAD) }
        val diff = valid("diff-text").obj("data")
        assertThrows(IllegalArgumentException::class.java) { parseGitDiff(diff, sessionId, snapshotId, "other.md") }
    }

    // ---- Repository ---------------------------------------------------------------------

    @Test
    fun sendsNoGitCommandWithoutTheCapability() = runTest {
        val transport = Transport()
        transport.configure(gitCapabilities - GIT_CAPABILITY) { error("must not be sent") }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", sessionId))
        repository.openChanges()
        runCurrent()
        assertTrue(transport.git().isEmpty())
        assertEquals(ChangesFailure.UNSUPPORTED, repository.state.value.changes?.failure)
        assertTrue(runCatching { repository.gitStatus(sessionId, GitBase.HEAD) }.isFailure)
        assertTrue(transport.git().isEmpty())
    }

    @Test
    fun requestsMatchTheSharedFixtureShapes() = runTest {
        val transport = Transport()
        transport.configure(gitCapabilities) { request ->
            when (request.text("type")) {
                "session.git.status" -> withRequest(valid("status-session-start"), request)
                "session.git.diff" -> withRequest(valid("diff-text"), request)
                else -> withRequest(valid("log"), request)
            }
        }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", sessionId))
        assertTrue(GIT_CAPABILITY in repository.state.value.capabilities)
        repository.openChanges()
        runCurrent()
        repository.openChangesFile("README.md")
        runCurrent()
        val sent = transport.git().map { JsonObject(it - "requestId") }
        assertEquals(command("status-command-session"), sent[0])
        assertEquals(command("log-command"), sent[1])
        assertEquals(JsonObject(command("diff-command") + ("path" to JsonPrimitive("README.md"))), sent[2])
        val changes = checkNotNull(repository.state.value.changes)
        assertEquals(GitBase.SESSION, changes.status?.base)
        assertEquals(2, changes.log?.commits?.size)
        assertEquals("README.md", changes.diff?.path)
        assertEquals(5, patchDiff(checkNotNull(changes.diff).patch).count { it.kind != DiffKind.HUNK })
    }

    @Test
    fun devBaseSendsTheDevCommand() = runTest {
        val transport = Transport()
        transport.configure(gitCapabilities) { request ->
            if (request.text("type") == "session.git.status")
                JsonObject(withRequest(valid("status-dev"), request) + ("base" to JsonPrimitive(request.text("base"))))
            else withRequest(valid("log"), request)
        }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", sessionId))
        repository.openChanges()
        runCurrent()
        repository.selectChangesBase(GitBase.DEV)
        runCurrent()
        assertEquals(command("status-command-dev"), JsonObject(transport.git().last { it.text("type") == "session.git.status" } - "requestId"))
        assertEquals("origin/dev", repository.state.value.changes?.status?.devRef)
        assertEquals(GitDevBase("origin/dev"), repository.state.value.changes?.devBase)
    }

    @Test
    fun devBaseOutlivesOtherBasesUntilTheHostReportsItGone() = runTest {
        val transport = Transport()
        var devGone = false
        transport.configure(gitCapabilities) { request ->
            when {
                request.text("type") != "session.git.status" -> withRequest(valid("log"), request)
                request.text("base") == "head" -> withRequest(valid("status-head-unborn"), request)
                devGone -> withRequest(valid("status-unavailable"), request)
                else -> JsonObject(withRequest(valid("status-dev"), request) + ("base" to JsonPrimitive(request.text("base"))))
            }
        }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", sessionId))
        repository.openChanges()
        runCurrent()
        repository.selectChangesBase(GitBase.DEV)
        runCurrent()
        assertEquals(GitDevBase("origin/dev"), repository.state.value.changes?.devBase)
        // A status without devBase (an older host's, here HEAD) keeps the last one.
        repository.selectChangesBase(GitBase.HEAD)
        runCurrent()
        assertEquals(GitBase.HEAD, repository.state.value.changes?.status?.base)
        assertEquals(GitDevBase("origin/dev"), repository.state.value.changes?.devBase)
        devGone = true
        repository.selectChangesBase(GitBase.DEV)
        runCurrent()
        assertEquals(GitUnavailable.BASE_UNAVAILABLE, repository.state.value.changes?.status?.unavailable)
        assertEquals(GitDevBase(null), repository.state.value.changes?.devBase)
    }

    @Test
    fun sessionStartFallsBackToHeadWhenUnavailable() = runTest {
        val transport = Transport()
        transport.configure(gitCapabilities) { request ->
            when (request.text("base")) {
                "session" -> Wire.objectOf("kind" to "git.status", "sessionId" to sessionId, "base" to "session",
                    "available" to false, "reason" to "session_unsupported")
                else -> withRequest(valid("status-head-unborn"), request)
            }
        }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", sessionId))
        repository.openChanges()
        runCurrent()
        assertEquals(listOf("session", "head"), transport.git().map { it.text("base") })
        val changes = checkNotNull(repository.state.value.changes)
        assertEquals(GitBase.HEAD, changes.status?.base)
        assertEquals(GitUnavailable.SESSION_UNSUPPORTED, changes.sessionUnavailable)
        // HEAD has no commits of its own: no log request.
        assertEquals(GitLog(), changes.log)

        // An explicit choice of session start shows why it is unavailable instead.
        repository.selectChangesBase(GitBase.SESSION)
        runCurrent()
        assertEquals(GitUnavailable.SESSION_UNSUPPORTED, repository.state.value.changes?.status?.unavailable)
        assertEquals(3, transport.git().size)
    }

    @Test
    fun notARepositoryDoesNotFallBack() = runTest {
        val transport = Transport()
        transport.configure(gitCapabilities) { request ->
            Wire.objectOf("kind" to "git.status", "sessionId" to sessionId, "base" to request.text("base"),
                "available" to false, "reason" to "not_a_repository")
        }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", sessionId))
        repository.openChanges()
        runCurrent()
        assertEquals(1, transport.git().size)
        assertEquals(GitUnavailable.NOT_A_REPOSITORY, repository.state.value.changes?.status?.unavailable)
    }

    @Test
    fun expiredSnapshotRequestsStatusAgainAndRetriesTheDiffOnce() = runTest {
        val transport = Transport()
        var diffs = 0
        transport.configure(gitCapabilities) { request ->
            when (request.text("type")) {
                "session.git.status" -> withRequest(valid("status-session-start"), request)
                "session.git.log" -> withRequest(valid("log"), request)
                else -> withRequest(valid("diff-text"), request)
            }
        }
        transport.failure = { request ->
            if (request.text("type") == "session.git.diff" && diffs++ == 0) "not_found" else null
        }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", sessionId))
        repository.openChanges()
        runCurrent()
        repository.openChangesFile("README.md")
        runCurrent()
        assertEquals(
            mapOf("session.git.status" to 2, "session.git.log" to 2, "session.git.diff" to 2),
            transport.git().groupingBy { it.text("type") }.eachCount(),
        )
        val changes = checkNotNull(repository.state.value.changes)
        assertEquals("README.md", changes.file)
        assertNull(changes.diffFailure)
        assertEquals("README.md", changes.diff?.path)

        // A second not_found in a row is shown instead of looping.
        transport.failure = { request -> if (request.text("type") == "session.git.diff") "not_found" else null }
        repository.openChangesFile(null)
        repository.openChangesFile("README.md")
        runCurrent()
        val requests = transport.git().size
        assertEquals(ChangesFailure.NOT_FOUND, repository.state.value.changes?.diffFailure)
        runCurrent()
        assertEquals(requests, transport.git().size)
    }

    @Test
    fun binaryAndTooLargeFilesNeedNoDiffRequest() = runTest {
        val transport = Transport()
        transport.configure(gitCapabilities) { request ->
            if (request.text("type") == "session.git.status") withRequest(valid("status-session-start"), request)
            else withRequest(valid("log"), request)
        }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", sessionId))
        repository.openChanges()
        runCurrent()
        repository.openChangesFile("assets/logo.png")
        assertEquals(true, repository.state.value.changes?.diff?.binary)
        repository.openChangesFile("data/dump.sql")
        assertEquals(GitOmitted.TOO_LARGE, repository.state.value.changes?.diff?.omitted)
        assertTrue(transport.git().none { it.text("type") == "session.git.diff" })
    }

    @Test
    fun reviewCommentsPersistInTheDraft() = runTest {
        val transport = Transport()
        transport.configure(gitCapabilities) { request -> withRequest(valid("status-first-contact"), request) }
        val drafts = Drafts()
        val repository = repository(transport, drafts)
        repository.activate(RemoteSelection("host", "project", sessionId))
        repository.openChanges()
        val comment = ReviewComment("README.md", null, 2, "New line", "Why?", GitBase.SESSION)
        assertTrue(repository.setReviewComment(comment))
        assertTrue(repository.setReviewComment(comment.copy(text = "Why this?")))
        // The same line under another base is a separate comment.
        assertTrue(repository.setReviewComment(comment.copy(base = GitBase.HEAD)))
        repository.removeReviewComment(comment.copy(base = GitBase.HEAD))
        assertFalse(repository.setReviewComment(comment.copy(text = " ")))
        repository.flush()
        assertEquals(listOf(comment.copy(text = "Why this?")), repository.state.value.changes?.comments)
        assertEquals(listOf(comment.copy(text = "Why this?")), drafts.values.values.single().reviewComments)
        repository.closeChanges()
        assertNull(repository.state.value.changes)
        repository.openChanges()
        assertEquals(1, repository.state.value.changes?.comments?.size)
        repository.clearReviewComments()
        repository.flush()
        assertTrue(drafts.values.values.single().reviewComments.isEmpty())
    }

    @Test
    fun reviewCommentsRoundTripThroughTheDraftJson() {
        val comment = ReviewComment("src/a.kt", 7, null, "old()", "Keep this", GitBase.DEV)
        assertEquals(comment, reviewComment(comment.json()))
        assertThrows(IllegalArgumentException::class.java) { reviewComment(comment.copy(text = " ").json()) }
    }

    // ---- Prompts ------------------------------------------------------------------------

    @Test
    fun reviewPromptListsLocationQuoteAndCommentInOrder() {
        val prompt =
            reviewPrompt(
                "Review:",
                listOf(
                    ReviewComment("b.kt", null, 3, "val b = 2", "Rename b", GitBase.HEAD),
                    ReviewComment("a.kt", 9, null, "gone()", "Why removed?", GitBase.SESSION),
                    ReviewComment("a.kt", null, 2, "  call()  ", " Check this ", GitBase.SESSION),
                ),
            ) { path, line, removed, base -> "$path:$line" + (if (removed) " (removed, " else " (") + "vs ${base.wire})" }
        assertEquals(
            "Review:\n\n" +
                "a.kt:2 (vs session)\n>   call()\nCheck this\n\n" +
                "a.kt:9 (removed, vs session)\n> gone()\nWhy removed?\n\n" +
                "b.kt:3 (vs head)\n> val b = 2\nRename b",
            prompt,
        )
    }

    @Test
    fun prefillKeepsWhatTheUserTyped() {
        assertEquals("Prompt", prefilledDraft("  ", "Prompt"))
        assertEquals("Mine\n\nPrompt", prefilledDraft("Mine\n", "Prompt"))
    }

    // ---- Header pill --------------------------------------------------------------------

    @Test
    fun actionsRankByDecayedUseWithChangesAndRefreshAsDefaults() {
        assertEquals(DEFAULT_CHAT_ACTIONS, rankChatActions(emptyMap(), 0))
        val day = 24L * 60 * 60 * 1000
        var settings: ActionUsage? = null
        repeat(3) { settings = settings.used(0) }
        val rename = null.used(28 * day)
        // Three uses 28 days ago weigh 0.75, less than one use today.
        assertEquals(0.75, checkNotNull(settings).decayed(28 * day), 1e-9)
        val ranked = rankChatActions(mapOf(ChatAction.SETTINGS to settings!!, ChatAction.RENAME to rename), 28 * day)
        assertEquals(listOf(ChatAction.RENAME, ChatAction.SETTINGS, ChatAction.CHANGES, ChatAction.REFRESH, ChatAction.FILES, ChatAction.NEW_SESSION, ChatAction.SESSION_SETTINGS, ChatAction.JOBS, ChatAction.HISTORY), ranked)
        // One half-life halves a use.
        assertEquals(0.5, null.used(0).decayed(14 * day), 1e-9)
    }

    @Test
    fun pillShowsTwoActionsAndTheRestInTheMenu() {
        val all = ChatAction.entries.toSet()
        val layout = chatActionLayout(listOf(ChatAction.SETTINGS, ChatAction.CHANGES, ChatAction.REFRESH, ChatAction.RENAME), all)
        assertEquals(listOf(ChatAction.CHANGES, ChatAction.SETTINGS), layout.shown)
        assertEquals(listOf(ChatAction.RENAME, ChatAction.REFRESH), layout.menu)
        // Unavailable actions are skipped; a single leftover takes the chevron's place.
        val offline = chatActionLayout(DEFAULT_CHAT_ACTIONS, setOf(ChatAction.REFRESH, ChatAction.SETTINGS, ChatAction.RENAME))
        assertEquals(listOf(ChatAction.RENAME, ChatAction.REFRESH, ChatAction.SETTINGS), offline.shown)
        assertTrue(offline.menu.isEmpty())
    }

    @Test
    fun historyJoinsThePillWithoutDisplacingAnAction() {
        val few = chatActionLayout(DEFAULT_CHAT_ACTIONS, setOf(ChatAction.REFRESH, ChatAction.SETTINGS, ChatAction.NEW_SESSION))
        assertEquals(
            ChatActionLayout(listOf(ChatAction.REFRESH, ChatAction.SETTINGS, ChatAction.NEW_SESSION, ChatAction.HISTORY), emptyList()),
            few.withHistory(),
        )
        val many = chatActionLayout(DEFAULT_CHAT_ACTIONS, ChatAction.entries.toSet() - ChatAction.HISTORY)
        assertEquals(many.shown, many.withHistory().shown)
        assertEquals(many.menu + ChatAction.HISTORY, many.withHistory().menu)
    }

    // ---- Review round 1 -----------------------------------------------------------------

    private fun TestScope.opened(
        git: (JsonObject) -> JsonObject?,
        drafts: Drafts = Drafts(),
        others: List<String> = emptyList(),
    ): Pair<Transport, DefaultRemoteRepository> {
        val transport = Transport()
        transport.configure(gitCapabilities, others, git)
        val repository = repository(transport, drafts)
        return transport to repository
    }

    private fun sessionFixtures(request: JsonObject): JsonObject =
        when (request.text("type")) {
            "session.git.status" -> withRequest(valid("status-session-start"), request)
            "session.git.log" -> withRequest(valid("log"), request)
            else -> withRequest(valid("diff-text"), request)
        }

    @Test
    fun sendingAMessageKeepsPendingReviewComments() = runTest {
        val drafts = Drafts()
        val (transport, repository) = opened(::sessionFixtures, drafts)
        repository.activate(RemoteSelection("host", "project", sessionId))
        repository.openChanges()
        runCurrent()
        val comment = ReviewComment("README.md", null, 2, "New line", "Why?", GitBase.SESSION)
        assertTrue(repository.setReviewComment(comment))
        repository.draft("Unrelated question")
        repository.prompt()
        runCurrent()
        repository.flush()
        assertTrue(transport.sent.any { it.text("type") == "session.prompt" })
        assertEquals("", repository.state.value.draft)
        assertEquals(listOf(comment), drafts.values.values.single().reviewComments)
        repository.closeChanges()
        repository.openChanges()
        assertEquals(listOf(comment), repository.state.value.changes?.comments)
    }

    @Test
    fun offlineReadsShowOfflineWithRetry() = runTest {
        val (transport, repository) = opened(::sessionFixtures)
        repository.activate(RemoteSelection("host", "project", sessionId))
        repository.openChanges()
        runCurrent()
        val requests = transport.git().size
        transport.listener.failed(true, R.string.remote_connection_error)
        runCurrent()
        assertFalse(repository.state.value.connected)
        assertEquals(
            "offline",
            (runCatching { repository.gitStatus(sessionId, GitBase.HEAD) }.exceptionOrNull() as RemoteRequestException).code,
        )
        repository.openChangesFile("README.md")
        assertEquals(ChangesFailure.OFFLINE, repository.state.value.changes?.diffFailure)
        assertEquals("README.md", repository.state.value.changes?.file)
        repository.selectChangesBase(GitBase.DEV)
        assertEquals(ChangesFailure.OFFLINE, repository.state.value.changes?.failure)
        assertEquals(requests, transport.git().size)
    }

    @Test
    fun hostOfflineErrorMapsToOffline() = runTest {
        val (transport, repository) = opened(::sessionFixtures)
        transport.failure = { if (it.text("type") == "session.git.status") "offline" else null }
        repository.activate(RemoteSelection("host", "project", sessionId))
        repository.openChanges()
        runCurrent()
        assertEquals(ChangesFailure.OFFLINE, repository.state.value.changes?.failure)
    }

    @Test
    fun backPressDuringReloadIsNotUndone() = runTest {
        var hold = false
        val held = mutableListOf<JsonObject>()
        val (transport, repository) = opened({ request ->
            if (hold && request.text("type") == "session.git.status") {
                held += request
                null
            } else sessionFixtures(request)
        })
        repository.activate(RemoteSelection("host", "project", sessionId))
        repository.openChanges()
        runCurrent()
        repository.openChangesFile("README.md")
        runCurrent()
        hold = true
        repository.reloadChanges()
        runCurrent()
        repository.openChangesFile(null)
        transport.result(held.single(), sessionFixtures(held.single()))
        runCurrent()
        assertNull(repository.state.value.changes?.file)
        assertFalse(repository.state.value.changes?.diffLoading == true)
    }

    @Test
    fun reconnectKeepsTheOpenDiffAndReadsItAgainInPlace() = runTest {
        var hold = false
        val held = mutableListOf<JsonObject>()
        val (transport, repository) = opened({ request ->
            if (hold) {
                held += request
                null
            } else sessionFixtures(request)
        })
        repository.activate(RemoteSelection("host", "project", sessionId))
        repository.setForeground(true)
        repository.setValidatedNetwork("wifi")
        repository.openChanges()
        runCurrent()
        repository.openChangesFile("README.md")
        repository.requestChangesPreview("README.md")
        runCurrent()
        val shown = checkNotNull(repository.state.value.changes)
        val diff = checkNotNull(shown.diff)
        val preview = shown.previews.getValue("README.md")
        assertFalse(preview.loading)

        hold = true
        transport.listener.failed(true, R.string.remote_connection_error)
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(repository.state.value.connected)
        // Read again after the snapshot, while the diff and its previews stay on screen.
        val status = held.single { it.text("type") == "session.git.status" }
        repository.state.value.changes.let { changes ->
            assertEquals("README.md", changes?.file)
            assertEquals(diff, changes?.diff)
            assertEquals(false, changes?.loading)
            assertEquals(false, changes?.diffLoading)
            assertEquals(preview, changes?.previews?.get("README.md"))
        }
        hold = false
        transport.result(status, sessionFixtures(status))
        runCurrent()
        held.filter { it.text("type") != "session.git.status" }.forEach { transport.result(it, sessionFixtures(it)) }
        runCurrent()
        repository.state.value.changes.let { changes ->
            assertEquals("README.md", changes?.file)
            assertNotNull(changes?.diff)
            assertNull(changes?.diffFailure)
            // The same change listed again keeps its preview.
            assertEquals(preview, changes?.previews?.get("README.md"))
        }
    }

    @Test
    fun aFailedReadAfterAReconnectLeavesTheDiffAndLogShown() = runTest {
        var hold = false
        val held = mutableListOf<JsonObject>()
        val (transport, repository) = opened({ request ->
            if (hold) {
                held += request
                null
            } else sessionFixtures(request)
        })
        repository.activate(RemoteSelection("host", "project", sessionId))
        repository.setForeground(true)
        repository.setValidatedNetwork("wifi")
        repository.openChanges()
        runCurrent()
        repository.openChangesFile("README.md")
        runCurrent()
        val shown = checkNotNull(repository.state.value.changes)
        val diff = checkNotNull(shown.diff)
        val log = checkNotNull(shown.log)

        hold = true
        transport.listener.failed(true, R.string.remote_connection_error)
        advanceTimeBy(5_000)
        runCurrent()
        val status = held.single { it.text("type") == "session.git.status" }
        transport.result(status, sessionFixtures(status))
        runCurrent()
        // The host is busy for the diff and the log read after the fresh status.
        held.filter { it.text("type") != "session.git.status" }.forEach { request ->
            transport.listener.message(
                Wire.objectOf(
                    "type" to "result",
                    "requestId" to request.text("requestId"),
                    "ok" to false,
                    "error" to Wire.objectOf("code" to "busy", "message" to "busy"),
                )
            )
        }
        runCurrent()
        repository.state.value.changes.let { changes ->
            assertEquals("README.md", changes?.file)
            assertEquals(diff, changes?.diff)
            assertNull(changes?.diffFailure)
            assertEquals(log, changes?.log)
            assertNull(changes?.logFailure)
        }
    }

    @Test
    fun returningToAChatReopensTheChangesItHadOpen() = runTest {
        // The session start's files, compared against HEAD instead.
        fun headFixtures(request: JsonObject): JsonObject =
            if (request.text("type") == "session.git.status" && request.text("base") == "head")
                JsonObject(sessionFixtures(request) - "since" + ("base" to JsonPrimitive("head")))
            else sessionFixtures(request)
        val (transport, repository) = opened(::headFixtures, others = listOf("other"))
        val first = RemoteSelection("host", "project", sessionId)
        repository.activate(first)
        repository.openChanges()
        runCurrent()
        repository.selectChangesBase(GitBase.HEAD)
        runCurrent()
        repository.openChangesFile("README.md")
        runCurrent()

        repository.activate(RemoteSelection("host", "project", "other"))
        runCurrent()
        assertNull(repository.state.value.changes)

        val statuses = transport.git().count { it.text("type") == "session.git.status" }
        repository.activate(first)
        runCurrent()
        val changes = checkNotNull(repository.state.value.changes)
        assertEquals(sessionId, changes.sessionId)
        assertEquals(GitBase.HEAD, changes.base)
        assertEquals("README.md", changes.file)
        assertNotNull(changes.diff)
        assertEquals("head", transport.git().last { it.text("type") == "session.git.status" }.text("base"))
        assertEquals(statuses + 1, transport.git().count { it.text("type") == "session.git.status" })

        // Closed before leaving, it stays closed on the way back.
        repository.closeChanges()
        repository.activate(RemoteSelection("host", "project", "other"))
        repository.activate(first)
        runCurrent()
        assertNull(repository.state.value.changes)
    }

    private fun Transport.statuses() = git().count { it.text("type") == "session.git.status" }

    /** The selected session's events follow its snapshot's revision 0 without a gap. */
    private var revision = 0L

    private fun Transport.event(kind: String, vararg fields: Pair<String, Any?>) =
        listener.message(Wire.objectOf("type" to "event", "sessionId" to sessionId, "revision" to ++revision, "kind" to kind, *fields))

    private fun Transport.tool(id: String, name: String, state: String) =
        event(
            "message.upsert",
            "message" to Wire.objectOf("id" to id, "role" to "tool", "text" to "", "state" to state, "toolName" to name),
        )

    @Test
    fun finishedEditsReloadTheOpenChangesInPlaceOnceTheAgentPauses() = runTest {
        var hold = false
        val held = mutableListOf<JsonObject>()
        val (transport, repository) = opened({ request ->
            if (hold && request.text("type") == "session.git.status") {
                held += request
                null
            } else sessionFixtures(request)
        })
        repository.activate(RemoteSelection("host", "project", sessionId))
        repository.openChanges()
        runCurrent()
        val status = checkNotNull(repository.state.value.changes?.status)
        val statuses = transport.statuses()

        // A read, a running edit and a failed edit change nothing.
        transport.tool("read", "read", "complete")
        transport.tool("running", "edit", "streaming")
        transport.tool("failed", "edit", "error")
        advanceTimeBy(CHANGES_LIVE_RELOAD_MILLIS + 1)
        assertEquals(statuses, transport.statuses())

        // Edits in quick succession read once, two seconds after the last one.
        hold = true
        transport.tool("one", "edit", "complete")
        advanceTimeBy(1_000)
        transport.tool("two", "write", "complete")
        advanceTimeBy(CHANGES_LIVE_RELOAD_MILLIS - 1)
        assertEquals(statuses, transport.statuses())
        advanceTimeBy(2)
        assertEquals(statuses + 1, transport.statuses())
        // The status stays on screen while it is read again, with no spinner.
        repository.state.value.changes.let { changes ->
            assertEquals(status, changes?.status)
            assertEquals(false, changes?.loading)
            assertNull(changes?.failure)
        }

        // A hint while that read runs waits for it, then reads once more.
        transport.tool("three", "bash", "error")
        advanceTimeBy(CHANGES_LIVE_RELOAD_MILLIS + 1)
        assertEquals(statuses + 1, transport.statuses())
        hold = false
        transport.result(held.single(), sessionFixtures(held.single()))
        runCurrent()
        assertEquals(statuses + 2, transport.statuses())
        assertEquals(false, repository.state.value.changes?.loading)
        assertNotNull(repository.state.value.changes?.status)
    }

    @Test
    fun aRunThatEndsReloadsTheOpenChangesButAnotherSessionsEventsDoNot() = runTest {
        val (transport, repository) = opened(::sessionFixtures, others = listOf("other"))
        repository.activate(RemoteSelection("host", "project", sessionId))
        repository.openChanges()
        runCurrent()
        val statuses = transport.statuses()

        // Another session's edit is not this view's change.
        transport.listener.message(
            Wire.objectOf(
                "type" to "event", "sessionId" to "other", "revision" to 1, "kind" to "message.upsert",
                "message" to Wire.objectOf("id" to "t", "role" to "tool", "state" to "complete", "toolName" to "edit"),
            )
        )
        advanceTimeBy(CHANGES_LIVE_RELOAD_MILLIS + 1)
        assertEquals(statuses, transport.statuses())

        transport.event("session.status", "status" to "running")
        advanceTimeBy(CHANGES_LIVE_RELOAD_MILLIS + 1)
        assertEquals(statuses, transport.statuses())
        transport.event("session.status", "status" to "idle")
        advanceTimeBy(CHANGES_LIVE_RELOAD_MILLIS + 1)
        assertEquals(statuses + 1, transport.statuses())

        // A closed view reads nothing.
        repository.closeChanges()
        transport.tool("after", "edit", "complete")
        advanceTimeBy(CHANGES_LIVE_RELOAD_MILLIS + 1)
        assertEquals(statuses + 1, transport.statuses())
    }

    @Test
    fun reopeningChangesShowsTheLastStatusAtOnceAndReadsItAgain() = runTest {
        val (transport, repository) = opened(::sessionFixtures)
        repository.activate(RemoteSelection("host", "project", sessionId))
        repository.openChanges()
        runCurrent()
        val shown = checkNotNull(repository.state.value.changes)
        val status = checkNotNull(shown.status)
        assertEquals(testScheduler.currentTime, shown.fetchedAt)
        repository.closeChanges()
        val statuses = transport.statuses()

        advanceTimeBy(60_000)
        repository.openChanges()
        repository.state.value.changes.let { changes ->
            assertEquals(status, changes?.status)
            assertEquals(shown.log, changes?.log)
            assertEquals(false, changes?.loading)
            assertEquals(shown.fetchedAt, changes?.fetchedAt)
        }
        runCurrent()
        assertEquals(statuses + 1, transport.statuses())
        assertEquals(testScheduler.currentTime, repository.state.value.changes?.fetchedAt)

        // Offline, the last status shows with when it was read, and nothing is asked.
        val fetchedAt = repository.state.value.changes?.fetchedAt
        repository.closeChanges()
        transport.listener.failed(true, R.string.remote_connection_error)
        runCurrent()
        val requests = transport.git().size
        repository.openChanges()
        repository.state.value.changes.let { changes ->
            assertEquals(status, changes?.status)
            assertNull(changes?.failure)
            assertEquals(fetchedAt, changes?.fetchedAt)
        }
        // Reload by hand still says why it cannot read.
        repository.reloadChanges()
        assertEquals(ChangesFailure.OFFLINE, repository.state.value.changes?.failure)
        assertEquals(requests, transport.git().size)
    }

    @Test
    fun reloadKeepsLoadedPreviewsOfUnchangedFiles() = runTest {
        val (_, repository) = opened(::sessionFixtures)
        repository.activate(RemoteSelection("host", "project", sessionId))
        repository.openChanges()
        runCurrent()
        repository.requestChangesPreview("README.md")
        runCurrent()
        val preview = repository.state.value.changes?.previews?.get("README.md")
        assertEquals(false, preview?.loading)
        repository.reloadChanges()
        assertEquals(true, repository.state.value.changes?.loading)
        assertEquals(preview, repository.state.value.changes?.previews?.get("README.md"))
        runCurrent()
        assertEquals(preview, repository.state.value.changes?.previews?.get("README.md"))
    }

    @Test
    fun reloadKeepsTheHeadFallbackNotice() = runTest {
        val (_, repository) = opened({ request ->
            when (request.text("base")) {
                "session" -> Wire.objectOf("kind" to "git.status", "sessionId" to sessionId, "base" to "session",
                    "available" to false, "reason" to "base_unavailable")
                else -> withRequest(valid("status-head-unborn"), request)
            }
        })
        repository.activate(RemoteSelection("host", "project", sessionId))
        repository.openChanges()
        runCurrent()
        assertEquals(GitUnavailable.BASE_UNAVAILABLE, repository.state.value.changes?.sessionUnavailable)
        repository.reloadChanges()
        runCurrent()
        assertEquals(GitBase.HEAD, repository.state.value.changes?.status?.base)
        assertEquals(GitUnavailable.BASE_UNAVAILABLE, repository.state.value.changes?.sessionUnavailable)
    }

    @Test
    fun commentsStopAtTheLimit() = runTest {
        val (_, repository) = opened(::sessionFixtures)
        repository.activate(RemoteSelection("host", "project", sessionId))
        repository.openChanges()
        runCurrent()
        repeat(MAX_REVIEW_COMMENTS) {
            assertTrue(repository.setReviewComment(ReviewComment("a", null, it + 1, "", "c", GitBase.SESSION)))
        }
        assertFalse(repository.setReviewComment(ReviewComment("a", null, 999, "", "c", GitBase.SESSION)))
        // Editing an existing comment still works at the limit.
        assertTrue(repository.setReviewComment(ReviewComment("a", null, 1, "", "edited", GitBase.SESSION)))
        assertEquals(MAX_REVIEW_COMMENTS, repository.state.value.changes?.comments?.size)
    }

    @Test
    fun logParserIsStrict() {
        val log = valid("log").obj("data")
        fun parse(data: JsonObject) = runCatching { parseGitLog(data, sessionId, snapshotId) }
        assertTrue(parse(log).isSuccess)
        assertTrue(parse(JsonObject(log + ("extra" to JsonPrimitive(1)))).isFailure)
        assertTrue(parse(JsonObject(log + ("reason" to JsonPrimitive("base_unavailable")))).isFailure)
        val commits = log.array("commits")
        val negative = JsonArray(listOf(JsonObject(commits[0] + ("time" to JsonPrimitive(-1)))))
        assertTrue(parse(JsonObject(log + ("commits" to negative))).isFailure)
        val extraKey = JsonArray(listOf(JsonObject(commits[0] + ("body" to JsonPrimitive("x")))))
        assertTrue(parse(JsonObject(log + ("commits" to extraKey))).isFailure)
        val unavailable = valid("log-unavailable").obj("data")
        assertTrue(parse(unavailable).isSuccess)
        assertTrue(parse(JsonObject(unavailable + ("reason" to JsonPrimitive("not_a_repository")))).isFailure)
    }

    @Test
    fun diffCarriesBaseUnavailableOmission() {
        val data = JsonObject(valid("diff-too-large").obj("data") + ("omitted" to JsonPrimitive("base_unavailable")))
        assertEquals(GitOmitted.BASE_UNAVAILABLE, parseGitDiff(data, sessionId, snapshotId, "data/dump.sql").omitted)
    }

    // ---- Review round 2 -----------------------------------------------------------------

    @Test
    fun badStoredCommentsNeverCostTheDraft() {
        val stored =
            JsonArray(
                listOf(
                    Wire.objectOf(
                        "routeId" to "host",
                        "sessionId" to sessionId,
                        "text" to "Keep me",
                        "reviewComments" to JsonArray(
                            listOf(
                                // Written before comments recorded their base.
                                Wire.objectOf("path" to "a.kt", "oldLine" to null, "newLine" to 3, "quoted" to "x", "text" to "Old"),
                                Wire.objectOf("path" to "", "newLine" to "three", "text" to 5),
                                JsonPrimitive("garbage"),
                            )
                        ),
                    )
                )
            )
        val draft = parseStoredDrafts(stored.toString().toByteArray()).getValue(DraftKey("host", sessionId))
        assertEquals("Keep me", draft.text)
        assertEquals(listOf(ReviewComment("a.kt", null, 3, "x", "Old", GitBase.SESSION)), draft.reviewComments)
    }

    @Test
    fun binaryIsOnlyEverTrue() {
        val data = valid("status-session-start").obj("data")
        val files = data.array("files").map {
            if (it.text("path") == "assets/logo.png") JsonObject(it + ("binary" to JsonPrimitive(false))) else it
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseGitStatus(JsonObject(data + ("files" to JsonArray(files))), sessionId, GitBase.SESSION)
        }
    }

    @Test
    fun busyStatusOffersARetryThatSucceeds() = runTest {
        var busy = true
        val (transport, repository) = opened(::sessionFixtures)
        transport.failure = { if (busy && it.text("type") == "session.git.status") "busy" else null }
        repository.activate(RemoteSelection("host", "project", sessionId))
        repository.openChanges()
        runCurrent()
        assertEquals(ChangesFailure.BUSY, repository.state.value.changes?.failure)
        busy = false
        repository.reloadChanges()
        runCurrent()
        assertNull(repository.state.value.changes?.failure)
        assertEquals(GitBase.SESSION, repository.state.value.changes?.status?.base)
    }
}
