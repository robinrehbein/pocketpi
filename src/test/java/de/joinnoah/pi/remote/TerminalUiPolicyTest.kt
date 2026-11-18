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


    @Test fun sendQueuesTheDraftThenEnterSoTheCommandRuns() {
        assertEquals(listOf(TerminalInput.Text("ls"), TerminalInput.Key("enter")), terminalInputForSend("ls"))
        assertEquals(listOf(TerminalInput.Text("ls"), TerminalInput.Chord("enter")), terminalInputForSend("ls", structured = true))
        // An empty draft still presses Enter.
        assertEquals(listOf(TerminalInput.Key("enter")), terminalInputForSend(""))
        // A multi-line draft keeps the paste rules: one Paste, then Enter.
        assertEquals(listOf(TerminalInput.Paste("one\ntwo"), TerminalInput.Key("enter")), terminalInputForSend("one\r\ntwo"))
        assertNull(terminalInputForSend("bad\u001b[200~"))
        // Draft and Enter are reserved together, consuming the draft, or not at all.
        val rendered = TerminalView("s", attaching = false, generation = 3)
        val accepted = terminalPrepareEnqueue(TerminalDraftQueue("ls", emptyList()), rendered, rendered, 0, terminalInputForSend("ls")!!, consumesDraft = true)
        assertEquals(TerminalEnqueueDecision.ACCEPTED, accepted.decision)
        assertEquals(TerminalDraftQueue("", listOf(TerminalInput.Text("ls"), TerminalInput.Key("enter"))), accepted.state)
        val busy = terminalPrepareEnqueue(TerminalDraftQueue("ls", emptyList()), rendered, rendered.copy(inputPending = true), 0, terminalInputForSend("ls")!!, consumesDraft = true)
        assertEquals(TerminalDraftQueue("ls", emptyList()), busy.state)
    }

    @Test fun insertSendsOnlyTheDraftWithoutEnter() {
        assertEquals(listOf(TerminalInput.Text("git status")), terminalInputForInsert("git status"))
        assertEquals(listOf(TerminalInput.Paste("one\ntwo")), terminalInputForInsert("one\ntwo"))
        assertNull(terminalInputForInsert(""))
        assertNull(terminalInputForInsert("bad\u001b"))
    }

    @Test fun everyEndReasonHasItsOwnTextAndRecovery() {
        fun end(reason: String, code: Int? = null) = terminalEnd(reason, code)
        assertEquals(TerminalEnd(R.string.remote_terminal_exit_code, TerminalEndKind.INFO, 2), end("exit", 2))
        assertEquals(TerminalEnd(R.string.remote_terminal_exit, TerminalEndKind.INFO), end("exit"))
        assertEquals(R.string.remote_terminal_slow, end("slow").text)
        assertEquals(R.string.remote_terminal_forbidden, end("forbidden").text)
        assertEquals(R.string.remote_terminal_unsupported, end("unsupported").text)
        assertEquals(R.string.remote_terminal_overflow, end("overflow").text)
        assertEquals(R.string.remote_terminal_connection_problem, end("protocol").text)
        assertEquals(R.string.remote_terminal_connection_problem, end("sequence").text)
        assertEquals(R.string.remote_terminal_connection_problem, end("resize_failed").text)
        assertEquals(R.string.remote_terminal_input_failed, end("input_failed").text)
        assertEquals(R.string.remote_terminal_failed, end("failed").text)
        assertEquals(R.string.remote_view_disconnected, end("offline").text)
        assertEquals(R.string.remote_terminal_revoked, end("ownership").text)
        assertEquals(R.string.remote_terminal_detached, end("left").text)
        assertEquals(R.string.remote_terminal_stopped, end("something new").text)
        for (reason in listOf("offline", "slow", "overflow", "protocol", "sequence", "input_failed", "resize_failed", "failed"))
            assertEquals(reason, TerminalEndKind.RETRY, end(reason).kind)
        for (reason in listOf("exit", "revoked", "idle", "detached", "left", "ownership"))
            assertEquals(reason, TerminalEndKind.INFO, end(reason).kind)
        // Attaching again cannot change a refusal.
        for (reason in listOf("forbidden", "unsupported")) assertFalse(reason, end(reason).canReattach)
    }

    @Test fun openingAnEndedTerminalAttachesAgain() {
        val live = TerminalView("s", attaching = false, generation = 3)
        assertTrue(terminalOpenAttaches(open = false, view = null))
        assertFalse(terminalOpenAttaches(open = true, view = live))
        assertFalse(terminalOpenAttaches(open = true, view = TerminalView("s")))
        assertTrue(terminalOpenAttaches(open = true, view = live.copy(ended = "exit", exitCode = 0)))
        assertTrue(terminalOpenAttaches(open = true, view = live.copy(ended = "offline")))
        assertFalse(terminalOpenAttaches(open = true, view = live.copy(ended = "forbidden")))
    }

    @Test fun queuedInputNeverGoesIntoANewAttachmentEvenWhenThePaneMissedTheOldOneEnding() {
        // Send was [Text, Enter]; Enter is still queued when the app goes to the background.
        val old = TerminalView("s", attaching = false, generation = 3)
        val rendered = TerminalRenderedAttachment("s", 3)
        val queued = listOf<TerminalInput>(TerminalInput.Key("enter"))
        // Back in front, the first state the pane sees is the live reattachment: never the end.
        val reattached = terminalAttachmentUpdate(rendered, old.copy(generation = 4), listOf("tail"), queued)
        assertEquals(TerminalRenderedAttachment("s", 4), reattached.attachment)
        assertEquals(emptyList<TerminalInput>(), reattached.input)
        assertTrue("the pane says input was not sent", reattached.inputDropped)
        assertEquals(listOf(TERMINAL_RESET), reattached.output)
        // Attaching again (no attachment yet) drops it as well.
        val attaching = terminalAttachmentUpdate(rendered, TerminalView("s"), emptyList(), queued)
        assertTrue(attaching.inputDropped)
        assertEquals(emptyList<TerminalInput>(), attaching.input)
        // The same attachment keeps its queue; an empty queue drops nothing to report.
        assertEquals(TerminalAttachmentUpdate(rendered, listOf("a"), queued, false), terminalAttachmentUpdate(rendered, old.copy(inputPending = true), listOf("a"), queued))
        assertFalse(terminalAttachmentUpdate(rendered, old.copy(generation = 4), emptyList(), emptyList()).inputDropped)
    }

    @Test fun aNewAttachmentDropsOldOutputAndStartsOnAResetScreen() {
        val first = TerminalView("s", attaching = false, generation = 3)
        var (rendered, pending) = terminalPendingWrites(null, first, emptyList())
        assertEquals(TerminalRenderedAttachment("s", 3), rendered)
        assertEquals(listOf(TERMINAL_RESET), pending)
        // The same attachment keeps its unwritten output, also once it ended.
        assertEquals(rendered to listOf("a", "b"), terminalPendingWrites(rendered, first, listOf("a", "b")))
        assertEquals(rendered to listOf("a"), terminalPendingWrites(rendered, first.copy(ended = "offline"), listOf("a")))
        // Attaching again forgets the old attachment; the next one resets the screen first.
        val attaching = terminalPendingWrites(rendered, TerminalView("s"), listOf("old"))
        assertNull(attaching.first)
        pending = terminalPendingWrites(attaching.first, first.copy(generation = 4), attaching.second).second
        assertEquals(listOf(TERMINAL_RESET), pending)
        // Even without the attaching state in between, a new generation never mixes with the old output.
        assertEquals(TerminalRenderedAttachment("s", 4) to listOf(TERMINAL_RESET), terminalPendingWrites(rendered, first.copy(generation = 4), listOf("old")))
        // A held terminal shows nothing of its own.
        assertEquals(rendered to listOf("a"), terminalPendingWrites(rendered, first.copy(generation = 9, holder = "mac"), listOf("a")))
    }

    @Test fun overTheChatTheTerminalShowsOnlyAsTheActiveTab() {
        assertEquals(PaneOverlayPlacement.SHOWN, terminalOverlayPlacement(panesBeside = false, inspectorShown = false, active = true))
        // A Files or Changes pane opened after it is not covered; the terminal stays composed.
        assertEquals(PaneOverlayPlacement.KEPT_HIDDEN, terminalOverlayPlacement(panesBeside = false, inspectorShown = false, active = false))
        assertEquals(PaneOverlayPlacement.KEPT_HIDDEN, terminalOverlayPlacement(panesBeside = true, inspectorShown = false, active = true))
        assertEquals(PaneOverlayPlacement.INSPECTOR, terminalOverlayPlacement(panesBeside = true, inspectorShown = true, active = false))
    }
}
