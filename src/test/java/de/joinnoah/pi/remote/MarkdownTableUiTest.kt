package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
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
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class MarkdownTableUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun narrowGermanTableWrapsAndStreamingKeepsContent() {
        val first = "Diese ausführliche deutsche Beschreibung erklärt die Einrichtung und alle notwendigen Schritte."
        val second = "Weitere hilfreiche Informationen stehen hier auch während der laufenden Übertragung zur Verfügung."
        val text = mutableStateOf("Beschreibung | Hinweise\n--- | --")
        compose.setContent { MaterialTheme { Box(Modifier.width(320.dp)) { MarkdownText(text.value) } } }
        compose.onNodeWithText("Beschreibung | Hinweise").assertExists()
        compose.runOnIdle { text.value = "Beschreibung | Hinweise\n--- | ---\n$first | $second" }
        val left = compose.onNodeWithText(first).fetchSemanticsNode().boundsInRoot
        val right = compose.onNodeWithText(second).fetchSemanticsNode().boundsInRoot
        assertEquals(left.width, right.width, 0.5f)
        assertEquals(left.top, right.top, 0.5f)
        assertEquals(left.height, right.height, 0.5f)
        compose.onNodeWithText(first).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { action ->
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            action(results)
            assertTrue(results.single().lineCount > 1)
        }
        compose.runOnIdle { text.value += "\nTeilweise |" }
        compose.onNodeWithText("Teilweise").assertExists()
        compose.runOnIdle { text.value += " vollständig" }
        compose.onNodeWithText("vollständig").assertExists()
    }

    @Test fun fencedTablesRemainCodeAndWideTablesScroll() {
        compose.setContent { MaterialTheme { Box(Modifier.width(320.dp)) {
            MarkdownText("```\nA | B\n--- | ---\n```\nA | B | C\n--- | --- | ---\neins | zwei | drei")
        } } }
        compose.onNodeWithText("A | B\n--- | ---").assertExists()
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("drei"))).performScrollToNode(hasText("drei"))
        compose.onNodeWithText("drei").assertIsDisplayed()
    }
}
