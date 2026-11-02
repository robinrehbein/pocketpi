package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TaskInsightPillUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun statusIconDistinguishesPendingMixedCompleteActiveAndStale() {
        assertEquals(Icons.Outlined.RadioButtonUnchecked, taskInsightStatusIcon(false, 0, 0, 1))
        assertEquals(Icons.Outlined.RadioButtonUnchecked, taskInsightStatusIcon(false, 0, 1, 2))
        assertEquals(Icons.Default.CheckCircle, taskInsightStatusIcon(false, 0, 2, 2))
        assertEquals(Icons.Default.PlayCircle, taskInsightStatusIcon(false, 1, 0, 1))
        assertEquals(Icons.Default.Warning, taskInsightStatusIcon(true, 1, 0, 1))
    }

    @Test
    fun pillExpandsWithLargeFontsAndKeepsItsTouchTarget() {
        val fontScale = mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) {
                MaterialTheme {
                    TaskInsightPill(
                        SessionTasks(listOf(SessionTask(1, "Task", TaskStatus.IN_PROGRESS)), TaskFreshness.LIVE),
                        onClick = {},
                    )
                }
            }
        }
        val pill = compose.onNodeWithTag("taskInsightPill")
        val normalHeight = pill.fetchSemanticsNode().boundsInRoot.height
        compose.runOnIdle { fontScale.value = 3f }
        val largeHeight = pill.fetchSemanticsNode().boundsInRoot.height
        assertTrue("Large text must increase the pill height", largeHeight > normalHeight)
        pill.assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun pillMatchesTouchedFilesHeightForEveryStateAndFontScale() {
        val fontScale = mutableStateOf(1f)
        val status = mutableStateOf(TaskStatus.COMPLETED)
        val freshness = mutableStateOf(TaskFreshness.LIVE)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) {
                MaterialTheme {
                    Column {
                        TaskInsightPill(SessionTasks(listOf(SessionTask(1, "Task", status.value)), freshness.value), onClick = {})
                        TouchedFilesSummary(TouchedFiles(listOf(TouchedFile("file", 1, "item")), emptyList()), null, onClick = {})
                    }
                }
            }
        }
        for (scale in listOf(1f, 3f)) {
            for (state in listOf(TaskStatus.COMPLETED, TaskStatus.IN_PROGRESS)) {
                for (fresh in listOf(TaskFreshness.LIVE, TaskFreshness.STALE)) {
                    compose.runOnIdle { fontScale.value = scale; status.value = state; freshness.value = fresh }
                    val taskHeight = compose.onNodeWithTag("taskInsightPill").fetchSemanticsNode().boundsInRoot.height
                    val filesHeight = compose.onNodeWithTag("touchedFilesSummary").fetchSemanticsNode().boundsInRoot.height
                    assertEquals("Pill heights for $state, $fresh at font scale $scale", filesHeight, taskHeight, 0.01f)
                }
            }
        }
    }

    @Test
    fun pillExposesButtonDescriptionAndStatusIcon() {
        val status = mutableStateOf(TaskStatus.IN_PROGRESS)
        val freshness = mutableStateOf(TaskFreshness.LIVE)
        var activeDescription = ""
        var staleDescription = ""
        var completeDescription = ""
        compose.setContent {
            activeDescription = stringResource(R.string.task_insight_description, 0, 1, 1)
            staleDescription = activeDescription + ", " + stringResource(R.string.task_insight_stale)
            completeDescription = stringResource(R.string.task_insight_description, 1, 1, 0)
            MaterialTheme {
                TaskInsightPill(SessionTasks(listOf(SessionTask(1, "Task", status.value)), freshness.value), onClick = {})
            }
        }
        val pill = compose.onNodeWithTag("taskInsightPill")
        pill.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHasClickAction()
            .assertContentDescriptionEquals(activeDescription)
        compose.onNodeWithTag("taskInsightStatusIcon", useUnmergedTree = true).assertExists()
        compose.runOnIdle { freshness.value = TaskFreshness.STALE }
        pill.assertContentDescriptionEquals(staleDescription)
        compose.onNodeWithTag("taskInsightStatusIcon", useUnmergedTree = true).assertExists()
        compose.runOnIdle { freshness.value = TaskFreshness.LIVE; status.value = TaskStatus.COMPLETED }
        pill.assertContentDescriptionEquals(completeDescription)
        compose.onNodeWithTag("taskInsightStatusIcon", useUnmergedTree = true).assertExists()
    }

    @Test
    fun pillStaysCompactAndOpensItsSheet() {
        var clicks = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    TaskInsightPill(
                        SessionTasks(listOf(SessionTask(1, "Task", TaskStatus.IN_PROGRESS)), TaskFreshness.LIVE),
                        onClick = { clicks++ },
                    )
                }
            }
        }
        val pill = compose.onNodeWithTag("taskInsightPill")
        pill.assertHeightIsAtLeast(48.dp)
        val bounds = pill.fetchSemanticsNode().boundsInRoot
        with(compose.density) { assertTrue(bounds.width < 160.dp.toPx()) }
        pill.performClick()
        assertEquals(1, clicks)
    }
}
