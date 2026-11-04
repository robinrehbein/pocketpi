package de.joinnoah.pi.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EscapeGestureTest {
    @Test
    fun aSecondEscWithinTheWindowStops() {
        val gesture = EscapeGesture()
        assertEquals(EscAction.HINT, gesture.press(1_000, running = true))
        assertEquals(EscAction.STOP, gesture.press(1_000 + ESCAPE_STOP_WINDOW_MILLIS, running = true))
        // The stop used both presses up: the next one starts over.
        assertEquals(EscAction.HINT, gesture.press(2_600, running = true))
    }

    @Test
    fun aLateSecondEscOnlyShowsTheHintAgain() {
        val gesture = EscapeGesture()
        assertEquals(EscAction.HINT, gesture.press(1_000, running = true))
        assertEquals(EscAction.HINT, gesture.press(1_000 + ESCAPE_STOP_WINDOW_MILLIS + 1, running = true))
        assertEquals(EscAction.STOP, gesture.press(3_000, running = true))
    }

    @Test
    fun nothingRunningNeverStopsAndForgetsTheFirstPress() {
        val gesture = EscapeGesture()
        assertEquals(EscAction.NONE, gesture.press(1_000, running = false))
        assertEquals(EscAction.HINT, gesture.press(1_100, running = true))
        assertEquals(EscAction.NONE, gesture.press(1_200, running = false))
        assertEquals(EscAction.HINT, gesture.press(1_300, running = true))
        gesture.reset()
        assertEquals(EscAction.HINT, gesture.press(1_400, running = true))
    }

    @Test
    fun escClosesTheNearestThingFirst() {
        fun step(
            transient: Boolean = false,
            selection: Boolean = false,
            pane: Boolean = false,
            focused: Boolean = false,
            blank: Boolean = true,
            running: Boolean = false,
        ) = escapeStep(transient, selection, pane, focused, blank, running)
        val everything = step(true, true, true, focused = true, blank = false, running = true)
        assertEquals(EscapeStep.CLOSE_TRANSIENT, everything)
        assertEquals(EscapeStep.CLEAR_SELECTION, step(selection = true, pane = true, focused = true, running = true))
        assertEquals(EscapeStep.CLOSE_PANE, step(pane = true, focused = true, blank = false, running = true))
        assertEquals(EscapeStep.BLUR_COMPOSER, step(focused = true, blank = false, running = true))
        assertEquals(EscapeStep.BLUR_COMPOSER, step(focused = true))
        // With nothing typed, the first Esc in a running chat already counts towards the stop.
        assertEquals(EscapeStep.STOP_GESTURE, step(focused = true, running = true))
        assertEquals(EscapeStep.STOP_GESTURE, step(running = true))
        assertEquals(EscapeStep.NOTHING, step())
    }

    @Test
    fun aChatRunsOneStepPerEscAndStopsOnlyOnTheSecondOne() {
        val target = ChatKeyTarget()
        var stops = 0
        var transient = true
        var blurred = 0
        target.running = true
        target.stop = { stops++ }
        target.transientOpen = { transient }
        target.closeTransient = { transient = false }
        target.composerFocused = true
        target.composerBlank = false
        target.blurComposer = {
            blurred++
            target.composerFocused = false
        }
        var paneClosed = 0
        val pane: () -> Unit = { paneClosed++ }
        target.addPane(pane)
        target.escape(1_000)
        assertFalse(transient)
        assertEquals(0, paneClosed)
        target.escape(1_100)
        assertEquals(1, paneClosed)
        target.removePane(pane)
        target.escape(1_200)
        assertEquals(1, blurred)
        assertFalse(target.stopHint)
        assertEquals(0, stops)
        target.escape(1_300)
        assertTrue(target.stopHint)
        assertEquals(0, stops)
        target.escape(1_400)
        assertFalse(target.stopHint)
        assertEquals(1, stops)
    }

    @Test
    fun theNewestPaneClosesFirst() {
        val target = ChatKeyTarget()
        val closed = mutableListOf<String>()
        val changes = { closed += "changes" }
        val tool = { closed += "tool" }
        target.addPane(changes)
        target.addPane(tool)
        assertTrue(target.closeTopPane())
        assertEquals(listOf("tool"), closed)
        target.removePane(tool)
        assertTrue(target.closeTopPane())
        target.removePane(changes)
        assertFalse(target.closeTopPane())
        assertEquals(listOf("tool", "changes"), closed)
    }

    @Test
    fun ctrlPeriodStopsAtOnceButOnlyWhileRunning() {
        val target = ChatKeyTarget()
        var stops = 0
        target.stop = { stops++ }
        assertFalse(target.stopNow())
        target.running = true
        target.escape(1_000)
        assertTrue(target.stopHint)
        assertTrue(target.stopNow())
        assertFalse(target.stopHint)
        assertEquals(1, stops)
    }

    @Test
    fun escLeavesAQuestionFieldWithAnAnswerBeforeItStops() {
        val target = ChatKeyTarget()
        var stops = 0
        var blurs = 0
        target.stop = { stops++ }
        target.blurComposer = { blurs++ }
        target.running = true
        val field = Any()
        target.questionFieldFocus(field, focused = true, blank = false)
        target.escape(1_000)
        target.escape(1_200)
        // Each Esc leaves the field (the layout parks focus); none of them stops pi.
        assertEquals(2, blurs)
        assertEquals(0, stops)
        assertFalse(target.stopHint)
        // A blank focused field stops with Esc, Esc, like the composer.
        target.questionFieldFocus(field, focused = true, blank = true)
        target.escape(2_000)
        target.escape(2_200)
        assertEquals(1, stops)
        // Another field's late focus loss does not clear this one's.
        target.questionFieldFocus(Any(), focused = false, blank = true)
        assertTrue(target.questionFieldFocused)
        target.questionFieldFocus(field, focused = false, blank = true)
        assertFalse(target.questionFieldFocused)
    }

    @Test
    fun escLeavesReviewModeBeforeAnythingElse() {
        assertEquals(
            EscapeStep.LEAVE_REVIEW,
            escapeStep(true, true, true, composerFocused = true, composerBlank = false, running = true, reviewing = true),
        )
        val target = ChatKeyTarget()
        var reviewing = true
        var closedPanes = 0
        target.reviewing = { reviewing }
        target.leaveReview = { reviewing = false }
        target.addPane { closedPanes++ }
        target.escape(0)
        assertFalse(reviewing)
        assertEquals(0, closedPanes)
        // The next Esc closes the pane as before.
        target.escape(10)
        assertEquals(1, closedPanes)
    }
}
