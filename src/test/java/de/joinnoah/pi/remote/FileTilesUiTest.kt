package de.joinnoah.pi.remote

import android.text.format.Formatter
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FileTilesUiTest {
    @get:Rule val compose = createComposeRule()

    private var opens = 0
    private var requests = 0
    private var peeks = 0

    private fun tile(entry: FileEntry, preview: FileTilePreview? = null) {
        compose.setContent {
            MaterialTheme(typography = RemoteTypography) {
                FileEntryTile(
                    entry = entry,
                    preview = preview,
                    onOpen = { opens++ },
                    onRequestPreview = { requests++ },
                    onPeek = { peeks++ },
                )
            }
        }
    }

    @Test fun fileShowsThreeLineExcerptAndRequestsPreviewWhenComposed() {
        tile(FileEntry("notes.kt", FileEntryType.FILE), FileTilePreview(loading = false, content = "one\ntwo\nthree\nfour"))
        compose.onNodeWithTag("fileTilePreview:notes.kt", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("notes.kt").assertIsDisplayed()
        compose.onNodeWithText("one\ntwo\nthree").assertIsDisplayed()
        compose.onNodeWithText("one\ntwo\nthree\nfour", substring = true).assertDoesNotExist()
        assertEquals(0, requests)
    }

    @Test fun fileShowsLoadingAndNonContentStates() {
        tile(FileEntry("notes.kt", FileEntryType.FILE))
        compose.onNodeWithTag("fileTilePreview:notes.kt", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Loading preview…").assertIsDisplayed()
        assertEquals(1, requests)
    }

    @Test fun fileRetainsFormattedSizeBelowItsName() {
        tile(FileEntry("README.md", FileEntryType.FILE, size = 2048))
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onNodeWithText(Formatter.formatShortFileSize(context, 2048)).assertIsDisplayed()
    }

    @Test fun clearingCompletedPreviewRequestsSameComposedTileAgain() {
        val current = mutableStateOf<FileTilePreview?>(FileTilePreview(loading = false, content = "old"))
        compose.setContent {
            MaterialTheme {
                FileEntryTile(
                    entry = FileEntry("same.kt", FileEntryType.FILE),
                    preview = current.value,
                    onOpen = {},
                    onRequestPreview = { requests++ },
                    onPeek = {},
                )
            }
        }
        compose.waitForIdle()
        assertEquals(0, requests)
        compose.runOnIdle { current.value = null }
        compose.waitForIdle()
        assertEquals(1, requests)
    }

    @Test fun binaryFileShowsNonContentState() {
        tile(FileEntry("image.png", FileEntryType.FILE), FileTilePreview(loading = false, binary = true))
        compose.onNodeWithText("Binary file").assertIsDisplayed()
    }

    @Test fun failedFileShowsNonContentState() {
        tile(FileEntry("gone.kt", FileEntryType.FILE), FileTilePreview(loading = false, failure = FilesFailure.NOT_FOUND))
        compose.onNodeWithText("Preview unavailable").assertIsDisplayed()
    }

    @Test fun folderHasNoDefaultExcerpt() {
        tile(FileEntry("src", FileEntryType.DIR))
        compose.onNodeWithTag("fileTilePreview:src").assertDoesNotExist()
        assertEquals(0, requests)
    }

    @Test fun symlinkCannotOpenOrPeek() {
        tile(FileEntry("link", FileEntryType.SYMLINK))
        compose.onNodeWithTag("fileTile:link").assertIsNotEnabled()
        assertEquals(0, requests)
    }

    @Test fun submoduleCannotOpenOrPeek() {
        tile(FileEntry("vendor", FileEntryType.SUBMODULE))
        compose.onNodeWithTag("fileTile:vendor").assertIsNotEnabled()
        assertEquals(0, requests)
    }

    @Test fun tapAndSemanticLongClickUseDifferentCallbacks() {
        tile(FileEntry("notes.kt", FileEntryType.FILE))
        compose.onNodeWithTag("fileTile:notes.kt").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("fileTile:notes.kt").performSemanticsAction(SemanticsActions.OnLongClick)
        assertEquals(1, opens)
        assertEquals(1, peeks)
    }

    @Test fun folderTapAndSemanticLongClickUseDifferentCallbacks() {
        tile(FileEntry("src", FileEntryType.DIR))
        compose.onNodeWithTag("fileTile:src").performClick()
        compose.onNodeWithTag("fileTile:src").performSemanticsAction(SemanticsActions.OnLongClick)
        assertEquals(1, opens)
        assertEquals(1, peeks)
    }

    @Test fun peekShowsFolderNamesAndCanDismiss() {
        var dismisses = 0
        val peek = FilesPeek(
            path = "src",
            type = FileEntryType.DIR,
            loading = false,
            listing = FileListing("src", (1..10).map { FileEntry("name$it", FileEntryType.FILE) }, nextAfter = "next"),
        )
        compose.setContent {
            MaterialTheme {
                FilePeekPopup(peek, null, IntRect(80, 400, 180, 500), onDismiss = { dismisses++ })
            }
        }
        compose.onNodeWithTag("filePeek").assertIsDisplayed()
        compose.onNodeWithText("name8").assertIsDisplayed()
        compose.onNodeWithText("name9").assertDoesNotExist()
        compose.onNodeWithTag("filePeekDismiss").performClick()
        assertEquals(1, dismisses)
    }

    @Test fun filePeekShowsCachedFourthLineWhileTileStaysAtThree() {
        val content = "one\ntwo\nthree\nfour"
        compose.setContent {
            MaterialTheme {
                FilePeekPopup(
                    peek = FilesPeek(path = "notes.kt", type = FileEntryType.FILE, loading = false),
                    preview = FileTilePreview(loading = false, content = content),
                    anchorBounds = IntRect(80, 400, 180, 500),
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText(content).assertIsDisplayed()
    }

    @Test fun positionProviderChoosesAboveOrBelowAndClampsEdges() {
        val provider = FilePeekPositionProvider(Density(1f))
        val window = IntSize(300, 600)
        val popup = IntSize(160, 200)
        val leftTop = provider.calculatePosition(IntRect(0, 10, 80, 90), window, LayoutDirection.Ltr, popup)
        assertEquals(16, leftTop.x)
        assertEquals(90, leftTop.y)
        val rightBottom = provider.calculatePosition(IntRect(270, 450, 300, 500), window, LayoutDirection.Ltr, popup)
        assertEquals(124, rightBottom.x)
        assertEquals(250, rightBottom.y)
    }

    @Test fun positionProviderCapsPopupWidthToKeepBothMargins() {
        val provider = FilePeekPositionProvider(Density(1f))
        val width = provider.widthForWindow(280)
        assertEquals(248, width)
        val x = provider.calculatePosition(
            IntRect(220, 350, 280, 400), IntSize(280, 600), LayoutDirection.Ltr, IntSize(width, 100),
        ).x
        assertEquals(16, x)
        assertEquals(16, 280 - (x + width))
    }
}
