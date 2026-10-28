package de.joinnoah.pi.remote

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The accept, comment, status and start sheets, the undo bar and the write notice, rendered from state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h1600dp")
class TicketSheetsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = 10_000_000_000L
    private val project = TicketProject("p1", "Atlas", listOf("DEV"), true, null, true, null)

    private fun ticket(
        actions: Set<TicketAction> = setOf(TicketAction.ACCEPT, TicketAction.COMMENT, TicketAction.MOVE),
        moveTargets: List<String> = listOf("Done", "Cancelled"),
    ) =
        Ticket(
            "DEV-1", "Title", "https://plane.example/DEV-1", "Review", TicketStateGroup.STARTED, "feature",
            TicketPriority.NONE, true, TicketSection.ACCEPTANCE, false, actions, moveTargets, now, emptyList(),
        )

    private fun stateOf(
        ticket: Ticket = ticket(),
        project: TicketProject = this.project,
        tickets: TicketsState = TicketsState(),
    ) =
        RemoteState(
            connected = true,
            tickets = tickets.copy(
                routeId = "studio", projects = listOf(project), detailRef = ticket.ref,
                detail = TicketDetail(ticket, "Body"),
            ),
        )

    private val accepted = mutableListOf<String>()
    private val comments = mutableListOf<String>()
    private val moves = mutableListOf<Pair<String, String?>>()
    private val starts = mutableListOf<String?>()
    private val chats = mutableListOf<String>()
    private var undone = 0
    private var dismissed = 0

    private var current by mutableStateOf(RemoteState())

    private fun show(state: RemoteState) {
        current = state
        compose.setContent {
            MaterialTheme {
                TicketDetailContent(
                    current, "p1", "DEV-1", onBack = {}, onRetry = {}, onOpenChat = { chats += it },
                    onOpenLink = { true },
                    onAccept = { accepted += it },
                    onComment = { comments += it },
                    onMove = { target, comment -> moves += target to comment },
                    onStart = { starts += it },
                    onUndo = { undone++ },
                    onDismissNotice = { dismissed++ },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun label(id: Int) = compose.activity.getString(id)

    private fun tap(tag: String) = compose.onNodeWithTag(tag).performClick()

    @Test
    fun acceptQueuesAndShowsUndo() {
        show(stateOf())
        compose.onNodeWithText(label(R.string.remote_ticket_accept)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.remote_tickets_accept_title, "DEV-1")).assertIsDisplayed()
        // The comment is prefilled and editable.
        compose.onNodeWithText(label(R.string.remote_tickets_accept_comment)).assertIsDisplayed()
        tap("ticketSheetConfirm")
        compose.waitForIdle()
        assertEquals(listOf("Accepted in PocketPi."), accepted)
        // The sheet closed.
        compose.onAllNodesWithText(compose.activity.getString(R.string.remote_tickets_accept_title, "DEV-1")).assertCountEquals(0)

        current = current.copy(tickets = current.tickets.copy(pendingAccept = PendingAccept("p1", "DEV-1", "c", now + 5_000)))
        compose.waitForIdle()
        compose.onNodeWithText(compose.activity.getString(R.string.remote_tickets_accepting, "DEV-1")).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.remote_tickets_undo)).assertIsDisplayed()
    }

    @Test
    fun acceptCommentCanBeEdited() {
        show(stateOf())
        compose.onNodeWithText(label(R.string.remote_ticket_accept)).performClick()
        compose.onNodeWithTag("ticketSheetField").performTextClearance()
        compose.onNodeWithTag("ticketSheetConfirm").assertIsNotEnabled()
        compose.onNodeWithTag("ticketSheetField").performTextInput("Looks good")
        tap("ticketSheetConfirm")
        assertEquals(listOf("Looks good"), accepted)
    }

    @Test
    fun undoRemovesBarAndSendsNothing() {
        show(stateOf(tickets = TicketsState(pendingAccept = PendingAccept("p1", "DEV-1", "c", now + 5_000))))
        compose.onNodeWithText(label(R.string.remote_tickets_undo)).performClick()
        assertEquals(1, undone)
        // The loader answers the undo by clearing the pending accept; nothing else was sent.
        current = current.copy(tickets = current.tickets.copy(pendingAccept = null))
        compose.waitForIdle()
        compose.onAllNodesWithText(label(R.string.remote_tickets_undo)).assertCountEquals(0)
        assertTrue(accepted.isEmpty() && comments.isEmpty() && moves.isEmpty() && starts.isEmpty())
    }

    @Test
    fun commentSendDisabledWhenBlank() {
        show(stateOf())
        compose.onNodeWithText(label(R.string.remote_ticket_comment)).performClick()
        compose.onNodeWithTag("ticketSheetConfirm").assertIsNotEnabled()
        compose.onNodeWithTag("ticketSheetField").performTextInput("   ")
        compose.onNodeWithTag("ticketSheetConfirm").assertIsNotEnabled()
        compose.onNodeWithTag("ticketSheetField").performTextInput("Hello")
        compose.onNodeWithTag("ticketSheetConfirm").assertIsEnabled()
        tap("ticketSheetConfirm")
        assertEquals(listOf("Hello"), comments)
    }

    @Test
    fun statusSheetOmitsDoneAndRequiresCommentForCancelled() {
        show(stateOf(ticket(moveTargets = listOf("Done", "Review", "Todo", "Cancelled"))))
        compose.onNodeWithText(label(R.string.remote_ticket_status)).performClick()
        compose.onNodeWithText("Todo").assertIsDisplayed()
        compose.onNodeWithText("Cancelled").assertIsDisplayed()
        // Done goes through accept; Review is the agent's to set.
        compose.onAllNodesWithText("Done").assertCountEquals(0)
        assertEquals(listOf("Todo", "Cancelled"), statusTargets(ticket(moveTargets = listOf("Done", "Review", "Todo", "Cancelled"))))

        compose.onNodeWithTag("ticketSheetConfirm").assertIsNotEnabled()
        compose.onNodeWithText("Cancelled").performClick()
        compose.onNodeWithTag("ticketSheetConfirm").assertIsNotEnabled()
        compose.onNodeWithTag("ticketSheetField").performTextInput("Not needed")
        compose.onNodeWithTag("ticketSheetConfirm").assertIsEnabled()
        tap("ticketSheetConfirm")
        assertEquals(listOf("Cancelled" to "Not needed"), moves)
    }

    @Test
    fun statusWithoutCommentNeedsNoField() {
        show(stateOf(ticket(moveTargets = listOf("Todo", "Cancelled"))))
        compose.onNodeWithText(label(R.string.remote_ticket_status)).performClick()
        compose.onNodeWithText("Todo").performClick()
        tap("ticketSheetConfirm")
        assertEquals(listOf<Pair<String, String?>>("Todo" to null), moves)
    }

    private fun startable() =
        stateOf(
            ticket(setOf(TicketAction.START, TicketAction.COMMENT, TicketAction.MOVE), listOf("Todo")),
        )

    private fun job(
        state: TicketStartState,
        step: TicketStartStep,
        reused: Boolean = false,
        moved: Boolean? = null,
        sessionId: String? = null,
        error: TicketStartError? = null,
    ) = TicketStart("abc", state, step, reused, moved, sessionId, error, "p1", "DEV-1")

    @Test
    fun startShowsProgressThenOpensChat() {
        val base = startable()
        show(base)
        compose.onNodeWithText(label(R.string.remote_ticket_start)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.remote_tickets_start_branch, "task/dev-1-…")).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.remote_tickets_start_moves)).assertIsDisplayed()
        compose.onNodeWithTag("ticketSheetField").performTextInput("Mind the migration")
        tap("ticketSheetConfirm")
        assertEquals(listOf<String?>("Mind the migration"), starts)

        current = base.copy(tickets = base.tickets.copy(start = job(TicketStartState.RUNNING, TicketStartStep.SESSION, moved = true)))
        compose.waitForIdle()
        compose.onNodeWithText(label(R.string.remote_tickets_step_session)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.remote_tickets_step_worktree)).assertIsDisplayed()
        assertTrue(chats.isEmpty())

        current = base.copy(
            tickets = base.tickets.copy(
                start = job(TicketStartState.SUCCEEDED, TicketStartStep.DONE, moved = true, sessionId = "s9"),
            ),
        )
        compose.waitForIdle()
        assertEquals(listOf("s9"), chats)
    }

    @Test
    fun anotherTicketsRunningStartIsNeitherShownNorOpened() {
        val base = startable()
        val other = job(TicketStartState.RUNNING, TicketStartStep.SESSION).copy(ref = "DEV-2")
        show(base.copy(tickets = base.tickets.copy(start = other)))
        compose.onNodeWithText(label(R.string.remote_ticket_start)).performClick()
        // The host runs one start at a time: the sheet says so instead of offering a start that is refused.
        compose.onNodeWithTag("ticketSheetConfirm").assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.remote_tickets_start_other_running)).assertIsDisplayed()
        current = base.copy(
            tickets = base.tickets.copy(
                start = other.copy(state = TicketStartState.SUCCEEDED, step = TicketStartStep.DONE, sessionId = "other"),
            ),
        )
        compose.waitForIdle()
        assertTrue(chats.isEmpty())
        compose.onNodeWithTag("ticketSheetConfirm").assertIsEnabled()
    }

    @Test
    fun confirmsAreDisabledWhileOffline() {
        show(stateOf().copy(connected = false))
        // Buttons are disabled offline, so the sheets cannot be opened at all.
        compose.onNodeWithText(label(R.string.remote_ticket_accept)).assertIsNotEnabled()
    }

    @Test
    fun aSubmitThatWentNowhereFailsInsteadOfSpinning() {
        val base = startable()
        show(base)
        compose.onNodeWithText(label(R.string.remote_ticket_start)).performClick()
        tap("ticketSheetConfirm")
        current = base.copy(connected = false)
        compose.waitForIdle()
        compose.onNodeWithText(label(R.string.remote_tickets_write_failed)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.remote_tickets_start_close)).assertIsDisplayed()
    }

    @Test
    fun aRefusedStartShowsTheFailureInsteadOfSpinning() {
        val base = startable()
        show(base)
        compose.onNodeWithText(label(R.string.remote_ticket_start)).performClick()
        tap("ticketSheetConfirm")
        current = base.copy(tickets = base.tickets.copy(writeFailure = TicketsFailure.BUSY, writeRef = "DEV-1"))
        compose.waitForIdle()
        val text = compose.activity.getString(
            R.string.remote_tickets_notice_ref, "DEV-1", label(R.string.remote_tickets_failure_busy),
        )
        compose.onNodeWithText(text).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.remote_tickets_start_close)).assertIsDisplayed()
    }

    @Test
    fun anotherTicketsFailureDoesNotEndThisStart() {
        val base = startable()
        show(base)
        compose.onNodeWithText(label(R.string.remote_ticket_start)).performClick()
        tap("ticketSheetConfirm")
        current = base.copy(tickets = base.tickets.copy(writeFailure = TicketsFailure.FAILED, writeRef = "DEV-2"))
        compose.waitForIdle()
        compose.onNodeWithTag("ticketStartProgress").assertIsDisplayed()
        compose.onAllNodesWithText(label(R.string.remote_tickets_start_close)).assertCountEquals(0)
    }

    @Test
    fun startStepsTellTheirStateToTalkBack() {
        val base = startable()
        show(base)
        compose.onNodeWithText(label(R.string.remote_ticket_start)).performClick()
        tap("ticketSheetConfirm")
        current = base.copy(
            tickets = base.tickets.copy(
                start = job(TicketStartState.FAILED, TicketStartStep.SESSION, error = TicketStartError.SESSION_FAILED),
            ),
        )
        compose.waitForIdle()
        fun step(text: Int, state: Int) =
            compose.onNode(hasText(label(text)) and hasStateDescription(label(state)), useUnmergedTree = false)
        step(R.string.remote_tickets_step_worktree, R.string.remote_tickets_step_state_done).assertIsDisplayed()
        step(R.string.remote_tickets_step_session, R.string.remote_tickets_step_state_failed).assertIsDisplayed()
        step(R.string.remote_tickets_step_prompt, R.string.remote_tickets_step_state_pending).assertIsDisplayed()
        current = base.copy(tickets = base.tickets.copy(start = job(TicketStartState.RUNNING, TicketStartStep.SESSION)))
        compose.waitForIdle()
        step(R.string.remote_tickets_step_session, R.string.remote_tickets_step_state_active).assertIsDisplayed()
    }

    private fun hasStateDescription(text: String) =
        androidx.compose.ui.test.SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription, text,
        )

    @Test
    fun anOldFinishedStartNeverOpensItsChat() {
        val base = startable()
        show(
            base.copy(
                tickets = base.tickets.copy(
                    start = job(TicketStartState.SUCCEEDED, TicketStartStep.DONE, moved = true, sessionId = "old"),
                ),
            ),
        )
        compose.onNodeWithText(label(R.string.remote_ticket_start)).performClick()
        compose.waitForIdle()
        assertTrue(chats.isEmpty())
        compose.onNodeWithTag("ticketSheetConfirm").assertIsEnabled()
    }

    @Test
    fun startFailureShowsStepAndFacts() {
        val base = startable()
        show(base)
        compose.onNodeWithText(label(R.string.remote_ticket_start)).performClick()
        tap("ticketSheetConfirm")
        // The host reports a failed In Progress move as session_failed at step move.
        current = base.copy(
            tickets = base.tickets.copy(
                start = job(
                    TicketStartState.FAILED, TicketStartStep.MOVE, reused = true, moved = false,
                    sessionId = "s9", error = TicketStartError.SESSION_FAILED,
                ),
            ),
        )
        compose.waitForIdle()
        compose.onNodeWithText(label(R.string.remote_tickets_start_failed_move)).assertIsDisplayed()
        compose.onAllNodesWithText(label(R.string.remote_tickets_start_failed_session)).assertCountEquals(0)
        compose.onNodeWithText(label(R.string.remote_tickets_fact_reused)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.remote_tickets_fact_not_moved)).assertIsDisplayed()
        // A session came to exist: the chat stays reachable, and the sheet did not open it by itself.
        compose.onNodeWithText(label(R.string.remote_tickets_start_open_chat)).performClick()
        assertEquals(listOf("s9"), chats)
    }

    @Test
    fun startFailureWordsEachStep() {
        val base = startable()
        show(base)
        compose.onNodeWithText(label(R.string.remote_ticket_start)).performClick()
        tap("ticketSheetConfirm")
        current = base.copy(
            tickets = base.tickets.copy(
                start = job(TicketStartState.FAILED, TicketStartStep.WORKTREE, error = TicketStartError.WORKTREE_FAILED),
            ),
        )
        compose.waitForIdle()
        compose.onNodeWithText(label(R.string.remote_tickets_start_failed_worktree)).assertIsDisplayed()
        compose.onAllNodesWithText(label(R.string.remote_tickets_start_open_chat)).assertCountEquals(0)
    }

    @Test
    fun writeButtonsAbsentWhenNotWritable() {
        show(stateOf(project = project.copy(writable = false, writeBlock = TicketWriteBlock.WRITES_OFF)))
        compose.onAllNodesWithText(label(R.string.remote_ticket_accept)).assertCountEquals(0)
        compose.onAllNodesWithText(label(R.string.remote_ticket_comment)).assertCountEquals(0)
        compose.onAllNodesWithText(label(R.string.remote_ticket_status)).assertCountEquals(0)
        compose.onAllNodesWithText(label(R.string.remote_ticket_start)).assertCountEquals(0)
        compose.onNodeWithText(label(R.string.remote_ticket_write_block_writes_off)).assertIsDisplayed()
        assertTrue(accepted.isEmpty() && comments.isEmpty() && moves.isEmpty() && starts.isEmpty())
    }

    @Test
    fun writeButtonsAbsentWithoutTheUserKey() {
        show(stateOf(project = project.copy(writable = false, writeBlock = TicketWriteBlock.MISSING_USER_KEY)))
        compose.onAllNodesWithText(label(R.string.remote_ticket_accept)).assertCountEquals(0)
        compose.onAllNodesWithText(label(R.string.remote_ticket_comment)).assertCountEquals(0)
        compose.onAllNodesWithText(label(R.string.remote_ticket_status)).assertCountEquals(0)
        compose.onAllNodesWithText(label(R.string.remote_ticket_start)).assertCountEquals(0)
        compose.onNodeWithText(label(R.string.remote_ticket_write_block_missing_user_key)).assertIsDisplayed()
    }

    @Test
    fun outcomeUnknownShowsMessage() {
        show(stateOf(tickets = TicketsState(writeFailure = TicketsFailure.FAILED, outcomeUnknown = true)))
        compose.onNodeWithText(label(R.string.remote_tickets_outcome_unknown)).assertIsDisplayed()
        compose.onAllNodesWithText(label(R.string.remote_tickets_write_failed)).assertCountEquals(0)
        compose.onNodeWithText(label(R.string.remote_tickets_dismiss)).performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun aPlainWriteFailureSaysSo() {
        show(stateOf(tickets = TicketsState(writeFailure = TicketsFailure.FAILED)))
        compose.onNodeWithText(label(R.string.remote_tickets_write_failed)).assertIsDisplayed()
    }
}
