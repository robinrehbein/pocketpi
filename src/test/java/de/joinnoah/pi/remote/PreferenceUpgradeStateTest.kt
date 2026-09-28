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
    fun eventsOfRoutesNoLongerPairedArePruned() {
        val tagKept = RemoteNotifications.tag("kept", "s1")
        val tagGone = RemoteNotifications.tag("gone", "s1")
        val setup = state(setOf("kept", "gone"))
        setup.setLatestEvent(tagKept, "e1")
        setup.setLatestEvent(tagGone, "e2")
        assertEquals("e1", setup.latestEvent(tagKept))
        assertEquals("e2", setup.latestEvent(tagGone))

        // "gone" is no longer among the paired routes; the next write anywhere prunes its keys.
        val afterUnpair = state(setOf("kept"))
        afterUnpair.setLatestEvent(RemoteNotifications.tag("kept", "s2"), "e3")
        assertEquals("e1", afterUnpair.latestEvent(tagKept))
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
}
