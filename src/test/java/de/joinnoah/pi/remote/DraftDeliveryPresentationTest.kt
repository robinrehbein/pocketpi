package de.joinnoah.pi.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun assertUnconfirmedWarning(sending: Boolean, connected: Boolean) {
        assertEquals(
            DraftDeliveryNotice(R.string.remote_draft_uncertain, DraftDeliverySeverity.WARNING),
            draftDeliveryNotice(sending = sending, uncertain = true, connected = connected),
        )
    }
}
