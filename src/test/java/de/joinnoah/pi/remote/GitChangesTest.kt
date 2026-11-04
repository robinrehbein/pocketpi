package de.joinnoah.pi.remote

import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
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
    private val pushFixture: JsonObject by lazy {
        Wire.json.parseToJsonElement(javaClass.getResource("/git-push-v1.json")!!.readText()).jsonObject
    }
    private fun pushData(name: String): JsonObject = pushFixture.array("valid").single { it.text("name") == name }.obj("payload").obj("data")

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
        var failSave = false
        var onSave: ((Map<DraftKey, StoredDraft>) -> Unit)? = null

        override fun load() = values

        override fun save(drafts: Map<DraftKey, StoredDraft>) {
            if (failSave) error("storage failed")
            onSave?.invoke(drafts)
            values = drafts.toMap()
        }
    }

    private class Transport : RemoteTransport {
        override lateinit var listener: RemoteTransport.Listener
        val sent = mutableListOf<JsonObject>()
        var response: (JsonObject) -> JsonObject? = { null }
        var failure: (JsonObject) -> String? = { null }
        var authCapabilities: Set<String> = emptySet()

        override fun connect(host: PairedHost) = listener.ready(authCapabilities)

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
    fun pullRequestStatusWorksWithoutCreateAndLateRepliesAreAccepted() = runTest {
        val transport = Transport().also { it.configure(gitCapabilities + SESSION_GIT_PULL_REQUEST_STATUS_CAPABILITY) { null } }
        val repo = repository(transport)
        repo.activate(RemoteSelection("host", "project", sessionId))
        val pending = async { repo.gitPullRequestStatus(sessionId, "d3JpdGUtaWQtdG9rZW4tMQ") }
        runCurrent()
        val sent = transport.git().single()
        advanceTimeBy(REQUEST_TIMEOUT_MILLIS + 1); runCurrent()
        assertFalse(pending.isCompleted)
        transport.result(sent, Wire.objectOf("kind" to "git.pull_request.status", "sessionId" to sessionId, "state" to "absent"))
        runCurrent()
        assertEquals(GitPullRequestStatus.ABSENT, pending.await().state)
        assertTrue(runCatching { repo.gitPullRequestPrepare(sessionId) }.isFailure)
        assertEquals(listOf("session.git.pull_request.status"), transport.git().map { it.text("type") })
    }

    @Test
    fun pullRequestLateSuccessAndLostReplyKeepExactRequestWithoutRetry() = runTest {
        val transport = Transport().also { it.configure(gitCapabilities + SESSION_GIT_PULL_REQUEST_CAPABILITY) { null } }
        val repo = repository(transport)
        repo.activate(RemoteSelection("host", "project", sessionId))
        val token = "d3JpdGUtaWQtdG9rZW4tMQ"
        var sent = false
        val pending = async { repo.gitPullRequest(sessionId, token, "dev", "Title", "body", false, "fixed-pr") { sent = true } }
        runCurrent(); assertTrue(sent)
        val request = transport.git().single()
        assertEquals("fixed-pr", request.text("requestId"))
        advanceTimeBy(REQUEST_TIMEOUT_MILLIS + 1); runCurrent(); assertFalse(pending.isCompleted)
        transport.result(request, Wire.objectOf("kind" to "git.pull_request", "sessionId" to sessionId, "available" to true,
            "created" to true, "number" to 12, "url" to "https://github.com/a/b/pull/12", "base" to "dev"))
        runCurrent(); assertTrue(pending.await() is GitWriteResult.PullRequest.Available)
        val lost = async { repo.gitPullRequest(sessionId, token, "dev", "Title", "", false, "lost-pr") }
        runCurrent(); advanceTimeBy(GIT_PULL_REQUEST_TIMEOUT_MILLIS + 1); runCurrent()
        assertEquals(GitWriteResult.PullRequest.Unknown, lost.await())
        assertEquals(listOf("fixed-pr", "lost-pr"), transport.git().map { it.text("requestId") })
    }

    @Test
    fun pullRequestCapabilitiesFromAuthAndV2DoNotAuthorize() = runTest {
        val caps = setOf(SESSION_GIT_PULL_REQUEST_CAPABILITY, SESSION_GIT_PULL_REQUEST_STATUS_CAPABILITY)
        val transport = Transport().also { it.authCapabilities = caps; it.configure(gitCapabilities + caps) { error("must not send") } }
        val response = transport.response
        transport.response = { request ->
            val result = response(request)
            if (request.text("type") == "projects.list" && request.text("requestId").startsWith("capabilities.v3:")) JsonObject(checkNotNull(result) - "capabilities") else result
        }
        val repo = repository(transport); repo.activate(RemoteSelection("host", "project", sessionId))
        assertTrue(caps.intersect(repo.state.value.capabilities).isEmpty())
        assertTrue(runCatching { repo.gitPullRequestPrepare(sessionId) }.isFailure)
        assertTrue(runCatching { repo.gitPullRequestStatus(sessionId, "d3JpdGUtaWQtdG9rZW4tMQ") }.isFailure)
        assertTrue(transport.git().isEmpty())
    }

    @Test
    fun queuedComposerSnapshotPreservesPullRequestIntentAndRemoval() = runTest {
        val storage = Drafts()
        val transport = Transport().also { it.configure(gitCapabilities) { null } }
        val repo = repository(transport, storage)
        val selection = RemoteSelection("host", "project", sessionId); repo.activate(selection)
        val intent = GitPullRequestIntent("feature", "a".repeat(40), "d3JpdGUtaWQtdG9rZW4tMQ", "fixed-pr", GitWriteTarget("origin", "feature", "github.com", "a/b"), "dev", "Title", "body", false)
        var queued = false
        storage.onSave = { values -> if (!queued && values[DraftKey("host", sessionId)]?.gitPullRequest != null) {
            queued = true; repo.draft("composer"); backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { repo.flush() }
        } }
        repo.saveGitPullRequestIntent(selection, intent); runCurrent(); repo.flush()
        assertTrue(queued); assertEquals(intent, storage.values[DraftKey("host", sessionId)]?.gitPullRequest)
        val reopened = repository(Transport().also { it.configure(gitCapabilities) { null } }, storage)
        reopened.activate(selection); assertEquals(intent, reopened.gitPullRequestIntent(selection))
        storage.onSave = null; repo.remove("host"); runCurrent(); repo.flush()
        assertTrue(storage.values.keys.none { it.routeId == "host" })
    }

    @Test
    fun pullRequestEditDraftQueuePreservesOtherIntentsAndForget() = runTest {
        val storage = Drafts()
        val repo = repository(Transport().also { it.configure(gitCapabilities) { null } }, storage)
        val selection = RemoteSelection("host", "project", sessionId); repo.activate(selection)
        val intent = GitPullRequestIntent("feature", "a".repeat(40), "d3JpdGUtaWQtdG9rZW4tMQ", "fixed-pr", GitWriteTarget("origin", "feature", "github.com", "a/b"), "dev", "Title", "body", false)
        repo.saveGitPullRequestIntent(selection, intent)
        val edit = GitPullRequestDraft("dev", "", "saved body", false)
        var queued = false
        storage.onSave = { values -> if (!queued && values[DraftKey("host", sessionId)]?.gitPullRequestDraft != null) {
            queued = true; repo.draft("composer"); backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { repo.flush() }
        } }
        repo.saveGitPullRequestDraft(selection, edit); runCurrent(); repo.flush()
        val saved = storage.values.getValue(DraftKey("host", sessionId))
        assertTrue(queued); assertEquals(edit, saved.gitPullRequestDraft); assertEquals(intent, saved.gitPullRequest); assertEquals("composer", saved.text)
        val reopened = repository(Transport().also { it.configure(gitCapabilities) { null } }, storage); reopened.activate(selection)
        assertEquals(edit, reopened.gitPullRequestDraft(selection)); assertEquals(intent, reopened.gitPullRequestIntent(selection))
        storage.onSave = null; repo.remove("host"); runCurrent(); repo.flush(); assertTrue(storage.values.keys.none { it.routeId == "host" })
    }

    @Test
    fun pullRequestIntentRoundTripsAndOldDraftsRemainReadable() {
        val intent = GitPullRequestIntent("feature", "a".repeat(40), "d3JpdGUtaWQtdG9rZW4tMQ", "fixed-pr", GitWriteTarget("origin", "feature", "github.com", "a/b"), "dev", "Title", "body", false)
        assertEquals(intent, parseGitPullRequestIntent(intent.json()))
        assertTrue(runCatching { parseGitPullRequestIntent(intent.copy(base = "/bad").json()) }.isFailure)
        val bytes = JsonArray(listOf(Wire.objectOf("routeId" to "host", "sessionId" to sessionId, "text" to "composer", "gitPullRequest" to intent.json()))).toString().toByteArray()
        assertEquals(intent, parseStoredDrafts(bytes).getValue(DraftKey("host", sessionId)).gitPullRequest)
        val old = JsonArray(listOf(Wire.objectOf("routeId" to "host", "sessionId" to sessionId, "text" to "old"))).toString().toByteArray()
        assertNull(parseStoredDrafts(old).getValue(DraftKey("host", sessionId)).gitPullRequest)
    }

    @Test
    fun pushStatusWorksWhenWritesAreDisabledAndNeverSendsPush() = runTest {
        val transport = Transport()
        transport.configure(gitCapabilities + GIT_PUSH_STATUS_CAPABILITY) { request ->
            assertEquals("session.git.push.status", request.text("type"))
            pushData("push-status-matches")
        }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", sessionId))
        val token = "d3JpdGUtaWQtdG9rZW4tMQ"
        assertEquals(GitPushStatus.MATCHES, repository.gitPushStatus(sessionId, token).state)
        assertTrue(runCatching { repository.gitPush(sessionId, token, "push-id") }.isFailure)
        assertTrue(runCatching { repository.gitPushPrepare(sessionId) }.isFailure)
        assertEquals(listOf("session.git.push.status"), transport.git().map { it.text("type") })
    }

    @Test
    fun pushPreparationRequiresHeadAndPushKeepsTheRequestId() = runTest {
        val transport = Transport()
        val branchFixture = pushData("branch-head-sha1")
        var includeHead = false
        transport.configure(gitCapabilities + GIT_PUSH_CAPABILITY) { request ->
            when (request.text("type")) {
                "session.git.branch" -> if (includeHead) branchFixture else JsonObject(branchFixture - "head")
                "session.git.push" -> Wire.objectOf("kind" to "git.push", "sessionId" to sessionId, "pushed" to true, "branch" to "feature", "remoteBranch" to "feature", "commit" to "a".repeat(40), "created" to false, "upstreamSet" to true)
                else -> error("unexpected")
            }
        }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", sessionId))
        assertTrue(runCatching { repository.gitPushPrepare(sessionId) }.isFailure)
        includeHead = true
        val branch = repository.gitPushPrepare(sessionId) as GitWriteResult.Branch.Available
        assertEquals("a".repeat(40), branch.head)
        val pushed = repository.gitPush(sessionId, branch.writeId, "push-fixed-id") as GitWriteResult.Push.Pushed
        assertEquals("a".repeat(40), pushed.commit)
        val requests = transport.git()
        assertEquals(JsonPrimitive(true), requests[0]["forPush"])
        assertEquals("push-fixed-id", requests.last().text("requestId"))
    }

    @Test
    fun pushCapabilitiesFromAuthOrV2NeverAuthorizeCommands() = runTest {
        val transport = Transport()
        transport.authCapabilities = setOf(GIT_PUSH_CAPABILITY, GIT_PUSH_STATUS_CAPABILITY)
        transport.configure(gitCapabilities + GIT_PUSH_CAPABILITY + GIT_PUSH_STATUS_CAPABILITY) { error("must not send") }
        val response = transport.response
        transport.response = { request ->
            val result = response(request)
            if (request.text("type") == "projects.list" && request.text("requestId").startsWith("capabilities.v3:")) JsonObject(checkNotNull(result) - "capabilities") else result
        }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", sessionId))
        assertFalse(GIT_PUSH_CAPABILITY in repository.state.value.capabilities)
        assertFalse(GIT_PUSH_STATUS_CAPABILITY in repository.state.value.capabilities)
        assertTrue(runCatching { repository.gitPushPrepare(sessionId) }.isFailure)
        assertTrue(runCatching { repository.gitPushStatus(sessionId, "d3JpdGUtaWQtdG9rZW4tMQ") }.isFailure)
        assertTrue(transport.git().isEmpty())

        val v1 = Transport().also { it.configure(gitCapabilities + GIT_PUSH_CAPABILITY + GIT_PUSH_STATUS_CAPABILITY) { error("must not send") } }
        val original = v1.response
        v1.response = { request ->
            val result = original(request)
            if (request.text("type") == "projects.list" &&
                (request.text("requestId").startsWith("capabilities.v3:") || request.text("requestId").startsWith("capabilities.v2:")))
                JsonObject(checkNotNull(result) - "capabilities") else result
        }
        val legacy = repository(v1)
        legacy.activate(RemoteSelection("host", "project", sessionId))
        assertFalse(GIT_PUSH_CAPABILITY in legacy.state.value.capabilities)
        assertFalse(GIT_PUSH_STATUS_CAPABILITY in legacy.state.value.capabilities)
        assertTrue(runCatching { legacy.gitPushPrepare(sessionId) }.isFailure)
        assertTrue(v1.git().isEmpty())
    }

    @Test
    fun lostPushReplyIsUnknownAndDoesNotRetry() = runTest {
        val transport = Transport()
        transport.configure(gitCapabilities + GIT_PUSH_CAPABILITY) { null }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", sessionId))
        var dispatched = false
        val pending = async { repository.gitPush(sessionId, "d3JpdGUtaWQtdG9rZW4tMQ", "push-unknown") { dispatched = true } }
        runCurrent()
        assertTrue(dispatched)
        advanceTimeBy(GIT_PUSH_REQUEST_TIMEOUT_MILLIS + 1)
        runCurrent()
        assertEquals(GitWriteResult.Push.Unknown, pending.await())
        assertEquals(listOf("push-unknown"), transport.git().map { it.text("requestId") })
    }

    @Test
    fun pushResentAfterReconnectCarriesTheIdenticalCommand() = runTest {
        // The Host's ledger answers a resend only when the command's JSON matches the first one.
        val transport = Transport()
        transport.configure(gitCapabilities + GIT_PUSH_CAPABILITY) { null }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", sessionId))
        repository.setForeground(true)
        repository.setValidatedNetwork("wifi")
        runCurrent()
        val token = "d3JpdGUtaWQtdG9rZW4tMQ"
        val first = async { repository.gitPush(sessionId, token, "push-resend") }
        runCurrent()
        transport.listener.failed(true, R.string.remote_connection_error)
        runCurrent()
        assertEquals(GitWriteResult.Push.Unknown, first.await())
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(repository.state.value.connected)
        val second = async { repository.gitPush(sessionId, token, "push-resend") }
        runCurrent()
        val pushes = transport.git().filter { it.text("type") == "session.git.push" }
        assertEquals(2, pushes.size)
        assertEquals(pushes[0], pushes[1])
        assertEquals(pushes[0].toString(), pushes[1].toString())
        assertEquals(setOf("type", "requestId", "sessionId", "writeId"), pushes[0].keys)
        transport.result(pushes[1], Wire.objectOf("kind" to "git.push", "sessionId" to sessionId, "pushed" to true,
            "branch" to "feature", "remoteBranch" to "feature", "commit" to "a".repeat(40), "created" to false, "upstreamSet" to true))
        runCurrent()
        assertTrue(second.await() is GitWriteResult.Push.Pushed)
    }

    @Test
    fun pushReplyAfterDefaultTimeoutIsStillAccepted() = runTest {
        val transport = Transport()
        transport.configure(gitCapabilities + GIT_PUSH_CAPABILITY) { null }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", sessionId))
        val pending = async { repository.gitPush(sessionId, "d3JpdGUtaWQtdG9rZW4tMQ", "late-push") }
        runCurrent()
        val sent = transport.git().single()
        advanceTimeBy(REQUEST_TIMEOUT_MILLIS + 1); runCurrent()
        assertFalse(pending.isCompleted)
        transport.result(sent, Wire.objectOf("kind" to "git.push", "sessionId" to sessionId, "pushed" to true,
            "branch" to "feature", "remoteBranch" to "feature", "commit" to "a".repeat(40), "created" to false, "upstreamSet" to true))
        runCurrent()
        assertTrue(pending.await() is GitWriteResult.Push.Pushed)
        assertEquals(1, transport.git().size)
    }

    @Test
    fun pushStatusReplyAfterDefaultTimeoutIsStillAccepted() = runTest {
        val transport = Transport()
        transport.configure(gitCapabilities + GIT_PUSH_STATUS_CAPABILITY) { null }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", sessionId))
        val pending = async { repository.gitPushStatus(sessionId, "d3JpdGUtaWQtdG9rZW4tMQ") }
        runCurrent()
        val sent = transport.git().single()
        advanceTimeBy(REQUEST_TIMEOUT_MILLIS + 1); runCurrent()
        assertFalse(pending.isCompleted)
        transport.result(sent, pushData("push-status-matches"))
        runCurrent()
        assertEquals(GitPushStatus.MATCHES, pending.await().state)
        assertEquals(1, transport.git().size)
    }

    @Test
    fun queuedOlderDraftSnapshotCannotEraseNewCommitIntent() = runTest {
        val storage = Drafts()
        val transport = Transport().also { it.configure(gitCapabilities + GIT_WRITE_CAPABILITY) { null } }
        val repository = repository(transport, storage)
        val selection = RemoteSelection("host", "project", sessionId)
        repository.activate(selection)
        val otherSelection = selection.copy(sessionId = "other-session")
        val otherKey = DraftKey("host", "other-session")
        val pending = GitCommitConfirmation("feature", listOf("a.kt"), "subject", "request-1", snapshotId)
        var queued = false
        storage.onSave = { values ->
            if (!queued && values[otherKey]?.gitCommit?.pending != null) {
                queued = true
                // This captures the ordinary draft before the successful intent write publishes
                // its new key in memory, but queues behind that write.
                repository.draft("other edit")
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { repository.flush() }
            }
        }
        repository.saveGitCommitDraft(otherSelection, GitCommitDraft("subject", pending))
        runCurrent()
        repository.flush()
        assertTrue(queued)
        assertEquals(pending, storage.values[otherKey]?.gitCommit?.pending)
        val reopened = repository(Transport().also { it.configure(gitCapabilities) { null } }, storage)
        reopened.activate(selection)
        assertEquals(pending, reopened.gitCommitDraft(otherSelection).pending)
        repository.remove("host")
        runCurrent()
        repository.flush()
        assertTrue(storage.values.keys.none { it.routeId == "host" })
    }

    @Test
    fun failedCommitIntentClearKeepsPendingAcrossReopenAndUnrelatedSave() = runTest {
        val storage = Drafts()
        val transport = Transport()
        transport.configure(gitCapabilities + GIT_WRITE_CAPABILITY) { null }
        val repository = repository(transport, storage)
        val selection = RemoteSelection("host", "project", sessionId)
        repository.activate(selection)
        val pending = GitCommitConfirmation("feature", listOf("a.kt"), "subject", "request-1", snapshotId)
        repository.saveGitCommitDraft(selection, GitCommitDraft("subject", pending))
        storage.failSave = true
        assertTrue(runCatching { repository.saveGitCommitDraft(selection, GitCommitDraft("subject")) }.isFailure)
        assertEquals(pending, repository.gitCommitDraft(selection).pending)
        storage.failSave = false
        repository.draft("unrelated chat draft")
        repository.flush()
        assertEquals(pending, storage.values[DraftKey("host", sessionId)]?.gitCommit?.pending)
        val reopened = repository(Transport().also { it.configure(gitCapabilities) { null } }, storage)
        reopened.activate(selection)
        assertEquals(pending, reopened.gitCommitDraft(selection).pending)
    }

    @Test
    fun commitReviewUsesExplicitHeadSnapshotAndRejectsMissingWriteCapability() = runTest {
        val transport = Transport()
        transport.configure(gitCapabilities) { error("must not be sent") }
        val denied = repository(transport)
        denied.activate(RemoteSelection("host", "project", sessionId))
        assertTrue(runCatching { denied.gitCommitStatus(sessionId) }.isFailure)
        assertTrue(transport.git().isEmpty())
        transport.configure(gitCapabilities + GIT_WRITE_CAPABILITY) { request ->
            JsonObject(withRequest(valid("status-head-without-dev-base"), request))
        }
        val allowed = repository(transport)
        allowed.activate(RemoteSelection("host", "project", sessionId))
        allowed.gitCommitStatus(sessionId)
        val sent = transport.git().single()
        assertEquals("head", sent.text("base"))
        assertEquals(JsonPrimitive(true), sent["forCommit"])
    }

    @Test
    fun commitStatusAcceptsOnlyTrueSubmoduleMarker() {
        val original = valid("status-head-without-dev-base").obj("data")
        val file = Wire.objectOf("path" to "module", "change" to "modified", "binary" to true, "submodule" to true)
        val data = JsonObject(original + ("files" to JsonArray(listOf(file))))
        assertTrue(parseGitStatus(data, sessionId, GitBase.HEAD).files.single().submodule)
        val invalid = JsonObject(data + ("files" to JsonArray(listOf(JsonObject(file + ("submodule" to JsonPrimitive(false)))))))
        assertTrue(runCatching { parseGitStatus(invalid, sessionId, GitBase.HEAD) }.isFailure)
    }

    @Test
    fun selectiveCommitRequiresWriteCapabilityAndKeepsTheConfirmedRequest() = runTest {
        val transport = Transport()
        transport.configure(gitCapabilities) { error("commit must not be sent") }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", sessionId))
        assertTrue(runCatching { repository.gitCommit(sessionId, snapshotId, "subject", listOf("a.kt"), "commit-id") }.isFailure)
        assertTrue(transport.git().isEmpty())
        transport.configure(gitCapabilities + GIT_WRITE_CAPABILITY) { request ->
            Wire.objectOf("kind" to "git.commit", "sessionId" to request.text("sessionId"), "committed" to true,
                "branch" to "feature", "commit" to "a".repeat(40), "files" to 1,
                "skipped" to JsonArray(emptyList()), "indexUpdated" to false)
        }
        val readyRepository = repository(transport)
        readyRepository.activate(RemoteSelection("host", "project", sessionId))
        assertTrue(GIT_WRITE_CAPABILITY in readyRepository.state.value.capabilities)
        assertTrue(runCatching { readyRepository.gitCommit(sessionId, snapshotId, "subject", emptyList(), "commit-id") }.isFailure)
        assertTrue(transport.git().isEmpty())
        val result = readyRepository.gitCommit(sessionId, snapshotId, "subject", listOf("a.kt"), "commit-id")
        assertFalse((result as GitWriteResult.Commit.Committed).indexUpdated)
        val sent = transport.git().single()
        assertEquals("commit-id", sent.text("requestId"))
        assertEquals(snapshotId, sent.text("snapshotId"))
        assertEquals(listOf("a.kt"), sent.getValue("paths").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun unansweredCommitIsUnknownAndNeverAutomaticallyResent() = runTest {
        val transport = Transport()
        transport.configure(gitCapabilities + GIT_WRITE_CAPABILITY) { null }
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", sessionId))
        var dispatched = false
        val result = async { repository.gitCommit(sessionId, snapshotId, "subject", listOf("a.kt"), "unknown-id") { dispatched = true } }
        runCurrent()
        assertTrue(dispatched)
        advanceTimeBy(REQUEST_TIMEOUT_MILLIS + 1)
        runCurrent()
        assertEquals(GitWriteResult.Commit.Unknown, result.await())
        assertEquals(listOf("unknown-id"), transport.git().map { it.text("requestId") })
    }

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
    fun actionUsageDecaysForThePalette() {
        val day = 24L * 60 * 60 * 1000
        var settings: ActionUsage? = null
        repeat(3) { settings = settings.used(0) }
        // Three uses 28 days ago weigh 0.75, less than one use today.
        assertEquals(0.75, checkNotNull(settings).decayed(28 * day), 1e-9)
        assertEquals(1.0, null.used(28 * day).decayed(28 * day), 1e-9)
        // One half-life halves a use.
        assertEquals(0.5, null.used(0).decayed(14 * day), 1e-9)
    }

    @Test
    fun pillKeepsAFixedOrderWithChangesAndRefreshFirst() {
        val all = ChatAction.entries.toSet() - ChatAction.CLOSE - ChatAction.STATUS
        val layout = chatActionLayout(DEFAULT_CHAT_ACTIONS, all)
        assertEquals(listOf(ChatAction.CHANGES, ChatAction.REFRESH), layout.shown)
        assertEquals(
            listOf(
                ChatAction.FILES, ChatAction.JOBS, ChatAction.TREE, ChatAction.ARTIFACTS, ChatAction.RENAME,
                ChatAction.EXPORT, ChatAction.RELOAD, ChatAction.SETTINGS, ChatAction.NEW_SESSION,
                ChatAction.SESSION_SETTINGS, ChatAction.HISTORY, ChatAction.BROWSER, ChatAction.PREVIEW, ChatAction.EMULATOR,
            ),
            layout.menu,
        )
        // Every action the header offers can take a place.
        assertEquals(all, DEFAULT_CHAT_ACTIONS.toSet())
    }

    @Test
    fun treeArtifactsExportAndReloadFollowTheirSlashCommands() {
        assertEquals(emptySet<ChatAction>(), localCommandActions(listOf(LocalCommand.NEW, LocalCommand.MODEL)))
        assertEquals(
            setOf(ChatAction.TREE, ChatAction.ARTIFACTS, ChatAction.EXPORT, ChatAction.RELOAD),
            localCommandActions(LocalCommand.entries),
        )
        assertEquals(setOf(ChatAction.TREE), localCommandActions(listOf(LocalCommand.TREE)))
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
        val expected = many.menu.toMutableList().apply { add(indexOf(ChatAction.BROWSER), ChatAction.HISTORY) }
        assertEquals(expected, many.withHistory().menu)
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
    fun aRememberedStatusThatCannotBeReadAgainSaysSo() = runTest {
        val (transport, repository) = opened(::sessionFixtures)
        repository.activate(RemoteSelection("host", "project", sessionId))
        repository.openChanges()
        runCurrent()
        val status = checkNotNull(repository.state.value.changes?.status)
        repository.closeChanges()

        // The remembered status stays, but no longer passes for current.
        transport.failure = { if (it.text("type") == "session.git.status") "forbidden" else null }
        repository.openChanges()
        assertEquals(false, repository.state.value.changes?.stale)
        runCurrent()
        repository.state.value.changes.let { changes ->
            assertEquals(status, changes?.status)
            assertEquals(false, changes?.loading)
            assertNull(changes?.failure)
            assertEquals(true, changes?.stale)
        }

        // A read that succeeds makes it current again.
        transport.failure = { null }
        repository.reloadChanges()
        runCurrent()
        repository.state.value.changes.let { changes ->
            assertEquals(false, changes?.stale)
            assertNull(changes?.failure)
            assertEquals(testScheduler.currentTime, changes?.fetchedAt)
        }
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
