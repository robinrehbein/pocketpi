package de.joinnoah.pi.remote

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertCountEquals
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
class ContextOfferBannerUiTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val offer = ContextOffer(CONTEXT_OFFER_PERCENT, 82, setOf(ContextOfferAction.COMPACT))

    @Test
    fun bannerNamesTheFillAndOffersCompactAndNotNow() {
        var compacts = 0
        var dismissals = 0
        val announce = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                ContextOfferBanner(offer, announce.value, onCompact = { compacts++ }, onDismiss = { dismissals++ })
            }
        }
        compose.onNodeWithTag("contextOfferBanner")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithTag("contextOfferText")
            .assertTextContains(context.getString(R.string.remote_context_full_title, 82))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assert(SemanticsMatcher("has a Not now action") { node ->
                node.config.getOrElse(SemanticsActions.CustomActions) { emptyList() }.map { it.label } ==
                    listOf(context.getString(R.string.remote_context_offer_dismiss))
            })
        compose.onNodeWithTag("contextOfferCompact")
            .assertContentDescriptionEquals(context.getString(R.string.remote_compact_now_description, 82))
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.onNodeWithTag("contextOfferDismiss").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle {
            compose.onNodeWithTag("contextOfferText").fetchSemanticsNode()
                .config[SemanticsActions.CustomActions].single().action()
        }
        compose.runOnIdle {
            assertEquals(1, compacts)
            assertEquals(2, dismissals)
            announce.value = false
        }
        // Once read out, the banner stops being a live region so a later return stays quiet.
        compose.onNodeWithTag("contextOfferBanner")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
    }

    @Test
    fun withoutCompactTheBannerHasNoCompactButton() {
        compose.setContent {
            MaterialTheme {
                Column {
                    ContextOfferBanner(offer.copy(actions = emptySet()), announce = false, onCompact = {}, onDismiss = {})
                    ContextOfferBanner(offer, announce = false, onCompact = {}, onDismiss = {}, floating = false)
                }
            }
        }
        compose.onAllNodesWithTag("contextOfferBanner").assertCountEquals(2)
        compose.onAllNodesWithTag("contextOfferCompact").assertCountEquals(1)
    }

    @Test
    fun statusChipCanShowTheFillLevel() {
        val label = context.getString(R.string.remote_context_full_title, 91)
        compose.setContent {
            MaterialTheme {
                ChatStatusChip(R.string.remote_context_full_title, running = false, label = label, announce = false) {}
            }
        }
        compose.onNodeWithTag("chatStatusChip").assertTextContains(label).assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
    }
}
