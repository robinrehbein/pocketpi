package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatStatusChipUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun statusMatchesFilesHeightAndKeepsItsSemanticsAcrossStatesAndFontScales() {
        val fontScale = mutableStateOf(1f)
        val line = mutableStateOf(R.string.remote_panel_compacting)
        var clicks = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) {
                MaterialTheme {
                    Column {
                        ChatStatusChip(line.value, running = line.value != R.string.remote_export_done) { clicks++ }
                        TouchedFilesSummary(TouchedFiles(listOf(TouchedFile("file", 1, "item")), emptyList()), null) {}
                    }
                }
            }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (scale in listOf(1f, 3f)) {
            for (status in listOf(R.string.remote_panel_compacting, R.string.remote_export_running, R.string.remote_export_done)) {
                compose.runOnIdle { fontScale.value = scale; line.value = status }
                val chip = compose.onNodeWithTag("chatStatusChip")
                chip.assertHeightIsAtLeast(48.dp)
                    .assertHasClickAction()
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
                    .assertTextContains(context.getString(status))
                val filesHeight = compose.onNodeWithTag("touchedFilesSummary").fetchSemanticsNode().boundsInRoot.height
                assertEquals(filesHeight, chip.fetchSemanticsNode().boundsInRoot.height, 0.01f)
            }
        }
        compose.onNodeWithTag("chatStatusChip").performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }
}
