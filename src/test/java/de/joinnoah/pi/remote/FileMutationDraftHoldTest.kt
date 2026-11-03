package de.joinnoah.pi.remote

import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Which edits hold a path against rename and delete ([ProjectFilesLoader.mutationHeld]). */
class FileMutationDraftHoldTest {
    private val source = object : FileSource {
        override suspend fun filesList(sessionId: String, path: String, after: String?) = error("unexpected")
        override suspend fun filesRead(sessionId: String, path: String, offset: Long, version: String?) = error("unexpected")
    }

    private suspend fun TestScope.held(editor: FileEditor?, paths: List<String> = listOf("a.txt"), parked: (FileDraftKey) -> Boolean = { false }): Boolean {
        var state = RemoteState(connected = true, selection = RemoteSelection("host", "project", "session"), files = FilesState("session", editor = editor))
        val loader = ProjectFilesLoader(backgroundScope, source, { state }) { state = it(state) }
        return loader.mutationHeld("session", paths, parked)
    }

    private val clean = FileEditor("a.txt", "version", "text")

    @Test
    fun aCleanEditorOrAnotherPathHoldsNothing() = runTest {
        assertFalse(held(null))
        assertFalse(held(clean))
        assertFalse(held(clean.copy(text = "changed"), paths = listOf("b.txt")))
    }

    @Test
    fun aDirtyOpenEditorHoldsItsPath() = runTest {
        assertTrue(held(clean.copy(text = "changed")))
        // Also as the destination of a rename.
        assertTrue(held(clean.copy(text = "changed"), paths = listOf("other.txt", "a.txt")))
    }

    @Test
    fun aSaveInFlightOrUnconfirmedHoldsItsPathEvenWithTheTextSaved() = runTest {
        assertTrue(held(clean.copy(saving = true)))
        assertTrue(held(clean.copy(unconfirmed = PendingWrite("request", "version", "text"))))
        assertTrue(held(clean.copy(superseded = listOf(PendingWrite("request", "version", "old")))))
    }

    @Test
    fun aParkedEditorHoldsItsPath() = runTest {
        val keys = mutableListOf<FileDraftKey>()
        assertTrue(held(null) { keys += it; it.path == "a.txt" })
        assertEquals(FileDraftKey("host", "session", "a.txt"), keys.single())
    }
}
