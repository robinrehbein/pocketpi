package de.joinnoah.pi.remote

import androidx.compose.ui.input.key.Key
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerKeyTest {
    private fun key(
        key: Key,
        suggestions: Boolean = false,
        typed: Boolean = false,
        prediction: Boolean = false,
        empty: Boolean = false,
        recalling: Boolean = false,
        hardware: Boolean = true,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
        keyDown: Boolean = true,
    ) = composerKey(
        key,
        keyDown,
        ctrl = ctrl,
        alt = alt,
        shift = shift,
        hardwareKeyboard = hardware,
        suggestionsOpen = suggestions,
        highlightTyped = typed,
        predictionShown = prediction,
        draftEmpty = empty,
        recalling = recalling,
    )

    @Test
    fun openSuggestionsTakeTheArrowsTabAndEnter() {
        assertEquals(ComposerKey.SUGGESTION_UP, key(Key.DirectionUp, suggestions = true))
        assertEquals(ComposerKey.SUGGESTION_DOWN, key(Key.DirectionDown, suggestions = true))
        assertEquals(ComposerKey.SUGGESTION_RUN, key(Key.Tab, suggestions = true))
        // Esc is the layout's (ChatKeyTarget.escape), which closes the list first.
        assertEquals(ComposerKey.DEFAULT, key(Key.Escape, suggestions = true))
        // Arrows walk the list even in an empty composer with a prediction.
        assertEquals(ComposerKey.SUGGESTION_UP, key(Key.DirectionUp, suggestions = true, empty = true, prediction = true))
    }

    @Test
    fun enterWithAPartialCommandRunsTheHighlightAndNeverSendsIt() {
        // "/com": Enter picks the highlighted command instead of sending "/com" as a prompt.
        assertEquals(ComposerKey.SUGGESTION_RUN, key(Key.Enter, suggestions = true, typed = false))
        assertEquals(ComposerKey.SUGGESTION_RUN, key(Key.NumPadEnter, suggestions = true, typed = false))
        // "/compact" typed in full: Enter goes on to composerEnter, which sends it.
        assertEquals(ComposerKey.DEFAULT, key(Key.Enter, suggestions = true, typed = true))
        // Tab completes either way.
        assertEquals(ComposerKey.SUGGESTION_RUN, key(Key.Tab, suggestions = true, typed = true))
    }

    @Test
    fun tabOrRightTakesThePredictionInAnEmptyComposer() {
        assertEquals(ComposerKey.ACCEPT_PREDICTION, key(Key.Tab, prediction = true, empty = true))
        assertEquals(ComposerKey.ACCEPT_PREDICTION, key(Key.DirectionRight, prediction = true, empty = true))
        // With text typed, Tab and Right stay the field's.
        assertEquals(ComposerKey.DEFAULT, key(Key.Tab, prediction = true, empty = false))
        assertEquals(ComposerKey.DEFAULT, key(Key.DirectionRight, prediction = true, empty = false))
        assertEquals(ComposerKey.DEFAULT, key(Key.Tab, prediction = false, empty = true))
    }

    @Test
    fun upInAnEmptyComposerRecallsAndDownWalksBackWhileRecalling() {
        assertEquals(ComposerKey.PREVIOUS_MESSAGE, key(Key.DirectionUp, empty = true))
        assertEquals(ComposerKey.DEFAULT, key(Key.DirectionDown, empty = true))
        assertEquals(ComposerKey.PREVIOUS_MESSAGE, key(Key.DirectionUp, recalling = true))
        assertEquals(ComposerKey.NEXT_MESSAGE, key(Key.DirectionDown, recalling = true))
        // Typed text keeps its arrows for the cursor.
        assertEquals(ComposerKey.DEFAULT, key(Key.DirectionUp))
        assertEquals(ComposerKey.DEFAULT, key(Key.DirectionDown))
    }

    @Test
    fun modifiersKeyUpsAndTheOnScreenKeyboardLeaveTheKeysAlone() {
        assertEquals(ComposerKey.DEFAULT, key(Key.Tab, suggestions = true, hardware = false))
        assertEquals(ComposerKey.DEFAULT, key(Key.DirectionUp, empty = true, hardware = false))
        assertEquals(ComposerKey.DEFAULT, key(Key.Enter, suggestions = true, keyDown = false))
        assertEquals(ComposerKey.DEFAULT, key(Key.Enter, suggestions = true, shift = true))
        assertEquals(ComposerKey.DEFAULT, key(Key.Enter, suggestions = true, alt = true))
        assertEquals(ComposerKey.DEFAULT, key(Key.DirectionUp, empty = true, ctrl = true))
        assertEquals(ComposerKey.DEFAULT, key(Key.A, suggestions = true, empty = true, prediction = true))
    }

    @Test
    fun theHighlightWrapsAtBothEnds() {
        assertEquals(1, moveHighlight(0, 3, 1))
        assertEquals(0, moveHighlight(2, 3, 1))
        assertEquals(2, moveHighlight(0, 3, -1))
        assertEquals(0, moveHighlight(5, 0, 1))
    }

    private fun message(role: String, text: String) = buildJsonObject {
        put("role", role)
        put("text", text)
    }

    @Test
    fun recallWalksThisSessionsOwnUserMessagesNewestFirst() {
        val history =
            recallableMessages(
                listOf(
                    message("user", "first"),
                    message("assistant", "answer"),
                    message("user", "second"),
                    message("user", "second"),
                    message("tool", "output"),
                    message("user", " "),
                    message("user", "third"),
                )
            )
        assertEquals(listOf("third", "second", "first"), history)
        val recall = MessageRecall()
        assertFalse(recall.recalling(""))
        // Down in an empty composer has nothing newer.
        assertNull(recall.step(history, "", -1))
        assertEquals("third", recall.step(history, "", 1))
        assertTrue(recall.recalling("third"))
        assertEquals("second", recall.step(history, "third", 1))
        assertEquals("first", recall.step(history, "second", 1))
        assertNull(recall.step(history, "first", 1))
        assertEquals("second", recall.step(history, "first", -1))
        assertEquals("third", recall.step(history, "second", -1))
        // Past the newest message the composer is empty again.
        assertEquals("", recall.step(history, "third", -1))
        assertFalse(recall.recalling(""))
    }

    @Test
    fun editingARecalledMessageEndsTheRecall() {
        val history = listOf("third", "second")
        val recall = MessageRecall()
        assertEquals("third", recall.step(history, "", 1))
        assertFalse(recall.recalling("third!"))
        // Once the field is empty again, Up starts over from the newest message.
        assertEquals("third", recall.step(history, "", 1))
    }
}
