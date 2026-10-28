package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionListWorkerUiTest {
    @get:Rule val compose = createComposeRule()

    // The laptop icon has no description of its own, so the row text stands in for it.
    @Test
    fun offlineHostWorkerRowSaysStartedByTheMacAndNotRunningInTheTerminal() {
        val item =
            SessionListItem(
                id = "worker",
                title = "Nightly checks",
                parentSessionId = null,
                depth = 0,
                hasChildren = false,
                availability = SessionAvailability.OFFLINE,
                preview = null,
                updatedAt = null,
                continuesAsCopy = false,
                daemonOwned = false,
                liveMacTui = false,
                hostWorker = true,
            )
        compose.setContent {
            MaterialTheme {
                SessionListRow(item = item, enabled = true, closeAvailable = true, onClick = {}, onClose = {})
            }
        }
        compose.onNodeWithText("Started by the Mac", substring = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Running in the Mac terminal", substring = true, useUnmergedTree = true)
            .assertDoesNotExist()
    }
}
