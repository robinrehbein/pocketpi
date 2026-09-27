package de.joinnoah.pi.remote

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SessionForkTest {
    @Test
    fun forkDraftKeepsBodyAndQuoteAndReportsDroppedAttachments() {
        val quote = MessageQuote("user-1", "user", "first")
        assertEquals(
            ForkDraft("again", quote, droppedAttachments = false),
            forkDraft(QuoteCodec.encode("again", quote)),
        )
        val attachment =
            RemoteAttachment(
                "AAAAAAAAAAAAAAAAAAAAAA",
                "photo.png",
                "image",
                "image/png",
                3,
                "a".repeat(64),
                1234,
            )
        val manifest =
            JsonObject(attachment.json() + ("path" to JsonPrimitive("/private/host/photo.png")))
        val text =
            "[PocketPi attachments v1]\n" +
                JsonArray(listOf(manifest)) +
                "\n[/PocketPi attachments]\n\n" +
                QuoteCodec.encode("look", quote)
        assertEquals(ForkDraft("look", quote, droppedAttachments = true), forkDraft(text))
        assertEquals(ForkDraft("/review", null, false), forkDraft("/review"))
    }

    @Test
    fun onlyBusyHasItsOwnForkError() {
        assertEquals(R.string.remote_fork_busy, forkError("busy"))
        assertEquals(R.string.remote_request_error, forkError("not_found"))
        assertEquals(R.string.remote_request_error, forkError(null))
    }

    @Test
    fun onlyTheFirstBubbleOfEachUserMessageIsForkable() {
        fun bubble(id: String, source: String, role: String = "user") =
            ConversationItem.Bubble(id, source, role, null, "x", null, false, null)
        val items =
            listOf(
                bubble("u1-attachments", "u1"),
                bubble("u1-text-0", "u1"),
                bubble("a1", "a1", role = "assistant"),
                bubble("u2", "u2"),
            )
        assertEquals(setOf("u1-attachments", "u2"), forkableBubbleIds(items))
    }

    @Test
    fun forkNeedsTheCapabilityAndNoSubagentParentAndAStoppedSource() {
        val session = Wire.objectOf("id" to "s", "origin" to "rpc", "status" to "idle")
        val state =
            RemoteState(capabilities = setOf(SESSION_FORK_CAPABILITY), session = session, status = "idle")
        assertTrue(canFork(state))
        assertFalse(canFork(state.copy(capabilities = emptySet())))
        assertFalse(
            canFork(state.copy(session = JsonObject(session + ("parentSessionId" to JsonPrimitive("p")))))
        )
        assertTrue(forkStopped(state))
        assertTrue(forkStopped(state.copy(status = "offline")))
        assertFalse(forkStopped(state.copy(status = "running")))
        assertFalse(forkStopped(state.copy(status = "waiting")))
        assertTrue(
            forkStopped(
                state.copy(
                    status = "running",
                    session = JsonObject(session + ("origin" to JsonPrimitive("history"))),
                )
            )
        )
    }

    @Test
    fun resendOutcomesMapToNoticesAndStopFirstNeedsARunningChat() {
        assertNull(forkResendNotice("accepted"))
        assertEquals(R.string.remote_fork_resend_uncertain, forkResendNotice("uncertain"))
        assertEquals(R.string.remote_fork_resend_failed, forkResendNotice("failed"))
        val session = Wire.objectOf("id" to "s", "origin" to "rpc", "status" to "idle")
        val state = RemoteState(session = session, status = "running")
        assertTrue(forkRunning(state))
        assertTrue(forkRunning(state.copy(status = "waiting")))
        assertFalse(forkRunning(state.copy(status = "idle")))
        assertFalse(forkRunning(state.copy(status = "unknown")))
        assertFalse(forkStopped(state.copy(status = "unknown")))
        assertFalse(
            forkRunning(state.copy(session = JsonObject(session + ("origin" to JsonPrimitive("history")))))
        )
    }
}
