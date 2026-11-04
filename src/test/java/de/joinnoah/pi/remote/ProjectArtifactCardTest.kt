package de.joinnoah.pi.remote

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectArtifactCardTest {
    private fun loaded() = ProjectArtifactResult.Loaded("a/report.html", "0".repeat(64), "<p>x</p>", 8)

    @Test fun everyResultMapsToItsState() {
        assertTrue(loaded().toCardState() is ProjectArtifactState.Ready)
        assertEquals(ProjectArtifactState.UnsupportedHost, ProjectArtifactResult.UnsupportedHost.toCardState())
        assertEquals(ProjectArtifactState.Unavailable, ProjectArtifactResult.Unavailable.toCardState())
        assertEquals(ProjectArtifactState.TooLarge, ProjectArtifactResult.TooLarge.toCardState())
        assertEquals(ProjectArtifactState.NotAnArtifact, ProjectArtifactResult.NotAnArtifact.toCardState())
        assertEquals(ProjectArtifactState.Busy, ProjectArtifactResult.Busy.toCardState())
        assertEquals(ProjectArtifactState.ConnectionFailure, ProjectArtifactResult.ConnectionFailure.toCardState())
    }

    @Test fun onlyTransientStatesOfferRetry() {
        assertTrue(ProjectArtifactState.Busy.retryable())
        assertTrue(ProjectArtifactState.ConnectionFailure.retryable())
        listOf(
            ProjectArtifactState.Loading, ProjectArtifactState.TooLarge, ProjectArtifactState.NotAnArtifact,
            ProjectArtifactState.Unavailable, ProjectArtifactState.UnsupportedHost,
        ).forEach { assertFalse(it.toString(), it.retryable()) }
    }

    @Test fun theTitleComesFromTheFirst4Kb() {
        assertEquals("Q3 &amp; more".replace("&amp;", "&"), artifactTitle("<html><head><TITLE>\n Q3 &amp;\n more </TITLE>", "f.html"))
        assertEquals("f.html", artifactTitle("<html><body>none</body>", "f.html"))
        assertEquals("f.html", artifactTitle("<title>  </title>", "f.html"))
        assertEquals("f.html", artifactTitle(" ".repeat(5000) + "<title>late</title>", "f.html"))
        assertEquals(80, artifactTitle("<title>${"x".repeat(200)}</title>", "f").length)
    }

    @Test fun mermaidLimitsAre100KbAnd2000Lines() {
        assertFalse(mermaidTooLarge("graph TD\nA-->B"))
        assertFalse(mermaidTooLarge("x".repeat(MERMAID_MAX_BYTES)))
        assertTrue(mermaidTooLarge("x".repeat(MERMAID_MAX_BYTES + 1)))
        assertTrue(mermaidTooLarge("é".repeat(MERMAID_MAX_BYTES / 2 + 1)))
        assertFalse(mermaidTooLarge("a\n".repeat(MERMAID_MAX_LINES - 1) + "a"))
        assertTrue(mermaidTooLarge("a\n".repeat(MERMAID_MAX_LINES) + "a"))
    }

    @Test fun theDiagramTypeSkipsCommentsAndFrontMatter() {
        assertEquals("flowchart", mermaidDiagramType("flowchart TD\nA-->B"))
        assertEquals("sequenceDiagram", mermaidDiagramType("\n%% note\nsequenceDiagram\nA->>B: hi"))
        assertEquals("gantt", mermaidDiagramType("---\ntitle: x\n---\ngantt\n"))
        assertEquals("stateDiagram-v2", mermaidDiagramType("stateDiagram-v2\n[*] --> A"))
        assertEquals("", mermaidDiagramType("%% only a comment"))
        assertEquals(3, mermaidLineCount("a\nb\nc\n"))
    }

    @Test fun exportedNamesAreSanitisedAndStayInTheFolder() {
        assertEquals("report.html", ArtifactStorage.safeName("a/b/report.html", "html"))
        assertEquals("a-b.html", ArtifactStorage.safeName("../x/a b.htm", "html"))
        assertEquals("artifact.html", ArtifactStorage.safeName("", "html"))
        assertEquals("diagram.mmd", ArtifactStorage.safeName("diagram.mmd", "mmd"))
        assertEquals("artifact.html", ArtifactStorage.safeName("/..\\.html", "html"))
    }

    @Test fun storageKeepsTheNewestFilesAndClears() {
        val cache = Files.createTempDirectory("artifacts").toFile()
        try {
            repeat(6) { ArtifactStorage.write(cache, "f$it.html", "<p>$it</p>".toByteArray()).setLastModified(1_000L + it * 1_000L) }
            val names = java.io.File(cache, ArtifactStorage.DIRECTORY).list()!!.sorted()
            assertEquals(ArtifactStorage.KEEP, names.size)
            assertTrue("f5.html" in names)
            assertFalse(java.io.File(cache, "f5.html.tmp").exists())
            ArtifactStorage.clear(cache)
            assertEquals(0, java.io.File(cache, ArtifactStorage.DIRECTORY).list()!!.size)
        } finally {
            cache.deleteRecursively()
        }
    }
}
