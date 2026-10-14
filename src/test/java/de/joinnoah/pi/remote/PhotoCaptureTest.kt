package de.joinnoah.pi.remote

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The composer's "Take photo": the camera's result goes into the chat's draft as a photo. */
class PhotoCaptureTest {
    @Before
    fun mainDispatcher() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun resetDispatcher() {
        Dispatchers.resetMain()
    }

    private val selection = RemoteSelection("host", "project", "session")
    private val capture = "content://de.robinrehbein.pocketpi.exports/camera/photo-0123.jpg"

    @Test
    fun aTakenPhotoImportsThroughTheChatsAttachmentImport() = runTest {
        val repository = NavigationFakeRepository()
        val model = ChatViewModel(repository, RemoteNavKey.Chat("host", "project", "session"), FakeSettingsRepository())
        repository.state.value = RemoteState(selection = selection)
        val discarded = mutableListOf<String>()
        repository.importsRun = true
        PhotoCapture.onResult(true, capture, selection, model::acceptAttachments) { discarded += it }
        runCurrent()
        assertEquals(listOf(Triple(selection, listOf(capture), true)), repository.imports)
        // The import still reads it.
        assertTrue(discarded.isEmpty())
        repository.state.value = repository.state.value.copy(importingAttachments = false)
        runCurrent()
        // The plaintext capture does not outlive the import that copied it.
        assertEquals(listOf(capture), discarded)
    }

    @Test
    fun aPhotoTheChatNoLongerTakesIsDiscarded() = runTest {
        val repository = NavigationFakeRepository()
        val model = ChatViewModel(repository, RemoteNavKey.Chat("host", "project", "session"), FakeSettingsRepository())
        val discarded = mutableListOf<String>()
        // The selection moved on while the camera was open.
        PhotoCapture.onResult(true, capture, RemoteSelection("host", "project", "other"), model::acceptAttachments) { discarded += it }
        runCurrent()
        assertTrue(repository.imports.isEmpty())
        assertEquals(listOf(capture), discarded)
    }

    @Test
    fun aRefusedImportDiscardsTheCaptureAtOnce() = runTest {
        val repository = NavigationFakeRepository()
        val model = ChatViewModel(repository, RemoteNavKey.Chat("host", "project", "session"), FakeSettingsRepository())
        repository.state.value = RemoteState(selection = selection)
        val discarded = mutableListOf<String>()
        // importsRun is off: the repository refused the import without starting it.
        PhotoCapture.onResult(true, capture, selection, model::acceptAttachments) { discarded += it }
        runCurrent()
        assertEquals(listOf(capture), discarded)
    }

    @Test
    fun theSweepDeletesOnlyOldCaptures() {
        val cache = Files.createTempDirectory("cache").toFile()
        try {
            val dir = File(cache, PhotoCapture.DIRECTORY).apply { mkdirs() }
            val now = 10 * 60 * 60_000L
            val old = File(dir, "photo-old.jpg").apply { writeText("x"); setLastModified(now - 2 * 60 * 60_000L) }
            val fresh = File(dir, "photo-fresh.jpg").apply { writeText("x"); setLastModified(now - 60_000L) }
            PhotoCapture.sweep(cache, now)
            assertFalse(old.exists())
            assertTrue(fresh.exists())
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun aCancelledCaptureDiscardsTheEmptyFile() {
        val accepted = mutableListOf<List<String>>()
        val discarded = mutableListOf<String>()
        val accept = { _: RemoteSelection, uris: List<String>, _: Boolean, _: () -> Unit -> accepted += uris; true }
        PhotoCapture.onResult(false, capture, selection, accept) { discarded += it }
        // The chat it was taken for is unknown (state lost): nothing to attach to.
        PhotoCapture.onResult(true, capture, null, accept) { discarded += it }
        PhotoCapture.onResult(true, null, selection, accept) { discarded += it }
        assertTrue(accepted.isEmpty())
        assertEquals(listOf(capture, capture), discarded)
    }
}
