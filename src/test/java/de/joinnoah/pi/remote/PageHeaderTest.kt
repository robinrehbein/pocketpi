package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp")
class PageHeaderTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var list: LazyListState
    private lateinit var scope: CoroutineScope

    /** A list page shaped like RemoteScreen's: the sticky connection item, the large title, the rows. */
    @Composable
    private fun ListPage() {
        list = rememberLazyListState()
        scope = rememberCoroutineScope()
        var headerSize by remember { mutableStateOf(IntSize.Zero) }
        val titleInPill by remember(list) { derivedStateOf { pageTitleScrolledAway(list.layoutInfo, headerSize.height) } }
        val headerHeight = with(LocalDensity.current) { headerSize.height.toDp() }
        Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().testTag("pageList"), state = list, contentPadding = PaddingValues(top = 48.dp)) {
                connectionStatusItem(null, headerHeight)
                item(key = PAGE_TITLE_KEY) {
                    Text("Hosts", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLarge)
                }
                items(40) { Text("Row $it", Modifier.height(60.dp)) }
            }
            PageHeader(
                title = "Hosts",
                titleInPill = titleInPill,
                onBack = {},
                modifier = Modifier.align(Alignment.TopCenter).onSizeChanged { headerSize = it },
                actions = { IconButton(onClick = {}) { Icon(Icons.Default.Refresh, "Refresh") } },
            )
        }
    }

    private fun headings() = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))

    @Test
    fun theTitleMovesIntoTheBackPillOnlyOnceTheLargeTitleScrolledAway() {
        compose.setContent { MaterialTheme { ListPage() } }
        compose.waitForIdle()
        // The large title is in the list and the pill holds no title yet; the title is announced once.
        compose.onAllNodesWithTag("pageHeaderTitle").assertCountEquals(0)
        headings().assertCountEquals(1)
        compose.runOnIdle { scope.launch { list.scrollToItem(4) } }
        compose.waitForIdle()
        // Scrolled out: the pill carries the title, and it is still announced once.
        compose.onNodeWithTag("pageHeaderTitle").assertIsDisplayed().assertTextEquals("Hosts")
        headings().assertCountEquals(1)
        compose.runOnIdle { scope.launch { list.scrollToItem(0) } }
        compose.waitForIdle()
        compose.onAllNodesWithTag("pageHeaderTitle").assertCountEquals(0)
        headings().assertCountEquals(1)
    }

    private fun layout(titleOffset: Int, titleSize: Int, topPadding: Int): LazyListLayoutInfo {
        val title =
            object : LazyListItemInfo {
                override val index = 1
                override val key: Any = PAGE_TITLE_KEY
                override val offset = titleOffset
                override val size = titleSize
            }
        return object : LazyListLayoutInfo {
            override val visibleItemsInfo = listOf<LazyListItemInfo>(title)
            override val viewportStartOffset = -topPadding
            override val viewportEndOffset = 1000
            override val totalItemsCount = 10
            override val beforeContentPadding = topPadding
        }
    }

    @Test
    fun theTitleCountsAsGoneOnlyOnceItsBottomIsUnderTheHeaderPastTheTopPadding() {
        // The list starts 48 px below its top: an item offset of 0 is 48 px down the screen.
        // A 100 px header covers the title once its bottom, offset + size + 48, is at most 100.
        assertEquals(true, pageTitleScrolledAway(layout(titleOffset = 12, titleSize = 40, topPadding = 48), headerPx = 100))
        assertEquals(false, pageTitleScrolledAway(layout(titleOffset = 13, titleSize = 40, topPadding = 48), headerPx = 100))
        // Without padding the header alone decides.
        assertEquals(true, pageTitleScrolledAway(layout(titleOffset = 60, titleSize = 40, topPadding = 0), headerPx = 100))
        assertEquals(false, pageTitleScrolledAway(layout(titleOffset = 61, titleSize = 40, topPadding = 0), headerPx = 100))
    }

    @Test
    fun aPageWithoutBackKeepsItsTitleInAPillOfItsOwn() {
        var inPill by mutableStateOf(false)
        compose.setContent { MaterialTheme { PageHeader(title = "Hosts", titleInPill = inPill, onBack = null) } }
        compose.onAllNodesWithTag("pageHeaderTitle").assertCountEquals(0)
        compose.onAllNodesWithTag("navigationPill").assertCountEquals(0)
        inPill = true
        compose.waitForIdle()
        compose.onNodeWithTag("titlePill").assertIsDisplayed()
        compose.onNodeWithTag("pageHeaderTitle").assertIsDisplayed().assertTextEquals("Hosts")
        compose.onAllNodesWithTag("navigationPill").assertCountEquals(0)
    }

    @Test
    fun theConnectionChipSitsUnderThePillsWhenGiven() {
        compose.setContent {
            MaterialTheme {
                PageHeader(
                    title = "Folders",
                    titleInPill = true,
                    onBack = {},
                    onClose = {},
                    connection = R.string.remote_offline,
                )
            }
        }
        compose.onNodeWithTag("pageHeaderTitle").assertTextEquals("Folders")
        compose.onNodeWithTag("pageHeaderClose").assertIsDisplayed()
        compose.onNodeWithTag("connectionStatusChip").assertIsDisplayed()
    }

    @Test
    fun theCloseButtonAndTheActionsAreEachTheirOwn() {
        var closed = 0
        var refreshed = 0
        compose.setContent {
            MaterialTheme {
                PageHeader(
                    title = "Providers",
                    titleInPill = true,
                    onBack = {},
                    onClose = { closed++ },
                    actions = { IconButton(onClick = { refreshed++ }) { Icon(Icons.Default.Refresh, "Refresh") } },
                )
            }
        }
        compose.onNodeWithTag("pageHeaderClose").performClick()
        compose.onNode(hasContentDescription("Refresh")).performClick()
        assertEquals(1, closed)
        assertEquals(1, refreshed)
    }
}
