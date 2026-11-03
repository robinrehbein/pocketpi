package de.joinnoah.pi.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityMarqueeTimingTest {
    @Test
    fun durationUsesDensityIndependentSpeed() {
        assertEquals(1000, activityMarqueeDurationMs(30, 1f))
        assertEquals(1000, activityMarqueeDurationMs(60, 2f))
        assertEquals(2000, activityMarqueeDurationMs(120, 2f))
        assertEquals(1, activityMarqueeDurationMs(0, 1f))
    }
}
