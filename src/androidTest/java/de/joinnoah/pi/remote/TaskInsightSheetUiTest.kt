package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class TaskInsightSheetUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun pillVisibilityFollowsFreshnessAndContents() {
        var tasks by mutableStateOf(SessionTasks())
        compose.setContent { MaterialTheme { TaskInsightPill(tasks) {} } }
        compose.onNodeWithTag("taskInsightPill").assertDoesNotExist()
        tasks = SessionTasks(listOf(SessionTask(1, "First", TaskStatus.PENDING)), TaskFreshness.LIVE)
        compose.onNodeWithTag("taskInsightPill").assertExists()
        tasks = tasks.copy(freshness = TaskFreshness.STALE)
        compose.onNodeWithTag("taskInsightPill").assertExists()
        tasks = tasks.copy(freshness = TaskFreshness.UNAVAILABLE)
        compose.onNodeWithTag("taskInsightPill").assertDoesNotExist()
        tasks = tasks.copy(freshness = TaskFreshness.UNKNOWN)
        compose.onNodeWithTag("taskInsightPill").assertDoesNotExist()
    }

    @Test
    fun sheetTracksStateAndShowsMultipleActiveTasks() {
        var tasks by mutableStateOf(SessionTasks(listOf(
            SessionTask(1, "First task", TaskStatus.IN_PROGRESS, "Working first"),
            SessionTask(2, "Second task", TaskStatus.IN_PROGRESS, "Working second"),
            SessionTask(3, "Finished task", TaskStatus.COMPLETED),
        ), TaskFreshness.LIVE))
        compose.setContent { MaterialTheme { TaskInsightSheet(tasks, "Session", {}) } }
        compose.onNodeWithText("First task").assertExists()
        compose.onNodeWithText("Second task").assertExists()
        compose.onNodeWithText("Working first").assertExists()
        compose.onNodeWithText("Working second").assertExists()
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val status = resources.getString(R.string.task_insight_in_progress)
        compose.onNodeWithText(resources.getString(R.string.task_insight_id_status, 1L, status)).assertExists()
        compose.onNodeWithText(resources.getString(R.string.task_insight_id_status, 2L, status)).assertExists()
        tasks = tasks.copy(freshness = TaskFreshness.STALE)
        compose.onNodeWithText(resources.getString(R.string.task_insight_stale)).assertExists()
        compose.onNodeWithText("First task").assertExists()
        tasks = SessionTasks(freshness = TaskFreshness.UNKNOWN)
        compose.onNodeWithText(resources.getString(R.string.task_insight_unavailable)).assertExists()
        compose.onNodeWithText("First task").assertDoesNotExist()
        tasks = SessionTasks(freshness = TaskFreshness.UNAVAILABLE)
        compose.onNodeWithText("First task").assertDoesNotExist()
        compose.onNodeWithTag("taskInsightSheet").assertExists()
    }
}
