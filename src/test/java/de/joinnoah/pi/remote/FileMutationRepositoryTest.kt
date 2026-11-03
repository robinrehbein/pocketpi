package de.joinnoah.pi.remote

import kotlinx.coroutines.test.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Create, rename and delete through the real repository, loader and journal, against a scripted host. */
class FileMutationRepositoryTest {
    private val host = PairedHost("host", "https://relay.test", "device", "secret", "Host")
    private val sessionId = "019a2f3c-7d41-7b1e-9c55-3e8f1a2b4c6d"
    private val epoch = "am91cm5hbC1lcG9jaC0wMQ"
    private val otherEpoch = "b3RoZXItZXBvY2gtMDAwMQ"
    private val version = "YmFzZS12ZXJzaW9uLTAxeA"
    private val newVersion = "bmV3LXZlcnNpb24tMDAwMQ"
    private val all = listOf(FILES_CAPABILITY, FILES_CREATE_CAPABILITY, FILES_RENAME_CAPABILITY, FILES_DELETE_CAPABILITY, FILES_MUTATION_RECOVERY_CAPABILITY)
    private val mutationTypes = setOf("session.files.create", "session.files.rename", "session.files.delete")

    private class Pairings(val host: PairedHost) : PairingStorage {
        override fun load() = listOf(host)
        override fun save(hosts: List<PairedHost>) {}
    }

    private class Drafts : DraftStorage {
        override fun load() = emptyMap<DraftKey, StoredDraft>()
        override fun save(drafts: Map<DraftKey, StoredDraft>) {}
    }

    private class FileDrafts(var drafts: Map<FileDraftKey, StoredFileDraft> = emptyMap()) : FileDraftStorage {
        override fun load() = drafts
        override fun save(drafts: Map<FileDraftKey, StoredFileDraft>) { this.drafts = drafts }
    }

    private class Journal : FileMutationStorage {
        override val lockIdentity: Any get() = this
        var entries: Map<FileMutationKey, FileMutationEntry> = emptyMap()
        var failSave = false
        override fun load() = entries
        override fun save(entries: Map<FileMutationKey, FileMutationEntry>) {
            if (failSave) error("disk full")
            this.entries = entries
        }
    }

    /** Answers each command through [answer]: data, or null to hold it; [error] answers `ok:false`. */
    private inner class Transport(private val advertised: List<String>) : RemoteTransport {
        override lateinit var listener: RemoteTransport.Listener
        val sent = mutableListOf<JsonObject>()
        var answer: (JsonObject) -> JsonObject? = { null }
        var error: (JsonObject) -> String? = { null }
        /** Called for every frame before it is answered, as the host would receive it. */
        var onSend: (JsonObject) -> Unit = {}
        var fileVersion = version
        var listEpoch = epoch
        var listOperations: List<JsonObject> = emptyList()

        override fun connect(host: PairedHost) = listener.ready(emptySet())
        override fun pair(value: JsonObject) {}
        override fun close() {}

        override fun send(payload: JsonObject) {
            sent += payload
            onSend(payload)
            error(payload)?.let { code ->
                listener.message(Wire.objectOf("type" to "result", "requestId" to payload.text("requestId"), "ok" to false,
                    "error" to Wire.objectOf("code" to code, "message" to code)))
                return
            }
            response(payload)?.let {
                listener.message(Wire.objectOf("type" to "result", "requestId" to payload.text("requestId"), "ok" to true, "data" to it))
            }
        }

        fun files() = sent.filter { it.text("type").startsWith("session.files.") }
        fun types() = files().map { it.text("type") }

        private fun response(request: JsonObject): JsonObject? =
            when (request.text("type")) {
                "projects.list" -> Wire.objectOf("kind" to "projects", "items" to JsonArray(listOf(Wire.objectOf("id" to "project"))),
                    "capabilities" to JsonArray(advertised.map(::JsonPrimitive)))
                "sessions.list" -> Wire.objectOf("kind" to "sessions", "items" to JsonArray(listOf(session())))
                "sessions.open" -> Wire.objectOf("kind" to "session", "session" to session())
                "session.snapshot" -> Wire.objectOf("kind" to "snapshot", "sessionId" to sessionId, "revision" to 0, "status" to "idle",
                    "messages" to JsonArray(emptyList()), "pendingQuestions" to JsonArray(emptyList()))
                "session.files.list" -> Wire.objectOf("kind" to "files.list", "sessionId" to sessionId, "path" to request.text("path"),
                    "available" to true, "entries" to JsonArray(listOf(Wire.objectOf("name" to "a.txt", "type" to "file", "size" to 1))), "truncated" to false)
                "session.files.read" -> Wire.objectOf("kind" to "files.read", "sessionId" to sessionId, "path" to request.text("path"),
                    "version" to fileVersion, "size" to 1, "offset" to 0, "content" to "a", "binary" to false)
                "session.files.mutation.list" -> Wire.objectOf("kind" to "files.mutation.list", "sessionId" to sessionId, "epoch" to listEpoch,
                    "operations" to JsonArray(listOperations))
                else -> if (request.text("type").startsWith("session.files.")) answer(request) else Wire.objectOf("kind" to "accepted")
            }

        private fun session() = Wire.objectOf("id" to sessionId, "origin" to "rpc", "status" to "idle", "projectId" to "project")
    }

    private fun record(request: JsonObject, state: String, reason: String? = null, acknowledged: Boolean = false): JsonObject {
        val kind = request.optionalText("operation") ?: request.text("type").removePrefix("session.files.")
        return JsonObject(buildMap {
            put("kind", JsonPrimitive("files.mutation"))
            put("sessionId", JsonPrimitive(sessionId))
            put("operationId", JsonPrimitive(request.text("operationId")))
            put("operation", JsonPrimitive(kind))
            put("path", JsonPrimitive(request.text("path")))
            request.optionalText("destinationPath")?.let { put("destinationPath", JsonPrimitive(it)) }
            put("state", JsonPrimitive(state))
            reason?.let { put("reason", JsonPrimitive(it)) }
            put("acknowledged", JsonPrimitive(acknowledged))
        })
    }

    private fun TestScope.repository(
        transport: Transport,
        journal: FileMutationStorage? = Journal(),
        drafts: FileDraftStorage? = null,
        io: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
    ) = DefaultRemoteRepository(
        Pairings(host), Drafts(), transport, backgroundScope, io,
        now = { testScheduler.currentTime }, fileDraftStorage = drafts, fileMutationStorage = journal,
    )

    private suspend fun TestScope.opened(repository: DefaultRemoteRepository): DefaultRemoteRepository {
        repository.activate(RemoteSelection("host", "project", sessionId))
        repository.openFiles()
        runCurrent()
        return repository
    }

    private fun RemoteRepository.dialog() = fileMutationsOf(state.value)?.dialog
    private fun RemoteRepository.outcome() = fileMutationsOf(state.value)?.outcome

    @Test
    fun eachActionNeedsItsCapabilityAndTheRecoveryCapability() = runTest {
        for (missing in listOf(FILES_CREATE_CAPABILITY, FILES_MUTATION_RECOVERY_CAPABILITY)) {
            val transport = Transport(all - missing)
            val repository = opened(repository(transport))
            assertFalse(FileMutationAccess.of(repository.state.value).create)
            repository.prepareFileCreate("")
            assertNull(repository.dialog())
            assertTrue(transport.types().none { it in mutationTypes })
        }
        val transport = Transport(all - FILES_DELETE_CAPABILITY)
        val repository = opened(repository(transport))
        assertEquals(FileMutationAccess(create = true, rename = true, delete = false), FileMutationAccess.of(repository.state.value))
        repository.prepareFileDelete("a.txt")
        runCurrent()
        assertNull(repository.dialog())
    }

    @Test
    fun aClientWithoutAJournalLikeTheWatchOffersAndSendsNothing() = runTest {
        val transport = Transport(all)
        val repository = opened(repository(transport, journal = null))
        assertNull(repository.state.value.fileMutations)
        assertEquals(FileMutationAccess(), FileMutationAccess.of(repository.state.value))
        repository.prepareFileCreate("")
        repository.prepareFileRename("a.txt")
        repository.prepareFileDelete("a.txt")
        repository.confirmFileMutation("b.txt")
        runCurrent()
        assertTrue(transport.types().none { it in mutationTypes || it.startsWith("session.files.mutation.") })
    }

    @Test
    fun createFetchesTheEpochJournalsBeforeSendingAndOpensTheFileOnlyAfterApplied() = runTest {
        val transport = Transport(all)
        val journal = Journal()
        var journaledFirst = false
        transport.onSend = { frame ->
            if (frame.text("type") == "session.files.create")
                journaledFirst = journal.entries.values.single().let {
                    it.state == FileMutationState.RESERVED && it.request.operationId == frame.text("operationId") && it.request.epoch == epoch
                }
        }
        val held = mutableListOf<JsonObject>()
        transport.answer = { held += it; null }
        val repository = opened(repository(transport, journal))
        repository.prepareFileCreate("")
        repository.confirmFileMutation("notes.md")
        runCurrent()
        val create = transport.files().single { it.text("type") == "session.files.create" }
        assertTrue(journaledFirst)
        assertEquals(setOf("type", "requestId", "sessionId", "operationId", "epoch", "path"), create.keys)
        assertEquals(epoch, create.text("epoch"))
        assertEquals("notes.md", create.text("path"))
        val listedBefore = transport.types().indexOf("session.files.mutation.list")
        assertTrue(listedBefore in 0 until transport.types().indexOf("session.files.create"))
        // Pending: no row changes and the file does not open.
        assertEquals(FileMutationStatus.PENDING, repository.outcome()?.status)
        assertNull(repository.state.value.files?.file)
        assertTrue(transport.files().none { it.text("type") == "session.files.read" })
        transport.answer = { null }
        repository.handleHeld(transport, held.single(), record(held.single(), "applied"))
        runCurrent()
        assertEquals(FileMutationStatus.APPLIED, repository.outcome()?.status)
        assertTrue(journal.entries.isEmpty())
        assertEquals("notes.md", repository.state.value.files?.file?.path)
        assertTrue(transport.files().any { it.text("type") == "session.files.read" && it.text("path") == "notes.md" })
    }

    /** Answers a held request as the host would. */
    private fun DefaultRemoteRepository.handleHeld(transport: Transport, request: JsonObject, data: JsonObject) =
        transport.listener.message(Wire.objectOf("type" to "result", "requestId" to request.text("requestId"), "ok" to true, "data" to data))

    @Test
    fun aJournalFailureSendsNothing() = runTest {
        val transport = Transport(all)
        val journal = Journal().apply { failSave = true }
        val repository = opened(repository(transport, journal))
        repository.prepareFileCreate("")
        repository.confirmFileMutation("notes.md")
        runCurrent()
        assertTrue(transport.types().none { it in mutationTypes })
        assertEquals(FilesFailure.STORAGE, repository.dialog()?.failure)
        assertTrue(fileMutationsOf(repository.state.value)!!.storageFailed)
    }

    @Test
    fun deleteSendsNothingBeforeConfirmationAndNamesTheExactPath() = runTest {
        val transport = Transport(all)
        transport.answer = { record(it, "applied") }
        val repository = opened(repository(transport))
        repository.prepareFileDelete("a.txt")
        runCurrent()
        val dialog = checkNotNull(repository.dialog())
        assertEquals(FileMutationKind.DELETE, dialog.kind)
        assertEquals("a.txt", dialog.path)
        assertEquals(version, dialog.version)
        assertTrue(dialog.confirmable)
        assertTrue(transport.types().none { it in mutationTypes })
        // Cancel, the default action, sends nothing.
        repository.cancelFileMutation()
        runCurrent()
        assertNull(repository.dialog())
        assertTrue(transport.types().none { it in mutationTypes })
        repository.prepareFileDelete("a.txt")
        runCurrent()
        repository.confirmFileMutation(null)
        runCurrent()
        val delete = transport.files().single { it.text("type") == "session.files.delete" }
        assertEquals("a.txt", delete.text("path"))
        assertEquals(version, delete.text("baseVersion"))
        assertEquals(FileMutationStatus.APPLIED, repository.outcome()?.status)
    }

    @Test
    fun aFileChangedSinceTheConfirmationIsNotDeleted() = runTest {
        val transport = Transport(all)
        transport.answer = { record(it, "applied") }
        val repository = opened(repository(transport))
        repository.prepareFileDelete("a.txt")
        runCurrent()
        transport.fileVersion = newVersion
        repository.confirmFileMutation(null)
        runCurrent()
        assertTrue(transport.types().none { it in mutationTypes })
        val dialog = checkNotNull(repository.dialog())
        assertTrue(dialog.changed)
        assertEquals(newVersion, dialog.version)
        // Confirming again sends against the version now shown.
        repository.confirmFileMutation(null)
        runCurrent()
        assertEquals(newVersion, transport.files().single { it.text("type") == "session.files.delete" }.text("baseVersion"))
    }

    @Test
    fun aKeptDraftOfTheSourceOrTheDestinationBlocksRenameAndDelete() = runTest {
        for ((held, target) in listOf("a.txt" to "b.txt", "b.txt" to "b.txt")) {
            val transport = Transport(all)
            transport.answer = { record(it, "applied") }
            val drafts = FileDrafts(mapOf(FileDraftKey("host", sessionId, held) to StoredFileDraft(version, "a", "edited")))
            val repository = opened(repository(transport, drafts = drafts))
            repository.prepareFileRename("a.txt")
            runCurrent()
            if (held == "a.txt") {
                assertTrue(repository.dialog()!!.blocked)
                assertFalse(repository.dialog()!!.confirmable)
            }
            repository.confirmFileMutation(target)
            runCurrent()
            assertTrue(transport.types().none { it in mutationTypes })
            assertTrue(repository.dialog()!!.blocked)
        }
    }

    @Test
    fun anOperationFromALostEpochBecomesUnknownAndIsNeverResent() = runTest {
        val transport = Transport(all)
        transport.listEpoch = otherEpoch
        val journal = Journal()
        val request = FileMutationRequest("host", "device", sessionId, "b3BlcmF0aW9uLTAwMDMtYw", epoch, FileMutationKind.DELETE, "a.txt", baseVersion = version)
        val key = FileMutationKey("device", request.operationId)
        journal.entries = mapOf(key to FileMutationEntry(request, FileMutationState.RESERVED))
        val repository = opened(repository(transport, journal))
        assertEquals(FileMutationState.LOST, journal.entries.getValue(key).state)
        val item = fileMutationsOf(repository.state.value)!!.recovery.single()
        assertTrue(item.lost)
        assertTrue(transport.types().none { it in mutationTypes || it == "session.files.mutation.status" })
        // A lost operation's host journal is gone: acknowledging it is local, and sends no mutation.
        repository.acknowledgeFileMutation(item.operationId)
        runCurrent()
        assertEquals(FileMutationState.ACKNOWLEDGED_UNKNOWN, journal.entries.getValue(key).state)
        assertTrue(fileMutationsOf(repository.state.value)!!.recovery.isEmpty())
        assertTrue(transport.types().none { it in mutationTypes })
    }

    @Test
    fun anUnansweredOperationIsOnlyAskedAboutAfterReconnecting() = runTest {
        val transport = Transport(all)
        val journal = Journal()
        transport.answer = { null }
        val repository = opened(repository(transport, journal))
        repository.prepareFileDelete("a.txt")
        runCurrent()
        repository.confirmFileMutation(null)
        runCurrent()
        assertEquals(1, transport.types().count { it == "session.files.delete" })
        transport.listener.failed(true, R.string.remote_connection_error)
        runCurrent()
        assertEquals(FileMutationState.RESERVED, journal.entries.values.single().state)
        transport.answer = { request -> if (request.text("type") == "session.files.mutation.status")
            record(Wire.objectOf("type" to "x", "operationId" to request.text("operationId"), "operation" to "delete", "path" to "a.txt"), "unknown") else null }
        repository.activate(RemoteSelection("host", "project", sessionId))
        repository.openFiles()
        runCurrent()
        assertEquals(1, transport.types().count { it == "session.files.delete" })
        val status = transport.files().last { it.text("type") == "session.files.mutation.status" }
        assertEquals(epoch, status.text("epoch"))
        assertEquals(FileMutationState.UNKNOWN, journal.entries.values.single().state)
        val item = fileMutationsOf(repository.state.value)!!.recovery.single()
        assertFalse(item.lost)
        // Acknowledging sends mutation.acknowledge, never the delete again.
        transport.answer = { request ->
            record(Wire.objectOf("type" to "x", "operationId" to request.text("operationId"), "operation" to "delete", "path" to "a.txt"), "unknown", acknowledged = true)
        }
        repository.acknowledgeFileMutation(item.operationId)
        runCurrent()
        val ack = transport.files().single { it.text("type") == "session.files.mutation.acknowledge" }
        assertEquals(setOf("type", "requestId", "sessionId", "operationId", "epoch"), ack.keys)
        assertEquals(FileMutationState.ACKNOWLEDGED_UNKNOWN, journal.entries.values.single().state)
        assertEquals(1, transport.types().count { it == "session.files.delete" })
    }

    @Test
    fun aRefusalLearnedAfterRestartIsShownWithItsReason() = runTest {
        val transport = Transport(all)
        val journal = Journal()
        val request = FileMutationRequest("host", "device", sessionId, "b3BlcmF0aW9uLTAwMDMtYw", epoch, FileMutationKind.DELETE, "a.txt", baseVersion = version)
        journal.entries = mapOf(FileMutationKey("device", request.operationId) to FileMutationEntry(request, FileMutationState.RESERVED))
        transport.answer = { frame ->
            record(Wire.objectOf("type" to "x", "operationId" to frame.text("operationId"), "operation" to "delete", "path" to "a.txt"), "refused", "conflict")
        }
        val repository = opened(repository(transport, journal))
        assertEquals(listOf("session.files.mutation.status"), transport.types().filter { it.startsWith("session.files.mutation.s") || it in mutationTypes })
        assertEquals(FileMutationStatus.REFUSED, repository.outcome()?.status)
        assertEquals(FileMutationRefusal.CONFLICT, repository.outcome()?.reason)
        assertEquals("a.txt", repository.outcome()?.path)
        assertTrue(journal.entries.isEmpty())
    }

    @Test
    fun refusalsAreTerminalAndKeepTheirReason() = runTest {
        for (reason in FileMutationRefusal.entries) {
            val transport = Transport(all)
            val journal = Journal()
            transport.answer = { record(it, "refused", reason.wire) }
            val repository = opened(repository(transport, journal))
            repository.prepareFileRename("a.txt")
            runCurrent()
            repository.confirmFileMutation("b.txt")
            runCurrent()
            assertEquals(FileMutationStatus.REFUSED, repository.outcome()?.status)
            assertEquals(reason, repository.outcome()?.reason)
            assertTrue(journal.entries.isEmpty())
            // The listing is not touched on a refusal: the open file stays.
            assertEquals(1, transport.types().count { it == "session.files.rename" })
        }
    }

    @Test
    fun anHostErrorAnswerIsAFailureThatRanNothing() = runTest {
        val transport = Transport(all)
        val journal = Journal()
        transport.error = { if (it.text("type") == "session.files.create") "busy" else null }
        val repository = opened(repository(transport, journal))
        repository.prepareFileCreate("")
        repository.confirmFileMutation("x.md")
        runCurrent()
        assertEquals(FileMutationStatus.FAILED, repository.outcome()?.status)
        assertEquals(FilesFailure.BUSY, repository.outcome()?.failure)
        assertTrue(journal.entries.isEmpty())
    }
}
