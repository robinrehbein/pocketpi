package de.joinnoah.pi.remote

import android.content.Context
import android.text.format.DateUtils
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The "Last used" words of a host card in German: the counts keep their own plural and stay lower case. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "de")
class LastUsedStatusGermanTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_800_000_000_000L

    private fun ago(millis: Long) = lastUsedStatus(context, now - millis, now)

    @Test
    fun theCountsReadAsGermanPhrasesWithTheirPlural() {
        assertEquals("Zuletzt genutzt gerade eben", ago(30_000L))
        assertEquals("Zuletzt genutzt vor 1 Minute", ago(DateUtils.MINUTE_IN_MILLIS))
        assertEquals("Zuletzt genutzt vor 5 Minuten", ago(5 * DateUtils.MINUTE_IN_MILLIS))
        assertEquals("Zuletzt genutzt vor 1 Stunde", ago(DateUtils.HOUR_IN_MILLIS))
        assertEquals("Zuletzt genutzt vor 3 Stunden", ago(3 * DateUtils.HOUR_IN_MILLIS))
        assertEquals("Zuletzt genutzt vor 2 Tagen", ago(2 * DateUtils.DAY_IN_MILLIS))
    }
}
