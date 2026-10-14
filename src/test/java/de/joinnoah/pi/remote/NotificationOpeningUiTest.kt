package de.joinnoah.pi.remote

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A tapped notification on start: no restore of the previous screen, and an opening state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationOpeningUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val repository = NavigationFakeRepository()
    private val lookup = CompletableDeferred<RemoteSelection?>()

    init {
        repository.notificationLookup = { lookup.await() }
    }

    private fun render(notification: RemoteNotification?) {
        compose.setContent {
            MaterialTheme {
                RemoteNavigation(repository, FakeSettingsRepository(), false, {}, notification)
            }
        }
        compose.waitForIdle()
    }

    private fun opening() = compose.activity.getString(R.string.remote_notification_opening)

    @Test
    fun aPendingNotificationSkipsTheRestoreAndShowsThatItOpens() {
        render(RemoteNotification("host", "live", 1))
        compose.onNodeWithText(opening()).assertIsDisplayed()
        assertEquals(listOf("host" to "live"), repository.notificationRequests)
        assertTrue(repository.activations.none { it.second == ActivationMode.RESTORE })

        val target = RemoteSelection("host", "project", "live")
        repository.state.value = RemoteState(selection = target)
        lookup.complete(target)
        compose.waitForIdle()
        compose.onNodeWithText(opening()).assertDoesNotExist()
    }

    @Test
    fun withoutANotificationTheStartRestores() {
        render(null)
        assertEquals(listOf(RemoteSelection() to ActivationMode.RESTORE), repository.activations)
        compose.onNodeWithText(opening()).assertDoesNotExist()
    }
}
