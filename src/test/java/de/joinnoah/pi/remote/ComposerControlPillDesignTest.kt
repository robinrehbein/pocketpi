package de.joinnoah.pi.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w600dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerControlPillDesignTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun materialMatchesFloatingSurfaceAndNotOldOpaqueChipInBothSchemes() {
        compose.setContent {
            Column {
                listOf(false, true).forEach { dark ->
                    MaterialTheme(colorScheme = remoteColorScheme(dark)) {
                        ComposerControlPill(
                            onClick = {},
                            modifier = Modifier.background(Color.Magenta).testTag("pill-$dark"),
                            leadingIcon = { Box(Modifier.size(16.dp)) },
                            label = { Box(Modifier.width(80.dp).height(16.dp)) },
                        )
                        ReferenceSample("glass-$dark", old = false)
                        ReferenceSample("old-$dark", old = true)
                    }
                }
            }
        }
        for (dark in listOf(false, true)) {
            val pill = compose.onNodeWithTag("pill-$dark").captureToImage().toPixelMap()
            val glass = compose.onNodeWithTag("glass-$dark").captureToImage().toPixelMap()
            val old = compose.onNodeWithTag("old-$dark").captureToImage().toPixelMap()
            assertEquals(glass.width, pill.width)
            assertEquals(glass.height, pill.height)
            assertEquals(glass.width, old.width)
            assertEquals(glass.height, old.height)
            // Center is inside an empty label, away from the icon, text, rounded edge and rim.
            val x = pill.width / 2
            val y = pill.height / 2
            assertNotEquals("glass must paint over the background, dark=$dark", Color.Magenta, glass[x, y])
            assertNotEquals("control must distinguish old opaque material, dark=$dark", old[x, y], glass[x, y])
            assertEquals("pill material, dark=$dark", glass[x, y], pill[x, y])
        }
    }

    @Composable
    private fun ReferenceSample(tag: String, old: Boolean) {
        Box(
            Modifier.background(Color.Magenta).testTag(tag).defaultMinSize(minHeight = 48.dp, minWidth = 48.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (old) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) { SampleContents() }
            } else {
                FloatingSurface(shape = CircleShape, blur = false) { SampleContents() }
            }
        }
    }

    @Composable
    private fun SampleContents() {
        Row(
            Modifier.defaultMinSize(minHeight = 32.dp).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(16.dp))
            Box(Modifier.width(80.dp).height(16.dp))
        }
    }

    @Test
    fun targetIsAccessibleAndOnlyEnabledPillRunsAction() {
        val enabled = mutableStateOf(true)
        var clicks = 0
        compose.setContent {
            MaterialTheme {
                ComposerControlPill(
                    onClick = { clicks++ }, modifier = Modifier.testTag("control"), enabled = enabled.value,
                    leadingIcon = { Box(Modifier.size(16.dp)) }, label = { Text("Model") },
                )
            }
        }
        val control = compose.onNodeWithTag("control")
        control.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, clicks); enabled.value = false }
        control.assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun unboundedPillKeepsItsLabelInsideHorizontalScroll() {
        compose.setContent {
            MaterialTheme {
                Row(Modifier.width(300.dp).horizontalScroll(rememberScrollState())) {
                    ComposerControlPill(
                        onClick = {},
                        leadingIcon = { Box(Modifier.size(16.dp)) },
                        label = { Text("Thinking", Modifier.testTag("unbounded-label")) },
                    )
                }
            }
        }
        val label = compose.onNodeWithTag("unbounded-label", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("unbounded labels must not collapse: $label", label.right > label.left && label.bottom > label.top)
    }

    @Test
    fun modelLabelRemainsVisibleAndCappedInsideHorizontalScroll() {
        compose.setContent {
            MaterialTheme {
                Row(Modifier.width(300.dp).horizontalScroll(rememberScrollState())) {
                    ComposerControlPill(
                        onClick = {}, modifier = Modifier.widthIn(max = 220.dp).testTag("model"),
                        leadingIcon = { Box(Modifier.size(16.dp)) },
                        label = {
                            Text(
                                "A very long model name ".repeat(10),
                                modifier = Modifier.testTag("model-label"),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }
        val pill = compose.onNodeWithTag("model").getUnclippedBoundsInRoot()
        val label = compose.onNodeWithTag("model-label", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("model width must be capped: $pill", pill.right - pill.left <= 220.dp)
        assertTrue("label must have positive bounds in a scroll row: $label", label.right > label.left && label.bottom > label.top)
        assertTrue("label must fit inside the model pill: pill=$pill label=$label", label.left >= pill.left && label.right <= pill.right)
    }

    @Test
    fun longLabelIsCappedAndLargeTextFitsVariableHeight() {
        var layout: TextLayoutResult? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme {
                    ComposerControlPill(
                        onClick = {}, modifier = Modifier.widthIn(max = 220.dp).testTag("control"),
                        leadingIcon = { Box(Modifier.size(16.dp)) },
                        label = {
                            Text(
                                "A very long model name ".repeat(10),
                                modifier = Modifier.testTag("label"), maxLines = 1,
                                overflow = TextOverflow.Ellipsis, onTextLayout = { layout = it },
                            )
                        },
                    )
                }
            }
        }
        val pill = compose.onNodeWithTag("control").getUnclippedBoundsInRoot()
        val label = compose.onNodeWithTag("label", useUnmergedTree = true).getUnclippedBoundsInRoot()
        compose.runOnIdle {
            assertTrue("width must be capped: $pill", pill.right - pill.left <= 220.dp)
            assertTrue("text must fit vertically: pill=$pill label=$label", label.top >= pill.top && label.bottom <= pill.bottom)
            assertTrue("large text should make the surface grow", label.bottom - label.top > 32.dp)
            assertNotNull(layout)
            assertTrue("long text should be ellipsized", layout!!.isLineEllipsized(0))
            // didOverflowHeight also reports intentional maxLines truncation. Check the
            // visible line itself, rather than treating the ellipsized lines as clipping.
            assertTrue("visible line must fit vertically", layout!!.getLineTop(0) >= 0f &&
                layout!!.getLineBottom(0) <= layout!!.size.height.toFloat())
        }
    }
}
