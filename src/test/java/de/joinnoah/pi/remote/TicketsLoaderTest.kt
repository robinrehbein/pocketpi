package de.joinnoah.pi.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TicketsLoaderTest {
    private class Fake : TicketSource {
        val calls = mutableListOf<String>()
        var projects = listOf(project("p1"), project("p2"))
        var pages = mutableMapOf<String, List<TicketsPage>>()
        var starts = ArrayDeque<TicketStart>()
        var gate: CompletableDeferred<Unit>? = null
        var failProjects: Exception? = null
        var failMove: Exception? = null
        var failComment: Exception? = null
        var failStart: Exception? = null
        var moveGate: CompletableDeferred<Unit>? = null
        var failStatus: Exception? = null

        override suspend fun ticketProjects(): List<TicketProject> {
            calls += "projects"
            gate?.await()
            failProjects?.let { throw it }
            return projects
        }

        override suspend fun ticketsPage(projectId: String, cursor: String?): TicketsPage {
            calls += "page:$projectId:${cursor ?: ""}"
            val list = pages[projectId] ?: listOf(page(projectId, emptyList()))
            return list[if (cursor == null) 0 else cursor.toInt()]
        }

        override suspend fun ticketDetail(projectId: String, ref: String): TicketDetail {
            calls += "detail:$ref"
            return TicketDetail(ticket(ref))
        }

        override suspend fun reloadTickets(projectId: String): Long {
            calls += "reload:$projectId"
            return 1
        }

        override suspend fun commentTicket(projectId: String, ref: String, text: String) {
            calls += "comment:$ref"
            failComment?.let { throw it }
        }

        override suspend fun moveTicket(projectId: String, ref: String, state: String, comment: String?): Boolean {
            calls += "move:$ref:$state:$comment"
            moveGate?.await()
            failMove?.let { throw it }
            return false
        }

        override suspend fun startTicket(projectId: String, ref: String, note: String?): String {
            calls += "start:$ref"
            failStart?.let { throw it }
            return "startid"
        }

        override suspend fun ticketStartStatus(startId: String): TicketStart {
            calls += "status"
            failStatus?.let { throw it }
            return starts.removeFirst()
        }
    }

    private class Env(scope: kotlinx.coroutines.CoroutineScope, val source: Fake, now: () -> Long) {
        var state = RemoteState(
            host = PairedHost("route", "https://relay.test", "device", "secret", "Mac"),
            connected = true,
            capabilities = setOf(TICKETS_CAPABILITY),
        )
        val loader = TicketsLoader(scope, source, { state }, now) { state = it(state) }
        val tickets get() = state.tickets
    }

    private fun TestScope.env(source: Fake = Fake()) = Env(backgroundScope, source) { testScheduler.currentTime }

    @Test
    fun refreshLoadsProjectsThenPages() = runTest {
        val e = env()
        e.source.pages["p1"] = listOf(page("p1", listOf(ticket("DEV-1", TicketSection.ACCEPTANCE, 5), ticket("DEV-2", TicketSection.ACCEPTANCE, 9))))
        e.loader.refresh(force = false)
        runCurrent()
        assertEquals(listOf("projects", "page:p1:", "page:p2:"), e.source.calls)
        assertEquals("route", e.tickets.routeId)
        assertFalse(e.tickets.loading)
        assertNull(e.tickets.failure)
        assertEquals(listOf("DEV-2", "DEV-1"), acceptanceItems(e.tickets).map { it.second.ref })
    }

    @Test
    fun forceRefreshCallsReloadThenList() = runTest {
        val e = env()
        e.loader.refresh(force = true)
        runCurrent()
        assertEquals(
            listOf("projects", "reload:p1", "page:p1:", "reload:p2", "page:p2:"),
            e.source.calls,
        )
    }

    @Test
    fun freshReadIsNotRepeatedUnlessForced() = runTest {
        val e = env()
        e.loader.refresh(false)
        runCurrent()
        e.loader.refresh(false)
        runCurrent()
        assertEquals(1, e.source.calls.count { it == "projects" })
        e.loader.refresh(true)
        runCurrent()
        assertEquals(2, e.source.calls.count { it == "projects" })
    }

    @Test
    fun followsCursorsAndMergesPages() = runTest {
        val e = env(Fake().apply { projects = listOf(project("p1")) })
        e.source.pages["p1"] = listOf(
            page("p1", listOf(ticket("DEV-1")), "1"),
            page("p1", listOf(ticket("DEV-2"))),
        )
        e.loader.refresh(false)
        runCurrent()
        assertEquals(listOf("DEV-1", "DEV-2"), e.tickets.pages.getValue("p1").items.map { it.ref })
        assertNull(e.tickets.pages.getValue("p1").nextCursor)
    }

    @Test
    fun acceptSendsAfterFiveSeconds() = runTest {
        val e = loaded()
        e.loader.queueAccept("p1", "DEV-1", "Accepted")
        runCurrent()
        assertEquals(5_000L, e.tickets.pendingAccept!!.deadline)
        advanceTimeBy(4_999)
        runCurrent()
        assertTrue(e.source.calls.none { it.startsWith("move") })
        advanceTimeBy(1)
        runCurrent()
        assertTrue("move:DEV-1:Done:Accepted" in e.source.calls)
        assertNull(e.tickets.pendingAccept)
        assertNull(e.tickets.writeFailure)
    }

    @Test
    fun undoBeforeDeadlineSendsNothing() = runTest {
        val e = loaded()
        e.loader.queueAccept("p1", "DEV-1", "Accepted")
        advanceTimeBy(3_000)
        e.loader.undo()
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(e.source.calls.none { it.startsWith("move") })
        assertNull(e.tickets.pendingAccept)
    }

    @Test
    fun connectionLossDuringUndoWindowSendsNothing() = runTest {
        val e = loaded()
        e.loader.queueAccept("p1", "DEV-1", "Accepted")
        advanceTimeBy(2_000)
        e.state = e.state.copy(connected = false)
        e.loader.connectionLost()
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(e.source.calls.none { it.startsWith("move") })
        assertNull(e.tickets.pendingAccept)
    }

    @Test
    fun routeChangeDuringUndoWindowSendsNothing() = runTest {
        val e = loaded()
        e.loader.queueAccept("p1", "DEV-1", "Accepted")
        advanceTimeBy(2_000)
        // No loss callback: the deadline itself checks the route.
        e.state = e.state.copy(host = e.state.host!!.copy(routeId = "other"))
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(e.source.calls.none { it.startsWith("move") })
    }

    @Test
    fun readOnlyProjectNeverSendsAWrite() = runTest {
        val e = loaded(Fake().apply { projects = listOf(project("p1", writable = false)) })
        e.loader.queueAccept("p1", "DEV-1", "Accepted")
        e.loader.comment("p1", "DEV-1", "hi")
        e.loader.move("p1", "DEV-1", "Todo", null)
        e.loader.start("p1", "DEV-1", null)
        advanceTimeBy(10_000)
        runCurrent()
        assertNull(e.tickets.pendingAccept)
        assertTrue(e.source.calls.none { it.startsWith("move") || it.startsWith("comment") || it.startsWith("start") })
    }

    @Test
    fun writeTimeoutIsOutcomeUnknownAndNotRetried() = runTest {
        val e = loaded(Fake().apply { failComment = RemoteRequestException.timeout() })
        e.source.calls.clear()
        e.loader.comment("p1", "DEV-1", "hi")
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, e.source.calls.count { it.startsWith("comment") })
        assertTrue(e.tickets.outcomeUnknown)
        assertEquals(TicketsFailure.FAILED, e.tickets.writeFailure)
        // The ticket's project is read again.
        assertTrue("page:p1:" in e.source.calls)
    }

    @Test
    fun hostOutcomeUnknownReasonIsOutcomeUnknown() = runTest {
        val e = loaded(Fake().apply { failMove = RemoteRequestException("busy", reason = "outcome_unknown") })
        e.loader.move("p1", "DEV-1", "Todo", null)
        runCurrent()
        assertTrue(e.tickets.outcomeUnknown)
        assertEquals(TicketsFailure.BUSY, e.tickets.writeFailure)
    }

    @Test
    fun plainHostErrorIsNotOutcomeUnknown() = runTest {
        val e = loaded(Fake().apply { failMove = RemoteRequestException("conflict") })
        e.loader.move("p1", "DEV-1", "Todo", null)
        runCurrent()
        assertFalse(e.tickets.outcomeUnknown)
        assertEquals(TicketsFailure.FAILED, e.tickets.writeFailure)
    }

    @Test
    fun startPollsUntilSucceeded() = runTest {
        val e = loaded()
        e.source.starts += listOf(
            startStatus(TicketStartState.RUNNING, TicketStartStep.WORKTREE),
            startStatus(TicketStartState.RUNNING, TicketStartStep.SESSION),
            startStatus(TicketStartState.SUCCEEDED, TicketStartStep.DONE, moved = true, sessionId = "s"),
        )
        e.loader.start("p1", "DEV-1", null)
        runCurrent()
        assertEquals(TicketStartState.RUNNING, e.tickets.start!!.state)
        advanceTimeBy(999)
        assertEquals(0, e.source.calls.count { it == "status" })
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, e.source.calls.count { it == "status" })
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(3, e.source.calls.count { it == "status" })
        assertEquals(TicketStartState.SUCCEEDED, e.tickets.start!!.state)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(3, e.source.calls.count { it == "status" })
    }

    @Test
    fun sentAcceptCutOffByConnectionLossIsOutcomeUnknown() = runTest {
        val e = loaded(Fake().apply { moveGate = CompletableDeferred() })
        e.loader.queueAccept("p1", "DEV-1", "Accepted")
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue("move:DEV-1:Done:Accepted" in e.source.calls)
        e.state = e.state.copy(connected = false)
        e.loader.connectionLost()
        assertTrue(e.tickets.outcomeUnknown)
        // After reconnecting the capability merge reads again, although the last read was fresh.
        e.state = e.state.copy(connected = true)
        e.source.calls.clear()
        e.loader.refresh(false)
        runCurrent()
        assertTrue("projects" in e.source.calls)
    }

    @Test
    fun startPollErrorEndsTheStart() = runTest {
        val e = loaded(Fake().apply { failStatus = RemoteRequestException("not_found") })
        e.loader.start("p1", "DEV-1", null)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(TicketStartState.FAILED, e.tickets.start!!.state)
        assertTrue(e.tickets.outcomeUnknown)
        assertEquals(TicketsFailure.NOT_FOUND, e.tickets.writeFailure)
        e.source.failStatus = null
        e.source.starts += startStatus(TicketStartState.SUCCEEDED, TicketStartStep.DONE)
        e.loader.start("p1", "DEV-1", null)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(TicketStartState.SUCCEEDED, e.tickets.start!!.state)
    }

    @Test
    fun detailBeforeListAndItsFailureIsSeparate() = runTest {
        val e = env()
        e.loader.loadDetail("p1", "DEV-1")
        runCurrent()
        assertEquals("DEV-1", e.tickets.detail!!.ticket!!.ref)
        assertNull(e.tickets.detailFailure)
        assertNull(e.tickets.failure)
    }

    @Test
    fun startReportsFailedStep() = runTest {
        val e = loaded()
        e.source.starts += startStatus(
            TicketStartState.FAILED, TicketStartStep.SESSION, moved = false, error = TicketStartError.SESSION_FAILED,
        )
        e.loader.start("p1", "DEV-1", "note")
        advanceTimeBy(1_000)
        runCurrent()
        val start = e.tickets.start!!
        assertEquals(TicketStartStep.SESSION, start.step)
        assertEquals(TicketStartError.SESSION_FAILED, start.error)
        assertEquals(false, start.moved)
    }

    @Test
    fun startPollingStopsAfterTenMinutes() = runTest {
        val e = loaded()
        repeat(700) { e.source.starts += startStatus(TicketStartState.RUNNING, TicketStartStep.SESSION) }
        e.loader.start("p1", "DEV-1", null)
        advanceTimeBy(11 * 60_000L)
        runCurrent()
        assertEquals(600, e.source.calls.count { it == "status" })
        assertEquals(TicketStartState.FAILED, e.tickets.start!!.state)
        assertEquals(TicketStartError.TIMEOUT, e.tickets.start!!.error)
    }

    @Test
    fun connectionLossStopsStartPolling() = runTest {
        val e = loaded()
        repeat(5) { e.source.starts += startStatus(TicketStartState.RUNNING, TicketStartStep.SESSION) }
        e.loader.start("p1", "DEV-1", null)
        advanceTimeBy(2_500)
        e.loader.connectionLost()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(2, e.source.calls.count { it == "status" })
        assertTrue(e.tickets.outcomeUnknown)
    }

    @Test
    fun staleReplyAfterRouteChangeIgnored() = runTest {
        val source = Fake().apply { gate = CompletableDeferred() }
        val e = env(source)
        e.loader.refresh(false)
        runCurrent()
        e.state = e.state.copy(host = e.state.host!!.copy(routeId = "other"))
        source.gate!!.complete(Unit)
        runCurrent()
        assertTrue(e.tickets.projects.isEmpty())
        assertTrue(e.tickets.pages.isEmpty())
    }

    @Test
    fun newerRefreshWinsOverOlderReply() = runTest {
        val source = Fake().apply { gate = CompletableDeferred() }
        val e = env(source)
        e.loader.refresh(false)
        runCurrent()
        source.projects = listOf(project("p9"))
        e.loader.refresh(true)
        source.gate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf("p9"), e.tickets.projects.map { it.projectId })
    }

    @Test
    fun busyMapsToBusyFailure() = runTest {
        val e = env(Fake().apply { failProjects = RemoteRequestException("busy") })
        e.loader.refresh(false)
        runCurrent()
        assertEquals(TicketsFailure.BUSY, e.tickets.failure)
        assertFalse(e.tickets.loading)
    }

    @Test
    fun failureKeepsEarlierData() = runTest {
        val e = loaded()
        e.source.failProjects = RemoteRequestException("forbidden")
        e.loader.refresh(true)
        runCurrent()
        assertEquals(TicketsFailure.FORBIDDEN, e.tickets.failure)
        assertEquals(2, e.tickets.projects.size)
        assertEquals(2, e.tickets.pages.size)
    }

    @Test
    fun withoutCapabilityNothingIsRead() = runTest {
        val e = env()
        e.state = e.state.copy(capabilities = emptySet())
        e.loader.refresh(true)
        runCurrent()
        assertTrue(e.source.calls.isEmpty())
    }

    @Test
    fun openTicketLoadsDetail() = runTest {
        val e = loaded()
        e.loader.loadDetail("p1", "DEV-1")
        runCurrent()
        assertEquals("DEV-1", e.tickets.detail!!.ticket!!.ref)
        assertFalse(e.tickets.detailLoading)
    }

    @Test
    fun loadDetailRecordsTheRequestedRef() = runTest {
        val e = loaded()
        e.loader.loadDetail("p1", "DEV-1")
        runCurrent()
        assertEquals("DEV-1", e.tickets.detailRef)
    }

    private fun TestScope.loaded(source: Fake = Fake()): Env {
        val e = env(source)
        e.loader.refresh(false)
        runCurrent()
        return e
    }

    companion object {
        fun project(id: String, writable: Boolean = true) =
            TicketProject(id, id, listOf("DEV"), writable, null, true, null)

        fun ticket(ref: String, section: TicketSection = TicketSection.WORKING, updatedAt: Long = 1) =
            Ticket(
                ref, "T $ref", "https://plane/$ref", "Review", TicketStateGroup.STARTED, null, TicketPriority.NONE,
                true, section, false, emptySet(), emptyList(), updatedAt, emptyList(),
            )

        fun page(projectId: String, items: List<Ticket>, next: String? = null) =
            TicketsPage(projectId, true, null, 1, items, next)

        fun startStatus(
            state: TicketStartState,
            step: TicketStartStep,
            moved: Boolean? = null,
            sessionId: String? = null,
            error: TicketStartError? = null,
        ) = TicketStart("startid", state, step, false, moved, sessionId, error)
    }
}
