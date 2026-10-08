package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Wide enough for every card to sit in the first row of the grid, so all of them are composed.
@Config(sdk = [35], qualifiers = "w900dp-h1200dp")
class SessionArtifactsGalleryUiTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun emptyCaches() {
        ArtifactThumbnails.clear()
        ProjectImageBitmaps.clear()
    }

    private val htmlId = "Zk3_xY9aB-0qWe7RtYuI1o"
    private val svgId = "Aa1_Bb2-Cc3Dd4Ee5Ff6Gg"
    private val mermaidId = "Hh7_Ii8-Jj9Kk0Ll1Mm2Nn"
    private val now = System.currentTimeMillis()

    private fun artifact(id: String, title: String, type: ArtifactType, version: Int) =
        SessionArtifact(id, title, type, version, "a".repeat(64), 100, now - 3_600_000, now - 60_000)

    private val html = artifact(htmlId, "Quarterly report", ArtifactType.HTML, 2)
    private val svg = artifact(svgId, "Company logo", ArtifactType.SVG, 1)
    private val mermaid = artifact(mermaidId, "Release flow", ArtifactType.MERMAID, 3)

    private fun listOf(vararg items: SessionArtifact, truncated: Boolean = false) =
        SessionArtifactListResult.Loaded(SessionArtifactList(items.toList(), truncated))

    private val svgBytes = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"40\" height=\"20\"><rect width=\"40\" height=\"20\" fill=\"red\"/></svg>".toByteArray()

    private fun opened(id: String, version: Int?): SessionArtifactOpenResult =
        when (id) {
            htmlId -> {
                val page = "<html><head><title>Quarterly report</title></head></html>"
                SessionArtifactOpenResult.Text(
                    ArtifactType.HTML, ProjectArtifactResult.Loaded("artifacts/$id/$version.html", "1".repeat(64), page, page.length),
                )
            }
            svgId ->
                SessionArtifactOpenResult.Svg(
                    ProjectImageResult.Loaded("artifacts/$id/$version.svg", "image/svg+xml", "2".repeat(64), svgBytes)
                )
            else ->
                SessionArtifactOpenResult.Text(
                    ArtifactType.MERMAID,
                    ProjectArtifactResult.Loaded("artifacts/$id/$version.mmd", "3".repeat(64), "flowchart TD\n A-->B", 19),
                )
        }

    private fun show(
        list: suspend () -> SessionArtifactListResult,
        open: suspend (String, Int?) -> SessionArtifactOpenResult = { id, version -> opened(id, version) },
    ) = compose.setContent { MaterialTheme { SessionArtifactsGallery("session", list, open) } }

    private fun waitFor(tag: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag, true).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun anEmptyListShowsTheEmptyState() {
        show({ listOf() })
        waitFor("artifactsEmpty")
        compose.onNodeWithText("Artifacts").assertIsDisplayed()
        compose.onNodeWithTag("artifactGrid").assertDoesNotExist()
    }

    @Test
    fun cardsShowTitleTypeVersionAndAge() {
        val opens = mutableListOf<Pair<String, Int?>>()
        show({ listOf(html, svg, mermaid) }) { id, version ->
            opens += id to version
            opened(id, version)
        }
        waitFor("artifactCard-$htmlId")
        compose.onNodeWithTag("artifactCard-$svgId").assertIsDisplayed()
        compose.onNodeWithTag("artifactCard-$mermaidId").assertIsDisplayed()
        compose.onNodeWithText("HTML · version 2 · ", substring = true).assertIsDisplayed()
        compose.onNodeWithText("SVG · version 1 · ", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Mermaid · version 3 · ", substring = true).assertIsDisplayed()
        // A thumbnail that cannot be drawn falls back to the title, so a title may appear twice.
        for (title in listOf("Quarterly report", "Company logo", "Release flow"))
            assertTrue(title, compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty())
        compose.onAllNodesWithText("1 minute ago", substring = true).assertCountEquals(3)
        // The svg is drawn from its decoded image.
        waitFor("artifactPicture-$svgId")
        compose.onNodeWithTag("artifactsTruncated").assertDoesNotExist()
        // Each card opens its artifact at the version the list named.
        compose.waitUntil(5_000) { opens.toSet().size == 3 }
        assertEquals(setOf(htmlId to 2, svgId to 1, mermaidId to 3), opens.toSet())
    }

    @Test
    fun aTruncatedListShowsTheHint() {
        show({ listOf(svg, truncated = true) })
        waitFor("artifactsTruncated")
        compose.onNodeWithText("The list is long, so only the newest artifacts are shown.").assertExists()
    }

    @Test
    fun reloadAsksForTheListAgain() {
        var reads = 0
        show({ reads++; if (reads == 1) listOf() else listOf(svg) })
        waitFor("artifactsEmpty")
        compose.onNodeWithTag("artifactsReload").performClick()
        waitFor("artifactCard-$svgId")
        assertEquals(2, reads)
    }

    @Test
    fun tappingAnSvgOpensTheImageViewer() {
        show({ listOf(svg) })
        waitFor("artifactPicture-$svgId")
        compose.onNodeWithTag("artifactPicture-$svgId").performClick()
        waitFor("imageViewer")
        compose.onNodeWithTag("imageViewerClose", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("imageViewer", true).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun tappingAPageOpensTheArtifactViewerWithItsTitle() {
        show({ listOf(html) })
        waitFor("artifactCard-$htmlId")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("artifactThumbnail", true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("artifactCard-$htmlId").performClick()
        waitFor("artifactViewer")
        compose.onNodeWithTag("artifactViewerReload", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("artifactViewerClose", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("artifactViewer", true).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun tappingADiagramOpensTheArtifactViewer() {
        show({ listOf(mermaid) })
        waitFor("artifactCard-$mermaidId")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("artifactThumbnail", true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("artifactCard-$mermaidId").performClick()
        waitFor("artifactViewer")
    }

    @Test
    fun aFailedListCanBeRetried() {
        var reads = 0
        show({ if (++reads == 1) SessionArtifactListResult.Failed else listOf(svg) })
        waitFor("artifactsError")
        compose.onNodeWithText("Could not load the artifacts. Check your connection.").assertIsDisplayed()
        compose.onNodeWithTag("artifactsRetry").performClick()
        waitFor("artifactCard-$svgId")
        assertEquals(2, reads)
    }

    @Test
    fun anOlderHostAsksForAnUpdateWithoutRetry() {
        show({ SessionArtifactListResult.Unsupported })
        waitFor("artifactsError")
        compose.onNodeWithText("Update pi Remote on your Mac to browse artifacts.").assertIsDisplayed()
        compose.onNodeWithTag("artifactsRetry").assertDoesNotExist()
    }

    @Test
    fun aCardThatCannotBeOpenedShowsItsReasonAndRetries() {
        var attempts = 0
        show({ listOf(svg) }) { id, version ->
            if (++attempts == 1) SessionArtifactOpenResult.ConnectionFailure else opened(id, version)
        }
        waitFor("artifactCardProblem-$svgId")
        compose.onNodeWithText("Could not load. Check your connection.").assertIsDisplayed()
        compose.onNodeWithTag("artifactCardRetry-$svgId").performClick()
        waitFor("artifactPicture-$svgId")
        assertEquals(2, attempts)
    }

    @Test
    fun aTooLargeOrUnreadableCardHasNoRetry() {
        show({ listOf(svg, html) }) { id, _ ->
            if (id == svgId) SessionArtifactOpenResult.TooLarge else SessionArtifactOpenResult.NotAnArtifact
        }
        waitFor("artifactCardProblem-$svgId")
        waitFor("artifactCardProblem-$htmlId")
        compose.onNodeWithText("Too large to show.").assertIsDisplayed()
        compose.onNodeWithText("This file cannot be shown.").assertIsDisplayed()
        compose.onNodeWithTag("artifactCardRetry-$svgId").assertDoesNotExist()
    }

    @Test
    fun theImageViewerReadsTheSameSnapshotAgain() {
        val reads = mutableListOf<Pair<String, Int?>>()
        val source = artifactImageSource("session", svg) { id, version ->
            reads += id to version
            opened(id, version)
        }
        val image = kotlinx.coroutines.runBlocking { source.read("session", "Company logo.svg", true) }
        assertTrue(image is ProjectImageResult.Loaded && image.isSvg)
        assertEquals(listOf(svgId to 1), reads)
    }
}
