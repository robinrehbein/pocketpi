package de.joinnoah.pi.remote

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AdvisorControlUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun controls(
        state: RemoteState,
        refresh: () -> Unit = {},
        set: (String?, String?, String?) -> Unit = { _, _, _ -> },
        currentState: () -> RemoteState = { state },
    ) {
        compose.setContent {
            MaterialTheme {
                SessionControls(
                    state = currentState(),
                    refreshConfiguration = {}, refreshContextUsage = {}, compactContext = {}, refreshAdvisor = refresh,
                    setAdvisor = set, setModel = { _, _ -> }, setThinking = {},
                    refreshCommands = {}, selectCommand = {},
                )
            }
        }
    }

    @Test
    fun missingHostSupportKeepsAnEntryWithAnExplanation() {
        controls(RemoteState(connected = true))
        compose.onNodeWithTag("advisorControl").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.remote_advisor_host_unsupported))
            .assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.remote_advisor_apply)).assertDoesNotExist()
    }

    @Test
    fun unsupportedSessionExplainsExtensionAndRecoversAfterRetry() {
        var state by mutableStateOf(RemoteState(connected = true, status = "idle",
            selection = RemoteSelection("host", "project", "session"),
            capabilities = setOf(ADVISOR_CAPABILITY),
            unavailableCapabilities = setOf(ADVISOR_CAPABILITY)))
        var refreshes = 0
        var applied: Triple<String?, String?, String?>? = null
        controls(state, currentState = { state }, refresh = {
            refreshes++
            if (refreshes > 1) state = state.copy(advisorLoading = true)
        }, set = { provider, id, level -> applied = Triple(provider, id, level) })
        compose.onNodeWithTag("advisorControl").performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.remote_advisor_extension_required))
            .assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.remote_advisor_retry)).performClick()
        assertEquals(2, refreshes)
        compose.onNodeWithText(compose.activity.getString(R.string.remote_advisor_retry)).assertDoesNotExist()
        compose.runOnIdle {
            state = state.copy(advisorLoading = false, unavailableCapabilities = emptySet(),
                advisor = SessionAdvisor("session", false, null, "high", false, false,
                    0, 3, 3, null, listOf(AdvisorChoice("provider", "model", "Advisor model", listOf("high")))))
        }
        compose.onNodeWithText("Advisor model").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.remote_advisor_apply)).performClick()
        assertEquals(Triple("provider", "model", "high"), applied)
    }

    @Test
    fun disconnectedSessionKeepsAnEntryWithConnectionGuidance() {
        controls(RemoteState(connected = false, capabilities = setOf(ADVISOR_CAPABILITY)))
        compose.onNodeWithText(compose.activity.getString(R.string.remote_advisor_unavailable_label))
            .performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.remote_advisor_disconnected)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.remote_advisor_retry)).assertDoesNotExist()
    }

    @Test
    fun disabledAdvisorCanBeConfiguredAndEnabled() {
        val choice = AdvisorChoice("provider", "model", "Advisor model", listOf("high"))
        val advisor = SessionAdvisor("session", false, null, "high", false, false,
            0, 3, 3, null, listOf(choice))
        var applied: Triple<String?, String?, String?>? = null
        controls(RemoteState(connected = true, status = "idle",
            selection = RemoteSelection("host", "project", "session"),
            capabilities = setOf(ADVISOR_CAPABILITY), advisor = advisor),
            set = { provider, id, level -> applied = Triple(provider, id, level) })
        compose.onNodeWithTag("advisorControl").performScrollTo().performClick()
        compose.onNodeWithTag("advisorProviderHeader-provider").assertExists()
        compose.onNodeWithText("Advisor model").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.remote_advisor_apply)).performClick()
        assertEquals(Triple("provider", "model", "high"), applied)
    }

    @Test
    fun groupedPickerKeepsHeadingsLabelsSelectionAndCurrentMarker() {
        val gpt = AdvisorChoice("openai", "gpt", "OpenAI: GPT-6", listOf("high"))
        val routed = AdvisorChoice("openrouter", "or-gpt", "OpenAI: GPT-6", listOf("high"))
        val claude = AdvisorChoice("anthropic", "claude", "Claude Opus", listOf("high"))
        val advisor = SessionAdvisor("session", true, "openai/gpt", "high", false, false,
            0, 3, 3, null, listOf(gpt, claude, routed))
        var applied: Triple<String?, String?, String?>? = null
        controls(RemoteState(connected = true, status = "idle",
            selection = RemoteSelection("host", "project", "session"),
            capabilities = setOf(ADVISOR_CAPABILITY), advisor = advisor),
            set = { provider, id, level -> applied = Triple(provider, id, level) })
        compose.onNodeWithTag("advisorControl").performScrollTo().performClick()
        listOf("openai", "anthropic", "openrouter").forEach {
            compose.onNodeWithTag("advisorPickerList").performScrollToNode(hasTestTag("advisorProviderHeader-$it"))
            compose.onNodeWithTag("advisorProviderHeader-$it").assertExists().assert(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        }
        // Under the OpenAI group the repeated "OpenAI" label is hidden: only the header carries the text.
        compose.onNodeWithTag("advisorPickerList").performScrollToNode(hasContentDescription("OpenAI, GPT-6"))
        compose.onNodeWithText("OpenAI").assertExists()
        compose.onNodeWithContentDescription("OpenAI, GPT-6").assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription))
        compose.onNodeWithTag("advisorPickerList").performScrollToNode(hasContentDescription("OpenRouter, OpenAI, GPT-6"))
        // Under OpenRouter the maker label stays.
        compose.onNodeWithText("OpenAI").assertExists()
        compose.onNodeWithContentDescription("OpenRouter, OpenAI, GPT-6").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.remote_advisor_apply)).performClick()
        assertEquals(Triple("openrouter", "or-gpt", "high"), applied)
    }
}
