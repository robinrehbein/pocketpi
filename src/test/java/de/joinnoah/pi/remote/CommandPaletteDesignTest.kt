package de.joinnoah.pi.remote

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CommandPaletteDesignTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val host = PairedHost("mac", "relay", "device", "secret", "Studio")
    private val session = Wire.objectOf("id" to "s1", "title" to "First session", "status" to "idle")
    private val secondSession = Wire.objectOf("id" to "s2", "title" to "Second session", "status" to "idle")
    private val input = PaletteInput(
        state = RemoteState(
            hosts = listOf(host), hostsLoaded = true, host = host, connected = true,
            selection = RemoteSelection("mac", "p1", "s1"),
            projects = listOf(Wire.objectOf("id" to "p1", "name" to "Alpha")),
            sessions = listOf(session, secondSession),
            projectSessions = mapOf("p1" to projectSessions("p1", "Alpha", listOf(session, secondSession), verified = true)),
        ),
        home = HomeSnapshot(), chat = false, usage = emptyMap(), now = 1_800_000_000_000L,
    )
    private val labels = PaletteLabels(
        command = { it.toString() }, status = { it }, project = "Project", current = "Current",
    )
    private var chosen: PaletteItem? = null
    private var dismissals = 0

    private fun render() {
        compose.setContent {
            MaterialTheme {
                CommandPalette(input, labels, PaletteMode.ALL, sheet = false,
                    onChoose = { chosen = it }, onDismiss = { dismissals++ })
            }
        }
        compose.waitForIdle()
    }

    private fun assertField(value: String) {
        compose.onNodeWithTag("paletteField").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)),
        )
    }

    @Test
    fun typedPrefixSelectsModeAndChipPreservesTextAndRestoresFocus() {
        render()
        compose.onNodeWithTag("paletteField").performTextReplacement("#search text")
        compose.onNodeWithTag("paletteModeSESSIONS").assertIsSelected()
        compose.onNodeWithTag("paletteModeALL").assertIsNotSelected()
        compose.onNodeWithTag("paletteModeCOMMANDS").performScrollTo().performClick()
        compose.waitForIdle()
        assertField("/search text")
        compose.onNodeWithTag("paletteModeCOMMANDS").assertIsSelected()
        compose.onNodeWithTag("paletteField").assertIsFocused()
        compose.onNodeWithTag("paletteModeALL").performScrollTo().performClick()
        compose.waitForIdle()
        assertField("search text")
        compose.onNodeWithTag("paletteModeALL").assertIsSelected()
        compose.onNodeWithTag("paletteField").assertIsFocused()
    }

    @Test
    fun clearRemovesPrefixAndSearchAndRestoresFocus() {
        render()
        compose.onNodeWithTag("paletteField").performTextReplacement("@file")
        compose.onNodeWithTag("paletteModeFILES").assertIsSelected()
        compose.onNodeWithTag("paletteClear").performClick()
        compose.waitForIdle()
        assertField("")
        compose.onNodeWithTag("paletteModeALL").assertIsSelected()
        compose.onNodeWithTag("paletteField").assertIsFocused()
    }

    @Test
    fun keyboardCrossesSectionHeadingAndChoosesDisplayedRow() {
        val displayed = paletteDisplayItems(paletteItems(input, labels, ""), "")
        val boundary = displayed.indexOfFirst { paletteSection(it) != paletteSection(displayed.first()) }
        require(boundary > 0)
        assertNotEquals(displayed[boundary - 1].key, displayed[boundary].key)
        render()
        compose.onNodeWithTag("paletteSection${paletteSection(displayed.first()).name}", useUnmergedTree = true)
            .assertExists().assertHasNoClickAction()
        compose.onAllNodesWithTag("paletteRow")[0].assertIsSelected().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("paletteList").performScrollToIndex(boundary)
        compose.onNodeWithTag("paletteSection${paletteSection(displayed[boundary]).name}", useUnmergedTree = true)
            .assertExists().assertHasNoClickAction()
        compose.onNodeWithTag("paletteList").performScrollToIndex(0)
        compose.onNodeWithTag("paletteField").performClick()
        repeat(boundary) {
            compose.onNodeWithTag("paletteField").performKeyInput { pressKey(Key.DirectionDown) }
        }
        compose.waitForIdle()
        compose.onNode(hasTestTag("paletteRow") and hasText(displayed[boundary].title)).assertIsSelected()
        compose.onNodeWithTag("paletteField").performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(displayed[boundary].key, chosen?.key) }
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun smallWindowAndLargeFontKeepSearchAndResultsAccessible() {
        val displayed = paletteDisplayItems(paletteItems(input, labels, ""), "")
        val target = displayed.last()
        val open = mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                MaterialTheme {
                    if (open.value) CommandPalette(input, labels, PaletteMode.ALL, sheet = false,
                        onChoose = { chosen = it }, onDismiss = { dismissals++ })
                }
            }
        }
        compose.runOnIdle { open.value = true }
        compose.waitForIdle()
        // Robolectric leaves the activity window focused after showing a Dialog. Deliver the
        // window-manager focus transition; this does not request focus on the text field.
        val dialog = android.view.inspector.WindowInspector.getGlobalWindowViews()
            .single { it !== compose.activity.window.decorView }
        compose.runOnIdle {
            val getRoot = android.view.View::class.java.getDeclaredMethod("getViewRootImpl")
            val activityRoot = getRoot.invoke(compose.activity.window.decorView)
            val dialogRoot = getRoot.invoke(dialog)
            org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowViewRootImpl>(activityRoot)
                .callWindowFocusChanged(false)
            org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowViewRootImpl>(dialogRoot)
                .callWindowFocusChanged(true)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue("Dialog did not receive window focus", dialog.hasWindowFocus()) }
        compose.onNodeWithTag("paletteField").assertIsDisplayed().assertIsFocused()
        compose.onNodeWithTag("paletteList").performScrollToIndex(displayed.lastIndex)
        compose.onNode(hasTestTag("paletteRow") and hasText(target.title)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(target.key, chosen?.key) }
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun compactViewportKeepsSectionsFilteredResultsAndKeyboardSelectionReachable() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                MaterialTheme {
                    CommandPalette(input, labels, PaletteMode.ALL, sheet = false,
                        onChoose = { chosen = it }, onDismiss = { dismissals++ },
                        modifier = Modifier.height(200.dp))
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("paletteModeALL").assertDoesNotExist()
        compose.onNodeWithTag("paletteClose").assertIsDisplayed()
        compose.onNodeWithTag("paletteField").performClick()
        for (query in listOf("", "#")) {
            val displayed = paletteDisplayItems(paletteItems(input, labels, query), query)
            require(displayed.size >= 2)
            compose.onNodeWithTag("paletteField").performTextReplacement(query)
            compose.onNodeWithTag("paletteList").assertHeightIsAtLeast(48.dp)
            repeat(displayed.lastIndex) {
                compose.onNodeWithTag("paletteField").performKeyInput { pressKey(Key.DirectionDown) }
                compose.waitForIdle()
            }
            val selected = compose.onNode(hasTestTag("paletteRow") and hasText(displayed.last().title))
                .assertIsDisplayed().assertIsSelected()
            val rowBounds = selected.getUnclippedBoundsInRoot()
            val listBounds = compose.onNodeWithTag("paletteList").getUnclippedBoundsInRoot()
            assertTrue("Selected row starts above list viewport for '$query'", rowBounds.top >= listBounds.top)
            assertTrue("Selected row ends below list viewport for '$query'", rowBounds.bottom <= listBounds.bottom)
            compose.onNodeWithTag("paletteField").assertIsDisplayed()
            compose.onNodeWithTag("paletteField").performKeyInput { pressKey(Key.Enter) }
            compose.runOnIdle { assertEquals(displayed.last().key, chosen?.key) }
        }
    }

    @Test
    fun escapeDismissesWithoutChoosing() {
        render()
        compose.onNodeWithTag("paletteField").assertIsDisplayed().performClick()
        compose.onNodeWithTag("paletteField").performKeyInput { pressKey(Key.Escape) }
        compose.runOnIdle {
            assertEquals(1, dismissals)
            assertNull(chosen)
        }
    }
}
