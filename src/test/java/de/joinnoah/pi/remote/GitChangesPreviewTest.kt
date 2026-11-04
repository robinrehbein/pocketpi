package de.joinnoah.pi.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GitChangesPreviewTest {
    private class Source : GitSource {
        data class Read(val snapshot: String, val path: String, val reply: CompletableDeferred<GitDiff> = CompletableDeferred())
        val reads = mutableListOf<Read>()
        override suspend fun gitStatus(sessionId: String, base: GitBase) = GitStatus(base, "fresh", listOf(GitFile("a.kt", GitChange.MODIFIED)))
        override suspend fun gitLog(sessionId: String, snapshotId: String) = GitLog()
        override suspend fun gitDiff(sessionId: String, snapshotId: String, path: String): GitDiff =
            Read(snapshotId, path).also { reads += it }.reply.await()
    }
    private class Browser(scope: CoroutineScope) {
        val source = Source()
        var state = RemoteState(selection = RemoteSelection(sessionId = "session"), connected = true,
            changes = ChangesState("session", loading = false,
                status = GitStatus(GitBase.HEAD, "snapshot", listOf(
                    GitFile("a.kt", GitChange.MODIFIED), GitFile("b.kt", GitChange.DELETED), GitFile("c.kt", GitChange.ADDED),
                    GitFile("binary", GitChange.ADDED, binary = true), GitFile("large", GitChange.MODIFIED, omitted = GitOmitted.TOO_LARGE),
                ))))
        val loader = GitChangesLoader(scope, source, { state }) { state = it(state) }
    }
    @Test fun deduplicatesReadsAndLimitsConcurrentDiffs() = runTest {
        val browser = Browser(backgroundScope)
        listOf("a.kt", "a.kt", "b.kt", "c.kt").forEach(browser.loader::requestPreview)
        runCurrent()
        assertEquals(listOf("a.kt", "b.kt"), browser.source.reads.map { it.path })
        val first = browser.source.reads.first()
        first.reply.complete(GitDiff(first.path, first.snapshot, "diff --git a/a b/a\n@@ -1 +1 @@\n-old\n+new", false, false))
        runCurrent()
        assertEquals(listOf("a.kt", "b.kt", "c.kt"), browser.source.reads.map { it.path })
        assertEquals("@@ -1 +1 @@\n-old\n+new", browser.state.changes!!.previews.getValue("a.kt").content)
        assertTrue(browser.source.reads.all { it.snapshot == "snapshot" })
        assertNull(browser.state.changes!!.file)
    }
    @Test fun binaryAndOmittedFilesSkipReads() = runTest {
        val browser = Browser(backgroundScope)
        browser.loader.requestPreview("binary")
        browser.loader.requestPreview("large")
        browser.loader.requestPreview("unlisted.kt")
        runCurrent()
        assertTrue(browser.source.reads.isEmpty())
        assertTrue(browser.state.changes!!.previews.getValue("binary").binary)
        assertTrue(browser.state.changes!!.previews.getValue("large").tooLarge)
    }
    @Test fun reloadCancelsOldPreviewsAndUsesFreshSnapshot() = runTest {
        val browser = Browser(backgroundScope)
        browser.loader.requestPreview("a.kt")
        runCurrent()
        val old = browser.source.reads.single()
        browser.loader.reload()
        runCurrent()
        old.reply.complete(GitDiff("a.kt", "snapshot", "@@ -1 +1 @@\n+stale", false, false))
        runCurrent()
        assertTrue(browser.state.changes!!.previews.isEmpty())
        browser.loader.requestPreview("a.kt")
        runCurrent()
        assertEquals("fresh", browser.source.reads.last().snapshot)
    }
    @Test fun failuresReleaseSlotsAndContentIsBounded() = runTest {
        val browser = Browser(backgroundScope)
        listOf("a.kt", "b.kt", "c.kt").forEach(browser.loader::requestPreview)
        runCurrent()
        browser.source.reads.first().reply.completeExceptionally(RemoteRequestException("offline"))
        runCurrent()
        assertEquals(FilesFailure.OFFLINE, browser.state.changes!!.previews.getValue("a.kt").failure)
        val third = browser.source.reads.last()
        third.reply.complete(GitDiff(third.path, third.snapshot, "@@ -1 +1 @@\n+" + "x".repeat(3000), false, false))
        runCurrent()
        assertEquals(FILE_TILE_PREVIEW_CHARS, browser.state.changes!!.previews.getValue("c.kt").content!!.length)
    }
}
