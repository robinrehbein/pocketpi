package de.joinnoah.pi.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
class SheetCardDesignTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun shortContentPaintsAcrossTheCardInBothSchemes() {
        compose.setContent {
            Column {
                listOf(false, true).forEach { dark ->
                    MaterialTheme(colorScheme = remoteColorScheme(dark)) {
                        CardSample("fixed-$dark") { SheetCard { ShortContent() } }
                        // Control reproduces the old implementation: its outer box fills the width,
                        // but its painted Surface only wraps the short child.
                        CardSample("old-$dark") {
                            FloatingSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), blur = false) {
                                ShortContent()
                            }
                        }
                    }
                }
            }
        }
        for (dark in listOf(false, true)) {
            val fixed = compose.onNodeWithTag("fixed-$dark").captureToImage().toPixelMap()
            val old = compose.onNodeWithTag("old-$dark").captureToImage().toPixelMap()
            // Middle height avoids the rounded corners; 90% width is well beyond the short child.
            val x = fixed.width * 9 / 10
            val y = fixed.height / 2
            assertEquals("control must expose the background in dark=$dark", Color.Magenta, old[x, y])
            assertNotEquals("card must paint the far right in dark=$dark", Color.Magenta, fixed[x, y])
        }
    }

    @Test
    fun optionColorsAndTextContrastMatchBothLocalSchemes() {
        compose.setContent {
            listOf(false, true).forEach { dark ->
                MaterialTheme(colorScheme = remoteColorScheme(dark)) {
                    val scheme = MaterialTheme.colorScheme
                    val unselected = sheetOptionColor(false)
                    val selected = sheetOptionColor(true)
                    assertNotEquals("test must distinguish the old color in dark=$dark", scheme.surfaceContainerLow, scheme.surfaceContainerHigh)
                    assertEquals(scheme.surfaceContainerHigh, unselected)
                    assertEquals(scheme.secondaryContainer, selected)
                    assertTrue("unselected contrast in dark=$dark", contrast(scheme.onSurface, unselected.compositeOver(scheme.background)) >= 4.5f)
                    assertTrue("selected contrast in dark=$dark", contrast(scheme.onSecondaryContainer, selected.compositeOver(scheme.background)) >= 4.5f)
                    assertTrue("unselected secondary text contrast in dark=$dark", contrast(scheme.onSurfaceVariant, unselected.compositeOver(scheme.background)) >= 4.5f)
                    assertTrue("selected secondary text contrast in dark=$dark", contrast(scheme.onSurfaceVariant, selected.compositeOver(scheme.background)) >= 4.5f)
                }
            }
        }
        compose.waitForIdle()
    }

    @Composable
    private fun CardSample(tag: String, content: @Composable () -> Unit) {
        Box(Modifier.width(280.dp).background(Color.Magenta).testTag(tag)) { content() }
    }

    @Composable
    private fun ShortContent() {
        Box(Modifier.size(40.dp))
    }

    private fun contrast(foreground: Color, background: Color): Float {
        val a = foreground.compositeOver(background).luminance()
        val b = background.luminance()
        return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
    }
}
