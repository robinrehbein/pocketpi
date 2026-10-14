package de.joinnoah.pi.remote

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a launch with a target does instead of the cold start on the last Mac, and what a recreated
 * activity keeps: the stack it was saved with, not a second cold start.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h1600dp")
class MacHubLaunchUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val repository = NavigationFakeRepository()
    private val settings = FakeSettingsRepository()
    private val studio = PairedHost("studio", "wss://relay.example", "device-studio", "secret", "Studio")
    private val laptop = PairedHost("laptop", "wss://relay.example", "device-laptop", "secret", "Laptop")

    @Before
    fun storeStudioAsLastMac() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("last_route", Context.MODE_PRIVATE).edit().clear().commit()
        LastRouteStore(context).save("studio")
        repository.state.value =
            RemoteState(
                hosts = listOf(studio, laptop),
                hostsLoaded = true,
                host = studio,
                selection = RemoteSelection("studio"),
                connected = true,
                connection = R.string.remote_connected,
            )
    }

    @Composable
    private fun App(
        notification: RemoteNotification? = null,
        share: PendingShare? = null,
        consumeNotification: (RemoteNotification) -> Unit = {},
    ) {
        MaterialTheme {
            RemoteNavigation(
                repository = repository,
                settings = settings,
                pushConfigured = false,
                enablePush = {},
                notification = notification,
                consumeNotification = consumeNotification,
                share = share,
            )
        }
    }

    /** The Macs the navigation activated, in order. */
    private fun openedMacs(): List<String> = repository.activations.mapNotNull { it.first.routeId }

    @Test
    fun aNotificationOpensItsOwnSessionInsteadOfTheLastMac() {
        repository.notificationLookup = { RemoteSelection("laptop", "project", "s1") }
        val notification = RemoteNotification("laptop", "s1", 1)
        var consumed: RemoteNotification? = null

        compose.setContent { App(notification = notification, consumeNotification = { consumed = it }) }
        compose.waitForIdle()

        // The notification was opened (and so consumed), and the stored Mac was not opened instead.
        compose.runOnIdle {
            assertEquals(notification, consumed)
            assertFalse("studio" in openedMacs())
        }
        compose.onAllNodesWithTag("projectsMacHeading").assertCountEquals(0)
    }

    @Test
    fun aShareTakesItsTargetInsteadOfTheLastMac() {
        val share = PendingShare(ShareRequest(SharedContent("Shared"), ShareTarget.Pick), delivery = 1L)

        compose.setContent { App(share = share) }
        compose.waitForIdle()

        compose.runOnIdle { assertFalse("studio" in openedMacs()) }
        compose.onAllNodesWithTag("projectsMacHeading").assertCountEquals(0)
    }

    @Test
    fun aRecreatedActivityKeepsItsStackAndDoesNotColdStartAgain() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { App() }
        compose.waitForIdle()
        // The cold start opened the stored Mac's Projects, above the hosts list.
        compose.onNodeWithTag("projectsMacHeading").assertIsDisplayed()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onAllNodesWithTag("projectsMacHeading").assertCountEquals(0)
        val studioOpens = openedMacs().count { it == "studio" }
        assertEquals(1, studioOpens)

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        // Back on the hosts list stays there: the cold start is not run again on the restored activity.
        compose.onAllNodesWithTag("projectsMacHeading").assertCountEquals(0)
        assertEquals(studioOpens, openedMacs().count { it == "studio" })
    }
}
