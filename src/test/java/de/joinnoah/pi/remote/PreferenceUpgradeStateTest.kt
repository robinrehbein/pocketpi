package de.joinnoah.pi.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PreferenceUpgradeStateTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun state(paired: Set<String>, clock: LongArray = longArrayOf(0)) =
        PreferenceUpgradeState(context, pairedRoutes = { paired }, now = { clock[0] })

    @Test
    fun aCompletionIsVisibleToAFreshInstanceRightAfterTheWrite() {
        // setLatestEvent(..., null) races the worker it is meant to cancel; a synchronous commit()
        // (not the async apply()) is what makes the removal visible immediately, without waiting
        // for a pending-write drain, to a second reader backed by the same SharedPreferences file.
        val tag = RemoteNotifications.tag("r", "s")
        state(setOf("r")).setLatestEvent(tag, "e1")
        assertEquals("e1", state(setOf("r")).latestEvent(tag))
        state(setOf("r")).setLatestEvent(tag, null)
        assertNull(state(setOf("r")).latestEvent(tag))
    }

    @Test
    fun forgetRouteRemovesItsEventsImmediatelyRegardlessOfTheCap() {
        // The unpair path must not wait for MAX_EVENT_KEYS to be crossed before cleaning up.
        val tag = RemoteNotifications.tag("gone", "s1")
        val s = state(setOf("gone"))
        s.setLatestEvent(tag, "e1")
        assertEquals("e1", s.latestEvent(tag))
        s.forgetRoute("gone")
        assertNull(s.latestEvent(tag))
    }

    @Test
    fun prunePaysToDecryptThePairingStoreOnlyOnceOverTheCap() {
        // pairedRoutes() (PairingStore.load()) decrypts the on-disk pairing store; a question push
        // calling setLatestEvent must not pay that cost while comfortably under the cap, since
        // forgetRoute() already handles the unpair case immediately.
        var pairedRoutesCalls = 0
        val s = PreferenceUpgradeState(context, pairedRoutes = { pairedRoutesCalls++; emptySet() }, now = { 0L })
        repeat(5) { s.setLatestEvent(RemoteNotifications.tag("r", "s$it"), "e$it") }
        assertEquals(0, pairedRoutesCalls)
    }

    @Test
    fun eventsOfRoutesNoLongerPairedArePrunedOnceOverTheCap() {
        val clock = longArrayOf(0)
        val tagGone = RemoteNotifications.tag("gone", "s1")
        state(setOf("kept", "gone"), clock).setLatestEvent(tagGone, "e_gone")

        // "gone" was unpaired without going through forgetRoute() in this scenario; only once
        // enough further writes push the store over the cap does the pairing-based sweep run and
        // catch it.
        val afterUnpair = state(setOf("kept"), clock)
        (1..201).forEach { i ->
            clock[0] = i.toLong()
            afterUnpair.setLatestEvent(RemoteNotifications.tag("kept", "s$i"), "e$i")
        }
        assertNull(afterUnpair.latestEvent(tagGone))
    }

    @Test
    fun eventsAreCappedToTheNewestTwoHundred() {
        val clock = longArrayOf(0)
        val paired = state(setOf("r"), clock)
        val tags = (1..205).map { RemoteNotifications.tag("r", "s$it") }
        tags.forEachIndexed { index, tag ->
            clock[0] = index.toLong()
            paired.setLatestEvent(tag, "e$index")
        }
        val remaining = tags.count { paired.latestEvent(it) != null }
        assertEquals(200, remaining)
        // The oldest writes are the ones dropped, the newest survive.
        assertNull(paired.latestEvent(tags.first()))
        assertEquals("e204", paired.latestEvent(tags.last()))
    }

    @Test
    fun aLegacyValueWithoutATimestampPrefixRoundTripsAndSurvivesAPruneUnderTheCap() {
        // Values written before the timestamp-stamped format existed have no delimiter at all.
        context.getSharedPreferences("question_upgrades", Context.MODE_PRIVATE)
            .edit()
            .putString("event:${RemoteNotifications.tag("r", "legacy")}", "legacy-event-id")
            .commit()
        val s = state(setOf("r"))
        assertEquals("legacy-event-id", s.latestEvent(RemoteNotifications.tag("r", "legacy")))
        // A further write elsewhere stays comfortably under the cap, so the legacy entry must not
        // be evicted just for lacking a timestamp.
        s.setLatestEvent(RemoteNotifications.tag("r", "other"), "e2")
        assertEquals("legacy-event-id", s.latestEvent(RemoteNotifications.tag("r", "legacy")))
    }
}
