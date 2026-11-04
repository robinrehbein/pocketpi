package de.joinnoah.pi.remote

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the real compact chat row, including the other insights rendered after the status chip. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class TaskInsightRowUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun tasksAppearOnceBeforeCompactStatusAndDisappearWhenEmpty() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val host = PairedHost("host", "wss://relay.test", "device", "secret", "Studio Mac")
        val project = Wire.objectOf("id" to "project", "name" to "noah-monorepo")
        val session = Wire.objectOf("id" to "s1", "title" to "First session", "status" to "idle", "origin" to "rpc")
        val repository = NavigationFakeRepository()
        val listed = RemoteState(
            hosts = listOf(host), hostsLoaded = true, host = host, connected = true,
            connection = R.string.remote_connected, projects = listOf(project), project = project,
            sessions = listOf(session),
        )
        repository.state.value = listed
        compose.setContent { RemoteApp(repository, FakeSettingsRepository(), false, {}) }
        compose.onNodeWithText("Studio Mac").performClick()
        compose.onNodeWithText("noah-monorepo").performClick()
        compose.onNodeWithText("First session").performClick()
        compose.runOnIdle {
            repository.state.value = listed.copy(
                selection = RemoteSelection("host", "project", "s1"), session = session, status = "running",
                tasks = SessionTasks(listOf(SessionTask(1, "Task", TaskStatus.IN_PROGRESS)), TaskFreshness.LIVE),
                compaction = SessionCompaction("threshold", System.currentTimeMillis()),
            )
        }

        val inRow = hasAnyAncestor(hasTestTag("chatInsights"))
        val status = compose.onNode(hasText(context.getString(R.string.remote_panel_compacting)) and inRow)
        val task = compose.onNode(hasTestTag("taskInsightPill") and inRow)
        compose.onAllNodesWithTag("taskInsightPill").assertCountEquals(1)
        task.assertIsDisplayed()
        status.assertIsDisplayed()
        val activity = compose.onNodeWithTag("chatCurrentActivity")
        val insights = compose.onNodeWithTag("chatInsights")
        compose.onAllNodesWithTag("chatCurrentActivity").assertCountEquals(1)
        activity.assertIsDisplayed()
        insights.assertIsDisplayed()
        val footerBounds = compose.onNodeWithTag("floatingComposer").fetchSemanticsNode().boundsInRoot
        val insightBounds = insights.fetchSemanticsNode().boundsInRoot
        assertTrue("Insights viewport must span the footer", kotlin.math.abs(footerBounds.width - insightBounds.width) < 1f)
        assertTrue("Activity must be above insights", activity.fetchSemanticsNode().boundsInRoot.bottom <= insights.fetchSemanticsNode().boundsInRoot.top)
        assertTrue("Tasks must precede the compact status chip", task.fetchSemanticsNode().boundsInRoot.right <= status.fetchSemanticsNode().boundsInRoot.left)

        compose.runOnIdle { repository.state.value = repository.state.value.copy(tasks = SessionTasks()) }
        compose.onAllNodesWithTag("taskInsightPill").assertCountEquals(0)
        status.assertIsDisplayed()
        compose.runOnIdle { repository.state.value = repository.state.value.copy(compaction = null) }
        compose.onNodeWithTag("chatInsights").assertDoesNotExist()
        compose.onAllNodesWithTag("chatCurrentActivity").assertCountEquals(1)
        activity.assertIsDisplayed()
    }
}
