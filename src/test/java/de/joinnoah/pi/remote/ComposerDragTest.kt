package de.joinnoah.pi.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerDragTest {
    @Test fun waitsForRestingEdgeThenTracksFinger() {
        assertEquals(0f, composerDragProgress(80f, 100f, 60f))
        assertEquals(0f, composerDragProgress(100f, 100f, 60f))
        assertEquals(25f, composerDragProgress(125f, 100f, 60f))
        assertEquals(60f, composerDragProgress(200f, 100f, 60f))
    }

    @Test fun reverseMovementTracksBackWithoutMovingRestingEdge() {
        assertEquals(45f, composerDragProgress(145f, 100f, 60f))
        assertEquals(15f, composerDragProgress(115f, 100f, 60f))
        assertEquals(0f, composerDragProgress(95f, 100f, 60f))
    }

    @Test fun settlesOnlyBeyondHalfway() {
        assertFalse(composerShouldSettleHidden(0f, 60f))
        assertFalse(composerShouldSettleHidden(30f, 60f))
        assertTrue(composerShouldSettleHidden(31f, 60f))
        assertFalse(composerShouldSettleHidden(1f, 0f))
        assertEquals(0f, composerDragProgress(200f, 100f, -1f))
    }
}
