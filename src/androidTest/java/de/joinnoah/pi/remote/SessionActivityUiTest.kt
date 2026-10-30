package de.joinnoah.pi.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SessionActivityUiTest {
    @get:Rule val compose = createComposeRule()

    private fun string(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.resources.getString(id)

    private val working get() = string(R.string.remote_chat_activity_working)

    @Test
    fun activeTaskLabelShowsAndIdleOrOfflineLineHides() {
        var line by mutableStateOf<SessionActivityLine?>(
            SessionActivityLine(R.string.remote_chat_activity_working, "Running first"),
        )
        compose.setContent { MaterialTheme { ChatCurrentActivity(line) } }
        compose.onNodeWithTag("chatCurrentActivity").assertIsDisplayed()
        compose.onNodeWithText("$working · Running first").assertExists()
        compose.runOnIdle { line = null }
        compose.onNodeWithTag("chatCurrentActivity").assertDoesNotExist()
        compose.onNodeWithText("$working · Running first").assertDoesNotExist()
    }

    @Test
    fun toolLabelReplacesTheTaskLineWhenTheContextSwitches() {
        var line by mutableStateOf<SessionActivityLine?>(SessionActivityLine(R.string.remote_chat_activity_working, "Running first"))
        compose.setContent { MaterialTheme { ChatCurrentActivity(line) } }
        compose.onNodeWithText("$working · Running first").assertExists()
        compose.runOnIdle { line = SessionActivityLine(R.string.remote_tool_read, "src/Main.kt") }
        compose.onNodeWithText("$working · Running first").assertDoesNotExist()
        compose.onNodeWithText("${string(R.string.remote_tool_read)} · src/Main.kt").assertExists()
    }

    @Test
    fun longActivityTextStaysOnOneLineAndEllipsizes() {
        val longTask = "A very long running description ".repeat(30)
        compose.setContent { MaterialTheme { ChatCurrentActivity(SessionActivityLine(R.string.remote_chat_activity_working, longTask)) } }
        compose.onNodeWithText("$working · $longTask").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            assertTrue(action(results))
            assertEquals(1, results.single().lineCount)
            assertTrue(results.single().hasVisualOverflow)
        }
    }
}
