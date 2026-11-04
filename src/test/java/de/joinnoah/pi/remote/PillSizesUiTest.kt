package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PillSizesUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sizesChangePaintButKeepTouchTargets() {
        var size by mutableStateOf(PillSize.SMALL)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalComposerPillSize provides size, LocalInsightPillSize provides size) {
                    Column {
                        ComposerControlPill({}, Modifier.testTag("composer"), leadingIcon = {}, label = { Text("Model", style = MaterialTheme.typography.labelLarge) })
                        InsightPillRow(Modifier.testTag("insight"), onClick = {}) { Text("Tasks", style = MaterialTheme.typography.labelLarge) }
                    }
                }
            }
        }
        compose.onNodeWithTag("composer").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("insight").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("composerPillSurface", true).assertHeightIsAtLeast(32.dp)
        compose.onNodeWithTag("insightPillSurface", true).assertHeightIsAtLeast(32.dp)
        val smallComposerHeight = compose.onNodeWithTag("composerPillSurface", true).fetchSemanticsNode().size.height
        val smallInsightHeight = compose.onNodeWithTag("insightPillSurface", true).fetchSemanticsNode().size.height
        compose.runOnIdle { size = PillSize.LARGE }
        compose.onNodeWithTag("composerPillSurface", true).assertHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("insight").assertHeightIsEqualTo(48.dp)
        assertTrue(compose.onNodeWithTag("composerPillSurface", true).fetchSemanticsNode().size.height > smallComposerHeight)
        assertTrue(compose.onNodeWithTag("insight").fetchSemanticsNode().size.height > smallInsightHeight)
    }

    @Test fun smallInsightPillsGrowForLargeFonts() {
        var textFits = false
        compose.setContent {
            MaterialTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f), LocalInsightPillSize provides PillSize.SMALL) {
                    InsightPillRow(Modifier.testTag("largeFontInsight"), onClick = {}) {
                        Text("Tasks", style = MaterialTheme.typography.labelLarge, onTextLayout = {
                            textFits = !it.hasVisualOverflow
                        })
                    }
                }
            }
        }
        compose.onNodeWithTag("largeFontInsight").assertHeightIsAtLeast(48.dp)
        compose.runOnIdle { assertTrue(textFits) }
    }

    @Test fun selectorsAreIndependentAndSelectBothSizes() {
        var composer by mutableStateOf(PillSize.SMALL)
        var insight by mutableStateOf(PillSize.LARGE)
        compose.setContent {
            MaterialTheme {
                Column {
                    PillSizeChoice("Composer", composer, "composer") { composer = it }
                    PillSizeChoice("Insights", insight, "insight") { insight = it }
                }
            }
        }
        compose.onNodeWithTag("composer-small").assertIsSelected()
        compose.onNodeWithTag("insight-large").assertIsSelected()
        compose.onNodeWithTag("composer-large").performClick()
        compose.runOnIdle { assertEquals(PillSize.LARGE, composer); assertEquals(PillSize.LARGE, insight) }
        compose.onNodeWithTag("insight-small").performClick()
        compose.onNodeWithTag("insight-small").assertIsSelected()
        compose.onNodeWithTag("composer-large").assertIsSelected()
    }
}
