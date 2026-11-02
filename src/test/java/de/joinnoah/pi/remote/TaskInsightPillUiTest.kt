package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
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
        pill.assertHeightIsAtLeast(32.dp)
        val bounds = pill.fetchSemanticsNode().boundsInRoot
        with(compose.density) { assertTrue(bounds.width < 160.dp.toPx()) }
        pill.performClick()
        assertEquals(1, clicks)
    }
}
