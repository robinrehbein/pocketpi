package de.joinnoah.pi.remote

import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SessionArtifactsRepositoryTest {
    private val host = PairedHost("host", "https://relay.test", "device", "secret", "Host")
    private val selection = RemoteSelection("host", "project", "session")
    private var content = ("<!doctype html><title>Report</title>" + "<p>Grüße</p>".repeat(6000)).toByteArray()
    private val digest get() = AttachmentImportRules.sha256(content)
    private val mediaId = "AAAAAAAAAAAAAAAAAAAAAA"
    private val id = "Zk3_xY9aB-0qWe7RtYuI1o"

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

    private inner class Transport : RemoteTransport {
        override lateinit var listener: RemoteTransport.Listener
        val sent = mutableListOf<JsonObject>()
        var advertised: List<String> = listOf(FILES_MEDIA_CAPABILITY, FILES_ARTIFACT_CAPABILITY, ARTIFACTS_CAPABILITY)
        var mime = "text/html"
        var latest = 2
        /** Replaces the path of an open answer. */
        var path: ((JsonObject) -> String)? = null
        var listReply: (JsonObject) -> JsonObject = { request ->
            Wire.objectOf(
                "kind" to "artifacts.list", "sessionId" to request.text("sessionId"),
                "artifacts" to
                    JsonArray(
                        listOf(
                            Wire.objectOf(
                                "id" to id, "title" to "Report", "type" to "html", "version" to latest, "sha256" to "a".repeat(64),
                                "bytes" to content.size, "createdAt" to "2026-01-01T00:00:00.000Z", "updatedAt" to "2026-01-01T00:05:00.000Z",
                            )
                        )
                    ),
                "truncated" to false,
            )
        }
        var openOmitted: String? = null
        var error: String? = null
        private val session =
            Wire.objectOf("id" to "session", "origin" to "rpc", "status" to "idle", "projectId" to "project")

        override fun connect(host: PairedHost) {
            listener.ready(emptySet())
        }

        override fun pair(value: JsonObject) {}

        override fun close() {}

        private fun extension() = MediaMime.entries.first { it.wire == mime }.extension

        override fun send(payload: JsonObject) {
            sent += payload
            fun reply(data: JsonObject) = respond(payload, "data" to data)
            fun fail(code: String) = respond(payload, "error" to Wire.objectOf("code" to code, "message" to code), ok = false)
            when (payload.text("type")) {
                "projects.list" ->
                    reply(
                        Wire.objectOf(
                            "kind" to "projects",
                            "items" to JsonArray(listOf(Wire.objectOf("id" to "project"))),
                            "capabilities" to JsonArray(advertised.map(::JsonPrimitive)),
                        )
                    )
                "sessions.list" -> reply(Wire.objectOf("kind" to "sessions", "items" to JsonArray(listOf(session))))
                "sessions.open" -> reply(Wire.objectOf("kind" to "session", "session" to session))
                "session.snapshot" ->
                    reply(
                        Wire.objectOf(
                            "kind" to "snapshot", "sessionId" to payload.text("sessionId"), "revision" to 0, "status" to "idle",
                            "messages" to JsonArray(emptyList()), "pendingQuestions" to JsonArray(emptyList()),
                        )
                    )
                "session.artifacts.list" -> error?.let(::fail) ?: reply(listReply(payload))
                "session.artifacts.open" ->
                    error?.let(::fail)
                        ?: reply(
                            Wire.objectOf(
                                "kind" to "files.media", "sessionId" to payload.text("sessionId"),
                                "path" to
                                    (path?.invoke(payload)
                                        ?: "artifacts/${payload.text("artifactId")}/${payload["version"]?.jsonPrimitive?.int ?: latest}.${extension()}"),
                                *(openOmitted?.let { arrayOf("omitted" to it) }
                                    ?: arrayOf(
                                        "mediaId" to mediaId, "mimeType" to mime, "totalBytes" to content.size, "sha256" to digest,
                                    )),
                            )
                        )
                "session.files.media" -> reply(Wire.objectOf("kind" to "files.media", "sessionId" to payload.text("sessionId"),
                    "path" to payload.text("path"), "mediaId" to mediaId, "mimeType" to mime, "totalBytes" to content.size, "sha256" to digest))
                "session.files.media.get" -> {
                    val offset = payload.long("offset").toInt()
                    val length = minOf(MEDIA_CHUNK_BYTES, content.size - offset)
                    reply(
                        Wire.objectOf(
                            "kind" to "files.media.content", "sessionId" to payload.text("sessionId"),
                            "mediaId" to payload.text("mediaId"), "offset" to offset, "totalBytes" to content.size,
                            "sha256" to digest, "data" to Wire.encode(content.copyOfRange(offset, offset + length)),
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

        fun artifactRequests() = sent.filter { it.text("type").startsWith("session.artifacts.") }

        fun media() = sent.filter { it.text("type").startsWith("session.files.media") || it.text("type") == "session.artifacts.open" }
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

    private suspend fun TestScope.list(repository: DefaultRemoteRepository, session: String = "session"): SessionArtifactListResult {
        val result = async { repository.listSessionArtifacts(session) }
        advanceUntilIdle()
        return result.await()
    }

    private suspend fun TestScope.open(repository: DefaultRemoteRepository, version: Int? = 2, artifactId: String = id): SessionArtifactOpenResult {
        val result = async { repository.openSessionArtifact("session", artifactId, version) }
        advanceUntilIdle()
        return result.await()
    }

    @Test
    fun theCapabilityIsMergedFromTheV2Route() = runTest {
        assertTrue(ARTIFACTS_CAPABILITY in connected(Transport()).state.value.capabilities)
    }

    @Test
    fun noArtifactRequestIsSentWithoutTheCapability() = runTest {
        // The media capability alone does not unlock the artifact commands.
        val transport = Transport().apply { advertised = listOf(FILES_MEDIA_CAPABILITY) }
        val repository = connected(transport)
        assertEquals(SessionArtifactListResult.Unsupported, list(repository))
        assertEquals(SessionArtifactOpenResult.Unsupported, open(repository))
        assertTrue(transport.artifactRequests().isEmpty())
        assertTrue(transport.media().isEmpty())
    }

    @Test
    fun listsTheArtifactsOfTheSelectedSession() = runTest {
        val transport = Transport()
        val repository = connected(transport)
        val loaded = list(repository) as SessionArtifactListResult.Loaded
        assertEquals(listOf(id), loaded.list.artifacts.map { it.id })
        assertFalse(loaded.list.truncated)
        val request = transport.artifactRequests().single()
        assertEquals("session.artifacts.list", request.text("type"))
        assertEquals(setOf("type", "requestId", "sessionId"), request.keys)
        assertEquals("session", request.text("sessionId"))
    }

    @Test
    fun aListForAnotherSessionOrWithABrokenAnswerIsUnavailable() = runTest {
        val transport = Transport()
        val repository = connected(transport)
        assertEquals(SessionArtifactListResult.Unavailable, list(repository, "other"))
        assertTrue(transport.artifactRequests().isEmpty())
        transport.listReply = { request ->
            Wire.objectOf("kind" to "artifacts.list", "sessionId" to request.text("sessionId"), "artifacts" to JsonArray(emptyList()))
        }
        assertEquals(SessionArtifactListResult.Unavailable, list(repository))
        transport.listReply = { request ->
            Wire.objectOf("kind" to "artifacts.list", "sessionId" to "other", "artifacts" to JsonArray(emptyList()), "truncated" to false)
        }
        assertEquals(SessionArtifactListResult.Unavailable, list(repository))
    }

    @Test
    fun listErrorsMapToTheirStates() = runTest {
        val transport = Transport()
        val repository = connected(transport)
        for (code in listOf("not_found", "forbidden")) {
            transport.error = code
            assertEquals(code, SessionArtifactListResult.Unavailable, list(repository))
        }
        transport.error = "internal"
        assertEquals(SessionArtifactListResult.Failed, list(repository))
    }

    @Test
    fun opensAVersionVerifiedSpacedAndKeepsItInMemory() = runTest {
        val transport = Transport()
        val repository = connected(transport)
        val result = open(repository) as SessionArtifactOpenResult.Text
        assertEquals(ArtifactType.HTML, result.type)
        assertEquals("artifacts/$id/2.html", result.loaded.path)
        assertEquals(String(content, Charsets.UTF_8), result.loaded.html)
        assertEquals(digest, result.loaded.sha256)
        val open = transport.sent.first { it.text("type") == "session.artifacts.open" }
        assertEquals(setOf("type", "requestId", "sessionId", "artifactId", "version"), open.keys)
        val count = transport.media().size
        assertSame(result.loaded, (open(repository) as SessionArtifactOpenResult.Text).loaded)
        assertEquals(count, transport.media().size)
    }

    @Test
    fun theLatestIsAskedWithoutAVersionAndNeverKept() = runTest {
        val transport = Transport()
        val repository = connected(transport)
        assertTrue(open(repository, null) is SessionArtifactOpenResult.Text)
        assertFalse(transport.sent.first { it.text("type") == "session.artifacts.open" }.containsKey("version"))
        val count = transport.media().size
        assertTrue(open(repository, null) is SessionArtifactOpenResult.Text)
        assertEquals(count * 2, transport.media().size)
    }

    @Test
    fun anOlderVersionCarriesItsOwnSha256() = runTest {
        val transport = Transport()
        val repository = connected(transport)
        val listed = (list(repository) as SessionArtifactListResult.Loaded).list.artifacts.single()
        val older = open(repository, 1) as SessionArtifactOpenResult.Text
        assertEquals("artifacts/$id/1.html", older.loaded.path)
        assertNotEquals(listed.sha256, older.loaded.sha256)
        assertEquals(digest, older.loaded.sha256)
    }

    @Test
    fun opensSvgAsAnImageAndKeepsIt() = runTest {
        content = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"4\" height=\"4\"/>".toByteArray()
        val transport = Transport().apply { mime = "image/svg+xml" }
        val repository = connected(transport)
        val svg = open(repository) as SessionArtifactOpenResult.Svg
        assertEquals("artifacts/$id/2.svg", svg.image.path)
        assertTrue(svg.image.isSvg)
        assertArrayEquals(content, svg.image.bytes)
        val count = transport.media().size
        assertSame(svg.image, (open(repository) as SessionArtifactOpenResult.Svg).image)
        assertEquals(count, transport.media().size)
    }

    @Test
    fun opensMermaidSourceAsText() = runTest {
        content = "flowchart TD\n  A-->B\n".toByteArray()
        val transport = Transport().apply { mime = "text/vnd.mermaid" }
        val repository = connected(transport)
        val result = open(repository) as SessionArtifactOpenResult.Text
        assertEquals(ArtifactType.MERMAID, result.type)
        assertEquals("artifacts/$id/2.mmd", result.loaded.path)
        assertEquals("flowchart TD\n  A-->B\n", result.loaded.html)
        assertSame(result.loaded, (open(repository) as SessionArtifactOpenResult.Text).loaded)
    }

    @Test
    fun malformedUtf8OrNulInTextIsNotAnArtifact() = runTest {
        for (bytes in listOf(byteArrayOf(0x3c, 0xFF.toByte(), 0x3e), "a\u0000b".toByteArray())) {
            content = bytes
            val transport = Transport().apply { mime = "text/vnd.mermaid" }
            assertEquals(SessionArtifactOpenResult.NotAnArtifact, open(connected(transport), null))
        }
    }

    @Test
    fun aPathThatDoesNotMatchTheRequestIsUnavailable() = runTest {
        val transport = Transport()
        val repository = connected(transport)
        // Version 2 was asked for, the answer names 1; another artifact; a wrong extension.
        for (wrong in listOf("artifacts/$id/1.html", "artifacts/Aa1_Bb2-Cc3Dd4Ee5Ff6Gg/2.html", "artifacts/$id/2.mmd", "docs/report.html")) {
            transport.path = { wrong }
            assertEquals(wrong, SessionArtifactOpenResult.Unavailable, open(repository))
        }
    }

    @Test
    fun mermaidIsRefusedFromTheMediaCommands() = runTest {
        content = "flowchart TD\n  A-->B\n".toByteArray()
        val repository = connected(Transport().apply { mime = "text/vnd.mermaid" })
        val artifact = async { repository.readProjectArtifact("session", "out/a.mmd") }
        val image = async { repository.readProjectImage("session", "out/a.mmd") }
        advanceUntilIdle()
        assertEquals(ProjectArtifactResult.Unavailable, artifact.await())
        assertEquals(ProjectImageResult.Unavailable, image.await())
    }

    @Test
    fun omittedAndErrorAnswersMapToTheirStates() = runTest {
        val transport = Transport()
        val repository = connected(transport)
        transport.openOmitted = "too_large"
        assertEquals(SessionArtifactOpenResult.TooLarge, open(repository))
        transport.openOmitted = "not_an_image"
        assertEquals(SessionArtifactOpenResult.NotAnArtifact, open(repository))
        transport.openOmitted = null
        for (code in listOf("not_found", "invalid_path")) {
            transport.error = code
            assertEquals(code, SessionArtifactOpenResult.Unavailable, open(repository))
        }
        transport.error = "busy"
        assertEquals(SessionArtifactOpenResult.Busy, open(repository))
        transport.error = "internal"
        assertEquals(SessionArtifactOpenResult.ConnectionFailure, open(repository))
    }

    @Test
    fun anInvalidIdOrVersionSendsNothing() = runTest {
        val transport = Transport()
        val repository = connected(transport)
        assertEquals(SessionArtifactOpenResult.Unavailable, open(repository, artifactId = "../../etc/passwd......."))
        assertEquals(SessionArtifactOpenResult.Unavailable, open(repository, version = 0))
        val other = async { repository.openSessionArtifact("other", id, 1) }
        advanceUntilIdle()
        assertEquals(SessionArtifactOpenResult.Unavailable, other.await())
        assertTrue(transport.artifactRequests().isEmpty())
    }
}
