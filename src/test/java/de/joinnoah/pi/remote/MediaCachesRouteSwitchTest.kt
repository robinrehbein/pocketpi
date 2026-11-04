package de.joinnoah.pi.remote

import android.graphics.Bitmap
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The decoded images and thumbnails outlive a switch between Macs; an unpair drops only its Mac's. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaCachesRouteSwitchTest {
    private val host = PairedHost("host", "https://relay.test", "device", "secret", "Host")
    private val other = host.copy(routeId = "other", name = "Other")

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

    /** A Mac with one project and one idle session, whichever route it is reached on. */
    private class Transport : RemoteTransport {
        override lateinit var listener: RemoteTransport.Listener
        private val session =
            Wire.objectOf("id" to "session", "origin" to "rpc", "status" to "idle", "projectId" to "project")

        override fun connect(host: PairedHost) {
            listener.ready(emptySet())
        }

        override fun pair(value: JsonObject) {}

        override fun close() {}

        override fun send(payload: JsonObject) {
            val data =
                when (payload.text("type")) {
                    "projects.list" ->
                        Wire.objectOf("kind" to "projects", "items" to JsonArray(listOf(Wire.objectOf("id" to "project"))))
                    "sessions.list" -> Wire.objectOf("kind" to "sessions", "items" to JsonArray(listOf(session)))
                    "sessions.open" -> Wire.objectOf("kind" to "session", "session" to session)
                    "session.snapshot" ->
                        Wire.objectOf(
                            "kind" to "snapshot", "sessionId" to payload.text("sessionId"), "revision" to 0, "status" to "idle",
                            "messages" to JsonArray(emptyList()), "pendingQuestions" to JsonArray(emptyList()),
                        )
                    else -> Wire.objectOf("kind" to "accepted")
                }
            listener.message(
                Wire.objectOf("type" to "result", "requestId" to payload.text("requestId"), "ok" to true, "data" to data)
            )
        }
    }

    private fun TestScope.repository() =
        DefaultRemoteRepository(
            Pairings(listOf(host, other)), Drafts(), Transport(), backgroundScope,
            StandardTestDispatcher(testScheduler), now = { testScheduler.currentTime },
        )

    private fun bitmap() = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

    @After
    fun clear() {
        ProjectImageBitmaps.clear()
        ArtifactThumbnails.clear()
        SentImageThumbnails.clear()
    }

    @Test
    fun theBitmapCachesSurviveASwitchToAnotherMacAndBack() = runTest {
        val repository = repository()
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        val image = ProjectImageBitmaps.Key("session", "shot.png", "sha", 100).also { ProjectImageBitmaps[it] = bitmap() }
        val thumbnail = ArtifactThumbnails.Key(ArtifactKind.entries.first(), "sha", 100, false).also { ArtifactThumbnails[it] = bitmap() }
        val sent = AttachmentByteCache.Key("host", "session", "attachment", "sha").also { SentImageThumbnails[it] = bitmap() }
        val kept = Triple(ProjectImageBitmaps[image], ArtifactThumbnails[thumbnail], SentImageThumbnails[sent])

        repository.activate(RemoteSelection("other", "project", "session"))
        runCurrent()
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()

        assertSame(kept.first, ProjectImageBitmaps[image])
        assertSame(kept.second, ArtifactThumbnails[thumbnail])
        assertSame(kept.third, SentImageThumbnails[sent])
    }

    @Test
    fun anUnpairDropsTheSentThumbnailsOfThatMacOnly() = runTest {
        val repository = repository()
        repository.activate(RemoteSelection("host", "project", "session"))
        runCurrent()
        val mine = AttachmentByteCache.Key("host", "session", "attachment", "sha").also { SentImageThumbnails[it] = bitmap() }
        val theirs = AttachmentByteCache.Key("other", "session", "attachment", "sha").also { SentImageThumbnails[it] = bitmap() }

        repository.remove("other")
        runCurrent()

        assertNotNull(SentImageThumbnails[mine])
        assertNull(SentImageThumbnails[theirs])
    }

    @Test
    fun clearRouteRemovesOnlyThatRoutesThumbnails() {
        val mine = AttachmentByteCache.Key("host", "session", "a", "sha").also { SentImageThumbnails[it] = bitmap() }
        val theirs = AttachmentByteCache.Key("other", "session", "a", "sha").also { SentImageThumbnails[it] = bitmap() }

        SentImageThumbnails.clearRoute("other")

        assertNotNull(SentImageThumbnails[mine])
        assertNull(SentImageThumbnails[theirs])
    }
}
