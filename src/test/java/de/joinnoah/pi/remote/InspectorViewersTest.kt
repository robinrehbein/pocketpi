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
        slot.show(page({ "v${++reloads + 1}" }), "edit-1")
        assertFalse(slot.newVersion)
        // The same change again, or none, is no news.
        slot.changed("edit-1")
        slot.changed(null)
        assertFalse(slot.newVersion)
        slot.changed("edit-2")
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
        slot.show(page({ answer.await() }), "edit-1")
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
        slot.show(page({ "v2" }), "edit-1")
        slot.changed("edit-2")
        slot.reloaded()
        assertFalse(slot.newVersion)
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
        // The same artifact with another text, a newer version from the gallery, replaces the page.
        slot.show(InspectorViewer.Artifact(ArtifactKind.Html, "a.html", "A", "v3", { "v3" }, ArtifactWatch.File("a.html")), null)
        assertEquals("v3", slot.artifact?.text)
        slot.show(page({ "v2" }, ArtifactWatch.Listed("id", 2)), null)
        assertFalse(first === slot.artifact)
        // A diagram has nothing to reload and offers no versions.
        slot.show(InspectorViewer.Artifact(ArtifactKind.Mermaid, "diagram.mmd", "D", "flowchart TD", null), null)
        slot.changed("anything")
        assertFalse(slot.newVersion)
        slot.close()
        assertNull(slot.viewer)
        assertNull(slot.artifact)
    }
}
