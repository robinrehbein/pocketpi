package de.joinnoah.pi.remote

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso.pressBack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h1000dp")
class TreeFlowDesignTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun backKeepsSelectionAndNoSummaryNavigates() {
        val nodes = listOf(TreeNode("old", null, TreeNodeKind.USER, "Earlier", "", 0, true))
        val tree = SessionTree("s", "current", false, true, nodes)
        val state = RemoteState(connected = true, status = "idle", selection = RemoteSelection("h", "p", "s"),
            capabilities = setOf(SESSION_TREE_CAPABILITY, SESSION_FORK_CAPABILITY),
            session = Wire.objectOf("id" to "s", "origin" to "rpc"))
        val moves = mutableListOf<Pair<String, Boolean>>()
        var dismissed = 0
        compose.setContent { MaterialTheme {
            val scope = rememberCoroutineScope()
            SessionTreeSheet(state, scope, { TreeLoadResult.Loaded(tree) },
                { id, summary -> moves += id to summary; TreeNavigateResult.Done(TreeNavigation(id, null)) },
                {}, {}, { dismissed++ })
        } }
        compose.onNodeWithText("Earlier").performClick()
        compose.onNodeWithTag("treeContinue").performClick()
        compose.onNodeWithTag("treeSummaryStep").assertIsDisplayed()
        pressBack()
        compose.onNodeWithTag("treeSummaryStep").assertDoesNotExist()
        compose.onNodeWithTag("treeSheet").assertIsDisplayed()
        assertEquals(0, dismissed)
        assertTrue(moves.isEmpty())
        compose.onNodeWithTag("treeContinue").performClick()
        compose.onNodeWithTag("treeNoSummary").performClick()
        compose.waitForIdle()
        assertEquals(listOf("old" to false), moves)
    }

    @Test fun cancelFromSummaryDismissesTheSheet() {
        val node = TreeNode("old", null, TreeNodeKind.USER, "Earlier", "", 0, true)
        val tree = SessionTree("s", "current", false, true, listOf(node))
        val state = RemoteState(connected = true, status = "idle", selection = RemoteSelection("h", "p", "s"),
            capabilities = setOf(SESSION_TREE_CAPABILITY, SESSION_FORK_CAPABILITY),
            session = Wire.objectOf("id" to "s", "origin" to "rpc"))
        var dismissed = 0
        compose.setContent { MaterialTheme {
            val scope = rememberCoroutineScope()
            SessionTreeSheet(state, scope, { TreeLoadResult.Loaded(tree) },
                { _, _ -> TreeNavigateResult.Failed(TreeFailure.FAILED) }, {}, {}, { dismissed++ })
        } }
        compose.onNodeWithText("Earlier").performClick()
        compose.onNodeWithTag("treeContinue").performClick()
        compose.onNodeWithTag("treeSummaryCancel").performClick()
        assertEquals(1, dismissed)
    }
}
