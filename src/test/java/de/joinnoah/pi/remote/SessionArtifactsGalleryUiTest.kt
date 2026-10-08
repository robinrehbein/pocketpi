package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
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
        open: suspend (SessionArtifact, Boolean) -> SessionArtifactOpenResult = { artifact, _ -> opened(artifact.id, artifact.version) },
    ) = compose.setContent { MaterialTheme { SessionArtifactsGallery("session", list, open, formatAge = { "just now" }) } }

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
        show({ listOf(html, svg, mermaid) }) { artifact, listed ->
            assertTrue(listed)
            opens += artifact.id to artifact.version
            opened(artifact.id, artifact.version)
        }
        waitFor("artifactCard-$htmlId")
        compose.onNodeWithTag("artifactCard-$svgId").assertIsDisplayed()
        compose.onNodeWithTag("artifactCard-$mermaidId").assertIsDisplayed()
        compose.onNodeWithText("HTML · version 2 · just now", substring = true).assertIsDisplayed()
        compose.onNodeWithText("SVG · version 1 · just now", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Mermaid · version 3 · just now", substring = true).assertIsDisplayed()
        // A thumbnail that cannot be drawn falls back to the title, so a title may appear twice.
        for (title in listOf("Quarterly report", "Company logo", "Release flow"))
            assertTrue(title, compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty())
        // One button per card, not one for the picture and another for the caption.
        for (id in listOf(htmlId, svgId, mermaidId)) {
            compose.onNodeWithTag("artifactCard-$id").assertHasClickAction()
            compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("artifactCard-$id"))).assertCountEquals(0)
        }
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
        compose.onNodeWithTag("artifactCard-$svgId").performClick()
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
        // The file shown (and offered to Share and Save) is named after the title, not "2.html".
        compose.onNodeWithText("Quarterly report.html", useUnmergedTree = true).assertExists()
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
        compose.onNodeWithTag("artifactsError").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        compose.onNodeWithTag("artifactsError").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
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
        show({ listOf(svg) }) { artifact, _ ->
            if (++attempts == 1) SessionArtifactOpenResult.ConnectionFailure else opened(artifact.id, artifact.version)
        }
        waitFor("artifactCardProblem-$svgId")
        compose.onNodeWithText("Could not load. Check your connection.").assertIsDisplayed()
        compose.onNodeWithTag("artifactCardRetry-$svgId").performClick()
        waitFor("artifactPicture-$svgId")
        assertEquals(2, attempts)
    }

    @Test
    fun aTooLargeOrUnreadableCardHasNoRetry() {
        show({ listOf(svg, html) }) { artifact, _ ->
            if (artifact.id == svgId) SessionArtifactOpenResult.TooLarge else SessionArtifactOpenResult.NotAnArtifact
        }
        waitFor("artifactCardProblem-$svgId")
        waitFor("artifactCardProblem-$htmlId")
        compose.onNodeWithText("Too large to show.").assertIsDisplayed()
        compose.onNodeWithText("This file cannot be shown.").assertIsDisplayed()
        compose.onNodeWithTag("artifactCardRetry-$svgId").assertDoesNotExist()
    }

    @Test
    fun aLoadingCardAndTheLoadingListAreDescribed() {
        val never = kotlinx.coroutines.CompletableDeferred<SessionArtifactOpenResult>()
        show({ listOf(svg) }) { _, _ -> never.await() }
        waitFor("artifactCardLoading-$svgId")
        compose.onNodeWithContentDescription("Loading artifact…", useUnmergedTree = true).assertExists()
    }

    @Test
    fun theImageViewerReadsTheSameListedSnapshotEveryTime() {
        val reads = mutableListOf<Pair<String, Boolean>>()
        var next: SessionArtifactOpenResult? = null
        val source = artifactImageSource("session", svg) { artifact, listed ->
            reads += artifact.id to listed
            next ?: opened(artifact.id, artifact.version)
        }
        kotlinx.coroutines.runBlocking {
            // Opening and Share/Save (fresh) both ask for the listed snapshot, never "the latest".
            for (fresh in listOf(false, true)) {
                val image = source.read("session", "Company logo.svg", fresh) as ProjectImageResult.Loaded
                assertTrue(image.isSvg)
                assertEquals("artifacts/$svgId/1.svg", image.path)
                assertEquals("2".repeat(64), image.sha256)
                org.junit.Assert.assertArrayEquals(svgBytes, image.bytes)
            }
            assertEquals(listOf(svgId to true, svgId to true), reads)
            for ((result, expected) in listOf(
                SessionArtifactOpenResult.TooLarge to ProjectImageResult.TooLarge,
                SessionArtifactOpenResult.NotAnArtifact to ProjectImageResult.NotAnImage,
                SessionArtifactOpenResult.Busy to ProjectImageResult.Busy,
                SessionArtifactOpenResult.ConnectionFailure to ProjectImageResult.Failed,
                SessionArtifactOpenResult.Unsupported to ProjectImageResult.Unsupported,
                SessionArtifactOpenResult.Unavailable to ProjectImageResult.Unavailable,
            )) {
                next = result
                assertEquals(expected, source.read("session", "x.svg", false))
            }
        }
    }

    @Test
    fun shareAndSaveNamesComeFromTheTitle() {
        assertEquals("Quarterly report.html", artifactFileName("Quarterly report", ArtifactType.HTML))
        assertEquals("Release flow.mmd", artifactFileName("Release flow", ArtifactType.MERMAID))
        assertEquals("a-b-c.svg", artifactFileName("a/b\\c", ArtifactType.SVG))
        // What the viewers finally write is a plain, sanitised name with the right extension.
        assertEquals("Q1-report-v2.html", ArtifactStorage.safeName(artifactFileName("Q1 report v2", ArtifactType.HTML), "html"))
        assertEquals("Release-flow.mmd", ArtifactStorage.safeName(artifactFileName("Release flow", ArtifactType.MERMAID), "mmd"))
        assertEquals("Logo.svg", ImageStorage.safeName(artifactFileName("Logo", ArtifactType.SVG), MediaMime.SVG))
    }
}
