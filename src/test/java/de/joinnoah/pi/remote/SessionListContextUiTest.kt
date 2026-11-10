package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionListContextUiTest {
    @get:Rule val compose = createComposeRule()

    private fun session(percent: Double?): JsonObject {
        val base = Wire.objectOf("id" to "s", "title" to "Session", "origin" to "worker", "status" to "idle")
        return if (percent == null) base
        else JsonObject(base + ("context" to Wire.objectOf(
            "usedTokens" to (percent * 2000).toLong(), "contextWindow" to 200_000, "percent" to percent,
        )))
    }

    private fun fill(percent: Int, level: ContextFillLevel) =
        SemanticsMatcher("context fill $percent% $level") {
            it.config.getOrElseNullable(ContextFillKey) { null }?.let { f -> f.percent == percent && f.level == level } == true
        }

    private fun projectRow(percent: Double?) {
        val item = projectChatItem(
            ProjectChatSummary("p", "Project", session(percent), verified = true),
            emptyMap(), connected = true, compactions = emptyMap(), nowMillis = 0, ticketRef = { null },
        )
        compose.setContent {
            MaterialTheme {
                SessionListRow(item = item, enabled = true, closeAvailable = false, onClick = {}, onClose = {})
            }
        }
    }

    @Test fun aProjectsRowRingsTheAvatarAndSaysTheFill() {
        projectRow(82.0)
        compose.onNodeWithTag("sessionStatusAvatar", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("sessionContextRing", useUnmergedTree = true).assert(fill(82, ContextFillLevel.WARNING))
        compose.onNodeWithContentDescription("Context 82% full", substring = true).assertExists()
    }

    @Test fun anUnknownFillShowsNoRing() {
        projectRow(null)
        compose.onNodeWithTag("sessionStatusAvatar", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("sessionContextRing", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Context", substring = true).assertDoesNotExist()
    }

    @Test @Config(qualifiers = "de")
    fun talkBackSaysTheFillInGerman() {
        projectRow(91.6)
        compose.onNodeWithContentDescription("Kontext zu 91\u00A0% voll", substring = true).assertExists()
        compose.onNodeWithTag("sessionContextRing", useUnmergedTree = true).assert(fill(91, ContextFillLevel.CRITICAL))
    }

    @Test fun theCleanUpCardShowsTheBar() {
        val item = sessionListItem(session(64.0), connected = true, loading = false, confirmed = true)
        compose.setContent { MaterialTheme { ReadyReviewCard(item, "Project", now = 0, old = false) } }
        compose.onNodeWithTag("contextFillBar", useUnmergedTree = true).assert(fill(64, ContextFillLevel.NORMAL))
        compose.onNodeWithContentDescription("Context 64% full", substring = true).assertExists()
    }

    @Test fun theCleanUpCardHidesTheBarWhenUnknown() {
        val item = sessionListItem(session(null), connected = true, loading = false, confirmed = true)
        compose.setContent { MaterialTheme { ReadyReviewCard(item, "Project", now = 0, old = false) } }
        compose.onNodeWithTag("readyReviewCard").assertExists()
        compose.onNodeWithTag("contextFillBar", useUnmergedTree = true).assertDoesNotExist()
    }
}
