package de.joinnoah.pi.remote

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class CompactChromeTest {
    @Test
    fun fieldLinesFollowTheChatHeightBetweenThreeAndFourteen() {
        // The classic six lines without a known height; the large editor takes the most.
        assertEquals(6, composerLineLimit(null, expanded = false))
        assertEquals(6, composerLineLimit(800.dp, expanded = false))
        assertEquals(10, composerLineLimit(1280.dp, expanded = false))
        assertEquals(3, composerLineLimit(300.dp, expanded = false))
        assertEquals(14, composerLineLimit(2000.dp, expanded = false))
        assertEquals(14, composerLineLimit(400.dp, expanded = true))
    }

    @Test
    fun composerExtrasTakeAtMostFortyPercentOfTheHeight() {
        assertEquals(320.dp, composerExtrasLimit(800.dp))
    }

    @Test
    fun downHidesTheHeaderUpAndTheTopShowIt() {
        assertTrue(headerHiddenAfterScroll(false, 0 to 0, 0 to 40))
        assertTrue(headerHiddenAfterScroll(false, 2 to 10, 3 to 0))
        assertFalse(headerHiddenAfterScroll(true, 3 to 0, 2 to 500))
        assertFalse(headerHiddenAfterScroll(true, 1 to 0, 0 to 0))
        // No movement keeps the header as it is.
        assertTrue(headerHiddenAfterScroll(true, 2 to 10, 2 to 10))
        assertFalse(headerHiddenAfterScroll(false, 2 to 10, 2 to 10))
    }

    @Test
    fun compactHeaderOffersTheBannersInItsMenuWhileTheyShow() {
        val layout = ChatActionLayout(listOf(ChatAction.CHANGES, ChatAction.REFRESH), listOf(ChatAction.FILES, ChatAction.NEW_SESSION))
        assertEquals(
            ChatActionLayout(listOf(ChatAction.CHANGES, ChatAction.REFRESH), listOf(ChatAction.FILES, ChatAction.NEW_SESSION, ChatAction.STATUS)),
            layout.withStatus(true),
        )
        assertEquals(layout, layout.withStatus(false))
        // A header without a menu gets one for the banners' entry alone.
        assertEquals(
            ChatActionLayout(listOf(ChatAction.CHANGES), listOf(ChatAction.STATUS)),
            ChatActionLayout(listOf(ChatAction.CHANGES), emptyList()).withStatus(true),
        )
    }

    private class Header(var position: Pair<Int, Int> = 0 to 0, var active: Boolean = true) {
        var hidden = false
        val connection = HeaderHideConnection({ position }, { active }, { hidden }, { hidden = it })

        fun scroll(to: Pair<Int, Int>, source: NestedScrollSource) {
            connection.onPreScroll(Offset.Zero, source)
            position = to
            connection.onPostScroll(Offset.Zero, Offset.Zero, source)
        }
    }

    @Test
    fun onlyTheUsersOwnScrollingHidesTheHeader() {
        val header = Header()
        // Auto-follow, a jump or a restored position scroll the list on their own.
        header.scroll(40 to 0, NestedScrollSource.SideEffect)
        assertFalse(header.hidden)
        header.scroll(41 to 10, NestedScrollSource.UserInput)
        assertTrue(header.hidden)
        // A programmatic scroll leaves it hidden; a drag up shows it.
        header.scroll(50 to 0, NestedScrollSource.SideEffect)
        assertTrue(header.hidden)
        header.scroll(49 to 0, NestedScrollSource.UserInput)
        assertFalse(header.hidden)
    }

    @Test
    fun theHeaderStaysWhenNotCompactAndShowsAtTheTop() {
        val header = Header(active = false)
        header.scroll(3 to 0, NestedScrollSource.UserInput)
        assertFalse(header.hidden)
        header.active = true
        header.scroll(5 to 0, NestedScrollSource.UserInput)
        assertTrue(header.hidden)
        header.scroll(0 to 0, NestedScrollSource.SideEffect)
        assertFalse(header.hidden)
    }
}
