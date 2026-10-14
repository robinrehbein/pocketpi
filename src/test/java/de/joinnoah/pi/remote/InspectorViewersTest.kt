package de.joinnoah.pi.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectorViewersTest {
    private fun edit(id: String, path: String, state: String = "complete", name: String = "edit") =
        ConversationItem.Activity(id, id, name, """{"path":"$path"}""", "done", state, false)

    @Test
    fun theLastFinishedEditOfTheFileIsItsChange() {
        val items =
            listOf(
                edit("a", "out/report.html"),
                edit("b", "out/other.html"),
                edit("c", "/Users/robin/project/out/report.html", name = "write"),
                edit("d", "out/report.html", state = "streaming"),
                edit("e", "out/report.html", state = "error"),
                edit("f", "out/report.html", name = "read"),
            )
        assertEquals("c", artifactChange(items, "out/report.html"))
        assertEquals("b", artifactChange(items, "./out/other.html"))
        assertNull(artifactChange(items, "report.htm"))
        assertNull(artifactChange(emptyList(), "out/report.html"))
    }

    @Test
    fun pathsMatchWhetherRelativeOrAbsolute() {
        assertTrue(samePath("out/report.html", "/Users/robin/project/out/report.html"))
        assertTrue(samePath("./out/report.html", "out/report.html"))
        assertFalse(samePath("report.html", "out/myreport.html"))
        assertFalse(samePath("", "out/report.html"))
    }

    private fun page(reload: (suspend () -> String?)?, watch: ArtifactWatch? = ArtifactWatch.File("a.html")) =
        InspectorViewer.Artifact(ArtifactKind.Html, "a.html", "A", "v1", reload, watch)

    private fun TestScope.slot() = InspectorViewerSlot(TestScope(StandardTestDispatcher(testScheduler)))

    @Test
    fun aNewVersionWaitsUntilTheUserTakesIt() = runTest {
        var reloads = 0
        val slot = slot()
        // Opened before the chat edited the file: the card's page is current.
        slot.show(page({ "v${++reloads + 1}" }), null)
        assertFalse(slot.newVersion)
        // No change, or the same again, is no news.
        slot.changed(null)
        assertFalse(slot.newVersion)
        slot.changed("edit-1")
        assertTrue(slot.newVersion)
        slot.changed("edit-1")
        assertTrue(slot.newVersion)
        advanceUntilIdle()
        assertEquals(0, reloads)
        assertEquals("v1", slot.artifact?.text)
        slot.reload()
        assertFalse(slot.newVersion)
        advanceUntilIdle()
        assertEquals(1, reloads)
        assertEquals("v2", slot.artifact?.text)
        assertEquals(1, slot.artifact?.generation)
    }

    @Test
    fun aCardLoadedBeforeTheLastEditOpensWhatIsOnTheMacNow() = runTest {
        var onMac = "v2"
        val slot = slot()
        // The card loaded v1, then the chat edited the file (edit-1), then the user tapped the card.
        slot.show(page({ onMac }), "edit-1")
        assertFalse(slot.newVersion)
        advanceUntilIdle()
        assertEquals("v2", slot.artifact?.text)
        assertFalse(slot.newVersion)
        // Tapping the old card again keeps the newer page instead of its stale text.
        slot.show(page({ onMac }), "edit-1")
        advanceUntilIdle()
        assertEquals("v2", slot.artifact?.text)
        // The same text fetched on opening keeps the loaded view.
        onMac = "v1"
        slot.show(page({ onMac }, ArtifactWatch.File("b.html")), "edit-2")
        advanceUntilIdle()
        assertEquals("v1", slot.artifact?.text)
        assertEquals(0, slot.artifact?.generation)
    }

    @Test
    fun aFailedFetchOnOpeningOffersTheNewestVersion() = runTest {
        val slot = slot()
        slot.show(page({ null }), "edit-1")
        advanceUntilIdle()
        assertEquals("v1", slot.artifact?.text)
        assertTrue(slot.newVersion)
    }

    @Test
    fun aLaterReloadSupersedesOneStillRunning() = runTest {
        val first = CompletableDeferred<String?>()
        val second = CompletableDeferred<String?>()
        val answers = ArrayDeque(listOf(first, second))
        val slot = slot()
        slot.show(page({ answers.removeFirst().await() }), null)
        slot.reloadAlways()
        slot.changed("edit-1")
        advanceUntilIdle()
        slot.changed("edit-2")
        advanceUntilIdle()
        second.complete("v3")
        advanceUntilIdle()
        // The older answer, coming last, does not replace the newer page.
        first.complete("v2")
        advanceUntilIdle()
        assertEquals("v3", slot.artifact?.text)
        assertFalse(slot.newVersion)
    }

    @Test
    fun alwaysReloadTakesEveryLaterVersionAtOnce() = runTest {
        var reloads = 0
        val slot = slot()
        slot.show(page({ "v${++reloads + 1}" }), null)
        slot.changed("edit-1")
        slot.reloadAlways()
        advanceUntilIdle()
        assertEquals("v2", slot.artifact?.text)
        slot.changed("edit-2")
        assertFalse(slot.newVersion)
        advanceUntilIdle()
        assertEquals("v3", slot.artifact?.text)
        // Another artifact starts without it.
        slot.show(page({ "x" }, ArtifactWatch.File("b.html")), null)
        assertFalse(slot.autoRefresh)
    }

    @Test
    fun aFailedReloadOffersTheVersionAgain() = runTest {
        val answer = CompletableDeferred<String?>()
        val slot = slot()
        slot.show(page({ answer.await() }), null)
        slot.changed("edit-2")
        slot.reload()
        answer.complete(null)
        advanceUntilIdle()
        assertTrue(slot.newVersion)
        assertEquals("v1", slot.artifact?.text)
    }

    @Test
    fun aReloadByHandCountsAsTheNewVersion() = runTest {
        val slot = slot()
        slot.show(page({ "v2" }), null)
        slot.changed("edit-2")
        slot.reloaded()
        assertFalse(slot.newVersion)
    }

    private fun listed(version: Int, text: String) =
        InspectorViewer.Artifact(ArtifactKind.Html, "a.html", "A", text, { "latest" }, ArtifactWatch.Listed("id", version))

    @Test
    fun anOpenedListedVersionOlderThanTheNewestOffersIt() = runTest {
        val slot = slot()
        // The gallery listed version 3 already, but the card opened version 2.
        slot.show(listed(2, "v2"), "3")
        assertTrue(slot.newVersion)
        // The gallery's version 3 replaces the page, and an older card again keeps it.
        slot.show(listed(3, "v3"), "3")
        assertEquals("v3", slot.artifact?.text)
        assertFalse(slot.newVersion)
        slot.show(listed(2, "v2"), "3")
        assertEquals("v3", slot.artifact?.text)
    }

    @Test
    fun showingTheSameAgainKeepsItsPageAndAnotherStartsFresh() = runTest {
        val slot = slot()
        slot.show(page({ "v2" }), null)
        val first = slot.artifact
        slot.poppedOut = true
        slot.show(page({ "v2" }), null)
        assertSame(first, slot.artifact)
        assertFalse(slot.poppedOut)
        // A newer version of the same artifact from the gallery replaces the page with a new one.
        slot.show(listed(1, "v1"), "1")
        val listedPage = slot.artifact
        slot.show(listed(2, "v2"), "2")
        assertEquals("v2", slot.artifact?.text)
        assertFalse(listedPage === slot.artifact)
        assertEquals(0, slot.artifact?.generation)
        // A diagram has nothing to reload and offers no versions.
        slot.show(InspectorViewer.Artifact(ArtifactKind.Mermaid, "diagram.mmd", "D", "flowchart TD", null), null)
        slot.changed("anything")
        assertFalse(slot.newVersion)
        slot.close()
        assertNull(slot.viewer)
        assertNull(slot.artifact)
    }
}
