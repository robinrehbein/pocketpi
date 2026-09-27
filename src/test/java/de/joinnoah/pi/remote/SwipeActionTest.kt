package de.joinnoah.pi.remote

import org.junit.Test
import org.junit.Assert.assertEquals

class SwipeActionTest {
    @Test
    fun `stored names parse to their action`() {
        assertEquals(SwipeAction.RENAME, parseSwipeAction("RENAME", SwipeAction.CLOSE))
        assertEquals(SwipeAction.NONE, parseSwipeAction("NONE", SwipeAction.CLOSE))
    }

    @Test
    fun `missing or unknown values fall back to the default`() {
        assertEquals(SwipeAction.CLOSE, parseSwipeAction(null, SwipeAction.CLOSE))
        assertEquals(SwipeAction.RENAME, parseSwipeAction("bogus", SwipeAction.RENAME))
        assertEquals(SwipeAction.CLOSE, parseSwipeAction("close", SwipeAction.CLOSE))
    }
}
