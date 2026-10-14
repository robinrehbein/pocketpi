package de.joinnoah.pi.remote

import android.animation.ValueAnimator
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** TalkBack, right-to-left and split-screen behaviour of the chat, tool and list surfaces. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatA11yUiTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun text(id: Int) = context.getString(id)

    /** What Remove animations sets: ValueAnimator's hidden duration scale. */
    private fun animatorDurationScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    @After fun restoreAnimations() = animatorDurationScale(1f)

    private fun stateIs(id: Int) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(id))
    private fun roleIs(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private fun activity() =
        ConversationItem.Activity(
            id = "tool", sourceId = "message", name = "bash", arguments = """{"command":"ls"}""",
            output = "done", state = "complete", truncated = false,
        )

    private fun show(item: ConversationItem, onOpenTool: ((ConversationItem.Activity) -> Unit)? = null, onQuote: (String) -> Unit = {}) {
        compose.setContent {
            MaterialTheme { ConversationMessage(item, "text", false, onQuote = onQuote, onOpenTool = onOpenTool) }
        }
    }

    @Test fun diffRowsTellTalkBackWhatWasAddedAndRemoved() {
        val lines = listOf(
            DiffLine(DiffKind.HUNK, "@@ -1,2 +1,3 @@", null, null),
            DiffLine(DiffKind.CONTEXT, "keep", 1, 1),
            DiffLine(DiffKind.REMOVED, "old", 2, null),
            DiffLine(DiffKind.ADDED, "new", null, 2),
            DiffLine(DiffKind.ADDED, "", null, 3),
        )
        compose.setContent { MaterialTheme { DiffView(lines) } }
        compose.onNodeWithContentDescription("Line 1: keep").assertExists()
        compose.onNodeWithContentDescription("Removed line 2: old").assertExists()
        compose.onNodeWithContentDescription("Added line 2: new").assertExists()
        compose.onNodeWithContentDescription("Added line 3: blank line").assertExists()
    }

    @Test fun splitDiffCellsTellTalkBackWhatWasAddedAndRemoved() {
        val context = DiffLine(DiffKind.CONTEXT, "keep", 4, 6)
        val removed = DiffLine(DiffKind.REMOVED, "old", 5, null)
        val added = DiffLine(DiffKind.ADDED, "new", null, 7)
        compose.setContent {
            MaterialTheme {
                val style = rememberDiffRowStyle(listOf(context, removed, added))
                val scroll = rememberScrollState()
                Column {
                    SplitDiffRow(context, context, style, scroll, wrap = false)
                    SplitDiffRow(removed, added, style, scroll, wrap = false)
                }
            }
        }
        // Each side reads its own number.
        compose.onNodeWithContentDescription("Line 4: keep").assertExists()
        compose.onNodeWithContentDescription("Line 6: keep").assertExists()
        compose.onNodeWithContentDescription("Removed line 5: old").assertExists()
        compose.onNodeWithContentDescription("Added line 7: new").assertExists()
    }

    @Test fun diffLinesWithoutNumbersStillSayTheirKind() {
        val labels = DiffLineLabels(
            text(R.string.remote_chat_a11y_diff_added_line),
            text(R.string.remote_chat_a11y_diff_removed_line),
            text(R.string.remote_chat_a11y_diff_context_line),
            text(R.string.remote_chat_a11y_diff_added),
            text(R.string.remote_chat_a11y_diff_removed),
            text(R.string.remote_chat_a11y_diff_blank),
        )
        assertEquals("Added: x", diffLineDescription(DiffLine(DiffKind.ADDED, "x", null, null), labels))
        assertEquals("Removed: y", diffLineDescription(DiffLine(DiffKind.REMOVED, "y", null, null), labels))
        assertEquals("z", diffLineDescription(DiffLine(DiffKind.CONTEXT, "z", null, null), labels))
        assertEquals(null, diffLineDescription(DiffLine(DiffKind.HUNK, "@@", null, null), labels))
    }

    @Test fun toolHeaderThatExpandsIsA48dpButtonWithItsState() {
        show(activity())
        val header = compose.onNodeWithTag("toolCardHeader")
        header.assertHeightIsAtLeast(48.dp).assert(roleIs(Role.Button)).assert(stateIs(R.string.remote_chat_a11y_collapsed))
        header.performClick()
        header.assert(stateIs(R.string.remote_chat_a11y_expanded))
    }

    @Test fun toolToggleBesideAnOpeningHeaderCarriesTheState() {
        show(activity(), onOpenTool = {})
        compose.onNodeWithTag("toolCardHeader").assert(roleIs(Role.Button))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        val toggle = compose.onNodeWithTag("toolCardToggle")
        toggle.assertHeightIsAtLeast(48.dp).assert(stateIs(R.string.remote_chat_a11y_collapsed))
        toggle.performClick()
        toggle.assert(stateIs(R.string.remote_chat_a11y_expanded))
    }

    @Test fun subagentRowsAre48dpButtons() {
        val subagent = ConversationItem.Subagent(
            id = "sub", sourceId = "message", mode = "parallel",
            agents = listOf(AgentProgress("scout", "running", "Looking", null, sessionId = "child")),
            legacy = false, output = "All done", truncated = false,
        )
        compose.setContent {
            MaterialTheme { ConversationMessage(subagent, "text", false, onQuote = {}, onOpenAgent = { _, _ -> }) }
        }
        val header = compose.onNodeWithTag("subagentHeader")
        header.assertHeightIsAtLeast(48.dp).assert(roleIs(Role.Button)).assert(stateIs(R.string.remote_chat_a11y_collapsed))
        header.performClick()
        header.assert(stateIs(R.string.remote_chat_a11y_expanded))
        compose.onNodeWithTag("subagentAgent").assertHeightIsAtLeast(48.dp).assert(roleIs(Role.Button))
    }

    @Test fun singleChoiceQuestionsAreRadioGroups() {
        val questionnaire = Wire.parse(
            """{"id":"q","kind":"questionnaire","questions":[
              {"id":"one","prompt":"Pick one","multiSelect":false,"required":true,"allowOther":true,"options":[{"value":"a","label":"A"},{"value":"b","label":"B"}],"defaults":[]},
              {"id":"many","prompt":"Pick many","multiSelect":true,"required":false,"allowOther":false,"options":[{"value":"c","label":"C"}],"defaults":[]}
            ]}"""
        )
        compose.setContent { MaterialTheme { QuestionCard(questionnaire, enabled = true) {} } }
        // Only the single choice question is a group; a checkbox list is not.
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)).assertCountEquals(1)
    }

    @Test fun selectQuestionIsARadioGroup() {
        val select = Wire.parse("""{"id":"s","kind":"select","options":["a","b"]}""")
        compose.setContent { MaterialTheme { QuestionCard(select, enabled = true) {} } }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)).assertCountEquals(1)
    }

    /** Whether [outline] covers any of a 4px square in the given bottom corner. */
    private fun coversBottomCorner(outline: Outline, size: Size, right: Boolean): Boolean {
        val corner = Path().apply {
            addRect(if (right) Rect(size.width - 4f, size.height - 4f, size.width, size.height) else Rect(0f, size.height - 4f, 4f, size.height))
        }
        return !Path.combine(PathOperation.Intersect, (outline as Outline.Generic).path, corner).isEmpty
    }

    @Test fun bubbleTailFollowsTheLayoutDirection() {
        val size = Size(200f, 100f)
        val density = Density(1f)
        val outgoingLtr = BubbleShape(true).createOutline(size, LayoutDirection.Ltr, density)
        val outgoingRtl = BubbleShape(true).createOutline(size, LayoutDirection.Rtl, density)
        val incomingRtl = BubbleShape(false).createOutline(size, LayoutDirection.Rtl, density)
        assertTrue(coversBottomCorner(outgoingLtr, size, right = true))
        assertFalse(coversBottomCorner(outgoingLtr, size, right = false))
        // Right to left, a sent message sits at the left, so its tail does too.
        assertTrue(coversBottomCorner(outgoingRtl, size, right = false))
        assertFalse(coversBottomCorner(outgoingRtl, size, right = true))
        assertTrue(coversBottomCorner(incomingRtl, size, right = true))
    }

    private fun bubble() =
        ConversationItem.Bubble(
            id = "b", sourceId = "message-b", role = "assistant", author = "model",
            text = "A reply that is long enough to swipe across comfortably", quote = null,
            truncated = false, timestamp = null,
        )

    @Test fun quoteSwipeMirrorsInRightToLeft() {
        val quoted = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme { ConversationMessage(bubble(), "text", false, onQuote = { quoted += it }) }
            }
        }
        compose.onNodeWithTag("messageBubble-b").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(emptyList<String>(), quoted) }
        compose.onNodeWithTag("messageBubble-b").performTouchInput { swipeRight() }
        compose.runOnIdle { assertEquals(listOf("message-b"), quoted) }
    }

    @Test fun quoteSwipeStillGoesLeftInLeftToRight() {
        val quoted = mutableListOf<String>()
        show(bubble(), onQuote = { quoted += it })
        compose.onNodeWithTag("messageBubble-b").performTouchInput { swipeRight() }
        compose.runOnIdle { assertEquals(emptyList<String>(), quoted) }
        compose.onNodeWithTag("messageBubble-b").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(listOf("message-b"), quoted) }
    }

    @Test fun chevronsMirrorInRightToLeft() {
        assertTrue(Icons.AutoMirrored.Filled.NavigateNext.autoMirror)
    }

    @Test fun subagentIndentIs16dpPerLevelUpToTwo() {
        assertEquals(listOf(0.dp, 16.dp, 32.dp, 32.dp, 32.dp), (0..4).map(::subagentIndent))
    }

    @Test fun listRowsAnimateOnlyWithAnimationsOn() {
        val motions = mutableListOf<Modifier>()
        var row by mutableIntStateOf(0)
        compose.setContent { LazyColumn { item(key = row) { motions += Modifier.itemMotion(this) } } }
        compose.runOnIdle { assertNotEquals(Modifier, motions.last()) }
        animatorDurationScale(0f)
        // A new row composes after the system turned animations off.
        compose.runOnIdle { row++ }
        compose.runOnIdle { assertEquals(Modifier, motions.last()) }
    }

    @Test
    @Config(qualifiers = "w640dp-h400dp")
    fun sheetHeightsFitAHalfOfASplitScreen() {
        var limit = Dp.Unspecified
        compose.setContent { limit = windowHeightFraction(0.9f) }
        compose.runOnIdle {
            assertTrue("$limit", limit > 0.dp)
            assertTrue("$limit", limit <= 360.dp)
        }
    }

    @Test
    @Config(qualifiers = "w640dp-h400dp")
    fun aLongSheetStaysWithinHalfOfASplitScreen() {
        val choices = (1..40).map { AdvisorChoice("p", "m$it", "Model $it", listOf("low", "high")) }
        val advisor = SessionAdvisor("s", true, "p/m1", "low", false, false, 0, 3, 3, null, choices)
        compose.setContent {
            MaterialTheme {
                AdvisorPickerSheet(advisor, choices.first(), "low", false, true, {}, {}, {}, {}, {})
            }
        }
        val height = compose.onNodeWithTag("advisorPickerList").getUnclippedBoundsInRoot().let { it.bottom - it.top }
        assertTrue("$height", height > 0.dp)
        assertTrue("$height", height <= 400.dp * 0.84f)
    }
}
