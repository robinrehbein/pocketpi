package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ProjectArtifactCardUiTest {
    @get:Rule val compose = createComposeRule()

    @Before fun emptyCaches() = ArtifactThumbnails.clear()

    private fun source(
        connected: Boolean = true,
        known: Boolean = true,
        read: suspend (String, String, Boolean) -> ProjectArtifactResult,
    ) =
        ProjectImageSource(
            "session", connected, known, supported = true,
            read = { _, _, _ -> ProjectImageResult.Unavailable },
            readArtifact = read,
            artifactSupported = true,
        )

    private fun show(text: String, source: ProjectImageSource?) =
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalProjectImageSource provides source) { MarkdownText(text) }
            }
        }

    private fun page(path: String, html: String = "<html><head><title>Quarterly report</title></head></html>") =
        ProjectArtifactResult.Loaded(path, "1".repeat(64), html, html.length)

    @Test fun withoutASourceTheLinkStaysText() {
        show("See [the report](out/report.html)", null)
        compose.onNodeWithText("See [the report](out/report.html)").assertIsDisplayed()
        compose.onNodeWithTag("projectArtifact-out/report.html").assertDoesNotExist()
    }

    @Test fun aLoadedPageShowsTitleFileNameAndBadge() {
        val reads = mutableListOf<Pair<String, Boolean>>()
        show("- [report](out/report.html)", source { _, path, fresh ->
            reads += path to fresh
            page(path)
        })
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Quarterly report").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("projectArtifact-out/report.html").assertIsDisplayed()
        compose.onNodeWithText("HTML").assertIsDisplayed()
        compose.onNodeWithText("report.html", substring = true).assertIsDisplayed()
        assertEquals(listOf("out/report.html" to false), reads)
    }

    private fun failure(result: ProjectArtifactResult, text: String) {
        show("[x](a.html)", source { _, _, _ -> result })
        compose.waitForIdle()
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    @Test fun tooLargeHasItsText() = failure(ProjectArtifactResult.TooLarge, "This page is too large to show.")

    @Test fun notAnArtifactHasItsText() =
        failure(ProjectArtifactResult.NotAnArtifact, "This file is not an HTML page that can be shown.")

    @Test fun anOlderHostAsksForAnUpdate() =
        failure(ProjectArtifactResult.UnsupportedHost, "Update pi Remote on your Mac to see HTML pages.")

    @Test fun aConnectionFailureCanBeRetried() {
        var attempts = 0
        show("[x](a.html)", source { _, path, _ -> if (++attempts == 1) ProjectArtifactResult.ConnectionFailure else page(path) })
        compose.onNodeWithTag("projectArtifactRetry").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Quarterly report").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, attempts)
    }

    @Test fun nothingIsAskedWhileCapabilitiesAreUnknown() {
        var asked = false
        show("[x](a.html)", source(known = false) { _, _, _ -> asked = true; ProjectArtifactResult.Busy })
        compose.onNodeWithText("Loading page…").assertIsDisplayed()
        assertEquals(false, asked)
    }

    @Test fun tappingThePageOpensTheViewerWithItsTabsAndNoBrowserButton() {
        show("[x](a.html)", source { _, path, _ -> page(path, "<title>T</title><img src=\"logo.png\">") })
        compose.waitUntil(5_000) { compose.onAllNodesWithText("T").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("projectArtifact-a.html").performClick()
        compose.onNodeWithText("Open", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("artifactViewer", true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("artifactLibrariesOnly", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("artifactExternalNotice", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("artifactViewerReload", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("artifactTabCode", useUnmergedTree = true).performClick()
        compose.onNodeWithText("<title>T</title><img src=\"logo.png\">", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("artifactOpenInBrowser", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("artifactViewerClose", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("artifactViewer", true).fetchSemanticsNodes().isEmpty() }
    }

    @Test fun aClosedMermaidFenceBecomesACardOnlyForAssistantText() {
        val text = "```mermaid\nflowchart TD\n  A-->B\n```"
        show(text, source { _, _, _ -> ProjectArtifactResult.Unavailable })
        compose.onNodeWithTag("mermaidCard").assertIsDisplayed()
        compose.onNodeWithText("Mermaid · flowchart · 2 lines").assertIsDisplayed()
    }

    @Test fun otherSurfacesKeepTheMermaidCode() {
        show("```mermaid\nflowchart TD\n  A-->B\n```", null)
        compose.onNodeWithTag("mermaidCard").assertDoesNotExist()
        compose.onNodeWithText("flowchart TD", substring = true).assertIsDisplayed()
    }

    @Test fun aStreamingMermaidFenceStaysCode() {
        show("```mermaid\nflowchart TD\n  A-->B", source { _, _, _ -> ProjectArtifactResult.Unavailable })
        compose.onNodeWithTag("mermaidCard").assertDoesNotExist()
        compose.onNodeWithText("flowchart TD", substring = true).assertIsDisplayed()
    }

    @Test fun anOversizedDiagramShowsTheNoticeAndTheCode() {
        val lines = (1..MERMAID_MAX_LINES + 5).joinToString("\n") { "  A$it-->B$it" }
        show("```mermaid\nflowchart TD\n$lines\n```", source { _, _, _ -> ProjectArtifactResult.Unavailable })
        compose.onNodeWithTag("mermaidTooLarge").assertExists()
        compose.onNodeWithTag("mermaidCard").assertDoesNotExist()
        compose.onNodeWithText("Diagram too large to draw. Showing the code instead.").assertExists()
    }
}
