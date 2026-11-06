package de.joinnoah.pi.remote

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HandoverBannerUiTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    @Test
    fun theOfferLeadsWithNewChatWithSummaryBesideCompact() {
        var handovers = 0
        compose.setContent {
            MaterialTheme {
                ContextOfferBanner(
                    ContextOffer(CONTEXT_OFFER_PERCENT, 82, setOf(ContextOfferAction.COMPACT, ContextOfferAction.HANDOVER)),
                    announce = false,
                    onCompact = {},
                    onDismiss = {},
                    onHandover = { handovers++ },
                )
            }
        }
        compose.onNodeWithTag("contextOfferText").assertTextContains(context.getString(R.string.remote_context_full_body_both))
        compose.onNodeWithTag("contextOfferHandover").assertTextEquals(context.getString(R.string.remote_handover_action))
            .assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("contextOfferCompact").assertHeightIsAtLeast(48.dp)
        assertEquals(1, handovers)
    }

    @Test
    fun progressIsAPoliteLiveRegionThroughBothSteps() {
        val handover = mutableStateOf<SessionHandover?>(null)
        val requesting = mutableStateOf(true)
        compose.setContent {
            MaterialTheme { HandoverBanner(handover.value, requesting.value, {}, null, {}, {}) }
        }
        compose.onNodeWithTag("handoverBanner").assert(polite)
        compose.onNodeWithTag("handoverProgress").assertTextContains(context.getString(R.string.remote_handover_summarizing))
        requesting.value = false
        handover.value = SessionHandover("h", HandoverState.SWITCHING, live = true)
        compose.onNodeWithTag("handoverProgress").assertTextContains(context.getString(R.string.remote_handover_switching))
        handover.value = SessionHandover("h", HandoverState.DONE, "new", live = true)
        // The screen does not pass a live done; one from the snapshot offers the new chat.
        handover.value = SessionHandover("h", HandoverState.DONE, "new")
        compose.onNodeWithTag("handoverOutcome").assertTextContains(context.getString(R.string.remote_handover_continued))
        compose.onNodeWithTag("handoverAction").assertTextEquals(context.getString(R.string.remote_handover_open_new))
        handover.value = null
        compose.onAllNodesWithTag("handoverBanner").assertCountEquals(0)
    }

    @Test
    fun tooLargeOffersCompactInsteadAndTimeoutOnlyExplains() {
        val handover = mutableStateOf(SessionHandover("h", HandoverState.FAILED, error = HandoverError.TOO_LARGE, live = true))
        var compacts = 0
        var retries = 0
        var dismissals = 0
        compose.setContent {
            MaterialTheme {
                HandoverBanner(handover.value, false, onRetry = { retries++ }, onCompact = { compacts++ }, onOpenTarget = {}, onDismiss = { dismissals++ })
            }
        }
        compose.onNodeWithTag("handoverBanner").assert(polite)
        compose.onNodeWithTag("handoverOutcome").assertTextContains(context.getString(R.string.remote_handover_failed_too_large))
        compose.onNodeWithTag("handoverAction").assertTextEquals(context.getString(R.string.remote_compact_now_action))
            .assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, compacts)

        handover.value = handover.value.copy(error = HandoverError.CANCELLED)
        compose.onNodeWithTag("handoverAction").assertTextEquals(context.getString(R.string.remote_handover_retry)).performClick()
        assertEquals(1, retries)

        handover.value = handover.value.copy(error = HandoverError.TIMEOUT)
        compose.onNodeWithTag("handoverOutcome").assertTextContains(context.getString(R.string.remote_handover_timeout_title))
        compose.onAllNodesWithTag("handoverAction").assertCountEquals(0)
        compose.onNodeWithTag("handoverDismiss").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, dismissals)
    }

    @Test
    fun theSummaryCardOpensItsMarkdownAndLeadsBack() {
        var opened = 0
        compose.setContent {
            MaterialTheme { HandoverSummaryCard("## Goal\nShip the handover.", partial = true, onOpenPrevious = { opened++ }) }
        }
        compose.onNodeWithTag("handoverSummaryToggle").assertTextContains(context.getString(R.string.remote_handover_summary_title))
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText(context.getString(R.string.remote_handover_summary_partial)).assertExists()
        compose.onAllNodesWithTag("handoverSummaryText").assertCountEquals(0)
        compose.onNodeWithTag("handoverSummaryToggle").performClick()
        compose.onNodeWithTag("handoverSummaryText").assertExists()
        compose.onNodeWithText("Ship the handover.", substring = true).assertExists()
        compose.onNodeWithTag("handoverOpenPrevious").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, opened)
    }
}
