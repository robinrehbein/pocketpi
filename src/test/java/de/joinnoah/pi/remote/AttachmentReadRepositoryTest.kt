package de.joinnoah.pi.remote

import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AttachmentReadRepositoryTest {
    private val host = PairedHost("host", "https://relay.test", "device", "secret", "Host")
    private val selection = RemoteSelection("host", "project", "session")
    private val attachmentId = "AAAAAAAAAAAAAAAAAAAAAA"
    private val bytes = ByteArray(ATTACHMENT_CHUNK_BYTES * 2 + 10) { (it * 7).toByte() }
    private val attachment =
        RemoteAttachment(
            attachmentId, "photo.jpg", "image", "image/jpeg", bytes.size.toLong(),
            AttachmentImportRules.sha256(bytes), Long.MAX_VALUE,
        )

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

    private class Transport(val now: () -> Long) : RemoteTransport {
        override lateinit var listener: RemoteTransport.Listener
        val sent = mutableListOf<JsonObject>()
        val sentAt = mutableListOf<Long>()
        var advertised = listOf(ATTACHMENT_READ_CAPABILITY)
        var read: (JsonObject) -> JsonObject? = { null }
        var error: String? = null
        private val session =
            Wire.objectOf("id" to "session", "origin" to "rpc", "status" to "idle", "projectId" to "project")

        override fun connect(host: PairedHost) {
            listener.ready(emptySet())
        }

        override fun pair(value: JsonObject) {}

        override fun close() {}

        override fun send(payload: JsonObject) {
            sent += payload
            sentAt += now()
            fun reply(body: Pair<String, JsonObject>, ok: Boolean = true) = respond(payload, body, ok)
            when (payload.text("type")) {
                "projects.list" ->
                    reply(
                        "data" to
                            Wire.objectOf(
                                "kind" to "projects",
                                "items" to JsonArray(listOf(Wire.objectOf("id" to "project"))),
                                "capabilities" to JsonArray(advertised.map(::JsonPrimitive)),
                            )
                    )
                "sessions.list" -> reply("data" to Wire.objectOf("kind" to "sessions", "items" to JsonArray(listOf(session))))
                "sessions.open" -> reply("data" to Wire.objectOf("kind" to "session", "session" to session))
                "session.snapshot" ->
                    reply(
                        "data" to
                            Wire.objectOf(
                                "kind" to "snapshot",
                                "sessionId" to payload.text("sessionId"),
                                "revision" to 0,
                                "status" to "idle",
                                "messages" to JsonArray(emptyList()),
                                "pendingQuestions" to JsonArray(emptyList()),
                            )
                    )
                "session.attachments.get" ->
                    error?.let { reply("error" to Wire.objectOf("code" to it, "message" to it), ok = false) }
                        ?: read(payload)?.let { reply("data" to it) }
                else -> reply("data" to Wire.objectOf("kind" to "accepted"))
            }
        }

        fun respond(request: JsonObject, body: Pair<String, JsonObject>, ok: Boolean = true) =
            listener.message(
                Wire.objectOf("type" to "result", "requestId" to request.text("requestId"), "ok" to ok, body.first to body.second)
            )

        fun reads() = sent.filter { it.text("type") == "session.attachments.get" }
    }

    private suspend fun TestScope.connected(transport: Transport): DefaultRemoteRepository {
        val repository =
            DefaultRemoteRepository(
                Pairings(listOf(host)), Drafts(), transport, backgroundScope,
                StandardTestDispatcher(testScheduler), now = { testScheduler.currentTime },
            )
        repository.activate(selection)
        runCurrent()
        return repository
    }

    private fun Transport.serve(content: ByteArray = bytes) {
        read = { request ->
            val offset = request.long("offset").toInt()
            val length = minOf(ATTACHMENT_CHUNK_BYTES, content.size - offset)
            Wire.objectOf(
                "kind" to "attachment.content",
                "sessionId" to request.text("sessionId"),
                "attachmentId" to request.text("attachmentId"),
                "offset" to offset,
                "totalBytes" to content.size,
                "sha256" to AttachmentImportRules.sha256(content),
                "data" to Wire.encode(content.copyOfRange(offset, offset + length)),
            )
        }
    }

    private fun TestScope.transport() = Transport { testScheduler.currentTime }.also { it.serve() }

    @Test
    fun noRequestIsSentWithoutTheCapability() = runTest {
        val transport = transport().apply { advertised = emptyList() }
        val repository = connected(transport)
        val result = async { repository.readAttachment("session", attachment) }
        runCurrent()
        assertEquals(AttachmentReadResult.Unsupported, result.await())
        assertTrue(transport.reads().isEmpty())
    }

    @Test
    fun onlyImagesOfTheCurrentSessionWithinTheCapAreRead() = runTest {
        val transport = transport()
        val repository = connected(transport)
        suspend fun read(session: String, value: RemoteAttachment) = repository.readAttachment(session, value)
        assertEquals(AttachmentReadResult.Unavailable, read("session", attachment.copy(kind = "file")))
        assertEquals(AttachmentReadResult.Unavailable, read("other", attachment))
        assertEquals(AttachmentReadResult.Unavailable, read("session", attachment.copy(size = MAX_ATTACHMENT_IMAGE_BYTES + 1)))
        assertEquals(AttachmentReadResult.Unavailable, read("session", attachment.copy(id = "short")))
        assertTrue(transport.reads().isEmpty())
    }

    @Test
    fun chunksAreReadSequentiallySpacedAndVerified() = runTest {
        val transport = transport()
        val repository = connected(transport)
        val result = async { repository.readAttachment("session", attachment) }
        advanceUntilIdle()
        val loaded = result.await() as AttachmentReadResult.Loaded
        assertArrayEquals(bytes, loaded.bytes)
        val reads = transport.reads()
        assertEquals(listOf(0L, 49152L, 98304L), reads.map { it.long("offset") })
        assertTrue(reads.all { it.keys == setOf("type", "requestId", "sessionId", "attachmentId", "offset") })
        assertTrue(reads.all { it.text("sessionId") == "session" && it.text("attachmentId") == attachmentId })
        val starts = transport.sentAt.takeLast(3)
        assertTrue(starts.zipWithNext().all { (a, b) -> b - a >= 200 })
    }

    @Test
    fun aSecondReadUsesTheCacheAndConcurrentReadsShareOneRequestChain() = runTest {
        val transport = transport()
        val repository = connected(transport)
        val first = async { repository.readAttachment("session", attachment) }
        val second = async { repository.readAttachment("session", attachment) }
        advanceUntilIdle()
        assertTrue(first.await() is AttachmentReadResult.Loaded && second.await() is AttachmentReadResult.Loaded)
        assertEquals(3, transport.reads().size)
        val again = async { repository.readAttachment("session", attachment) }
        advanceUntilIdle()
        assertArrayEquals(bytes, (again.await() as AttachmentReadResult.Loaded).bytes)
        assertEquals(3, transport.reads().size)
    }

    @Test
    fun differentAttachmentsNeverOverlapAndStaySpaced() = runTest {
        val transport = transport()
        val repository = connected(transport)
        val other = attachment.copy(id = "BBBBBBBBBBBBBBBBBBBBBA")
        val a = async { repository.readAttachment("session", attachment) }
        val b = async { repository.readAttachment("session", other) }
        advanceUntilIdle()
        assertTrue(a.await() is AttachmentReadResult.Loaded && b.await() is AttachmentReadResult.Loaded)
        val starts = transport.sentAt.takeLast(6)
        assertTrue(starts.zipWithNext().all { (x, y) -> y - x >= 200 })
    }

    @Test
    fun hostErrorsMapToUnavailableOrRetryable() = runTest {
        val transport = transport()
        val repository = connected(transport)
        val expected =
            mapOf(
                "not_found" to AttachmentReadResult.Unavailable,
                "forbidden" to AttachmentReadResult.Unavailable,
                "invalid_request" to AttachmentReadResult.Unavailable,
                "busy" to AttachmentReadResult.Failed,
                "internal" to AttachmentReadResult.Failed,
            )
        for ((code, result) in expected) {
            transport.error = code
            val read = async { repository.readAttachment("session", attachment) }
            advanceUntilIdle()
            assertEquals(code, result, read.await())
        }
        // Nothing failed is cached: the next attempt reads again.
        transport.error = null
        val retry = async { repository.readAttachment("session", attachment) }
        advanceUntilIdle()
        assertTrue(retry.await() is AttachmentReadResult.Loaded)
    }

    @Test
    fun aDroppedConnectionIsARetryableFailure() = runTest {
        val transport = transport().apply { read = { null } }
        val repository = connected(transport)
        val result = async { repository.readAttachment("session", attachment) }
        runCurrent()
        transport.listener.failed(false, R.string.remote_offline)
        runCurrent()
        assertEquals(AttachmentReadResult.Failed, result.await())
    }

    @Test
    fun aReplyThatBreaksTheProtocolIsUnavailable() = runTest {
        val transport = transport()
        val repository = connected(transport)
        val good = transport.read
        transport.read = { request -> good(request)?.let { JsonObject(it + ("totalBytes" to JsonPrimitive(1))) } }
        val result = async { repository.readAttachment("session", attachment) }
        advanceUntilIdle()
        assertEquals(AttachmentReadResult.Unavailable, result.await())
        assertEquals(1, transport.reads().size)
    }

    @Test
    fun aCancelledSharedReadFailsLiveWaitersInsteadOfLeavingThemWaiting() = runTest {
        val transport = transport().apply { read = { null } }
        val repository = connected(transport)
        val waiter = async { repository.readAttachment("session", attachment) }
        runCurrent()
        assertEquals(1, transport.reads().size)
        repository.cancelSelection()
        runCurrent()
        assertEquals(AttachmentReadResult.Failed, waiter.await())
    }

    @Test
    fun aCallerThatLeavesStillCancelsNormally() = runTest {
        val transport = transport().apply { read = { null } }
        val repository = connected(transport)
        val waiter = async { repository.readAttachment("session", attachment) }
        runCurrent()
        waiter.cancel()
        runCurrent()
        assertTrue(waiter.isCancelled)
    }

    @Test
    fun anOldReadFinishingDoesNotRemoveANewerEntry() = runTest {
        val transport = transport()
        val serve = transport.read
        transport.read = { null }
        val repository = connected(transport)
        val first = async { repository.readAttachment("session", attachment) }
        runCurrent()
        first.cancel()
        runCurrent()
        transport.read = serve
        val second = async { repository.readAttachment("session", attachment) }
        runCurrent()
        // The abandoned request is answered; the first job ends while the second one waits its turn.
        transport.respond(transport.reads().first(), "data" to serve(transport.reads().first())!!)
        runCurrent()
        val third = async { repository.readAttachment("session", attachment) }
        advanceUntilIdle()
        assertTrue(second.await() is AttachmentReadResult.Loaded && third.await() is AttachmentReadResult.Loaded)
        // One abandoned request plus a single chain of three, shared by the later callers.
        assertEquals(4, transport.reads().size)
    }

    @Test
    fun noReadStartsWhileAnAbandonedRequestIsStillOutstanding() = runTest {
        val transport = transport()
        val serve = transport.read
        transport.read = { null }
        val repository = connected(transport)
        val first = async { repository.readAttachment("session", attachment) }
        runCurrent()
        first.cancel()
        runCurrent()
        transport.read = serve
        val second = async { repository.readAttachment("session", attachment) }
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, transport.reads().size)
        val abandoned = transport.reads().single()
        val answeredAt = testScheduler.currentTime
        transport.respond(abandoned, "data" to serve(abandoned)!!)
        advanceUntilIdle()
        assertTrue(second.await() is AttachmentReadResult.Loaded)
        assertTrue(transport.sentAt[transport.sent.indexOf(transport.reads()[1])] - answeredAt >= 0)
        assertEquals(4, transport.reads().size)
    }

    @Test
    fun capabilitiesNotYetKnownAreNeverReportedAsUnsupported() = runTest {
        val transport = transport().apply { advertised = emptyList() }
        val repository = connected(transport)
        assertTrue(repository.state.value.capabilitiesKnown)
        transport.listener.ready(emptySet())
        runCurrent()
        assertFalse(repository.state.value.capabilitiesKnown)
        val result = async { repository.readAttachment("session", attachment) }
        runCurrent()
        assertEquals(AttachmentReadResult.Failed, result.await())
        assertTrue(transport.reads().isEmpty())
    }
}
