package de.joinnoah.pi.remote

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScheduledActivityUiTest {
    @get:Rule val compose = createComposeRule()

    private val item = ScheduledActivityItem(
        id = "check-a", kind = ScheduledActivityKind.WAKEUP,
        phase = ScheduledActivityPhase.AFTER_TURN, purpose = "Observe CI", delayMs = 180_000,
    )

    @Test fun absentScheduleHasNoPill() {
        compose.setContent { MaterialTheme { ScheduledActivityPill(ScheduledActivityPresentation()) {} } }
        compose.onNodeWithTag("scheduledActivityPill").assertDoesNotExist()
    }

    @Test fun smallPillLabelIsVisibleInScrollingRow() = assertScrollingPill(PillSize.SMALL, 1f)

    @Test fun largePillLabelIsVisibleInScrollingRow() = assertScrollingPill(PillSize.LARGE, 1f)

    @Test fun smallPillLabelIsVisibleInScrollingRowAtLargeFont() = assertScrollingPill(PillSize.SMALL, 1.5f)

    @Test fun largePillLabelIsVisibleInScrollingRowAtLargeFont() = assertScrollingPill(PillSize.LARGE, 1.5f)

    private fun assertScrollingPill(size: PillSize, fontScale: Float) {
        var opened = 0
        var label = ""
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalInsightPillSize provides size,
                LocalDensity provides Density(density.density, fontScale),
            ) {
                MaterialTheme {
                    label = stringResource(R.string.scheduled_activity_after_turn)
                    Box(Modifier.width(320.dp)) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                            ScheduledActivityPill(ScheduledActivityPresentation(listOf(item))) { opened++ }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val text = compose.onNodeWithText(label, useUnmergedTree = true)
        text.assertIsDisplayed()
        assertTrue("The label must have a positive measured width", text.fetchSemanticsNode().boundsInRoot.width > 0f)
        compose.onNodeWithTag("scheduledActivityPill").assertIsDisplayed().performClick()
        assertEquals(1, opened)
    }

    @Test fun stopTargetsOnlySelectedEntry() {
        val stopped = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                ScheduledActivitySheet(
                    ScheduledActivityPresentation(listOf(item, item.copy(id = "check-b", purpose = "Watch deployment"))),
                    sessionTitle = "Project", onStop = stopped::add, onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("scheduledActivityStop:check-b").performClick()
        assertEquals(listOf("check-b"), stopped)
        // Clicking does not optimistically remove the host-owned entry.
        compose.onNodeWithText("Watch deployment").assertIsDisplayed()
    }

    @Test fun staleEntriesRemainVisibleButCannotBeStopped() {
        compose.setContent {
            MaterialTheme {
                ScheduledActivitySheet(
                    ScheduledActivityPresentation(listOf(item), stale = true),
                    sessionTitle = null, onStop = { error("Stale action dispatched") }, onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("Observe CI").assertIsDisplayed()
        compose.onNodeWithTag("scheduledActivityStop:check-a").assertIsNotEnabled()
    }

    @Test fun pendingStopCannotBeSubmittedTwice() {
        compose.setContent {
            MaterialTheme {
                ScheduledActivitySheet(
                    ScheduledActivityPresentation(listOf(item), stoppingIds = setOf("check-a")),
                    sessionTitle = null, onStop = { error("Duplicate action dispatched") }, onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("scheduledActivityStop:check-a").assertIsNotEnabled()
        compose.onNodeWithText("Observe CI").assertIsDisplayed()
    }
}
