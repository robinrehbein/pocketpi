package de.joinnoah.pi.remote

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** First pairing: the first-run card, the wait for the Mac's approval, the code dialog and the revoked card. */
@RunWith(RobolectricTestRunner::class)
// A phone-sized window, so the hosts page's first rows are on screen below the header.
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class PairingUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val repository = NavigationFakeRepository()
    private val studio = PairedHost("studio", "wss://relay.test", "device-studio", "secret", "Studio")
    private val laptop = PairedHost("laptop", "wss://relay.test", "device-laptop", "secret", "Laptop")

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun label(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    @Before
    fun forgetTheLastMac() {
        // Two Macs and no last one: the app stays on the hosts page instead of opening a Mac.
        context().getSharedPreferences("last_route", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun render(state: RemoteState) {
        repository.state.value = state
        compose.setContent { RemoteApp(repository, FakeSettingsRepository(), false, {}) }
        compose.waitForIdle()
    }

    // ---- The wait for approval -----------------------------------------------------------

    @Test
    fun theWaitCardCountsDownFromTheDeadlineAndSaysWhenItRanOut() {
        var clock by mutableLongStateOf(0L)
        compose.setContent { MaterialTheme { PairingWaitCard(deadline = 120_000L, onCancel = {}, now = { clock }) } }
        compose.onNodeWithTag("pairingCountdown").assertTextEquals(label(R.string.remote_pairing_time_left, "2:00"))
        compose.onNodeWithText(label(R.string.remote_approval)).assertExists()

        clock = 60_500L
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("pairingCountdown").assertTextEquals(label(R.string.remote_pairing_time_left, "1:00"))

        clock = 120_000L
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("pairingCountdown").assertDoesNotExist()
        compose.onNodeWithTag("pairingTimedOut")
            .assertTextEquals(label(R.string.remote_pairing_timeout))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithTag("cancelPairing").assertExists()
    }

    @Test
    fun theCountdownRoundsUpAndNeverGoesBelowZero() {
        assertEquals(120, pairingSecondsLeft(deadline = 120_000L, now = 0L))
        assertEquals(1, pairingSecondsLeft(deadline = 120_000L, now = 119_001L))
        assertEquals(0, pairingSecondsLeft(deadline = 120_000L, now = 125_000L))
        assertEquals("2:00", pairingCountdownText(120))
        assertEquals("0:09", pairingCountdownText(9))
    }

    @Test
    fun theHostsPageShowsTheWaitWhileTheMacAsksAndCancelGivesThePairingUp() {
        render(RemoteState(hostsLoaded = true, loading = true, pairingDeadline = Diagnostics.now() + 120_000L))
        compose.onNodeWithTag("pairingWaitCard").assertExists()
        // The wait takes the first-run card's place until the Mac answers.
        compose.onNodeWithTag("firstRunCard").assertDoesNotExist()
        compose.onNodeWithTag("cancelPairing").performClick()
        assertEquals(1, repository.cancelledPairings)

        // The repository ends the wait: the first-run card is back.
        repository.state.value = RemoteState(hostsLoaded = true)
        compose.waitForIdle()
        compose.onNodeWithTag("pairingWaitCard").assertDoesNotExist()
        compose.onNodeWithTag("firstRunCard").assertExists()
    }

    @Test
    fun aPairingThatTimedOutSaysSoOnTheHostsPage() {
        render(RemoteState(hostsLoaded = true, error = R.string.remote_pairing_timeout))
        compose.onNode(hasTestTag("errorCard")).assertExists()
        compose.onNodeWithText(label(R.string.remote_pairing_timeout)).assertExists()
        compose.onNodeWithTag("pairingWaitCard").assertDoesNotExist()
    }

    // ---- First run -----------------------------------------------------------------------

    @Test
    fun theFirstRunCardNumbersTheStepsAndLabelsBothWaysToPair() {
        render(RemoteState(hostsLoaded = true))
        compose.onNodeWithTag("firstRunHeadline")
            .assertTextEquals(label(R.string.remote_welcome))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        for (step in listOf(R.string.remote_onboarding_step_1, R.string.remote_onboarding_step_2, R.string.remote_onboarding_step_3))
            compose.onNodeWithText(label(step), substring = true).assertExists()
        compose.onNodeWithTag("firstRunScan").assert(hasText(label(R.string.remote_onboarding_scan)))
        compose.onNodeWithTag("firstRunPaste").assert(hasText(label(R.string.remote_onboarding_paste)))
        // The card carries both ways: no icon-only button and no bar below it.
        compose.onNodeWithTag("scanQrButton").assertDoesNotExist()
        compose.onNodeWithTag("pasteCodeButton").assertDoesNotExist()

        compose.onNodeWithTag("firstRunSetup").performClick()
        compose.onNodeWithTag("setupMacHelp").assertExists()
        compose.onNodeWithText(label(R.string.remote_onboarding_setup_body)).assertExists()
        compose.onNodeWithText(label(R.string.remote_onboarding_setup_done)).performClick()
        compose.onNodeWithTag("setupMacHelp").assertDoesNotExist()

        compose.onNodeWithTag("firstRunScan").performClick()
        compose.onNodeWithTag("cameraMessage").assertExists()
    }

    @Test
    fun theFirstRunCardWaitsForThePairingsToBeRead() {
        render(RemoteState())
        compose.onNodeWithTag("firstRunCard").assertDoesNotExist()
        repository.state.value = RemoteState(hostsLoaded = true)
        compose.waitForIdle()
        compose.onNodeWithTag("firstRunCard").assertExists()
    }

    @Test
    fun withAMacPairedTheButtonsNameTheirAction() {
        render(RemoteState(hosts = listOf(studio, laptop), hostsLoaded = true))
        compose.onNodeWithTag("firstRunCard").assertDoesNotExist()
        compose.onNodeWithTag("scanQrButton").assert(hasText(label(R.string.remote_onboarding_scan)))
        compose.onNodeWithTag("pasteCodeButton").assert(hasText(label(R.string.remote_onboarding_paste)))
        compose.onNodeWithTag("scanQrButton").performClick()
        compose.onNodeWithTag("cameraMessage").assertExists()
    }

    // ---- A phone the Mac removed ---------------------------------------------------------

    @Test
    fun theRevokedCardOffersToPairAgain() {
        render(
            RemoteState(
                hosts = listOf(studio, laptop),
                hostsLoaded = true,
                error = R.string.remote_device_revoked,
                deniedRouteId = "studio",
            )
        )
        compose.onNodeWithTag("removePairing").assertExists()
        compose.onNodeWithTag("pairAgain").assert(hasText(label(R.string.remote_pair_again))).performClick()
        compose.onNodeWithTag("cameraMessage").assertExists()
    }

    @Test
    fun pairAgainOnAProjectListOpensTheScanner() {
        LastRouteStore(context()).save("studio")
        render(
            RemoteState(
                hosts = listOf(studio, laptop),
                hostsLoaded = true,
                host = studio,
                selection = RemoteSelection("studio"),
                error = R.string.remote_device_revoked,
                deniedRouteId = "studio",
                // The Mac already asks its user: the wait shows on the hosts page only.
                pairingDeadline = Diagnostics.now() + 120_000L,
            )
        )
        // The cold start opened the studio's projects, not the hosts page.
        compose.runOnIdle { assertEquals(RemoteSelection("studio"), repository.activations.last().first) }
        compose.onNodeWithTag("pairingWaitCard").assertDoesNotExist()
        compose.onNodeWithTag("pairAgain").performClick()
        compose.onNodeWithTag("cameraMessage").assertExists()
        // The code dialog behind Paste is PairingCodeDialogTest's: Robolectric never settles with its
        // text field over the whole app. Where the pairing then goes is the next test's.
    }

    /** A pairing started on a project list or in a chat moves to the hosts page, where PairingWaitCard shows. */
    @Test
    fun aPairingStartedAwayFromTheHostsPageMovesThere() = runTest {
        for (from in listOf(
            listOf(RemoteNavKey.Hosts, RemoteNavKey.Projects("studio")),
            listOf(
                RemoteNavKey.Hosts,
                RemoteNavKey.Projects("studio"),
                RemoteNavKey.Sessions("studio", "project"),
                RemoteNavKey.Chat("studio", "project", "session"),
            ),
        )) {
            val stack = from.toMutableList<NavKey>()
            val navigator = RemoteNavigator(repository, stack, backgroundScope)
            navigator.pair("code")
            runCurrent()
            assertEquals(listOf<NavKey>(RemoteNavKey.Hosts), stack)
        }
        // A pairing from the hosts page stays there.
        val stack = mutableListOf<NavKey>(RemoteNavKey.Hosts)
        RemoteNavigator(repository, stack, backgroundScope).pair("code")
        runCurrent()
        assertEquals(listOf<NavKey>(RemoteNavKey.Hosts), stack)
    }

    @Test
    fun otherErrorsDoNotOfferToPairAgain() {
        render(RemoteState(hosts = listOf(studio, laptop), hostsLoaded = true, error = R.string.remote_connection_error))
        compose.onNodeWithTag("errorCard").assertExists()
        compose.onNodeWithTag("pairAgain").assertDoesNotExist()
    }
}
