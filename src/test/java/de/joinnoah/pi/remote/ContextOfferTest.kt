package de.joinnoah.pi.remote

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextOfferTest {
    private fun usage(percent: Double?, window: Long = 200_000, used: Long? = percent?.let { (window * it / 100).toLong() }) =
        SessionContextUsage("s1", "anthropic", "model-a", used, window, percent)

    private fun idle(usage: SessionContextUsage? = usage(80.0), capabilities: Set<String> = setOf(COMPACT_CAPABILITY)) =
        RemoteState(
            connected = true,
            status = "idle",
            selection = RemoteSelection("host", "project", "s1"),
            capabilities = capabilities,
            contextUsage = usage,
        )

    @Test
    fun levelStartsAtSeventyFivePercentAndReoffersAtNinety() {
        assertNull(contextOfferLevel(usage(49.0)))
        assertNull(contextOfferLevel(usage(50.0)))
        assertNull(contextOfferLevel(usage(74.9)))
        assertEquals(CONTEXT_OFFER_PERCENT, contextOfferLevel(usage(75.0)))
        assertEquals(CONTEXT_OFFER_PERCENT, contextOfferLevel(usage(89.9)))
        assertEquals(CONTEXT_OFFER_REOFFER_PERCENT, contextOfferLevel(usage(90.0)))
        assertEquals(CONTEXT_OFFER_REOFFER_PERCENT, contextOfferLevel(usage(100.0)))
    }

    @Test
    fun largeWindowsOfferFromTwoHundredThousandTokensButNeverBelowHalf() {
        // 300k window: 210k used is only 70%, but past 200k tokens.
        assertEquals(CONTEXT_OFFER_PERCENT, contextOfferLevel(usage(70.0, window = 300_000)))
        assertNull(contextOfferLevel(usage(66.0, window = 300_000, used = 199_999)))
        // From about 267k (where 200k is 75%) the token rule applies.
        assertEquals(CONTEXT_OFFER_PERCENT, contextOfferLevel(usage(74.99, window = CONTEXT_OFFER_LARGE_WINDOW, used = 200_000)))
        assertNull(contextOfferLevel(usage(74.0, window = CONTEXT_OFFER_LARGE_WINDOW - 1, used = 200_000)))
        // 400k window: 200k used is exactly half.
        assertEquals(CONTEXT_OFFER_PERCENT, contextOfferLevel(usage(50.0, window = 400_000)))
        assertNull(contextOfferLevel(usage(50.0, window = 400_000, used = 199_999)))
        // Beyond 400k the floor wins: on a 1M window 200k used is only 20%.
        assertNull(contextOfferLevel(usage(20.0, window = 1_000_000)))
        assertNull(contextOfferLevel(usage(49.9, window = 1_000_000)))
        assertEquals(CONTEXT_OFFER_PERCENT, contextOfferLevel(usage(50.0, window = 1_000_000)))
        assertEquals(CONTEXT_OFFER_REOFFER_PERCENT, contextOfferLevel(usage(95.0, window = 1_000_000)))
        // Smaller windows keep the plain 75% rule.
        assertNull(contextOfferLevel(usage(74.0, window = 260_000)))
        assertEquals(
            ContextOffer(CONTEXT_OFFER_PERCENT, 70, setOf(ContextOfferAction.COMPACT)),
            contextOffer(idle(usage(70.0, window = 300_000)), null, 0L),
        )
    }

    @Test
    fun unknownUsageOffersNothing() {
        assertNull(contextOfferLevel(null))
        assertNull(contextOfferLevel(usage(null)))
        assertNull(contextOffer(idle(usage(null)), null, 0L))
        assertNull(contextOffer(idle(null), null, 0L))
    }

    @Test
    fun anIdleFullChatOffersCompact() {
        assertEquals(
            ContextOffer(CONTEXT_OFFER_PERCENT, 80, setOf(ContextOfferAction.COMPACT)),
            contextOffer(idle(usage(80.6)), null, 0L),
        )
        assertNull(contextOffer(idle(usage(74.0)), null, 0L))
    }

    @Test
    fun onlyAnIdleSettledChatOffers() {
        val base = idle()
        assertNull(contextOffer(base.copy(status = "running"), null, 0L))
        assertNull(contextOffer(base.copy(connected = false), null, 0L))
        assertNull(contextOffer(base.copy(loading = true), null, 0L))
        assertNull(contextOffer(base.copy(sending = true), null, 0L))
        assertNull(contextOffer(base.copy(configurationChanging = true), null, 0L))
        // A usage read in flight (after the turn ended) has not measured the idle chat yet.
        assertNull(contextOffer(base.copy(contextLoading = true), null, 0L))
        // A read taken before the turn ended still shows the old fill until the next one finishes.
        assertNull(contextOffer(base.copy(contextUsageStale = true), null, 0L))
        assertNull(contextOffer(base.copy(compactionRequesting = true), null, 0L))
        assertNull(contextOffer(base.copy(compaction = SessionCompaction("threshold", 0L)), null, 1_000L))
        // Usage of another chat or of a model other than the confirmed one is stale.
        assertNull(contextOffer(base.copy(contextUsage = usage(80.0).copy(sessionId = "other")), null, 0L))
        assertNull(contextOffer(base.copy(selection = RemoteSelection("host", "project")), null, 0L))
    }

    @Test
    fun noCompactCapabilityMeansNoOffer() {
        assertNull(contextOffer(idle(capabilities = emptySet()), null, 0L))
        assertNull(
            contextOffer(idle().copy(unavailableCapabilities = setOf(COMPACT_CAPABILITY)), null, 0L),
        )
    }

    @Test
    fun aDismissedLevelHoldsUntilTheNextLevel() {
        assertNull(contextOffer(idle(usage(80.0)), CONTEXT_OFFER_PERCENT, 0L))
        assertNull(contextOffer(idle(usage(89.0)), CONTEXT_OFFER_PERCENT, 0L))
        assertEquals(CONTEXT_OFFER_REOFFER_PERCENT, contextOffer(idle(usage(90.0)), CONTEXT_OFFER_PERCENT, 0L)?.level)
        assertNull(contextOffer(idle(usage(95.0)), CONTEXT_OFFER_REOFFER_PERCENT, 0L))
    }

    @Test
    fun dismissalResetsOnCompactionOrUsageBelowTheOffer() {
        assertFalse(contextOfferResets(idle(usage(80.0)), 0L))
        assertTrue(contextOfferResets(idle(usage(40.0)), 0L))
        assertTrue(contextOfferResets(idle().copy(compactionRequesting = true), 0L))
        assertTrue(contextOfferResets(idle().copy(compaction = SessionCompaction("manual", 0L)), 1_000L))
        // No finished read says anything about the fill: keep the dismissal.
        assertFalse(contextOfferResets(idle(null), 0L))
        assertFalse(contextOfferResets(idle(usage(null)), 0L))
        assertFalse(contextOfferResets(idle(usage(40.0)).copy(contextLoading = true), 0L))
        assertFalse(contextOfferResets(idle(usage(40.0)).copy(contextUsageStale = true), 0L))
    }

    @Test
    fun memoryKeepsTheHighestDismissedLevelPerChatAndAnnouncesOncePerLevel() {
        val memory = ContextOfferMemory()
        memory.dismiss("s1", CONTEXT_OFFER_REOFFER_PERCENT)
        memory.dismiss("s1", CONTEXT_OFFER_PERCENT)
        memory.dismiss("s2", CONTEXT_OFFER_PERCENT)
        assertEquals(CONTEXT_OFFER_REOFFER_PERCENT, memory.dismissed("s1"))
        assertEquals(CONTEXT_OFFER_PERCENT, memory.dismissed("s2"))
        assertNull(memory.dismissed("s3"))

        assertTrue(memory.shouldAnnounce("s1", CONTEXT_OFFER_PERCENT))
        memory.announced("s1", CONTEXT_OFFER_PERCENT)
        assertFalse(memory.shouldAnnounce("s1", CONTEXT_OFFER_PERCENT))
        assertTrue(memory.shouldAnnounce("s1", CONTEXT_OFFER_REOFFER_PERCENT))
        assertTrue(memory.shouldAnnounce("s2", CONTEXT_OFFER_PERCENT))

        memory.forget("s1")
        assertNull(memory.dismissed("s1"))
        assertEquals(CONTEXT_OFFER_PERCENT, memory.dismissed("s2"))
        assertTrue(memory.shouldAnnounce("s1", CONTEXT_OFFER_PERCENT))
    }

    @Test
    fun memoryChangesAreSnapshotStateTheScreenObserves() {
        val memory = ContextOfferMemory()
        var reads = 0
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            val scope = Any()
            fun observe() {
                observer.observeReads(scope, { observe() }) {
                    reads++
                    memory.dismissed("s1")
                    memory.shouldAnnounce("s1", CONTEXT_OFFER_PERCENT)
                }
            }
            observe()
            assertEquals(1, reads)
            memory.announced("s1", CONTEXT_OFFER_PERCENT)
            Snapshot.sendApplyNotifications()
            assertTrue("an announcement is observed", reads > 1)
            val beforeDismiss = reads
            memory.dismiss("s1", CONTEXT_OFFER_PERCENT)
            Snapshot.sendApplyNotifications()
            assertTrue("a dismissal is observed", reads > beforeDismiss)
        } finally {
            observer.stop()
        }
    }
}
