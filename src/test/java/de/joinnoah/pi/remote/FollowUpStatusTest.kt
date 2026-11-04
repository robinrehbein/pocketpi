package de.joinnoah.pi.remote

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FollowUpStatusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun confirmedDeliveryDisappearsAndIsDismissedAutomatically() {
        val state = mutableStateOf(RemoteState(followUps = listOf(PendingFollowUp("one", "My queued message"))))
        val dismissed = mutableListOf<String>()
        compose.setContent { MaterialTheme { FollowUpStatus(state.value, dismissed::add) } }
        compose.onNodeWithText("My queued message", substring = true).assertExists()
        compose.runOnIdle { state.value = state.value.copy(followUps = listOf(PendingFollowUp("one", "My queued message", "delivered"))) }
        compose.onNodeWithTag("followUpJournal").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("one"), dismissed) }
    }

    @Test fun deliveryCleanupWaitsForLoadingToFinish() {
        val state = mutableStateOf(RemoteState(loading = true,
            followUps = listOf(PendingFollowUp("one", "Confirmed message", "delivered"))))
        val dismissed = mutableListOf<String>()
        compose.setContent { MaterialTheme { FollowUpStatus(state.value, dismissed::add) } }
        compose.runOnIdle { assertEquals(emptyList<String>(), dismissed) }
        compose.onNodeWithTag("followUpJournal").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(loading = false) }
        compose.runOnIdle { assertEquals(listOf("one"), dismissed) }
    }

    @Test fun uncertainDeliveryStaysVisibleAndCanBeDismissed() {
        val dismissed = mutableListOf<String>()
        compose.setContent { MaterialTheme {
            FollowUpStatus(RemoteState(followUps = listOf(PendingFollowUp("one", "Check delivery", "uncertain"))), dismissed::add)
        } }
        compose.onNodeWithText("Check delivery", substring = true).assertExists()
        compose.onNodeWithTag("dismissFollowUp-one").performClick()
        compose.runOnIdle { assertEquals(listOf("one"), dismissed) }
    }
}
