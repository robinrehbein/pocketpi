package de.joinnoah.pi.remote

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which chats linked to an accepted ticket the accept sheet offers to close. */
class AcceptCloseOfferTest {
    private fun chat(
        id: String,
        status: String = "idle",
        origin: String = "rpc",
        parent: String? = null,
        verified: Boolean = true,
        projectId: String = "p1",
    ): ProjectChatSummary {
        val fields = mutableListOf<Pair<String, Any>>("id" to id, "status" to status, "origin" to origin, "updatedAt" to 1)
        if (parent != null) fields += "parentSessionId" to parent
        return ProjectChatSummary(projectId, "Atlas", Wire.objectOf(*fields.toTypedArray()), verified)
    }

    private fun state(vararg chats: ProjectChatSummary) =
        RemoteState(
            selection = RemoteSelection(routeId = "studio"),
            connected = true,
            capabilities = setOf(SESSION_CLOSE_CAPABILITY),
            projectChats = chats.toList(),
        )

    private fun ticket(vararg links: TicketSession) =
        Ticket(
            "DEV-1", "Title", "https://plane.example/DEV-1", "Review", TicketStateGroup.STARTED, null,
            TicketPriority.NONE, true, TicketSection.ACCEPTANCE, false, emptySet(), emptyList(), 1, links.toList(),
        )

    private fun link(id: String, status: SessionLinkStatus = SessionLinkStatus.IDLE) = TicketSession(id, id, status)

    @Test
    fun acceptCloseOfferSortsLinkedChatsIntoClosableAndBusy() {
        val state =
            state(
                chat("done"),
                chat("done2"),
                chat("running", status = "running"),
                chat("main"),
                chat("sub", status = "waiting", parent = "main"),
                chat("history", origin = "history"),
                chat("pinned"),
                chat("other", projectId = "p2"),
            )
        val offer =
            acceptCloseOffer(
                state,
                "p1",
                ticket(
                    link("done"), link("done"), link("done2"), link("running"), link("main"), link("sub"),
                    link("history"), link("pinned"), link("other"), link("unfeatured"),
                    link("unfeaturedRunning", SessionLinkStatus.RUNNING), link("unfeaturedWaiting", SessionLinkStatus.WAITING),
                ),
                mapOf("pinned" to SessionMark(pinned = true)),
            )
        // A duplicate link counts once.
        assertEquals(listOf("done", "done2"), offer.closable)
        // The running chat, the main chat its linked waiting subagent lends its status to (counted
        // once, not again for the subagent), and the two unfeatured busy links. The history and
        // pinned chats, another project's chat and an unfeatured idle one are offered nothing.
        assertEquals(4, offer.busy)
    }

    @Test
    fun aBusySubagentWhoseMainChatIsNotLinkedCountsOnItsOwn() {
        val state = state(chat("main"), chat("sub", status = "running", parent = "main"))
        val offer = acceptCloseOffer(state, "p1", ticket(link("sub", SessionLinkStatus.RUNNING)))
        assertEquals(AcceptCloseOffer(emptyList(), 1), offer)
    }

    @Test
    fun anUnverifiedFeaturedChatIsNotOffered() {
        val offer = acceptCloseOffer(state(chat("s1", verified = false)), "p1", ticket(link("s1")))
        assertEquals(AcceptCloseOffer(emptyList(), 0), offer)
    }

    @Test
    fun nothingIsOfferedWhileAProjectIsOpenOrOffline() {
        val open = state(chat("s1")).copy(selection = RemoteSelection("studio", "p1"))
        assertEquals(emptyList<String>(), acceptCloseOffer(open, "p1", ticket(link("s1"))).closable)
        val offline = state(chat("s1")).copy(connected = false)
        assertEquals(emptyList<String>(), acceptCloseOffer(offline, "p1", ticket(link("s1"))).closable)
    }

    @Test
    fun theFeaturedChatsStatusWinsOverTheTicketsLinkStatus() {
        // The ticket was read while the chat ran; the featured chat is idle now.
        val offer = acceptCloseOffer(state(chat("s1")), "p1", ticket(link("s1", SessionLinkStatus.RUNNING)))
        assertEquals(listOf("s1"), offer.closable)
        assertEquals(0, offer.busy)
    }
}
