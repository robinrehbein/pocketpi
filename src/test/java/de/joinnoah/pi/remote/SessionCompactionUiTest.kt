package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionCompactionUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactionLabelAndAccessibilityStayStableAcrossSpinnerFrames() {
        val frame = mutableIntStateOf(0)
        val compacting = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                SessionListRow(
                    item = sessionListItem(
                        Wire.objectOf("id" to "s", "title" to "Session", "origin" to "rpc", "status" to "idle"),
                        connected = true, loading = false, confirmed = true,
                        compaction = if (compacting.value) SessionCompaction("manual", 100) else null, nowMillis = 200,
                    ),
                    enabled = true, closeAvailable = false,
                    onClick = {}, onClose = {}, compactionFrame = frame,
                )
            }
        }
        compose.onNodeWithText("Compacting context …").assertExists()
        compose.onNodeWithContentDescription("Session, Compacting context …").assertExists()
        compose.onNodeWithTag("sessionCompactionAvatar", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("sessionStatusAvatar", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("⠋", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { frame.intValue = 8 }
        compose.onNodeWithContentDescription("Session, Compacting context …").assertExists()
        compose.runOnIdle { compacting.value = false }
        compose.onNodeWithTag("sessionCompactionAvatar", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("sessionStatusAvatar", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Compacting context …").assertDoesNotExist()
    }

    @Test fun waitingTakesPrecedenceOverCompaction() {
        compose.setContent {
            MaterialTheme {
                SessionListRow(
                    item = sessionListItem(
                        Wire.objectOf("id" to "s", "title" to "Session", "origin" to "rpc", "status" to "waiting"),
                        connected = true, loading = false, confirmed = true,
                        compaction = SessionCompaction("threshold", 100), nowMillis = 200,
                    ),
                    enabled = true, closeAvailable = false, onClick = {}, onClose = {},
                )
            }
        }
        compose.onNodeWithText("Needs you").assertExists()
        compose.onNodeWithText("Compacting context …").assertDoesNotExist()
    }
}
