package de.joinnoah.pi.remote

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native drawing tests; Robolectric deliberately does not exercise PixelCopy capture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FrostedPopupSurfaceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun assertColor(expected: Color, actual: Color) {
        assertEquals("red", expected.red, actual.red, 3f / 255)
        assertEquals("green", expected.green, actual.green, 3f / 255)
        assertEquals("blue", expected.blue, actual.blue, 3f / 255)
        assertEquals("alpha", expected.alpha, actual.alpha, 3f / 255)
    }

    @Test fun nullBackdropIsOpaqueInBothSchemesAndKeepsSizeShapeAndContentColor() {
        var dark by mutableStateOf(false)
        var observedContentColor = Color.Unspecified
        compose.setContent {
            val scheme = remoteColorScheme(dark)
            MaterialTheme(colorScheme = scheme) {
                Box(Modifier.size(160.dp).background(if (dark) Color.White else Color.Black)) {
                    FrostedPopupSurface(
                        backdrop = null,
                        modifier = Modifier.padding(16.dp).testTag("surface"),
                        shape = RoundedCornerShape(24.dp),
                        // Even a caller-specified alpha must not weaken the fallback.
                        color = scheme.surfaceContainer.copy(alpha = 0.5f),
                    ) {
                        observedContentColor = LocalContentColor.current
                        Box(Modifier.size(100.dp).testTag("content"))
                    }
                }
            }
        }
        for (isDark in listOf(false, true)) {
            compose.runOnIdle { dark = isDark }
            val scheme = remoteColorScheme(isDark)
            compose.onNodeWithTag("content").assertWidthIsEqualTo(100.dp).assertHeightIsEqualTo(100.dp)
            val pixels = compose.onNodeWithTag("surface").captureToImage().toPixelMap()
            assertColor(scheme.surfaceContainer, pixels[pixels.width / 2, pixels.height / 2])
            // Rounded clipping leaves the contrasting parent visible at the extreme corner.
            val corner = pixels[0, 0]
            assertTrue("Rounded corner must reveal the parent (allowing the soft shadow)",
                if (isDark) corner.red > 0.8f else corner.red < 0.2f)
            compose.runOnIdle { assertEquals(scheme.onSurface, observedContentColor) }
        }
    }

    @Test fun suppliedSnapshotIsScreenAlignedAndTintedExactlyOnce() {
        var backdrop by mutableStateOf<AppBackdrop?>(null)
        val scheme = remoteColorScheme(false)
        compose.setContent {
            MaterialTheme(colorScheme = scheme) {
                Box(Modifier.fillMaxSize().background(Color.Green)) {
                    Box(Modifier.padding(start = 37.dp, top = 61.dp)) {
                        FrostedPopupSurface(backdrop, Modifier.testTag("surface")) {
                            Spacer(Modifier.size(120.dp))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bounds = compose.onNodeWithTag("surface").fetchSemanticsNode().boundsInWindow
        val stripeX = bounds.center.x.toInt()
        // The split is in Activity coordinates, not surface-local coordinates. A stretched or
        // locally aligned snapshot would put both samples on the same side of this split.
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            val colors = IntArray(root.width * root.height) { index ->
                if (index % root.width < stripeX) android.graphics.Color.RED else android.graphics.Color.BLUE
            }
            bitmap.setPixels(colors, 0, root.width, 0, 0, root.width, root.height)
            backdrop = AppBackdrop(bitmap.asImageBitmap(), root, IntSize(root.width, root.height), mutableStateOf(0))
            assertTrue(root.isAttachedToWindow)
        }
        val pixels = compose.onNodeWithTag("surface").captureToImage().toPixelMap()
        val tint = glassTint(scheme.surfaceContainer, scheme, blur = true)
        for ((x, underlying) in listOf(pixels.width / 4 to Color.Red, pixels.width * 3 / 4 to Color.Blue)) {
            val actual = pixels[x, pixels.height / 2]
            val once = tint.compositeOver(underlying)
            val twice = tint.compositeOver(once)
            assertColor(once, actual)
            assertTrue("A second tint must be detectable", kotlin.math.abs(actual.red - twice.red) > 0.02f ||
                kotlin.math.abs(actual.blue - twice.blue) > 0.02f)
        }
    }

    @Test fun morphingMenuUsesOpaqueFallbackAndPreservesContentThroughOpenAndClose() {
        lateinit var state: MorphMenuState
        val scheme = remoteColorScheme(false)
        compose.setContent {
            MaterialTheme(colorScheme = scheme) {
                state = rememberMorphMenuState()
                Box(Modifier.fillMaxSize().background(Color.Magenta)) {
                    Box(Modifier.padding(start = 32.dp, top = 80.dp)) {
                        Box(Modifier.size(48.dp).morphMenuAnchor(state).testTag("anchor"))
                    }
                    MorphingMenu(
                        state, MorphMenuDirection.DownFromEnd,
                        modifier = Modifier.testTag("menu"), panelWidth = 160.dp,
                        controls = { Spacer(Modifier.height(48.dp)) },
                    ) {
                        Box(Modifier.fillMaxWidth().height(80.dp)) { Text("Menu content") }
                    }
                }
            }
        }
        compose.runOnIdle { state.expanded = true }
        compose.waitForIdle()
        compose.onNodeWithText("Menu content").assertIsDisplayed()
        compose.onNodeWithTag("menu").assertWidthIsEqualTo(160.dp).assertHeightIsEqualTo(128.dp)
        val pixels = compose.onNodeWithTag("menu").captureToImage().toPixelMap()
        // Sample the empty controls area, away from text, clipping and rim.
        assertColor(scheme.surfaceContainer, pixels[pixels.width / 2, pixels.height / 5])
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.dismiss() }
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithTag("menu").assertExists()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithTag("menu").assertDoesNotExist()
        compose.onNodeWithTag("anchor").assertExists()
    }
}
