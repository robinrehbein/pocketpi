package de.joinnoah.pi.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Geometry and interaction checks only; Robolectric cannot validate a real GPU blur. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w480dp-h1400dp")
class FolderFooterGlassTest {
    @get:Rule val compose = createComposeRule()

    private val opens = mutableListOf<String>()
    private var dismissedNotice = 0
    private var dismissedClone = 0
    private val entries = (0 until 40).map {
        FolderEntry("folder%02d".format(it), git = false, piConfig = false, shared = false, trusted = true)
    }

    private fun render(
        path: String,
        width: Int,
        height: Int,
        fontScale: Float = 1f,
        noticeAndClone: Boolean = false,
        folderEntries: List<FolderEntry> = entries,
        truncated: Boolean = false,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme {
                    Box(Modifier.size(width.dp, height.dp)) {
                        FolderBrowserContent(
                            folders = FolderBrowserState(
                                routeId = "host", root = "~", path = path, entries = folderEntries, loaded = true, truncated = truncated,
                                notice = if (noticeAndClone) R.string.remote_folders_error_browse_busy else null,
                                clones = if (noticeAndClone) mapOf("host" to CloneProgress("host", "clone", path, "repo")) else emptyMap(),
                            ),
                            connected = true,
                            available = true,
                            connection = R.string.remote_connected,
                            onBack = {}, onJump = {}, onEnter = {}, onRetry = {},
                            onCreateFolder = {}, onClone = { _, _ -> }, onOpen = { opens += it },
                            onConfirm = {}, onCancelTrust = {}, onDismissClone = { dismissedClone++ },
                            onDismissNotice = { dismissedNotice++ },
                        )
                    }
                }
            }
        }
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun swipeInUncoveredBand(fraction: Float = 0.8f) {
        val list = bounds("folderList")
        val top = maxOf(bounds("folderBreadcrumb").bottom, list.top) - list.top + 4f
        val bottom = bounds("folderFooter").top - list.top - 4f
        assertTrue("No uncovered swipe band: top=$top bottom=$bottom", bottom > top)
        compose.onNodeWithTag("folderList").performTouchInput {
            swipeUp(startY = bottom, endY = bottom - (bottom - top) * fraction, durationMillis = 400)
        }
    }

    @Test
    fun listViewportAndScrolledRowsExtendBehindActions() {
        render(path = "Code", width = 400, height = 800)
        compose.onNodeWithTag("folderList").performScrollToIndex(10)
        val list = bounds("folderList")
        val action = bounds("folderNewAction")
        assertTrue("List viewport $list must extend below action $action", list.bottom > action.bottom)

        val rows = (10 until 25).mapNotNull { index ->
            val nodes = compose.onAllNodes(androidx.compose.ui.test.hasTestTag("folderRow:folder%02d".format(index)))
                .fetchSemanticsNodes()
            nodes.singleOrNull()?.boundsInRoot
        }
        assertTrue("A composed row must overlap the action region: rows=$rows action=$action", rows.any {
            it.top < action.bottom && it.bottom > action.top && it.right > action.left && it.left < action.right
        })
    }

    @Test
    fun lastRowClearsTheEntireRootFooterAtEndOfList() {
        // A tall fixture leaves room above even the stacked, large-font root footer.
        render(path = "", width = 400, height = 1200, fontScale = 2f)
        compose.onNodeWithTag("folderOpenHere").assertIsNotEnabled()
        compose.onNodeWithTag("folderRootHint").assertIsDisplayed()
        compose.onNodeWithTag("folderList").performScrollToIndex(entries.lastIndex)
        // scrollToIndex alone can place the last row anywhere in the viewport, not at its end.
        repeat(4) { swipeInUncoveredBand() }
        val last = compose.onNodeWithTag("folderRow:folder39").getUnclippedBoundsInRoot()
        val footer = compose.onNodeWithTag("folderFooter").getUnclippedBoundsInRoot()
        val list = compose.onNodeWithTag("folderList").getUnclippedBoundsInRoot()
        assertTrue("Last row $last must be fully within list $list", last.top >= list.top && last.bottom <= list.bottom)
        assertTrue("Last row $last must clear footer $footer", last.bottom <= footer.top)
    }

    @Test
    fun compactLargeFontRootLeavesUsableListBandAndReachableFooter() {
        render(path = "", width = 320, height = 480, fontScale = 2f)
        val breadcrumb = compose.onNodeWithTag("folderBreadcrumb").getUnclippedBoundsInRoot()
        val footer = compose.onNodeWithTag("folderFooter").getUnclippedBoundsInRoot()

        compose.onNodeWithTag("folderList").performScrollToIndex(entries.lastIndex)
        val lastRow = compose.onNodeWithTag("folderRow:folder39").getUnclippedBoundsInRoot()
        assertTrue(
            "The band between breadcrumb $breadcrumb and footer $footer must fit a full row $lastRow",
            footer.top - breadcrumb.bottom > lastRow.bottom - lastRow.top,
        )
        // Swipe only in the uncovered band, not on the overlaid footer or header.
        repeat(4) { swipeInUncoveredBand() }
        val endRow = compose.onNodeWithTag("folderRow:folder39").getUnclippedBoundsInRoot()
        assertTrue("Last row $endRow must clear breadcrumb $breadcrumb", endRow.top >= breadcrumb.bottom)
        assertTrue("Last row $endRow must clear footer $footer", endRow.bottom <= footer.top)

        compose.onNodeWithTag("folderNewAction").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("folderCloneAction").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("folderOpenHere").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("folderRootHint").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun compactRootNoticeAndCloneScrollWithListWithoutStarvingIt() {
        render(path = "", width = 320, height = 480, fontScale = 2f, noticeAndClone = true)
        val dismissLabel = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.remote_folders_dismiss)
        val noticeDismiss = compose.onNode(
            hasContentDescription(dismissLabel) and hasAnyAncestor(hasTestTag("folderNotice")),
            useUnmergedTree = true,
        )
        val cloneHide = compose.onNodeWithTag("cloneHide")

        // The header item is taller than the uncovered band; scroll until each action is
        // physically in that band, rather than accepting semantics behind the footer.
        compose.onNodeWithTag("folderList").performScrollToIndex(0)
        fun reach(action: SemanticsNodeInteraction) {
            repeat(60) {
                val button = action.getUnclippedBoundsInRoot()
                val top = compose.onNodeWithTag("folderBreadcrumb").getUnclippedBoundsInRoot().bottom
                val bottom = compose.onNodeWithTag("folderFooter").getUnclippedBoundsInRoot().top
                if (button.top >= top && button.bottom <= bottom) {
                    action.assertIsDisplayed().performClick()
                    return
                }
                assertTrue("Action $button already passed the readable band $top..$bottom", button.top >= top)
                swipeInUncoveredBand(0.15f)
            }
            throw AssertionError("Status action did not reach the uncovered band")
        }
        reach(noticeDismiss)
        reach(cloneHide)
        assertEquals(1, dismissedNotice)
        assertEquals(1, dismissedClone)

        // One lazy header item precedes the folder rows.
        compose.onNodeWithTag("folderList").performScrollToIndex(entries.size)
        repeat(4) { swipeInUncoveredBand() }
        val row = compose.onNodeWithTag("folderRow:folder39").getUnclippedBoundsInRoot()
        val breadcrumb = compose.onNodeWithTag("folderBreadcrumb").getUnclippedBoundsInRoot()
        val footer = compose.onNodeWithTag("folderFooter").getUnclippedBoundsInRoot()
        assertTrue("Readable band must fit a full row: $breadcrumb $footer $row", footer.top - breadcrumb.bottom > row.bottom - row.top)
        assertTrue("Last row $row must clear breadcrumb $breadcrumb", row.top >= breadcrumb.bottom)
        assertTrue("Last row $row must clear footer $footer", row.bottom <= footer.top)
    }

    @Test
    fun structuralItemsDoNotCollideWithFolderNames() {
        val folders = listOf("folderStatus", "truncated").map {
            FolderEntry(it, git = false, piConfig = false, shared = false, trusted = true)
        }
        render(path = "Code", width = 400, height = 800, noticeAndClone = true, folderEntries = folders, truncated = true)
        compose.onNodeWithTag("folderList").performScrollToIndex(0)
        compose.onNodeWithTag("folderNotice").assertExists()
        compose.onNodeWithTag("cloneProgress").assertExists()
        compose.onNodeWithTag("folderList").performScrollToIndex(1)
        compose.onNodeWithTag("folderRow:folderStatus").assertExists()
        compose.onNodeWithTag("folderList").performScrollToIndex(2)
        compose.onNodeWithTag("folderRow:truncated").assertExists()
        compose.onNodeWithTag("folderList").performScrollToIndex(3)
        compose.onNodeWithTag("folderTruncated").assertExists()
    }

    @Test
    fun narrowLargeFontCompactHeightActionsRemainReachable() {
        render(path = "Code/app", width = 320, height = 480, fontScale = 2f)
        compose.onNodeWithTag("folderNewAction").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("folderCloneAction").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("folderOpenHere").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(listOf("Code/app"), opens)
        compose.onNodeWithTag("folderRootHint").assertDoesNotExist()
    }
}
