package de.joinnoah.pi.remote

import android.content.Context
import android.text.format.DateUtils
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The "Last used" words of a host card, in English. The German words are in [LastUsedStatusGermanTest]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LastUsedStatusTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_800_000_000_000L

    private fun ago(millis: Long) = lastUsedStatus(context, now - millis, now)

    @Test
    fun aMomentAgoIsJustNowAndMinutesHoursAndDaysAreCounted() {
        assertEquals("Last used just now", ago(30_000L))
        assertEquals("Last used 1 minute ago", ago(DateUtils.MINUTE_IN_MILLIS))
        assertEquals("Last used 5 minutes ago", ago(5 * DateUtils.MINUTE_IN_MILLIS))
        assertEquals("Last used 1 hour ago", ago(DateUtils.HOUR_IN_MILLIS))
        assertEquals("Last used 3 hours ago", ago(3 * DateUtils.HOUR_IN_MILLIS))
        assertEquals("Last used 1 day ago", ago(DateUtils.DAY_IN_MILLIS))
        assertEquals("Last used 2 days ago", ago(2 * DateUtils.DAY_IN_MILLIS))
    }

    @Test
    fun aUseInTheFutureReadsAsJustNow() {
        assertEquals("Last used just now", lastUsedStatus(context, now + 3 * DateUtils.MINUTE_IN_MILLIS, now))
    }

    @Test
    fun aUseAWeekOldOrMoreShowsItsDateInsteadOfACount() {
        val text = ago(8 * DateUtils.DAY_IN_MILLIS)

        assertTrue(text.startsWith("Last used "))
        assertFalse(text.contains("ago"))
    }
}
