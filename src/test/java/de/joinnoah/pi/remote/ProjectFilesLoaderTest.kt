package de.joinnoah.pi.remote

import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** The file browser through the real repository and loader, against a scripted host. */
class ProjectFilesLoaderTest {
    private val host = PairedHost("host", "https://relay.test", "device", "secret", "Host")
    private val sessionId = "019a2f3c-7d41-7b1e-9c55-3e8f1a2b4c6d"
    private val version = "RmlsZVZlcnNpb25BYmMxMg"

    private val fixture: JsonObject by lazy {
        Wire.json.parseToJsonElement(javaClass.getResource("/files-v1.json")!!.readText()).jsonObject
    }

    private fun valid(name: String): JsonObject =
        fixture.getValue("valid").jsonArray.map { it.jsonObject }.single { it.text("name") == name }.obj("payload")

    private fun command(name: String): JsonObject = JsonObject(valid(name) - "requestId")

    private val capabilities =
        valid("projects-files-capability").obj("data").getValue("capabilities").jsonArray.map { it.jsonPrimitive.content }

    private class Pairings(val host: PairedHost) : PairingStorage {
        override fun load() = listOf(host)

        override fun save(hosts: List<PairedHost>) {}
    }

    private class Drafts : DraftStorage {
        override fun load() = emptyMap<DraftKey, StoredDraft>()

        override fun save(drafts: Map<DraftKey, StoredDraft>) {}
    }

    /** Answers `session.files.*` through [files]; null holds the request for [result]. */
    private inner class Transport(
        private val advertised: List<String>,
        var files: (JsonObject) -> JsonObject?,
    ) : RemoteTransport {
        override lateinit var listener: RemoteTransport.Listener
        val sent = mutableListOf<JsonObject>()
        var failure: (JsonObject) -> String? = { null }

        override fun connect(host: PairedHost) = listener.ready(emptySet())

        override fun pair(value: JsonObject) {}

        override fun close() {}

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

        fun result(request: JsonObject, data: JsonObject) =
            listener.message(
                Wire.objectOf("type" to "result", "requestId" to request.text("requestId"), "ok" to true, "data" to data)
            )

        fun files() = sent.filter { it.text("type").startsWith("session.files.") }

        private fun response(request: JsonObject): JsonObject? {
            val session = Wire.objectOf("id" to sessionId, "origin" to "rpc", "status" to "idle", "projectId" to "project")
            return when (request.text("type")) {
                "projects.list" ->
                    Wire.objectOf(
                        "kind" to "projects",
                        "items" to JsonArray(listOf(Wire.objectOf("id" to "project"))),
                        "capabilities" to JsonArray(advertised.map(::JsonPrimitive)),
                    )
                "sessions.list" -> Wire.objectOf("kind" to "sessions", "items" to JsonArray(listOf(session)))
                "sessions.open" -> Wire.objectOf("kind" to "session", "session" to session)
                "session.snapshot" ->
                    Wire.objectOf(
                        "kind" to "snapshot",
                        "sessionId" to sessionId,
                        "revision" to 0,
                        "status" to "idle",
                        "messages" to JsonArray(emptyList()),
                        "pendingQuestions" to JsonArray(emptyList()),
                    )
                else -> if (request.text("type").startsWith("session.files.")) files(request) else Wire.objectOf("kind" to "accepted")
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

    private suspend fun TestScope.opened(transport: Transport): DefaultRemoteRepository {
        val repository = repository(transport)
        repository.activate(RemoteSelection("host", "project", sessionId))
        repository.openFiles()
        runCurrent()
        return repository
    }

    private fun entry(name: String, type: String = "file"): JsonObject =
        if (type == "file") Wire.objectOf("name" to name, "type" to type, "size" to 1) else Wire.objectOf("name" to name, "type" to type)

    private fun listing(request: JsonObject, vararg names: String, nextAfter: String? = null): JsonObject =
        JsonObject(
            buildMap {
                put("kind", JsonPrimitive("files.list"))
                put("sessionId", JsonPrimitive(request.text("sessionId")))
                put("path", JsonPrimitive(request.text("path")))
                put("available", JsonPrimitive(true))
                put("entries", JsonArray(names.map { if (it.endsWith("/")) entry(it.dropLast(1), "dir") else entry(it) }))
                put("truncated", JsonPrimitive(false))
                if (nextAfter != null) put("nextAfter", JsonPrimitive(nextAfter))
            }
        )

    private fun page(request: JsonObject, content: String, size: Int, nextOffset: Int? = null, binary: Boolean = false): JsonObject =
        JsonObject(
            buildMap {
                put("kind", JsonPrimitive("files.read"))
                put("sessionId", JsonPrimitive(request.text("sessionId")))
                put("path", JsonPrimitive(request.text("path")))
                put("version", JsonPrimitive(version))
                put("size", JsonPrimitive(size))
                put("offset", JsonPrimitive(request["offset"]?.jsonPrimitive?.int ?: 0))
                put("content", JsonPrimitive(content))
                put("binary", JsonPrimitive(binary))
                if (nextOffset != null) put("nextOffset", JsonPrimitive(nextOffset))
            }
        )

    private fun offset(request: JsonObject) = request["offset"]?.jsonPrimitive?.int ?: 0

    @Test
    fun sendsNoFilesCommandWithoutTheCapability() = runTest {
        val transport = Transport(capabilities - FILES_CAPABILITY) { error("must not be sent") }
        val repository = opened(transport)
        assertFalse(canBrowseFiles(repository.state.value))
        assertEquals(FilesFailure.UNSUPPORTED, repository.state.value.files?.failure)
        assertTrue(runCatching { repository.filesList(sessionId, "", null) }.isFailure)
        assertTrue(transport.files().isEmpty())
    }

    @Test
    fun requestsMatchTheSharedFixtureShapes() = runTest {
        val transport = Transport(capabilities) { request ->
            when {
                request.text("type") == "session.files.read" ->
                    if (offset(request) == 0) page(request, "line one\n", 300000, nextOffset = 196000)
                    else page(request, "line two\n", 300000)
                request.text("path") == "" -> JsonObject(valid("list-root").obj("data") + ("sessionId" to JsonPrimitive(sessionId)))
                request["after"] == null -> listing(request, "main.ts", "model.ts", nextAfter = "model.ts")
                else -> listing(request, "zeta.ts")
            }
        }
        val repository = opened(transport)
        assertTrue(canBrowseFiles(repository.state.value))
        assertEquals(5, repository.state.value.files?.listing?.entries?.size)
        repository.openFilesDir("src")
        runCurrent()
        repository.openFilesDir("src/app")
        runCurrent()
        repository.loadMoreFiles()
        runCurrent()
        repository.openFilesFile("src/app/main.ts")
        runCurrent()
        repository.loadMoreFiles()
        runCurrent()
        val sent = transport.files().map { JsonObject(it - "requestId") }
        assertEquals(command("list-command-root"), sent[0])
        assertEquals(command("list-command-next-page"), sent[3])
        assertEquals(command("read-command-first-page"), sent[4])
        assertEquals(command("read-command-next-page"), sent[5])
        val files = checkNotNull(repository.state.value.files)
        assertEquals(listOf("main.ts", "model.ts", "zeta.ts"), files.listing?.entries?.map { it.name })
        assertEquals("line one\nline two\n", files.file?.content)
        assertNull(files.file?.nextOffset)
    }

    @Test
    fun offlineShowsOfflineWithoutRequests() = runTest {
        val transport = Transport(capabilities) { listing(it, "src/") }
        val repository = opened(transport)
        val requests = transport.files().size
        transport.listener.failed(true, R.string.remote_connection_error)
        runCurrent()
        repository.openFilesDir("src")
        assertEquals(FilesFailure.OFFLINE, repository.state.value.files?.failure)
        repository.openFilesFile("src/a.kt")
        assertEquals(FilesFailure.OFFLINE, repository.state.value.files?.file?.failure)
        assertEquals(requests, transport.files().size)
        assertEquals("offline", (runCatching { repository.filesList(sessionId, "", null) }.exceptionOrNull() as RemoteRequestException).code)
    }

    @Test
    fun backReturnsToKeptListingsWithoutRequests() = runTest {
        val transport = Transport(capabilities) { request ->
            when (request.text("path")) {
                "" -> listing(request, "src/", "README.md")
                "src" -> listing(request, "app/", "build.gradle.kts")
                else -> listing(request, "main.ts")
            }
        }
        val repository = opened(transport)
        repository.openFilesDir("src")
        runCurrent()
        repository.openFilesDir("src/app")
        runCurrent()
        assertEquals(listOf("", "src"), repository.state.value.files?.parents?.map { it.path })
        assertEquals(3, transport.files().size)
        repository.openFilesDir("src")
        var files = checkNotNull(repository.state.value.files)
        assertEquals("src", files.path)
        assertEquals(listOf("app", "build.gradle.kts"), files.listing?.entries?.map { it.name })
        assertEquals(listOf(""), files.parents.map { it.path })
        // A breadcrumb jump to the top is kept as well.
        repository.openFilesDir("src/app")
        runCurrent()
        repository.openFilesDir("")
        files = checkNotNull(repository.state.value.files)
        assertEquals(listOf("src", "README.md"), files.listing?.entries?.map { it.name })
        assertTrue(files.parents.isEmpty())
        assertEquals(4, transport.files().size)
        // Reload asks again.
        repository.reloadFiles()
        runCurrent()
        assertEquals(5, transport.files().size)
        repository.closeFiles()
        assertNull(repository.state.value.files)
    }

    @Test
    fun loadMoreDropsNamesARepeatedPageSendsAgain() = runTest {
        val transport = Transport(capabilities) { request ->
            // The `after` entry vanished, so the host starts the next page earlier.
            if (request["after"] == null) listing(request, "a.kt", "b.kt", nextAfter = "b.kt")
            else listing(request, "b.kt", "c.kt")
        }
        val repository = opened(transport)
        repository.loadMoreFiles()
        runCurrent()
        val listing = checkNotNull(repository.state.value.files?.listing)
        assertEquals(listOf("a.kt", "b.kt", "c.kt"), listing.entries.map { it.name })
        assertNull(listing.nextAfter)
        assertEquals("b.kt", transport.files().last().text("after"))
    }

    @Test
    fun aChangedFileIsReadAgainOnceFromTheStart() = runTest {
        var firstPages = 0
        val transport = Transport(capabilities) { request ->
            if (request.text("type") == "session.files.list") listing(request, "a.kt")
            else {
                firstPages++
                page(request, "page one\n", 300000, nextOffset = 9)
            }
        }
        transport.failure = { request -> if (offset(request) > 0) "not_found" else null }
        val repository = opened(transport)
        repository.openFilesFile("a.kt")
        runCurrent()
        repository.selectFileLines(LineSelection(1))
        repository.loadMoreFiles()
        runCurrent()
        val reads = transport.files().filter { it.text("type") == "session.files.read" }
        assertEquals(listOf(0, 9, 0), reads.map(::offset))
        assertNull(reads[2]["version"])
        var file = checkNotNull(repository.state.value.files?.file)
        assertTrue(file.reopened)
        assertNull(file.failure)
        assertNull(file.selection)
        assertEquals("page one\n", file.content)
        assertEquals(2, firstPages)

        // When the new first page fails too, the failure shows instead of another round.
        transport.failure = { request -> if (offset(request) > 0 || firstPages >= 2) "not_found" else null }
        repository.loadMoreFiles()
        runCurrent()
        file = checkNotNull(repository.state.value.files?.file)
        assertEquals(FilesFailure.NOT_FOUND, file.failure)
        assertEquals(5, transport.files().count { it.text("type") == "session.files.read" })
    }

    @Test
    fun aLaterBinaryPageMakesTheFileBinary() = runTest {
        val transport = Transport(capabilities) { request ->
            when {
                request.text("type") == "session.files.list" -> listing(request, "a.kt")
                offset(request) == 0 -> page(request, "text\n", 300000, nextOffset = 5)
                else -> page(request, "", 300000, binary = true)
            }
        }
        val repository = opened(transport)
        repository.openFilesFile("a.kt")
        runCurrent()
        assertFalse(checkNotNull(repository.state.value.files?.file).binary)
        repository.loadMoreFiles()
        runCurrent()
        val file = checkNotNull(repository.state.value.files?.file)
        assertTrue(file.binary)
        assertEquals("", file.content)
        assertNull(file.nextOffset)
    }

    @Test
    fun staleResponsesAreDroppedAfterNavigation() = runTest {
        val held = mutableListOf<JsonObject>()
        var hold = false
        val transport = Transport(capabilities) { request ->
            when {
                hold -> {
                    held += request
                    null
                }
                request.text("type") == "session.files.read" -> page(request, "x\n", 2)
                else -> listing(request, "src/", "a.kt")
            }
        }
        val repository = opened(transport)
        hold = true
        repository.openFilesDir("src")
        runCurrent()
        // Back to the top before the folder arrives.
        repository.openFilesDir("")
        hold = false
        transport.result(held.single(), listing(held.single(), "late.kt"))
        runCurrent()
        var files = checkNotNull(repository.state.value.files)
        assertEquals("", files.path)
        assertEquals(listOf("src", "a.kt"), files.listing?.entries?.map { it.name })

        hold = true
        held.clear()
        repository.openFilesFile("a.kt")
        runCurrent()
        repository.openFilesFile(null)
        transport.result(held.single(), page(held.single(), "late\n", 5))
        runCurrent()
        files = checkNotNull(repository.state.value.files)
        assertNull(files.file)
    }

    @Test
    fun hostErrorsMapToFailures() = runTest {
        val transport = Transport(capabilities) { listing(it, "a.kt") }
        transport.failure = { request -> if (request.text("type") == "session.files.read") "invalid_path" else null }
        val repository = opened(transport)
        repository.openFilesFile("a.kt")
        runCurrent()
        assertEquals(FilesFailure.INVALID_PATH, repository.state.value.files?.file?.failure)
        transport.failure = { "busy" }
        repository.reloadFiles()
        runCurrent()
        assertEquals(FilesFailure.BUSY, repository.state.value.files?.file?.failure)
    }
}
