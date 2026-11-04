package de.joinnoah.pi.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The queue and the steps of "Review all" on the Projects page's Ready group. */
class ReadyReviewTest {
    private fun chat(
        id: String,
        status: String = "idle",
        origin: String = "rpc",
        updatedAt: Long = 1L,
        parent: String? = null,
        verified: Boolean = true,
        project: String = "p1",
    ): ProjectChatSummary {
        val fields = mutableListOf<Pair<String, Any?>>(
            "id" to id, "title" to "Chat $id", "status" to status, "origin" to origin, "updatedAt" to updatedAt,
        )
        if (parent != null) fields += "parentSessionId" to parent
        return ProjectChatSummary(project, "Project $project", Wire.objectOf(*fields.toTypedArray()), verified)
    }

    private fun entries(vararg ids: String) = ids.map { ReadyReviewEntry("p1", it) }

    private fun step(
        queue: List<ReadyReviewEntry>,
        chats: List<ProjectChatSummary>,
        position: Int = 0,
        connected: Boolean = true,
        marks: Map<String, SessionMark> = emptyMap(),
        closing: Set<String> = emptySet(),
    ) = readyReviewStep(queue, position, mainSessionChats(chats), connected, marks, closing)

    @Test
    fun theQueueIsTheReadyGroupInItsOrderUncutAndWithoutPinnedChats() {
        val chats = (1..5).map { chat("c$it", updatedAt = it.toLong()) }
        val quick =
            quickChats(
                chats, connected = true, showRunningAll = false, showRecentAll = false,
                marks = mapOf("c4" to SessionMark(pinned = true)),
            )

        // The group shows three; the review walks all of them but the pinned one, newest first.
        assertEquals(QUICK_CHAT_LIMIT, quick.ready.size)
        assertEquals(entries("c5", "c3", "c2", "c1"), readyReviewQueue(quick.readyQueue))
    }

    @Test
    fun theQueueSkipsChatsThatStartedWorkingWereHiddenClosedOrLeft() {
        val queue = entries("running", "hidden", "closing", "gone", "pinned", "ready")
        val chats =
            listOf(
                chat("running", status = "running"),
                chat("hidden"),
                chat("closing"),
                chat("pinned"),
                chat("ready"),
            )
        val marks = mapOf("hidden" to SessionMark(hiddenUpdatedAt = 1L, hiddenAt = 1L), "pinned" to SessionMark(pinned = true))

        val found = step(queue, chats, marks = marks, closing = setOf("closing"))

        assertEquals(5, found?.index)
        assertEquals("ready", found?.chat?.session?.text("id"))
    }

    @Test
    fun aChatWhoseSubagentWorksIsSkipped() {
        val queue = entries("main")
        assertNull(step(queue, listOf(chat("main"), chat("sub", status = "running", parent = "main"))))
    }

    @Test
    fun theStepStartsAtThePositionAndDoneAfterTheLastCard() {
        val queue = entries("a", "b")
        val chats = listOf(chat("a"), chat("b"))
        assertEquals(0, step(queue, chats)?.index)
        assertEquals(1, step(queue, chats, position = 1)?.index)
        assertNull(step(queue, chats, position = 2))
    }

    @Test
    fun anOfflineOrUnverifiedChatEndsTheReview() {
        val queue = entries("a")
        assertNull(step(queue, listOf(chat("a")), connected = false))
        assertNull(step(queue, listOf(chat("a", verified = false))))
    }

    @Test
    fun aChatOfTheSameIdInAnotherProjectIsNotTheQueuedOne() {
        val queue = listOf(ReadyReviewEntry("p2", "a"))
        assertNull(step(queue, listOf(chat("a", project = "p1"))))
        assertEquals(0, step(queue, listOf(chat("a", project = "p2")))?.index)
    }

    private fun state(vararg chats: ProjectChatSummary, capabilities: Set<String> = setOf(SESSION_CLOSE_CAPABILITY)) =
        RemoteState(
            selection = RemoteSelection(routeId = "host"),
            connected = true,
            capabilities = capabilities,
            projectChats = chats.toList(),
        )

    private fun item(chat: ProjectChatSummary, pinned: Boolean = false) =
        sessionListItem(chat.session, connected = true, loading = false, confirmed = true).copy(pinned = pinned)

    @Test
    fun closeIsOfferedWhereTheRowOffersCloseAndHideWhereThePhoneCannotClose() {
        val rpc = chat("rpc", origin = "rpc")
        val mac = chat("mac", origin = "pi")
        val terminal = chat("tui", origin = "tui")
        val withTerminal = setOf(SESSION_CLOSE_CAPABILITY, SESSION_CLOSE_TERMINAL_CAPABILITY)

        assertEquals(ReadyReviewVerb.CLOSE, readyReviewVerb(state(rpc), rpc, item(rpc)))
        assertEquals(ReadyReviewVerb.HIDE, readyReviewVerb(state(mac), mac, item(mac)))
        // A terminal chat closes where the host can close terminals, and hides where it cannot.
        assertEquals(ReadyReviewVerb.CLOSE, readyReviewVerb(state(terminal, capabilities = withTerminal), terminal, item(terminal)))
        assertEquals(ReadyReviewVerb.HIDE, readyReviewVerb(state(terminal), terminal, item(terminal)))
    }

    @Test
    fun theVerbDoesNotChangeWhileThePageLoads() {
        val rpc = chat("rpc", origin = "rpc")
        val loading = state(rpc).copy(loading = true)
        // Close stays the verb (greyed out by closableProjectChat); it never turns into Hide meanwhile.
        assertEquals(ReadyReviewVerb.CLOSE, readyReviewVerb(loading, rpc, item(rpc)))
        assertEquals(false, closableProjectChat(loading, "p1", "rpc"))
    }

    @Test
    fun aPinnedChatThatCannotCloseOffersNoVerb() {
        val mac = chat("mac", origin = "pi")
        assertEquals(ReadyReviewVerb.NONE, readyReviewVerb(state(mac), mac, item(mac, pinned = true)))
    }
}
