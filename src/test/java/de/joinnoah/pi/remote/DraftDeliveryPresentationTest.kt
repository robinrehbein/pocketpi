package de.joinnoah.pi.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftDeliveryPresentationTest {
    @Test
    fun awaitingAcknowledgementOnLiveConnectionIsNeutral() {
        assertEquals(
            DraftDeliveryNotice(R.string.remote_draft_confirmation_pending, DraftDeliverySeverity.NEUTRAL),
            draftDeliveryNotice(sending = true, uncertain = true, connected = true),
        )
    }

    @Test
    fun timeoutOnLiveConnectionWarnsThatDeliveryIsUnconfirmed() {
        assertUnconfirmedWarning(sending = false, connected = true)
    }

    @Test
    fun restoredUncertainDraftWarnsBeforeResending() {
        for (connected in listOf(false, true)) {
            assertUnconfirmedWarning(sending = false, connected = connected)
        }
    }

    @Test
    fun disconnectBeforeSendingUnwindsAlreadyWarns() {
        assertUnconfirmedWarning(sending = true, connected = false)
    }

    @Test
    fun confirmedDeliveryHasNoIndicatorEvenBeforeSendingUnwinds() {
        for (sending in listOf(false, true)) {
            for (connected in listOf(false, true)) {
                assertNull(draftDeliveryNotice(sending = sending, uncertain = false, connected = connected))
            }
        }
    }

    private fun offlineChat(draft: String = "Hello pi") =
        RemoteState(
            selection = RemoteSelection("host", "project", "session"),
            connected = false,
            stale = true,
            status = "idle",
            draft = draft,
        )

    @Test
    fun plainDraftOfALoadedChatCanBeSentWhileTheConnectionIsDown() {
        assertTrue(canQueuePrompt(offlineChat()))
        assertTrue(canSendDraft(offlineChat()))
        // While a reconnect loads, too.
        assertTrue(canSendDraft(offlineChat().copy(loading = true)))
    }

    @Test
    fun draftsThatAreNotPlainPromptsOrChatsNotLoadedFromTheMacAreNotQueued() {
        val chat = offlineChat()
        assertFalse(canQueuePrompt(chat.copy(draft = "")))
        assertFalse(canQueuePrompt(chat.copy(draft = "  \n")))
        assertFalse(canQueuePrompt(chat.copy(draft = "/review")))
        assertFalse(canQueuePrompt(chat.copy(draft = " /review now")))
        assertFalse(canQueuePrompt(chat.copy(draft = "!ls")))
        assertFalse(canQueuePrompt(chat.copy(attachments = listOf(LocalAttachment("a", "a.txt", "file", "text/plain", 1, "x")))))
        // Not loaded from this Mac, or not idle when the connection went.
        assertFalse(canQueuePrompt(chat.copy(stale = false)))
        assertFalse(canQueuePrompt(chat.copy(status = "offline")))
        assertFalse(canQueuePrompt(chat.copy(status = "running")))
        assertFalse(canQueuePrompt(chat.copy(selection = RemoteSelection("host", "project"))))
        // One at a time, and never while a send is in flight.
        assertFalse(canQueuePrompt(chat.copy(pendingSend = PendingSend("m", "x", null, emptyList(), PendingSendState.QUEUED))))
        assertFalse(canQueuePrompt(chat.copy(sending = true)))
        // A live connection sends instead.
        assertFalse(canQueuePrompt(chat.copy(connected = true)))
    }

    @Test
    fun queuedPromptShowsNoDeliveryWarning() {
        // The queue is journaled but not uncertain: the notice stays empty until it is sent.
        assertNull(draftDeliveryNotice(sending = false, uncertain = false, connected = false))
    }

    private fun assertUnconfirmedWarning(sending: Boolean, connected: Boolean) {
        assertEquals(
            DraftDeliveryNotice(R.string.remote_draft_uncertain, DraftDeliverySeverity.WARNING),
            draftDeliveryNotice(sending = sending, uncertain = true, connected = connected),
        )
    }
}
