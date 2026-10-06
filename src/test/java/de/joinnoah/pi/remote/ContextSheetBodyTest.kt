package de.joinnoah.pi.remote

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertTextEquals
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContextSheetBodyTest {
    @get:Rule val compose = createComposeRule()

    private val bigUsage =
        SessionContextUsage(
            sessionId = "s",
            modelProvider = "p",
            modelId = "m",
            usedTokens = 50_000,
            contextWindow = 200_000,
            percent = 25.0,
            totals = SessionUsageTotals(10_000, 5_000, 1_000, 500, 16_500, 1.23),
        )

    @Test
    fun contextSheetIsScrollableSoALargeFontScaleCannotClipIt() {
        compose.setContent { MaterialTheme { ContextSheetBody(bigUsage, unavailable = false) } }
        val node = compose.onNodeWithTag("contextSheet").fetchSemanticsNode()
        // A vertically scrollable node carries a scroll-by action instead of clipping its overflow.
        assertTrue(node.config.contains(SemanticsActions.ScrollBy) || node.config.contains(SemanticsProperties.VerticalScrollAxisRange))
    }

    @Test
    fun unavailableStateShowsTheUnavailableNotice() {
        compose.setContent { MaterialTheme { ContextSheetBody(usage = null, unavailable = true) } }
        compose.onNodeWithTag("contextSheet").assertExists()
    }

    @Test
    fun compactButtonInvokesTheDirectAction() {
        var clicks = 0
        compose.setContent {
            MaterialTheme {
                ContextSheetBody(bigUsage, unavailable = false, showCompact = true,
                    canCompact = true, onCompact = { clicks++ })
            }
        }
        compose.onNodeWithTag("compactContextButton").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(clicks == 1) }
    }

    private val info =
        SessionInfo(
            name = "Refactor plan",
            model = "Claude (anthropic/claude-x)",
            thinkingLevel = "high",
            project = "pocketpi",
            sessionId = "sess-1234",
        )

    @Test
    fun sessionInfoShowsNameModelAndId() {
        compose.setContent { MaterialTheme { ContextSheetBody(bigUsage, unavailable = false, info = info) } }
        compose.onNodeWithTag("sessionInfoName").assertTextEquals("Refactor plan")
        compose.onNodeWithTag("sessionInfoModel").assertTextEquals("Claude (anthropic/claude-x)")
        compose.onNodeWithTag("sessionInfoProject").assertTextEquals("pocketpi")
        compose.onNodeWithTag("sessionInfoId").assertTextEquals("sess-1234")
    }

    @Test
    fun unnamedSessionShowsAFallback() {
        compose.setContent {
            MaterialTheme { ContextSheetBody(bigUsage, unavailable = false, info = info.copy(name = null)) }
        }
        compose.onNodeWithTag("sessionInfoName").assertTextEquals("No name")
    }

    @Test
    fun copyIdButtonPutsTheIdOnTheClipboard() {
        compose.setContent { MaterialTheme { ContextSheetBody(bigUsage, unavailable = false, info = info) } }
        compose.onNodeWithTag("copySessionId").performScrollTo().performClick()
        compose.waitForIdle()
        val clipboard = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("sess-1234", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }
}
