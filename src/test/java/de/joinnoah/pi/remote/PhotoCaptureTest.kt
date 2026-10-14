package de.joinnoah.pi.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
        PhotoCapture.onResult(true, capture, selection, model::acceptAttachments) { discarded += it }
        runCurrent()
        assertEquals(listOf(Triple(selection, listOf(capture), true)), repository.imports)
        assertTrue(discarded.isEmpty())
    }

    @Test
    fun aCancelledCaptureDiscardsTheEmptyFile() {
        val accepted = mutableListOf<List<String>>()
        val discarded = mutableListOf<String>()
        PhotoCapture.onResult(false, capture, selection, { _, uris, _ -> accepted += uris }) { discarded += it }
        // The chat it was taken for is unknown (state lost): nothing to attach to.
        PhotoCapture.onResult(true, capture, null, { _, uris, _ -> accepted += uris }) { discarded += it }
        PhotoCapture.onResult(true, null, selection, { _, uris, _ -> accepted += uris }) { discarded += it }
        assertTrue(accepted.isEmpty())
        assertEquals(listOf(capture, capture), discarded)
    }
}
