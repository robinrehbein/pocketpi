package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionActivityAlignmentUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun activityContentMovesTogetherTowardStartInLtr() = checkAlignment(LayoutDirection.Ltr)

    @Test fun activityContentMovesTogetherTowardStartInRtl() = checkAlignment(LayoutDirection.Rtl)

    private fun checkAlignment(direction: LayoutDirection) {
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                MaterialTheme {
                    Box(Modifier.width(320.dp)) {
                        ChatCurrentActivity(SessionActivityLine(
                            R.string.remote_chat_activity_working, "Task", StatusGlyphKind.TASK_RUNNING,
                        ))
                    }
                }
            }
        }
        val row = compose.onNodeWithTag("chatCurrentActivity").fetchSemanticsNode().boundsInRoot
        val text = compose.onNodeWithTag("activityMarquee").fetchSemanticsNode().boundsInRoot
        // 10dp start inset + 14dp glyph + the unchanged 8dp gap; end inset is 22dp.
        val start = if (direction == LayoutDirection.Ltr) text.left - row.left else row.right - text.right
        val end = if (direction == LayoutDirection.Ltr) row.right - text.right else text.left - row.left
        assertEquals(32f * density, start, 1f)
        assertEquals(22f * density, end, 1f)
        // Total reserved width remains 54dp, so marquee timing/overflow does not change.
        assertEquals(row.width - 54f * density, text.width, 2f)
    }
}
