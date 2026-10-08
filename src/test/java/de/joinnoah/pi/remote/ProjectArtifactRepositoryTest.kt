package de.joinnoah.pi.remote

import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProjectArtifactRepositoryTest {
    private val host = PairedHost("host", "https://relay.test", "device", "secret", "Host")
    private val selection = RemoteSelection("host", "project", "session")
    private var content = ("<!doctype html><title>Report</title>" + "<p>Grüße</p>".repeat(6000)).toByteArray()
    private val digest get() = AttachmentImportRules.sha256(content)
    private val mediaId = "AAAAAAAAAAAAAAAAAAAAAA"

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

    private inner class Transport(val now: () -> Long) : RemoteTransport {
        override lateinit var listener: RemoteTransport.Listener
        val sent = mutableListOf<JsonObject>()
        val sentAt = mutableListOf<Long>()
        var advertised: List<String> = listOf(FILES_MEDIA_CAPABILITY, FILES_ARTIFACT_CAPABILITY)
        /** A host that predates the v2 route sends no list for it. */
        var legacyHost = false
        var startReply: (JsonObject) -> JsonObject = { request ->
            Wire.objectOf(
                "kind" to "files.media", "sessionId" to request.text("sessionId"), "path" to request.text("path").removePrefix("/p/"),
                "mediaId" to mediaId, "mimeType" to mime, "totalBytes" to content.size, "sha256" to digest,
            )
        }
        var mime = "text/html"
        var error: String? = null
        var open = 0
        var peakOpen = 0
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
            fun reply(data: JsonObject) = respond(payload, "data" to data)
            when (payload.text("type")) {
                "projects.list" -> {
                    val id = payload.text("requestId")
                    val listed = !legacyHost || id.startsWith("capabilities.v1:")
                    reply(
                        Wire.objectOf(
                            "kind" to "projects",
                            "items" to JsonArray(listOf(Wire.objectOf("id" to "project"))),
                            *(if (listed) arrayOf("capabilities" to JsonArray(advertised.map(::JsonPrimitive))) else emptyArray()),
                        )
                    )
                }
                "sessions.list" -> reply(Wire.objectOf("kind" to "sessions", "items" to JsonArray(listOf(session))))
                "sessions.open" -> reply(Wire.objectOf("kind" to "session", "session" to session))
                "session.snapshot" ->
                    reply(
                        Wire.objectOf(
                            "kind" to "snapshot", "sessionId" to payload.text("sessionId"), "revision" to 0, "status" to "idle",
                            "messages" to JsonArray(emptyList()), "pendingQuestions" to JsonArray(emptyList()),
                        )
                    )
                "session.files.media" ->
                    error?.takeIf { it != "mid" }?.let { respond(payload, "error" to Wire.objectOf("code" to it, "message" to it), ok = false) }
                        ?: reply(startReply(payload).also { if (it.containsKey("mediaId")) { open++; peakOpen = maxOf(peakOpen, open) } })
                "session.files.media.get" -> {
                    val offset = payload.long("offset").toInt()
                    val length = minOf(MEDIA_CHUNK_BYTES, content.size - offset)
                    if (offset + length == content.size) open--
                    reply(
                        Wire.objectOf(
                            "kind" to "files.media.content", "sessionId" to payload.text("sessionId"),
                            "mediaId" to payload.text("mediaId"), "offset" to offset, "totalBytes" to content.size,
                            "sha256" to AttachmentImportRules.sha256(content), "data" to Wire.encode(content.copyOfRange(offset, offset + length)),
                        )
                    )
                }
                else -> reply(Wire.objectOf("kind" to "accepted"))
            }
        }

        fun respond(request: JsonObject, body: Pair<String, JsonObject>, ok: Boolean = true) =
            listener.message(
                Wire.objectOf("type" to "result", "requestId" to request.text("requestId"), "ok" to ok, body.first to body.second)
            )

        fun discoveries() = sent.filter { it.text("type") == "projects.list" }

        fun media() = sent.filter { it.text("type").startsWith("session.files.media") }
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

    private fun TestScope.transport() = Transport { testScheduler.currentTime }

    private suspend fun TestScope.read(repository: DefaultRemoteRepository, path: String = "out/report.html", fresh: Boolean = false): ProjectArtifactResult {
        val result = async { repository.readProjectArtifact("session", path, fresh) }
        advanceUntilIdle()
        return result.await()
    }

    @Test
    fun theArtifactCapabilityIsMergedFromTheV2Route() = runTest {
        val repository = connected(transport())
        assertTrue(FILES_ARTIFACT_CAPABILITY in repository.state.value.capabilities)
    }

    @Test
    fun noRequestIsSentWithoutTheArtifactCapability() = runTest {
        // The image capability alone does not unlock artifacts.
        val transport = transport().apply { advertised = listOf(FILES_MEDIA_CAPABILITY) }
        val repository = connected(transport)
        assertEquals(ProjectArtifactResult.UnsupportedHost, read(repository))
        assertTrue(transport.media().isEmpty())
    }

    @Test
    fun invalidPathsAndOtherSessionsSendNothing() = runTest {
        val transport = transport()
        val repository = connected(transport)
        assertEquals(ProjectArtifactResult.Unavailable, read(repository, "a/../b.html"))
        assertEquals(ProjectArtifactResult.Unavailable, repository.readProjectArtifact("other", "a.html"))
        assertTrue(transport.media().isEmpty())
    }

    @Test
    fun downloadsSpacedVerifiedAndCachesInMemory() = runTest {
        val transport = transport()
        val repository = connected(transport)
        val loaded = read(repository) as ProjectArtifactResult.Loaded
        assertEquals("out/report.html", loaded.path)
        assertEquals(content.size, loaded.byteCount)
        assertEquals(String(content, Charsets.UTF_8), loaded.html)
        assertEquals(AttachmentImportRules.sha256(content), loaded.sha256)
        assertTrue(transport.sentAt.takeLast(transport.media().size).zipWithNext().all { (a, b) -> b - a >= 200 })
        val count = transport.media().size
        assertSame(loaded, repository.readProjectArtifact("session", "out/report.html"))
        assertEquals(count, transport.media().size)
        assertTrue(read(repository, fresh = true) is ProjectArtifactResult.Loaded)
        assertEquals(count * 2, transport.media().size)
    }

    @Test
    fun anAbsolutePathIsAnsweredRelative() = runTest {
        val transport = transport()
        val repository = connected(transport)
        val loaded = read(repository, "/p/out/report.html") as ProjectArtifactResult.Loaded
        assertEquals("/p/out/report.html", transport.media().first().text("path"))
        assertEquals("out/report.html", loaded.path)
    }

    @Test
    fun aLeadingByteOrderMarkIsStripped() = runTest {
        content = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "<p>x</p>".toByteArray()
        val loaded = read(connected(transport())) as ProjectArtifactResult.Loaded
        assertEquals("<p>x</p>", loaded.html)
        assertEquals(content.size, loaded.byteCount)
    }

    @Test
    fun malformedUtf8AndNulBytesAreNotAnArtifact() = runTest {
        for (bytes in listOf(byteArrayOf(0x3c, 0xFF.toByte(), 0xFE.toByte(), 0x3e), "<p>a\u0000b</p>".toByteArray(), "ab\u0000".toByteArray())) {
            content = bytes
            assertEquals(ProjectArtifactResult.NotAnArtifact, read(connected(transport()), fresh = true))
        }
    }

    @Test
    fun aHostThatReportsAnImageTypeIsNotAnArtifactAndHtmlIsNotAnImage() = runTest {
        val transport = transport().apply { mime = "image/png" }
        val repository = connected(transport)
        assertEquals(ProjectArtifactResult.NotAnArtifact, read(repository, "out/shot.html"))
        val html = transport().apply { mime = "text/html" }
        val other = connected(html)
        val image = async { other.readProjectImage("session", "out/report.png") }
        advanceUntilIdle()
        assertEquals(ProjectImageResult.NotAnImage, image.await())
    }

    @Test
    fun aShaMismatchIsAConnectionFailure() = runTest {
        val transport = transport().apply {
            startReply = { request ->
                Wire.objectOf(
                    "kind" to "files.media", "sessionId" to request.text("sessionId"), "path" to request.text("path"),
                    "mediaId" to mediaId, "mimeType" to "text/html", "totalBytes" to content.size, "sha256" to "b".repeat(64),
                )
            }
        }
        val repository = connected(transport)
        // The chunk replies carry the real digest, which differs from the announced one.
        assertEquals(ProjectArtifactResult.Unavailable, read(repository))
    }

    @Test
    fun omittedAndErrorResultsMapToTheirStates() = runTest {
        val transport = transport()
        val repository = connected(transport)
        for ((reason, expected) in listOf("too_large" to ProjectArtifactResult.TooLarge, "not_an_image" to ProjectArtifactResult.NotAnArtifact)) {
            transport.startReply = { request ->
                Wire.objectOf("kind" to "files.media", "sessionId" to request.text("sessionId"), "path" to request.text("path"), "omitted" to reason)
            }
            assertEquals(reason, expected, read(repository))
        }
        transport.error = "invalid_path"
        assertEquals(ProjectArtifactResult.Unavailable, read(repository))
        transport.error = "busy"
        assertEquals(ProjectArtifactResult.Busy, read(repository))
        transport.error = "internal"
        assertEquals(ProjectArtifactResult.ConnectionFailure, read(repository))
    }

    @Test
    fun anOversizedAnnouncementIsMalformedProtocolAndUnavailable() = runTest {
        val transport = transport().apply {
            startReply = { request ->
                Wire.objectOf(
                    "kind" to "files.media", "sessionId" to request.text("sessionId"), "path" to request.text("path"),
                    "mediaId" to mediaId, "mimeType" to "text/html", "totalBytes" to MAX_MEDIA_HTML_BYTES + 1, "sha256" to digest,
                )
            }
        }
        assertEquals(ProjectArtifactResult.Unavailable, read(connected(transport)))
    }

    @Test
    fun theCacheEvictsLeastRecentlyUsedAndIgnoresOversizedEntries() {
        fun loaded(size: Int) = ProjectArtifactResult.Loaded("p", "s", "", size)
        val cache = ProjectArtifactCache(capacity = 10)
        val a = ProjectArtifactCache.Key("s", "a")
        val b = ProjectArtifactCache.Key("s", "b")
        val c = ProjectArtifactCache.Key("s", "c")
        cache[a] = loaded(4)
        cache[b] = loaded(4)
        assertNotNull(cache[a])
        cache[c] = loaded(4)
        assertNull(cache[b])
        assertNotNull(cache[a])
        cache[ProjectArtifactCache.Key("s", "big")] = loaded(11)
        assertNull(cache[ProjectArtifactCache.Key("s", "big")])
        cache.clear()
        assertNull(cache[a])
    }

    @Test
    fun theDecoderRejectsEmptyInput() {
        assertNull(decodeArtifactHtml(ByteArray(0)))
        assertEquals("<p>ü</p>", decodeArtifactHtml("<p>ü</p>".toByteArray()))
    }
}
