package de.joinnoah.pi.remote

import org.junit.Assert.*
import org.junit.Test

class TerminalUiPolicyTest {
    @Test fun pendingChordThenRejectedCannotDiscardDraftThroughEnter() {
        val rendered = TerminalView("s", attaching = false, generation = 3, keysEnabled = true)
        val pending = rendered.copy(inputPending = true)
        val state = TerminalDraftQueue("keep this", emptyList())
        val enter = terminalInputForKey(state.draft, "enter")!!
        val attempt = terminalPrepareEnqueue(state, rendered, pending, 0, enter, consumesDraft = true)
        assertEquals(TerminalEnqueueDecision.BUSY, attempt.decision)
        assertEquals(state, attempt.state)
        val rejected = pending.copy(inputPending = false, inputRejected = 1)
        assertEquals(TerminalQueueAction.CLEAR_REJECTED, terminalQueueAction(0, rejected.inputRejected, rejected.inputPending, attempt.state.queue.isNotEmpty()))
        assertEquals("keep this", attempt.state.draft)
        assertTrue(attempt.state.queue.isEmpty())
    }

    @Test fun draftEnqueueRefusesQueuedInputAndChangedAttachment() {
        val rendered = TerminalView("s", attaching = false, generation = 3)
        val state = TerminalDraftQueue("draft", listOf(TerminalInput.Key("up")))
        val batch = listOf(TerminalInput.Text("draft"))
        assertEquals(TerminalEnqueueDecision.BUSY, terminalPrepareEnqueue(state, rendered, rendered, 0, batch, true).decision)
        val empty = state.copy(queue = emptyList())
        assertEquals(TerminalEnqueueDecision.UNAVAILABLE, terminalPrepareEnqueue(empty, rendered, rendered.copy(generation = 4), 0, batch, true).decision)
        assertEquals(TerminalEnqueueDecision.BUSY, terminalPrepareEnqueue(empty, rendered, rendered.copy(inputRejected = 1), 0, batch, true).decision)
        val accepted = terminalPrepareEnqueue(empty, rendered, rendered, 0, batch, true)
        assertEquals(TerminalEnqueueDecision.ACCEPTED, accepted.decision)
        assertEquals(TerminalDraftQueue("", batch), accepted.state)
    }
    @Test fun rejectedInputClearsOnlyUnsentQueueBeforeAnotherEnterCanRun() {
        val queued = listOf(TerminalInput.Key("enter"))
        assertEquals(TerminalQueueAction.CLEAR_REJECTED, terminalQueueAction(0, 1, false, queued.isNotEmpty()))
        assertEquals(TerminalQueueAction.SEND, terminalQueueAction(1, 1, false, queued.isNotEmpty()))
        assertEquals(TerminalQueueAction.WAIT, terminalQueueAction(1, 1, false, false))
    }
    @Test fun heldNavigationKeyCoalescesIntoBoundedChunks() {
        var queue = emptyList<TerminalInput>()
        repeat(100) { queue = terminalQueueAfterEnqueue(queue, listOf(TerminalInput.Key("down")))!! }
        assertEquals(listOf(16, 16, 16, 16, 16, 16, 4), queue.map { (it as TerminalInput.Key).repeat })
        assertEquals(100, queue.sumOf { (it as TerminalInput.Key).repeat })
    }

    @Test fun coalescingRespectsIdentityAndNeverCombinesControlLettersOrEnter() {
        val batch = listOf(
            TerminalInput.Chord("left", ctrl = true), TerminalInput.Chord("left", ctrl = true),
            TerminalInput.Chord("left", alt = true), TerminalInput.Chord("right", alt = true),
            TerminalInput.Chord("c", ctrl = true), TerminalInput.Chord("c", ctrl = true),
            TerminalInput.Key("enter"), TerminalInput.Key("enter"),
        )
        val result = terminalQueueAfterEnqueue(emptyList(), batch)!!
        assertEquals(7, result.size)
        assertEquals(TerminalInput.Chord("left", ctrl = true, repeat = 2), result.first())
        assertEquals(batch.drop(2), result.drop(1))
    }

    @Test fun fullBatchIsAtomicAndOnlyStillQueuedEntriesCanMerge() {
        val full = List(63) { TerminalInput.Text("x") }
        assertNull(terminalQueueAfterEnqueue(full, listOf(TerminalInput.Key("up"), TerminalInput.Key("enter"))))
        assertEquals(63, full.size)
        // A sent key is absent from the queue and cannot be replayed as part of a repeat.
        val queued = terminalQueueAfterEnqueue(emptyList(), listOf(TerminalInput.Key("up")))!!
        assertEquals(listOf(TerminalInput.Key("up")), queued)
        val almostFull = List(63) { TerminalInput.Text("x") } + TerminalInput.Key("up")
        assertEquals(2, (terminalQueueAfterEnqueue(almostFull, listOf(TerminalInput.Key("up")))!!.last() as TerminalInput.Key).repeat)
    }
    @Test fun modifiedKeysPreserveDraftExceptEnter() {
        val ctrlC = TerminalInput.Chord("c", ctrl = true)
        assertEquals(listOf(ctrlC), terminalInputForChord("unsent", ctrlC))
        val enter = TerminalInput.Chord("enter", alt = true)
        assertEquals(listOf(TerminalInput.Text("unsent"), enter), terminalInputForChord("unsent", enter))
        assertFalse(terminalQueueCanAcceptAll(List(63) { TerminalInput.Key("up") }, terminalInputForChord("unsent", enter)!!))
    }

    @Test fun structuredKeyBoundsAndFields() {
        assertNull(terminalInputField(TerminalInput.Chord("\u001b", ctrl = true)))
        assertNull(terminalInputField(TerminalInput.Chord("f13")))
        assertNull(terminalInputField(TerminalInput.Chord("c", repeat = 17)))
        assertNull(terminalInputField(TerminalInput.Chord("a", shifted = "A")))
        assertNull(terminalInputField(TerminalInput.Chord("A", shift = true)))
        assertNull(terminalInputField(TerminalInput.Chord("A", ctrl = true)))
        assertNull(terminalInputField(TerminalInput.Chord("A", alt = true)))
        assertNull(terminalInputField(TerminalInput.Chord("enter", shift = true, shifted = "A")))
        assertNotNull(terminalInputField(TerminalInput.Chord("2", shift = true, shifted = "\"")))
        val key = terminalInputField(TerminalInput.Chord("c", ctrl = true))!!.second as kotlinx.serialization.json.JsonObject
        assertEquals(setOf("name", "ctrl"), key.keys)
    }
    @Test fun normalizesPasteAndRejectsUnsafeControls() {
        assertEquals("one\ntwo\nthree    four", normalizeTerminalPaste("one\r\ntwo\rthree\tfour"))
        assertNull(normalizeTerminalPaste("bad\u001b[200~paste"))
    }

    @Test fun queueRejectsOverflowAndInvalidInput() {
        assertFalse(terminalQueueCanAccept(List(64) { TerminalInput.Key("up") }, TerminalInput.Text("x")))
        assertFalse(terminalQueueCanAccept(listOf(TerminalInput.Paste("a".repeat(16384))), TerminalInput.Text("x")))
        assertFalse(terminalQueueCanAccept(emptyList(), TerminalInput.Text("\u001b")))
        assertTrue(terminalQueueCanAccept(emptyList(), TerminalInput.Text("hello")))
    }

    @Test fun draftAndEnterAreQueuedInOrderOrRejectedTogether() {
        val batch = terminalInputForKey("ls", "enter")!!
        assertEquals(listOf(TerminalInput.Text("ls"), TerminalInput.Key("enter")), batch)
        assertTrue(terminalQueueCanAcceptAll(emptyList(), batch))
        assertFalse(terminalQueueCanAcceptAll(List(63) { TerminalInput.Key("up") }, batch))
        assertNull(terminalInputForKey("bad\u001b", "enter"))
    }

    @Test fun dimensionsAreClampedToProtocolLimits() {
        assertEquals(20 to 300, terminalDimensions(1, 999))
        assertEquals(500 to 5, terminalDimensions(999, 1))
    }
    @Test fun shiftTabTouchCombinesModifiersAsOneCanonicalChord() {
        assertEquals(TerminalInput.Chord("tab", ctrl = true, shift = true), terminalTouchChord("shift_tab", true, false, false))
        assertEquals(TerminalInput.Chord("tab", alt = true, shift = true), terminalTouchChord("shift_tab", false, true, true))
        assertEquals(TerminalInput.Chord("f1"), terminalTouchChord("f1", false, false, false))
        assertNull(terminalTouchChord("enter", false, false, false))
    }

    @Test fun addonUsesModeAwareNamedKeysWithExistingDraftOrdering() {
        assertEquals(listOf(TerminalInput.Chord("escape")), terminalInputForKey("", "escape", structured = true))
        assertEquals(listOf(TerminalInput.Chord("tab", shift = true)), terminalInputForKey("", "shift_tab", structured = true))
        assertEquals(listOf(TerminalInput.Text("ls"), TerminalInput.Chord("enter")), terminalInputForKey("ls", "enter", structured = true))
        assertEquals(listOf(TerminalInput.Key("escape")), terminalInputForKey("", "escape"))
    }

}
