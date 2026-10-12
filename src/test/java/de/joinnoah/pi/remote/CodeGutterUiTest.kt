package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CodeGutterUiTest {
    @get:Rule val compose = createComposeRule()
    private val densities = listOf(1f, 1.33125f, 1.5f, 2.125f, 2.625f, 2.75f, 3.0625f, 3.375f, 3.5f)
    private val scales = listOf(0.85f, 1f, 1.3f, 2f)

    private fun assertNumberFits(number: Int) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(number.toString(), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        assertFalse("Gutter $number clips", layout.hasVisualOverflow)
        assertTrue("Gutter $number has less width than its intrinsic text",
            layout.size.width >= ceil(layout.multiParagraph.intrinsics.maxIntrinsicWidth).toInt())
    }

    @Test fun bothDiffGuttersFitCompleteNumbersAtFractionalDensitiesAndLargeFonts() {
        val density = mutableStateOf(Density(1f))
        val number = mutableStateOf(19)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides density.value) {
                MaterialTheme {
                    Box(Modifier.requiredWidth(360.dp)) {
                        DiffView(listOf(DiffLine(DiffKind.CONTEXT, "code", number.value, number.value + 1)))
                    }
                }
            }
        }
        for (scale in scales) for (dpi in densities) for (value in listOf(19, 999, 9999)) {
            compose.runOnIdle { density.value = Density(dpi, scale); number.value = value }
            assertNumberFits(value)
            assertNumberFits(value + 1)
        }
    }

    @Test fun toolOutputGutterFitsAcrossDensityAndFontSettings() {
        val density = mutableStateOf(Density(1f))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides density.value) {
                MaterialTheme {
                    Box(Modifier.requiredSize(360.dp, 800.dp)) {
                        ToolDetailScreen(
                            item = ConversationItem.Activity("tool", "message", "read", null,
                                List(20) { "output row" }.joinToString("\n"), "done", false),
                            download = null, canLoadFullOutput = false, canAskToFix = false,
                            onLoadFullOutput = {}, onCancelFullOutput = {}, onAskToFix = {},
                            onQuote = {}, onClose = {},
                        )
                    }
                }
            }
        }
        for (scale in scales) for (dpi in densities) {
            compose.runOnIdle { density.value = Density(dpi, scale) }
            compose.onNodeWithTag("toolDetailContent").performScrollToNode(hasText("19"))
            assertNumberFits(19)
        }
    }

    @Test fun fileGutterFitsFourDigitsAndKeepsItsTouchWidth() {
        val density = mutableStateOf(Density(1f))
        val state = FilesState("session", loading = false,
            file = OpenFile("source.txt", loading = false, content = List(1000) { "code" }.joinToString("\n")))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides density.value) {
                MaterialTheme {
                    Box(Modifier.requiredSize(360.dp, 800.dp)) {
                        FilesPane(state, FilesActions(onClose = {}, onOpenDir = {}, onOpenFile = {},
                            onLoadMore = {}, onReload = {}, onSelectLines = {}, onSend = { true },
                            onRequestPreview = {}, onShowPeek = { _, _ -> }, onDismissPeek = {}))
                    }
                }
            }
        }
        for (scale in scales) for (dpi in densities) {
            compose.runOnIdle { density.value = Density(dpi, scale) }
            compose.onNodeWithTag("filesFile").performScrollToNode(hasText("1000"))
            assertNumberFits(1000)
            compose.onNodeWithTag("filesLine:1000").assertWidthIsAtLeast(48.dp)
        }
    }
}
