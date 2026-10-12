package de.joinnoah.pi.remote

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import java.io.ByteArrayOutputStream
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
class ProjectImageCardUiTest {
    @get:Rule val compose = createComposeRule()

    @Before fun emptyCache() = ProjectImageBitmaps.clear()

    private fun pngBytes(): ByteArray =
        ByteArrayOutputStream().also {
            Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()

    private fun source(
        connected: Boolean = true,
        known: Boolean = true,
        read: suspend (String, String, Boolean) -> ProjectImageResult,
    ) = ProjectImageSource("session", connected, known, supported = true, read = read)

    private fun show(text: String, source: ProjectImageSource?) =
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalProjectImageSource provides source) { MarkdownText(text) }
            }
        }

    @Test fun withoutASourceTheAltTextShowsAndNothingIsRequested() {
        show("Before\n![Result screenshot](build/shot.png)\nAfter", null)
        compose.onNodeWithText("Result screenshot").assertIsDisplayed()
        compose.onNodeWithTag("projectImage-build/shot.png").assertDoesNotExist()
    }

    @Test fun aLoadedImageShowsTheCardWithItsFileName() {
        val reads = mutableListOf<Pair<String, Boolean>>()
        show("![Result](build/shot.png)", source { _, path, fresh ->
            reads += path to fresh
            ProjectImageResult.Loaded(path, "image/png", "0".repeat(64), pngBytes())
        })
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("projectImagePicture-build/shot.png", true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("shot.png").assertIsDisplayed()
        assertEquals(listOf("build/shot.png" to false), reads)
    }

    private fun failure(result: ProjectImageResult, text: String) {
        show("![x](a.png)", source { _, _, _ -> result })
        compose.waitForIdle()
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    @Test fun tooLargeHasItsText() = failure(ProjectImageResult.TooLarge, "This image is too large to show.")

    @Test fun notAnImageHasItsText() =
        failure(ProjectImageResult.NotAnImage, "This file is not an image that can be shown.")

    @Test fun unavailableHasItsText() = failure(ProjectImageResult.Unavailable, "This image is not available.")

    @Test fun anOlderHostAsksForAnUpdate() =
        failure(ProjectImageResult.Unsupported, "Update pi Remote on your Mac to see images.")

    @Test fun aConnectionFailureCanBeRetried() {
        var attempts = 0
        show("![x](a.png)", source { _, path, _ ->
            if (++attempts == 1) ProjectImageResult.Failed
            else ProjectImageResult.Loaded(path, "image/png", "0".repeat(64), pngBytes())
        })
        compose.onNodeWithTag("projectImageRetry").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("projectImagePicture-a.png", true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(2, attempts)
    }

    @Test fun nothingIsAskedWhileCapabilitiesAreUnknown() {
        var asked = false
        show("![x](a.png)", source(known = false) { _, _, _ -> asked = true; ProjectImageResult.Failed })
        compose.onNodeWithText("Loading image…").assertIsDisplayed()
        assertEquals(false, asked)
    }

    @Test fun tappingTheLoadedImageOpensTheViewer() {
        show("![x](a.png)", source { _, path, _ -> ProjectImageResult.Loaded(path, "image/png", "0".repeat(64), pngBytes()) })
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("projectImagePicture-a.png", true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("projectImage-a.png").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("imageViewer", true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("imageViewerShare", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("imageViewerSave", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("imageViewerClose", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("imageViewer", true).fetchSemanticsNodes().isEmpty() }
    }
}
