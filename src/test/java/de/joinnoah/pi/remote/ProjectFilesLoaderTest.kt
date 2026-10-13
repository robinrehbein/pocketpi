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
        /** `session.git.*` answers; held by default. */
        var git: (JsonObject) -> JsonObject? = { null }

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

        /** The sessions of the project; the first is [sessionId]. */
        var sessionIds = listOf(sessionId)

        private fun session(id: String) = Wire.objectOf("id" to id, "origin" to "rpc", "status" to "idle", "projectId" to "project")

        private fun response(request: JsonObject): JsonObject? {
            return when (request.text("type")) {
                "projects.list" ->
                    Wire.objectOf(
                        "kind" to "projects",
                        "items" to JsonArray(listOf(Wire.objectOf("id" to "project"))),
                        "capabilities" to JsonArray(advertised.map(::JsonPrimitive)),
                    )
                "sessions.list" -> Wire.objectOf("kind" to "sessions", "items" to JsonArray(sessionIds.map(::session)))
                "sessions.open" -> Wire.objectOf("kind" to "session", "session" to session(request.text("sessionId")))
                "session.snapshot" ->
                    Wire.objectOf(
                        "kind" to "snapshot",
                        "sessionId" to (request["sessionId"]?.jsonPrimitive?.content ?: sessionId),
                        "revision" to 0,
                        "status" to "idle",
                        "messages" to JsonArray(emptyList()),
                        "pendingQuestions" to JsonArray(emptyList()),
                    )
                else ->
                    when {
                        request.text("type").startsWith("session.files.") -> files(request)
                        request.text("type").startsWith("session.git.") -> git(request)
                        else -> Wire.objectOf("kind" to "accepted")
                    }
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

    private fun page(
        request: JsonObject,
        content: String,
        size: Int,
        nextOffset: Int? = null,
        binary: Boolean = false,
        fileVersion: String = version,
    ): JsonObject =
        JsonObject(
            buildMap {
                put("kind", JsonPrimitive("files.read"))
                put("sessionId", JsonPrimitive(request.text("sessionId")))
                put("path", JsonPrimitive(request.text("path")))
                put("version", JsonPrimitive(fileVersion))
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

    @Test
    fun filesAndChangesCloseEachOther() = runTest {
        val heldFiles = mutableListOf<JsonObject>()
        var holdFiles = false
        val transport = Transport(capabilities) { request ->
            if (holdFiles) {
                heldFiles += request
                null
            } else listing(request, "a.kt")
        }
        val repository = opened(transport)
        assertNotNull(repository.state.value.files)

        // Changes close the files, and a files answer arriving later does not bring them back.
        holdFiles = true
        repository.openFilesDir("src")
        runCurrent()
        repository.openChanges()
        runCurrent()
        assertNull(repository.state.value.files)
        assertNotNull(repository.state.value.changes)
        transport.result(heldFiles.single(), listing(heldFiles.single(), "late.kt"))
        runCurrent()
        assertNull(repository.state.value.files)

        // Files close the changes, whose status is still on its way.
        val status = transport.sent.last { it.text("type") == "session.git.status" }
        holdFiles = false
        repository.openFiles()
        runCurrent()
        assertNull(repository.state.value.changes)
        assertNotNull(repository.state.value.files)
        transport.result(
            status,
            Wire.objectOf(
                "kind" to "git.status", "sessionId" to sessionId, "base" to "session",
                "available" to false, "reason" to "not_a_repository",
            ),
        )
        runCurrent()
        assertNull(repository.state.value.changes)
        assertEquals(listOf("a.kt"), repository.state.value.files?.listing?.entries?.map { it.name })
    }

    // ---- Editor ---------------------------------------------------------------------------

    private val writeFixture: JsonObject by lazy {
        Wire.json.parseToJsonElement(javaClass.getResource("/files-write-v1.json")!!.readText()).jsonObject
    }

    private fun writeValid(name: String): JsonObject =
        writeFixture.getValue("valid").jsonArray.map { it.jsonObject }.single { it.text("name") == name }.obj("payload")

    private val writeCapabilities =
        writeValid("projects-files-write-capability").obj("data").getValue("capabilities").jsonArray.map { it.jsonPrimitive.content }

    private val newVersion = "TmV3VmVyc2lvblh5ejk4Nw"

    private fun written(request: JsonObject): JsonObject =
        Wire.objectOf(
            "kind" to "files.written",
            "sessionId" to request.text("sessionId"),
            "path" to request.text("path"),
            "version" to newVersion,
            "size" to request.text("content").encodeToByteArray().size,
        )

    /** A host with `src/app/main.ts` ("hello\n") that answers writes through [write]. */
    private fun editorTransport(
        advertised: List<String> = writeCapabilities,
        write: (JsonObject) -> JsonObject? = ::written,
    ) = Transport(advertised) { request ->
        when (request.text("type")) {
            "session.files.write" -> write(request)
            "session.files.read" -> page(request, "hello\n", 6)
            else -> listing(request, "main.ts")
        }
    }

    private suspend fun TestScope.editing(transport: Transport): DefaultRemoteRepository {
        val repository = opened(transport)
        repository.openFilesFile("src/app/main.ts")
        runCurrent()
        repository.startFileEditing()
        return repository
    }

    /** [actual] is [expected] apart from its text field, which belongs to each editor alone. */
    private fun assertEditor(expected: FileEditor, actual: FileEditor?) =
        assertEquals(expected, actual?.copy(buffer = expected.buffer))

    @Test
    fun aNewEditorNeverEqualsAnOldOne() {
        val first = FileEditor("a.kt", version, "a\n")
        assertEquals(first, first.copy(saving = false))
        assertNotEquals(first, FileEditor("a.kt", version, "a\n"))
    }

    private fun writes(transport: Transport) = transport.files().filter { it.text("type") == "session.files.write" }

    @Test
    fun saveSendsTheFixtureCommandAndKeepsTheNewVersion() = runTest {
        val transport = editorTransport()
        val repository = editing(transport)
        var editor = checkNotNull(repository.state.value.files?.editor)
        assertEditor(FileEditor("src/app/main.ts", version, "hello\n"), editor)
        assertFalse(editor.changed)
        repository.updateFileEditorText("hello\nworld\n")
        assertTrue(checkNotNull(repository.state.value.files?.editor).changed)
        repository.saveFileEditor()
        assertTrue(checkNotNull(repository.state.value.files?.editor).saving)
        runCurrent()

        assertEquals(JsonObject(writeValid("write-command") - "requestId"), JsonObject(writes(transport).single() - "requestId"))
        val files = checkNotNull(repository.state.value.files)
        editor = checkNotNull(files.editor)
        assertEquals(newVersion, editor.baseVersion)
        assertEquals("hello\nworld\n", editor.original)
        assertFalse(editor.changed)
        assertFalse(editor.saving)
        assertNull(editor.saveFailure)
        assertEquals(1L, editor.savedAt)
        val file = checkNotNull(files.file)
        assertEquals(newVersion, file.version)
        assertEquals("hello\nworld\n", file.content)
        assertEquals(12L, file.size)

        // The next save is checked against the saved version.
        repository.updateFileEditorText("hello\n")
        repository.saveFileEditor()
        runCurrent()
        assertEquals(newVersion, writes(transport).last().text("baseVersion"))
        assertEquals(2L, repository.state.value.files?.editor?.savedAt)
    }

    @Test
    fun textTypedDuringASaveStaysChanged() = runTest {
        val held = mutableListOf<JsonObject>()
        val transport = editorTransport { held += it; null }
        val repository = editing(transport)
        repository.updateFileEditorText("one\n")
        repository.saveFileEditor()
        runCurrent()
        repository.updateFileEditorText("one\ntwo\n")
        // A second save while one runs sends nothing.
        repository.saveFileEditor()
        runCurrent()
        assertEquals(1, held.size)
        transport.result(held.single(), written(held.single()))
        runCurrent()
        val editor = checkNotNull(repository.state.value.files?.editor)
        assertEquals("one\n", editor.original)
        assertEquals("one\ntwo\n", editor.text)
        assertTrue(editor.changed)
    }

    @Test
    fun aConflictKeepsTheEditUntilReload() = runTest {
        val transport = editorTransport()
        transport.failure = { request -> if (request.text("type") == "session.files.write") "conflict" else null }
        val repository = editing(transport)
        repository.updateFileEditorText("mine\n")
        repository.saveFileEditor()
        runCurrent()
        var editor = checkNotNull(repository.state.value.files?.editor)
        assertTrue(editor.conflict)
        assertEquals(FilesFailure.CONFLICT, editor.saveFailure)
        assertFalse(editor.saving)
        assertEquals("mine\n", editor.text)
        assertEquals(version, editor.baseVersion)
        // The edit still goes to the agent as a diff against the version it was opened at.
        val prompt = repository.fileEditorPrompt() as EditorPrompt.Ready
        assertTrue(prompt.text.startsWith(editorPromptIntro("src/app/main.ts") + "\n\n```diff\n"))

        val reads = transport.files().count { it.text("type") == "session.files.read" }
        repository.reloadFileAfterConflict()
        runCurrent()
        val files = checkNotNull(repository.state.value.files)
        assertNull(files.editor)
        assertEquals("hello\n", files.file?.content)
        assertEquals(reads + 1, transport.files().count { it.text("type") == "session.files.read" })
    }

    @Test
    fun hostErrorsAndOfflineLeaveTheEdit() = runTest {
        val transport = editorTransport()
        transport.failure = { request -> if (request.text("type") == "session.files.write") "busy" else null }
        val repository = editing(transport)
        repository.updateFileEditorText("mine\n")
        repository.saveFileEditor()
        runCurrent()
        var editor = checkNotNull(repository.state.value.files?.editor)
        assertEquals(FilesFailure.BUSY, editor.saveFailure)
        assertFalse(editor.conflict)

        transport.listener.failed(true, R.string.remote_connection_error)
        runCurrent()
        val sent = writes(transport).size
        repository.saveFileEditor()
        runCurrent()
        editor = checkNotNull(repository.state.value.files?.editor)
        assertEquals(FilesFailure.OFFLINE, editor.saveFailure)
        assertEquals("mine\n", editor.text)
        assertEquals(sent, writes(transport).size)
    }

    @Test
    fun noEditorOrWriteWithoutTheCapability() = runTest {
        val transport = editorTransport(advertised = capabilities) { error("must not be sent") }
        val repository = editing(transport)
        assertNotNull(repository.state.value.files?.file?.version)
        assertFalse(canEditFile(repository.state.value))
        assertNull(repository.state.value.files?.editor)
        val code = (runCatching { repository.filesWrite(sessionId, "src/app/main.ts", version, "x") }.exceptionOrNull() as RemoteRequestException).code
        assertEquals("unsupported", code)
        assertTrue(writes(transport).isEmpty())
    }

    @Test
    fun aFileWithMorePagesIsNotEditable() = runTest {
        val transport = Transport(writeCapabilities) { request ->
            if (request.text("type") == "session.files.read") page(request, "page one\n", 300000, nextOffset = 9)
            else listing(request, "a.kt")
        }
        val repository = opened(transport)
        repository.openFilesFile("a.kt")
        runCurrent()
        repository.startFileEditing()
        assertNull(repository.state.value.files?.editor)
    }

    @Test
    fun lateSaveRepliesAreIgnored() = runTest {
        val held = mutableListOf<JsonObject>()
        val transport = editorTransport { held += it; null }
        val repository = editing(transport)

        // Discarded, then edited again: the old reply touches neither the new editor nor the file.
        repository.updateFileEditorText("first\n")
        repository.saveFileEditor()
        runCurrent()
        repository.discardFileEditor()
        assertNull(repository.state.value.files?.editor)
        repository.startFileEditing()
        transport.result(held.single(), written(held.single()))
        runCurrent()
        var files = checkNotNull(repository.state.value.files)
        assertEditor(FileEditor("src/app/main.ts", version, "hello\n"), files.editor)
        assertEquals("hello\n", files.file?.content)
        assertEquals(version, files.file?.version)

        // Closed and opened again: the reply is dropped as well.
        held.clear()
        repository.updateFileEditorText("second\n")
        repository.saveFileEditor()
        runCurrent()
        repository.closeFiles()
        repository.openFiles()
        runCurrent()
        repository.openFilesFile("src/app/main.ts")
        runCurrent()
        repository.startFileEditing()
        transport.result(held.single(), written(held.single()))
        runCurrent()
        files = checkNotNull(repository.state.value.files)
        assertEditor(FileEditor("src/app/main.ts", version, "hello\n"), files.editor)

        // Leaving the file drops the editor.
        repository.openFilesFile(null)
        assertNull(repository.state.value.files?.editor)
    }

    @Test
    fun aReconnectToTheSameSessionKeepsTheEditor() = runTest {
        val transport = editorTransport()
        val repository = editing(transport)
        repository.updateFileEditorText("unsaved\n")
        transport.listener.failed(false, R.string.remote_connection_error)
        repository.activate(RemoteSelection("host", "project", sessionId))
        runCurrent()
        assertTrue(repository.state.value.connected)
        val editor = checkNotNull(repository.state.value.files?.editor)
        assertEquals("unsaved\n", editor.text)
        assertTrue(editor.changed)
        // The kept editor still saves.
        repository.saveFileEditor()
        runCurrent()
        assertEquals("unsaved\n", writes(transport).single().text("content"))
        assertFalse(checkNotNull(repository.state.value.files?.editor).changed)
    }

    @Test
    fun anotherSessionClosesTheBrowserAndAReturnOpensItAgain() = runTest {
        // An unchanged editor is dropped with its session; the return shows its file read-only.
        val plainTransport = editorTransport()
        val plain = editing(plainTransport)
        plain.activate(RemoteSelection("host", "project", "other"))
        runCurrent()
        assertNull(plain.state.value.files)
        plain.activate(RemoteSelection("host", "project", sessionId))
        runCurrent()
        val reopened = checkNotNull(plain.state.value.files)
        assertNull(reopened.editor)
        assertEquals("src/app/main.ts", reopened.file?.path)
        assertFalse(reopened.file?.loading == true)

        // The browser of the chat on screen stays open across a reconnect and reads again.
        val browsingTransport = editorTransport()
        val browsing = opened(browsingTransport)
        val listing = checkNotNull(browsing.state.value.files?.listing)
        val lists = browsingTransport.files().count { it.text("type") == "session.files.list" }
        browsingTransport.listener.failed(false, R.string.remote_connection_error)
        browsing.activate(RemoteSelection("host", "project", sessionId))
        runCurrent()
        assertEquals(listing, browsing.state.value.files?.listing)
        assertEquals(lists + 1, browsingTransport.files().count { it.text("type") == "session.files.list" })
    }

    @Test
    fun anUnsavedEditIsParkedWithItsSessionAndComesBack() = runTest {
        val held = mutableListOf<JsonObject>()
        val transport = editorTransport { held += it; null }
        val repository = editing(transport)
        repository.updateFileEditorText("unsaved\n")
        // A save still runs when another session opens without asking (a notification tap).
        repository.saveFileEditor()
        runCurrent()
        repository.activate(RemoteSelection("host", "project", "other"))
        runCurrent()
        assertNull(repository.state.value.files)
        repository.activate(RemoteSelection("host", "project", sessionId))
        runCurrent()
        val editor = checkNotNull(repository.state.value.files?.editor)
        assertEquals("unsaved\n", editor.text)
        assertTrue(editor.changed)
        // Its reply never came, so it waits for Retry instead of spinning.
        assertFalse(editor.saving)
        assertEquals(FilesFailure.OFFLINE, editor.saveFailure)
        repository.saveFileEditor()
        runCurrent()
        assertEquals(2, held.size)
        assertEquals(held[0].text("requestId"), held[1].text("requestId"))
    }

    @Test
    fun theOldestOfTooManyParkedEditsIsDroppedWithAMessage() = runTest {
        val transport = editorTransport()
        val others = listOf("s2", "s3", "s4", "s5")
        transport.sessionIds = listOf(sessionId) + others + "s6"
        val repository = editing(transport)
        repository.updateFileEditorText("edit $sessionId\n")
        for (id in others) {
            repository.activate(RemoteSelection("host", "project", id))
            runCurrent()
            assertNull(repository.state.value.error)
            repository.openFiles()
            runCurrent()
            repository.openFilesFile("src/app/main.ts")
            runCurrent()
            repository.startFileEditing()
            repository.updateFileEditorText("edit $id\n")
        }
        // The fifth parked edit pushes out the first one, and the user hears of it.
        repository.activate(RemoteSelection("host", "project", "s6"))
        runCurrent()
        assertEquals(R.string.remote_files_editor_parked_dropped, repository.state.value.error)
        repository.activate(RemoteSelection("host", "project", sessionId))
        runCurrent()
        assertNull(repository.state.value.files)
        repository.activate(RemoteSelection("host", "project", "s2"))
        runCurrent()
        assertEquals("edit s2\n", repository.state.value.files?.editor?.text)
    }

    @Test
    fun aSaveCutOffByASelectionChangeEndsAndRetriesUnderItsRequestId() = runTest {
        val held = mutableListOf<JsonObject>()
        val transport = editorTransport { held += it; null }
        val repository = editing(transport)
        repository.updateFileEditorText("mine\n")
        repository.saveFileEditor()
        runCurrent()
        assertTrue(checkNotNull(repository.state.value.files?.editor).saving)
        // Navigation cancels the open requests while the save's coroutine lives on.
        repository.cancelSelection()
        runCurrent()
        var editor = checkNotNull(repository.state.value.files?.editor)
        assertFalse(editor.saving)
        assertEquals(FilesFailure.OFFLINE, editor.saveFailure)
        repository.saveFileEditor()
        runCurrent()
        assertEquals(2, held.size)
        assertEquals(held[0].text("requestId"), held[1].text("requestId"))
        transport.result(held[1], written(held[1]))
        runCurrent()
        editor = checkNotNull(repository.state.value.files?.editor)
        assertFalse(editor.changed)
        assertNull(editor.unconfirmed)
        // Other text is a new save with a new ID.
        repository.updateFileEditorText("again\n")
        repository.saveFileEditor()
        runCurrent()
        assertNotEquals(held[1].text("requestId"), held[2].text("requestId"))
    }

    @Test
    fun aHostErrorForgetsTheRequestId() = runTest {
        val transport = editorTransport()
        transport.failure = { request -> if (request.text("type") == "session.files.write") "busy" else null }
        val repository = editing(transport)
        repository.updateFileEditorText("mine\n")
        repository.saveFileEditor()
        runCurrent()
        repository.saveFileEditor()
        runCurrent()
        val ids = writes(transport).map { it.text("requestId") }
        assertEquals(2, ids.size)
        assertNotEquals(ids[0], ids[1])
    }

    @Test
    fun aConflictWithOurOwnTextCountsAsSaved() = runTest {
        var hostContent = "hello\n"
        val transport = Transport(writeCapabilities) { request ->
            when (request.text("type")) {
                // The write reached the host, but its reply was lost: a retry finds the file changed.
                "session.files.write" -> null.also { hostContent = request.text("content") }
                "session.files.read" -> page(request, hostContent, hostContent.encodeToByteArray().size)
                else -> listing(request, "main.ts")
            }
        }
        val repository = editing(transport)
        repository.updateFileEditorText("mine\n")
        repository.saveFileEditor()
        runCurrent()
        repository.cancelSelection()
        runCurrent()
        transport.failure = { request -> if (request.text("type") == "session.files.write") "conflict" else null }
        // The host's ledger may be gone; the text it holds still proves the save.
        repository.saveFileEditor()
        runCurrent()
        val saved = checkNotNull(repository.state.value.files?.editor)
        assertFalse(saved.conflict)
        assertNull(saved.saveFailure)
        assertFalse(saved.changed)
        assertEquals(1L, saved.savedAt)
        assertEquals("mine\n", repository.state.value.files?.file?.content)

        // Other text on the host is a real conflict.
        hostContent = "theirs\n"
        repository.updateFileEditorText("mine again\n")
        repository.saveFileEditor()
        runCurrent()
        val conflicted = checkNotNull(repository.state.value.files?.editor)
        assertTrue(conflicted.conflict)
        assertEquals(FilesFailure.CONFLICT, conflicted.saveFailure)
    }

    @Test
    fun moreTypingAfterALostReplySavesOnTopOfTheEarlierWrite() = runTest {
        // A host that writes against the current version and refuses any other base.
        var hostContent = "hello\n"
        var hostVersion = version
        val versions = ArrayDeque(listOf(newVersion, "VGhpcmRWZXJzaW9uQWJjMQ"))
        val transport = Transport(writeCapabilities) { request ->
            when (request.text("type")) {
                "session.files.write" -> {
                    hostContent = request.text("content")
                    hostVersion = versions.removeFirst()
                    null
                }
                "session.files.read" -> page(request, hostContent, hostContent.encodeToByteArray().size, fileVersion = hostVersion)
                else -> listing(request, "main.ts")
            }
        }
        val repository = editing(transport)
        // C1 reaches the host, but its reply is lost.
        repository.updateFileEditorText("one\n")
        repository.saveFileEditor()
        runCurrent()
        repository.cancelSelection()
        runCurrent()
        assertEquals(FilesFailure.OFFLINE, repository.state.value.files?.editor?.saveFailure)

        // More typing, then a save against the old base: the host has moved on to C1.
        transport.failure = { request ->
            if (request.text("type") == "session.files.write" && request.text("baseVersion") != hostVersion) "conflict" else null
        }
        val held = mutableListOf<JsonObject>()
        transport.files = { request ->
            when (request.text("type")) {
                "session.files.write" -> {
                    held += request
                    hostContent = request.text("content")
                    hostVersion = versions.removeFirst()
                    written(request).let { JsonObject(it + ("version" to JsonPrimitive(hostVersion))) }
                }
                "session.files.read" -> page(request, hostContent, hostContent.encodeToByteArray().size, fileVersion = hostVersion)
                else -> listing(request, "main.ts")
            }
        }
        repository.updateFileEditorText("one\ntwo\n")
        repository.saveFileEditor()
        runCurrent()

        val editor = checkNotNull(repository.state.value.files?.editor)
        assertFalse(editor.conflict)
        assertNull(editor.saveFailure)
        assertFalse(editor.saving)
        assertFalse(editor.changed)
        assertEquals("one\ntwo\n", editor.original)
        assertEquals("one\ntwo\n", hostContent)
        assertEquals("VGhpcmRWZXJzaW9uQWJjMQ", editor.baseVersion)
        assertNull(editor.unconfirmed)
        assertTrue(editor.superseded.isEmpty())
        // The resend went against C1's version, under a new request ID.
        val resend = held.single()
        assertEquals(newVersion, resend.text("baseVersion"))
        val sent = writes(transport)
        assertEquals(3, sent.size)
        assertEquals(version, sent[1].text("baseVersion"))
        assertNotEquals(sent[1].text("requestId"), resend.text("requestId"))
        assertEquals("one\ntwo\n", repository.state.value.files?.file?.content)
    }

    @Test
    fun aSecondConflictAfterTheResendIsAConflict() = runTest {
        var hostContent = "hello\n"
        var hostVersion = version
        val transport = Transport(writeCapabilities) { request ->
            when (request.text("type")) {
                "session.files.write" -> {
                    hostContent = request.text("content")
                    hostVersion = newVersion
                    null
                }
                "session.files.read" -> page(request, hostContent, hostContent.encodeToByteArray().size, fileVersion = hostVersion)
                else -> listing(request, "main.ts")
            }
        }
        val repository = editing(transport)
        repository.updateFileEditorText("one\n")
        repository.saveFileEditor()
        runCurrent()
        repository.cancelSelection()
        runCurrent()
        // Every later write conflicts, the resend on top of C1 included.
        transport.failure = { request -> if (request.text("type") == "session.files.write") "conflict" else null }
        repository.updateFileEditorText("one\ntwo\n")
        repository.saveFileEditor()
        runCurrent()
        val editor = checkNotNull(repository.state.value.files?.editor)
        assertTrue(editor.conflict)
        assertEquals(FilesFailure.CONFLICT, editor.saveFailure)
        // The base moved to C1, so Reload or a diff starts from what the host holds.
        assertEquals(newVersion, editor.baseVersion)
        assertEquals("one\n", editor.original)
        assertEquals("one\ntwo\n", editor.text)
        assertEquals(3, writes(transport).size)
    }

    @Test
    fun saveOfUnchangedTextClearsAnOldFailure() = runTest {
        val transport = editorTransport()
        transport.failure = { request -> if (request.text("type") == "session.files.write") "busy" else null }
        val repository = editing(transport)
        repository.updateFileEditorText("mine\n")
        repository.saveFileEditor()
        runCurrent()
        assertEquals(FilesFailure.BUSY, repository.state.value.files?.editor?.saveFailure)
        repository.updateFileEditorText("hello\n")
        repository.saveFileEditor()
        runCurrent()
        assertNull(repository.state.value.files?.editor?.saveFailure)
        assertEquals(1, writes(transport).size)
    }

    @Test
    fun aTextTooLargeForOneFrameIsNotSent() = runTest {
        val transport = editorTransport()
        val repository = editing(transport)
        // Quotes turn into \" in JSON: short enough in characters, too long once encoded.
        repository.updateFileEditorText("\"".repeat(MAX_FILES_CONTENT_JSON_BYTES / 2 + 1))
        repository.saveFileEditor()
        runCurrent()
        assertEquals(FilesFailure.TOO_LARGE, repository.state.value.files?.editor?.saveFailure)
        assertTrue(writes(transport).isEmpty())
    }

    @Test
    fun aSaveBeforeTheCapabilitiesAreKnownIsOffline() = runTest {
        // Just after a reconnect: connected, but projects.list has not told the capabilities yet.
        var state =
            RemoteState(
                connected = true,
                capabilitiesKnown = false,
                selection = RemoteSelection("host", "project", sessionId),
                files = FilesState(sessionId, loading = false, editor = FileEditor("a.kt", version, "a\n", "b\n")),
            )
        val loader =
            ProjectFilesLoader(
                backgroundScope,
                object : FileSource {
                    override suspend fun filesList(sessionId: String, path: String, after: String?) = error("unused")

                    override suspend fun filesRead(sessionId: String, path: String, offset: Long, version: String?) = error("unused")

                    override suspend fun filesWrite(sessionId: String, path: String, baseVersion: String, content: String, requestId: String) =
                        error("must not be sent")
                },
                { state },
                { state = it(state) },
            )
        loader.saveEditor()
        runCurrent()
        assertEquals(FilesFailure.OFFLINE, state.files?.editor?.saveFailure)
        assertFalse(checkNotNull(state.files?.editor).saving)
    }

    @Test
    fun aLaterFailureClearsTheConflict() = runTest {
        val transport = editorTransport()
        transport.failure = { request -> if (request.text("type") == "session.files.write") "conflict" else null }
        val repository = editing(transport)
        repository.updateFileEditorText("mine\n")
        repository.saveFileEditor()
        runCurrent()
        assertTrue(checkNotNull(repository.state.value.files?.editor).conflict)
        transport.failure = { request -> if (request.text("type") == "session.files.write") "busy" else null }
        repository.saveFileEditor()
        runCurrent()
        val editor = checkNotNull(repository.state.value.files?.editor)
        assertFalse(editor.conflict)
        assertEquals(FilesFailure.BUSY, editor.saveFailure)
    }

    @Test
    fun filesAndChangesNeverDropAnUnsavedEdit() = runTest {
        val transport = editorTransport()
        val repository = editing(transport)
        repository.updateFileEditorText("mine\n")
        // Files again while the editor shows: nothing happens.
        repository.openFiles()
        runCurrent()
        assertEquals("mine\n", repository.state.value.files?.editor?.text)
        // Changes ask first; keeping the edit cancels them.
        repository.openChanges()
        runCurrent()
        assertEquals(EditorLeave.CHANGES, repository.state.value.files?.editor?.leaveRequest)
        assertNull(repository.state.value.changes)
        repository.cancelFileEditorLeave()
        assertNull(repository.state.value.files?.editor?.leaveRequest)
        assertNull(repository.state.value.changes)
        // Discarding runs them.
        repository.openChanges()
        repository.confirmFileEditorLeave()
        runCurrent()
        assertNull(repository.state.value.files)
        assertNotNull(repository.state.value.changes)
    }

    @Test
    fun theCursorStartsAtThePickedLine() = runTest {
        val transport = Transport(writeCapabilities) { request ->
            if (request.text("type") == "session.files.read") page(request, "one\ntwo\nthree\n", 14) else listing(request, "a.kt")
        }
        val repository = opened(transport)
        repository.openFilesFile("a.kt")
        runCurrent()
        repository.selectFileLines(LineSelection(3, 2))
        repository.startFileEditing()
        assertEquals(4, repository.state.value.files?.editor?.cursor)
    }

    @Test
    fun promptIsNullUntilTheTextChanges() = runTest {
        val repository = editing(editorTransport())
        assertNull(repository.fileEditorPrompt())
        repository.updateFileEditorText("hello\nworld\n")
        val prompt = repository.fileEditorPrompt("Intro") as EditorPrompt.Ready
        assertTrue(prompt.text, prompt.text.startsWith("Intro\n\n```diff\n--- a/src/app/main.ts\n"))
        assertTrue(prompt.text.endsWith("\n```"))
        repository.discardFileEditor()
        assertNull(repository.fileEditorPrompt())
    }
}
