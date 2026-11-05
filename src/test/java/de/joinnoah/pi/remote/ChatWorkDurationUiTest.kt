package de.joinnoah.pi.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "de")
class ChatWorkDurationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun completedDurationIsLocalizedAndDoesNotMarquee() =
        checkDuration(312000, "Für 5 Minuten und 12 Sekunden gearbeitet")

    @Test fun completedHoursUseNaturalThreeUnitJoin() =
        checkDuration(3912000, "Für 1 Stunde, 5 Minuten und 12 Sekunden gearbeitet")

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun completedWorkDrawsDiagonalQuarterBlocksInsteadOfSuccessCircle() {
        compose.setContent {
            StatusGlyph(
                StatusGlyphKind.WORK_COMPLETED, Color.Black,
                Modifier.size(40.dp).background(Color.White).testTag("completedWork"),
                animate = false,
            )
        }
        val pixels = compose.onNodeWithTag("completedWork").captureToImage().toPixelMap()
        fun at(x: Float, y: Float) = pixels[(pixels.width * x).toInt(), (pixels.height * y).toInt()]
        assertEquals(Color.Black, at(0.35f, 0.35f))
        assertEquals(Color.Black, at(0.65f, 0.65f))
        assertEquals(Color.White, at(0.65f, 0.35f))
        assertEquals(Color.White, at(0.35f, 0.65f))
        assertEquals(Color.White, at(0.1f, 0.1f))
    }

    private fun checkDuration(millis: Long, expected: String) {
        compose.setContent {
            MaterialTheme {
                ChatCurrentActivity(SessionActivityLine(
                    R.string.remote_chat_activity_completed, glyphKind = StatusGlyphKind.WORK_COMPLETED,
                    animate = false, completedDurationMillis = millis,
                ))
            }
        }
        compose.onNodeWithText(expected).assertExists()
        compose.onNodeWithTag("chatCurrentActivityGlyph").assertExists()
        compose.onNodeWithTag("activityMarquee").assertDoesNotExist()
    }
}
