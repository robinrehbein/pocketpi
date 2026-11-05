package de.joinnoah.pi.remote

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The "Context full" offer on the real chat screen: its memory outlives the chat's nav entry, only
 * the user's own typing dismisses it, and TalkBack hears it once per level.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class ContextOfferScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val repository = NavigationFakeRepository()
    private val settings = FakeSettingsRepository()
    private val chat = RemoteSelection("host", "project", "s1")

    init {
        val session = buildJsonObject {
            put("id", "s1")
            put("title", "First session")
            put("status", "idle")
            put("origin", "rpc")
        }
        val project = buildJsonObject {
            put("id", "project")
            put("name", "noah-monorepo")
        }
        repository.state.value =
            RemoteState(
                connected = true,
                connection = R.string.remote_connected,
                status = "idle",
                projects = listOf(project),
                project = project,
                sessions = listOf(session),
                session = session,
                capabilities = setOf(COMPACT_CAPABILITY),
                contextUsage = SessionContextUsage("s1", null, null, 160_000, 200_000, 80.0),
            )
        repository.notificationLookup = {
            repository.state.value = repository.state.value.copy(selection = chat)
            chat
        }
    }

    private fun render() {
        compose.setContent {
            val configuration =
                Configuration(LocalConfiguration.current).apply {
                    keyboard = Configuration.KEYBOARD_NOKEYS
                    hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_YES
                }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                MaterialTheme {
                    RemoteNavigation(repository, settings, false, {}, RemoteNotification("host", "s1", 1))
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("composerField").assertExists()
    }

    private val offerLine get() = compose.activity.getString(R.string.remote_context_full_title, 80)

    /** On a phone the offer waits behind the status chip above the composer. */
    private fun offerChips() = compose.onAllNodesWithText(offerLine)

    private fun dismissFromSheet() {
        compose.onNodeWithTag("chatStatusChip").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("contextOfferDismiss").performClick()
        compose.waitForIdle()
    }

    @Test
    fun notNowHoldsAfterLeavingTheChatAndOpeningItAgain() {
        render()
        offerChips().assertCountEquals(1)
        dismissFromSheet()
        offerChips().assertCountEquals(0)

        // Back to the session list pops the chat's entry and its ViewModel with it.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(RemoteSelection("host", "project"), repository.state.value.selection)
        compose.onAllNodesWithText("First session").onFirst().performClick()
        compose.waitForIdle()
        assertEquals(chat, repository.state.value.selection)
        compose.onNodeWithTag("composerField").assertExists()
        offerChips().assertCountEquals(0)

        // The second level still offers.
        repository.state.value =
            repository.state.value.copy(contextUsage = SessionContextUsage("s1", null, null, 186_000, 200_000, 93.0))
        compose.waitForIdle()
        compose.onAllNodesWithText(compose.activity.getString(R.string.remote_context_full_title, 93))
            .assertCountEquals(1)
    }

    @Test
    fun onlyTheUsersOwnTypingWhileTheOfferShowsDismissesIt() {
        render()
        offerChips().assertCountEquals(1)

        // A draft from the Mac hides the offer but is no "Not now".
        repository.state.value = repository.state.value.copy(draft = "Draft from the Mac")
        compose.waitForIdle()
        offerChips().assertCountEquals(0)
        repository.state.value = repository.state.value.copy(draft = "")
        compose.waitForIdle()
        offerChips().assertCountEquals(1)

        // Typing while it shows is.
        compose.onNodeWithTag("composerField").performTextInput("Next step")
        compose.waitForIdle()
        offerChips().assertCountEquals(0)
        compose.mainClock.advanceTimeBy(COMPOSER_DRAFT_DEBOUNCE_MILLIS * 2)
        repository.state.value = repository.state.value.copy(draft = "")
        compose.waitForIdle()
        offerChips().assertCountEquals(0)
    }

    @Test
    fun typingWhileTheOfferIsHiddenKeepsIt() {
        // The usage read after the turn has not finished: no offer yet.
        repository.state.value = repository.state.value.copy(contextUsageStale = true)
        render()
        offerChips().assertCountEquals(0)
        compose.onNodeWithTag("composerField").performTextInput("x")
        compose.mainClock.advanceTimeBy(COMPOSER_DRAFT_DEBOUNCE_MILLIS * 2)
        compose.waitForIdle()
        // The user clears the text; the fresh read then brings the offer.
        repository.state.value = repository.state.value.copy(draft = "", contextUsageStale = false)
        compose.waitForIdle()
        offerChips().assertCountEquals(1)
    }

    @Test
    fun theOfferIsReadOutOncePerLevel() {
        val liveRegion = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        compose.mainClock.autoAdvance = false
        render()
        compose.onNodeWithTag("chatStatusChip").assertTextContains(offerLine).assert(liveRegion)
        // Once announced, the memory says so and the screen withdraws the live region.
        compose.mainClock.advanceTimeBy(CONTEXT_OFFER_ANNOUNCE_MILLIS + 100)
        compose.waitForIdle()
        compose.onNodeWithTag("chatStatusChip").assertTextContains(offerLine)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))

        // A turn hides it; when it returns at the same level it stays quiet.
        repository.state.value = repository.state.value.copy(status = "running")
        compose.mainClock.advanceTimeBy(100)
        compose.onAllNodesWithTag("chatStatusChip").assertCountEquals(0)
        repository.state.value = repository.state.value.copy(status = "idle")
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("chatStatusChip").assertTextContains(offerLine)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
    }
}
