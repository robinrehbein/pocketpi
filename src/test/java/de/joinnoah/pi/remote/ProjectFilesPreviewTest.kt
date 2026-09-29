package de.joinnoah.pi.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ProjectFilesPreviewTest {
    private class Source : FileSource {
        data class Read(val path: String, val offset: Long, val version: String?, val reply: CompletableDeferred<FileChunk> = CompletableDeferred())
        data class ListRequest(val path: String, val after: String?, val reply: CompletableDeferred<FileListing> = CompletableDeferred())
        val reads = mutableListOf<Read>()
        val lists = mutableListOf<ListRequest>()

        override suspend fun filesRead(sessionId: String, path: String, offset: Long, version: String?): FileChunk =
            Read(path, offset, version).also { reads += it }.reply.await()

        override suspend fun filesList(sessionId: String, path: String, after: String?): FileListing =
            ListRequest(path, after).also { lists += it }.reply.await()
    }

    private class Browser(scope: kotlinx.coroutines.CoroutineScope) {
        val source = Source()
        var state = RemoteState(
            selection = RemoteSelection(sessionId = "session"), connected = true,
            capabilities = setOf(FILES_CAPABILITY),
            files = FilesState("session", loading = false, listing = FileListing("")),
        )
        val loader = ProjectFilesLoader(scope, source, { state }) { update -> state = update(state) }
        val files get() = checkNotNull(state.files)
    }

    private fun Source.complete(path: String, content: String, binary: Boolean = false, tooLarge: Boolean = false) {
        val request = reads.single { it.path == path }
        request.reply.complete(FileChunk(path, "version", content.length.toLong(), 0, content, binary, tooLarge = tooLarge))
    }

    @Test fun onlyTwoReadsRunAtOnceAndRequestsAreDeduplicated() = runTest {
        val browser = Browser(backgroundScope)
        browser.loader.requestPreview("a.kt")
        browser.loader.requestPreview("a.kt")
        browser.loader.requestPreview("b.kt")
        browser.loader.requestPreview("c.kt")
        runCurrent()
        assertEquals(listOf("a.kt", "b.kt"), browser.source.reads.map { it.path })
        assertTrue(browser.files.previews.getValue("c.kt").loading)
        browser.source.complete("a.kt", "alpha")
        runCurrent()
        assertEquals(listOf("a.kt", "b.kt", "c.kt"), browser.source.reads.map { it.path })
        assertEquals("alpha", browser.files.previews.getValue("a.kt").content)
        assertEquals(0L, browser.source.reads.last().offset)
        assertNull(browser.source.reads.last().version)
    }

    @Test fun previewContentAndCacheAreBounded() = runTest {
        val browser = Browser(backgroundScope)
        repeat(25) { index ->
            val path = "$index.kt"
            browser.loader.requestPreview(path)
            runCurrent()
            browser.source.complete(path, "x".repeat(3000))
            runCurrent()
        }
        assertEquals(24, browser.files.previews.size)
        assertFalse(browser.files.previews.containsKey("0.kt"))
        assertEquals(2048, browser.files.previews.getValue("24.kt").content?.length)
    }

    @Test fun previewStatesRepresentBinaryTooLargeEmptyOfflineAndFailure() = runTest {
        val browser = Browser(backgroundScope)
        browser.loader.requestPreview("binary")
        browser.loader.requestPreview("large")
        runCurrent()
        browser.source.complete("binary", "", binary = true)
        browser.source.complete("large", "", tooLarge = true)
        runCurrent()
        browser.loader.requestPreview("empty")
        runCurrent()
        browser.source.complete("empty", "")
        runCurrent()
        browser.loader.requestPreview("failure")
        runCurrent()
        browser.source.reads.single { it.path == "failure" }.reply.completeExceptionally(IllegalStateException("failed"))
        runCurrent()
        browser.state = browser.state.copy(connected = false)
        browser.loader.requestPreview("offline")
        assertTrue(browser.files.previews.getValue("binary").binary)
        assertTrue(browser.files.previews.getValue("large").tooLarge)
        assertEquals("", browser.files.previews.getValue("empty").content)
        assertEquals(FilesFailure.FAILED, browser.files.previews.getValue("failure").failure)
        assertEquals(FilesFailure.OFFLINE, browser.files.previews.getValue("offline").failure)
    }

    @Test fun filePeekReusesPreviewAndFolderPeekDoesNotNavigate() = runTest {
        val browser = Browser(backgroundScope)
        browser.loader.requestPreview("file.kt")
        runCurrent()
        browser.source.complete("file.kt", "hello")
        runCurrent()
        browser.loader.showPeek("file.kt", FileEntryType.FILE)
        assertFalse(browser.files.peek!!.loading)
        assertEquals(1, browser.source.reads.size)
        browser.loader.showPeek("folder", FileEntryType.DIR)
        runCurrent()
        assertEquals("", browser.files.path)
        assertEquals("", browser.files.listing?.path)
        assertNull(browser.files.file)
        val request = browser.source.lists.single()
        assertEquals("folder", request.path)
        assertNull(request.after)
        request.reply.complete(FileListing("folder", (1..12).map { FileEntry("$it", FileEntryType.FILE) }, nextAfter = "12"))
        runCurrent()
        assertFalse(browser.files.peek!!.loading)
        assertEquals((1..8).map(Int::toString), browser.files.peek!!.listing?.entries?.map { it.name })
        assertNotNull(browser.files.peek!!.listing?.nextAfter)
    }

    @Test fun filePeekCompletesWhenItsInFlightPreviewWasEvicted() = runTest {
        val browser = Browser(backgroundScope)
        repeat(25) { browser.loader.requestPreview("$it.kt") }
        runCurrent()
        assertFalse(browser.files.previews.containsKey("0.kt"))
        assertEquals(listOf("0.kt", "1.kt"), browser.source.reads.map { it.path })

        browser.loader.showPeek("0.kt", FileEntryType.FILE)
        browser.source.complete("0.kt", "peek content")
        runCurrent()

        assertFalse(browser.files.peek!!.loading)
        assertEquals("peek content", browser.files.previews.getValue("0.kt").content)
        assertTrue(browser.files.previews.size <= 24)
    }

    @Test fun lateRepliesAreDroppedAfterDismissNavigationReloadAndSessionChange() = runTest {
        val browser = Browser(backgroundScope)
        browser.loader.showPeek("folder", FileEntryType.DIR)
        browser.loader.requestPreview("a.kt")
        runCurrent()
        browser.loader.dismissPeek()
        browser.loader.openDir("other")
        runCurrent()
        browser.source.lists.first().reply.complete(FileListing("folder", listOf(FileEntry("late", FileEntryType.FILE))))
        browser.source.reads.first().reply.complete(FileChunk("a.kt", "version", 4, 0, "late", false))
        runCurrent()
        assertNull(browser.files.peek)
        assertTrue(browser.files.previews.isEmpty())
        browser.loader.requestPreview("other/b.kt")
        runCurrent()
        assertEquals("other/b.kt", browser.source.reads.last().path)
        assertEquals(2, browser.source.reads.size)
        browser.loader.reload()
        browser.source.reads.last().reply.complete(FileChunk("other/b.kt", "version", 4, 0, "late", false))
        runCurrent()
        assertTrue(browser.files.previews.isEmpty())
        browser.loader.requestPreview("other/c.kt")
        runCurrent()
        assertEquals("other/c.kt", browser.source.reads.last().path)
        assertEquals(3, browser.source.reads.size)
        browser.state = browser.state.copy(selection = RemoteSelection(sessionId = "new"), files = FilesState("new"))
        browser.source.reads.last().reply.complete(FileChunk("other/c.kt", "version", 4, 0, "late", false))
        runCurrent()
        assertTrue(browser.files.previews.isEmpty())
    }
}
