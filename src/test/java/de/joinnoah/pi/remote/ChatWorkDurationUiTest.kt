package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
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

    private fun checkDuration(millis: Long, expected: String) {
        compose.setContent {
            MaterialTheme {
                ChatCurrentActivity(SessionActivityLine(
                    R.string.remote_chat_activity_completed, glyphKind = StatusGlyphKind.DONE,
                    animate = false, completedDurationMillis = millis,
                ))
            }
        }
        compose.onNodeWithText(expected).assertExists()
        compose.onNodeWithTag("chatCurrentActivityGlyph").assertExists()
        compose.onNodeWithTag("activityMarquee").assertDoesNotExist()
    }
}
