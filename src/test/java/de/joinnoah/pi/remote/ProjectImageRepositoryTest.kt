package de.joinnoah.pi.remote

import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectImageRepositoryTest {
    private val host = PairedHost("host", "https://relay.test", "device", "secret", "Host")
    private val selection = RemoteSelection("host", "project", "session")
    private val content = ByteArray(MEDIA_CHUNK_BYTES + 100) { (it * 13).toByte() }
    private val digest = AttachmentImportRules.sha256(content)
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
        var advertised: List<String> = listOf(FILES_MEDIA_CAPABILITY)
        /** The file the Mac serves; an agent may overwrite it between runs. */
        var served: ByteArray = content
        private val servedDigest get() = AttachmentImportRules.sha256(served)
        /** A host that predates the v2 route sends no list for it. */
        var legacyHost = false
        var startReply: (JsonObject) -> JsonObject = { request ->
            Wire.objectOf(
                "kind" to "files.media", "sessionId" to request.text("sessionId"), "path" to request.text("path").removePrefix("/p/"),
                "mediaId" to mediaId, "mimeType" to "image/png", "totalBytes" to served.size, "sha256" to servedDigest,
            )
        }
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
                    val length = minOf(MEDIA_CHUNK_BYTES, served.size - offset)
                    if (offset + length == served.size) open--
                    reply(
                        Wire.objectOf(
                            "kind" to "files.media.content", "sessionId" to payload.text("sessionId"),
                            "mediaId" to payload.text("mediaId"), "offset" to offset, "totalBytes" to served.size,
                            "sha256" to servedDigest, "data" to Wire.encode(served.copyOfRange(offset, offset + length)),
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

    private suspend fun TestScope.connected(
        transport: Transport,
        media: MediaDiskCache? = null,
        hosts: List<PairedHost> = listOf(host),
    ): DefaultRemoteRepository {
        val repository =
            DefaultRemoteRepository(
                Pairings(hosts), Drafts(), transport, backgroundScope,
                StandardTestDispatcher(testScheduler), now = { testScheduler.currentTime }, media = media,
            )
        repository.activate(selection)
        runCurrent()
        return repository
    }

    @get:Rule
    val folder = TemporaryFolder()

    private fun TestScope.diskCache() =
        MediaDiskCache(
            File(folder.root, "media"),
            now = { testScheduler.currentTime },
            alias = "test",
            wrap = TestMediaKey.wrap,
            unwrap = TestMediaKey.unwrap,
        )

    @Test
    fun aPictureKeptOnDiskOpensAfterARestartWithoutAskingTheMac() = runTest {
        val media = diskCache()
        val first = transport()
        val running = connected(first, media)
        val read = async { running.readProjectImage("session", "build/shot.png") }
        advanceUntilIdle()
        assertTrue(read.await() is ProjectImageResult.Loaded)
        assertTrue(first.media().isNotEmpty())

        // The Mac cannot answer after the restart: the kept copy shows.
        val second = transport()
        val restarted = connected(second, media)
        second.listener.failed(false, R.string.remote_connection_error)
        runCurrent()
        val result = restarted.readProjectImage("session", "build/shot.png")
        assertArrayEquals(content, (result as ProjectImageResult.Loaded).bytes)
        assertEquals("build/shot.png", result.path)
        assertTrue(second.media().isEmpty())
    }

    @Test
    fun aKeptPointerIsCheckedWithTheMacOnceARunSoAnOverwrittenPictureShows() = runTest {
        val media = diskCache()
        val first = transport()
        val running = connected(first, media)
        val read = async { running.readProjectImage("session", "build/shot.png") }
        advanceUntilIdle()
        read.await()

        // The agent overwrote the file before the next run; a connected Mac is asked once.
        val overwritten = ByteArray(300) { (it * 7).toByte() }
        val second = transport().apply { served = overwritten }
        val restarted = connected(second, media)
        val again = async { restarted.readProjectImage("session", "build/shot.png") }
        advanceUntilIdle()
        assertArrayEquals(overwritten, (again.await() as ProjectImageResult.Loaded).bytes)
        val reads = second.media().size
        assertTrue(reads > 0)

        assertArrayEquals(overwritten, (restarted.readProjectImage("session", "build/shot.png") as ProjectImageResult.Loaded).bytes)
        assertEquals(reads, second.media().size)
    }

    @Test
    fun aFreshReadAsksTheMacEvenWithAKeptCopy() = runTest {
        val media = diskCache()
        val transport = transport()
        val repository = connected(transport, media)
        val first = async { repository.readProjectImage("session", "build/shot.png") }
        advanceUntilIdle()
        first.await()
        val before = transport.media().size

        val fresh = async { repository.readProjectImage("session", "build/shot.png", fresh = true) }
        advanceUntilIdle()
        assertTrue(fresh.await() is ProjectImageResult.Loaded)
        assertTrue(transport.media().size > before)
    }

    @Test
    fun aKeptPointerWhoseImageIsGoneFallsBackToTheMac() = runTest {
        val media = diskCache()
        val first = transport()
        val running = connected(first, media)
        val read = async { running.readProjectImage("session", "build/shot.png") }
        advanceUntilIdle()
        read.await()
        // The image file is evicted or lost; its pointer is still there.
        folder.root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".v2") && !it.name.startsWith("ptr-") }
            .forEach { it.delete() }

        val second = transport()
        val restarted = connected(second, media)
        val fallback = async { restarted.readProjectImage("session", "build/shot.png") }
        advanceUntilIdle()
        assertArrayEquals(content, (fallback.await() as ProjectImageResult.Loaded).bytes)
        assertTrue(second.media().isNotEmpty())
    }

    @Test
    fun aMacSwitchKeepsEachMacsImagesWithoutAnotherRead() = runTest {
        val other = host.copy(routeId = "other")
        val transport = transport()
        val repository = connected(transport, hosts = listOf(host, other))
        val read = async { repository.readProjectImage("session", "build/shot.png") }
        advanceUntilIdle()
        read.await()
        val reads = transport.media().size

        repository.activate(RemoteSelection("other", "project", "session"))
        runCurrent()
        repository.activate(selection)
        runCurrent()
        assertTrue(repository.readProjectImage("session", "build/shot.png") is ProjectImageResult.Loaded)
        assertEquals(reads, transport.media().size)
    }

    private fun TestScope.transport() = Transport { testScheduler.currentTime }

    @Test
    fun discoveryUsesTheV2RouteAndMergesTheMediaCapability() = runTest {
        val transport = transport()
        val repository = connected(transport)
        assertTrue(transport.discoveries().first().text("requestId").startsWith("capabilities.v2:"))
        assertEquals(1, transport.discoveries().size)
        assertTrue(FILES_MEDIA_CAPABILITY in repository.state.value.capabilities)
    }

    @Test
    fun aHostWithoutAListOnV2IsAskedOnceMoreOnV1() = runTest {
        val transport = transport().apply { legacyHost = true; advertised = listOf(STEER_CAPABILITY) }
        val repository = connected(transport)
        val routes = transport.discoveries().map { it.text("requestId").substringBefore(':') }
        assertEquals(listOf("capabilities.v2", "capabilities.v1"), routes.take(2))
        assertTrue(STEER_CAPABILITY in repository.state.value.capabilities)
        assertTrue(repository.state.value.capabilitiesKnown)
        // Later discoveries go straight to v1: the host was found to be older.
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        assertTrue(transport.discoveries().drop(2).all { it.text("requestId").startsWith("capabilities.v1:") })
    }

    @Test
    fun theCapabilityListMayHoldThirtyTwoEntriesButNotThirtyThree() = runTest {
        val filler = (1..31).map { "filler.$it" }
        for ((entries, expected) in listOf(filler + FILES_MEDIA_CAPABILITY to true, filler + FILES_MEDIA_CAPABILITY + "one-more" to false)) {
            val transport = transport().apply { advertised = entries }
            val repository = connected(transport)
            assertEquals(entries.size.toString(), expected, FILES_MEDIA_CAPABILITY in repository.state.value.capabilities)
        }
    }

    @Test
    fun noRequestIsSentWithoutTheCapability() = runTest {
        val transport = transport().apply { advertised = emptyList() }
        val repository = connected(transport)
        val result = async { repository.readProjectImage("session", "build/shot.png") }
        runCurrent()
        assertEquals(ProjectImageResult.Unsupported, result.await())
        assertTrue(transport.media().isEmpty())
    }

    @Test
    fun invalidPathsAndOtherSessionsSendNothing() = runTest {
        val transport = transport()
        val repository = connected(transport)
        assertEquals(ProjectImageResult.Unavailable, repository.readProjectImage("session", "a/../b.png"))
        assertEquals(ProjectImageResult.Unavailable, repository.readProjectImage("other", "a.png"))
        assertTrue(transport.media().isEmpty())
    }

    @Test
    fun readsTheImageSpacedVerifiedAndCachesTheFirstVersion() = runTest {
        val transport = transport()
        val repository = connected(transport)
        val first = async { repository.readProjectImage("session", "build/shot.png") }
        val second = async { repository.readProjectImage("session", "build/shot.png") }
        advanceUntilIdle()
        val loaded = first.await() as ProjectImageResult.Loaded
        assertTrue(second.await() is ProjectImageResult.Loaded)
        assertArrayEquals(content, loaded.bytes)
        assertEquals("image/png", loaded.mimeType)
        assertEquals("build/shot.png", loaded.path)
        val media = transport.media()
        assertEquals(listOf("session.files.media", "session.files.media.get", "session.files.media.get"), media.map { it.text("type") })
        assertEquals(setOf("type", "requestId", "sessionId", "path"), media[0].keys)
        assertEquals(setOf("type", "requestId", "sessionId", "mediaId", "offset"), media[1].keys)
        assertTrue(transport.sentAt.takeLast(3).zipWithNext().all { (a, b) -> b - a >= 200 })
        // The cached copy answers without another request; a fresh read asks again.
        assertTrue(repository.readProjectImage("session", "build/shot.png") is ProjectImageResult.Loaded)
        assertEquals(3, transport.media().size)
        assertTrue(async { repository.readProjectImage("session", "build/shot.png", fresh = true) }.also { advanceUntilIdle() }.await() is ProjectImageResult.Loaded)
        assertEquals(6, transport.media().size)
    }

    @Test
    fun anAbsolutePathIsSentAsItIsAndAnsweredRelative() = runTest {
        val transport = transport()
        val repository = connected(transport)
        val result = async { repository.readProjectImage("session", "/p/build/shot.png") }
        advanceUntilIdle()
        val loaded = result.await() as ProjectImageResult.Loaded
        assertEquals("/p/build/shot.png", transport.media().first().text("path"))
        assertEquals("build/shot.png", loaded.path)
    }

    @Test
    fun omittedAndErrorResultsMapToTheirStates() = runTest {
        val transport = transport()
        val repository = connected(transport)
        suspend fun read(): ProjectImageResult {
            val result = async { repository.readProjectImage("session", "build/shot.png") }
            advanceUntilIdle()
            return result.await()
        }
        for ((reason, expected) in listOf("too_large" to ProjectImageResult.TooLarge, "not_an_image" to ProjectImageResult.NotAnImage)) {
            transport.startReply = { request ->
                Wire.objectOf("kind" to "files.media", "sessionId" to request.text("sessionId"), "path" to request.text("path"), "omitted" to reason)
            }
            assertEquals(reason, expected, read())
        }
        transport.error = "invalid_path"
        assertEquals(ProjectImageResult.Unavailable, read())
        transport.error = "busy"
        assertEquals(ProjectImageResult.Busy, read())
        transport.error = "internal"
        assertEquals(ProjectImageResult.Failed, read())
    }

    @Test
    fun atMostTwoCopiesAreOpenAtOnce() = runTest {
        val transport = transport()
        val repository = connected(transport)
        val reads = (1..5).map { async { repository.readProjectImage("session", "build/shot$it.png") } }
        advanceUntilIdle()
        assertTrue(reads.all { it.await() is ProjectImageResult.Loaded })
        assertEquals(2, transport.peakOpen)
    }

    @Test
    fun aBusyHostIsARetryableStateAndTheNextAttemptWorks() = runTest {
        val transport = transport().apply { error = "busy" }
        val repository = connected(transport)
        val busy = async { repository.readProjectImage("session", "build/shot.png") }
        advanceUntilIdle()
        assertEquals(ProjectImageResult.Busy, busy.await())
        transport.error = null
        val again = async { repository.readProjectImage("session", "build/shot.png") }
        advanceUntilIdle()
        assertTrue(again.await() is ProjectImageResult.Loaded)
    }

    @Test
    fun aFreshReadReplacesTheCachedCopy() = runTest {
        val transport = transport()
        val repository = connected(transport)
        val first = async { repository.readProjectImage("session", "build/shot.png") }
        advanceUntilIdle()
        val old = first.await() as ProjectImageResult.Loaded
        val fresh = async { repository.readProjectImage("session", "build/shot.png", fresh = true) }
        advanceUntilIdle()
        val latest = fresh.await() as ProjectImageResult.Loaded
        val count = transport.media().size
        // The ordinary read now answers with the fresh copy, without a request.
        assertSame(latest, repository.readProjectImage("session", "build/shot.png"))
        assertNotSame(old, latest)
        assertEquals(count, transport.media().size)
    }
}
