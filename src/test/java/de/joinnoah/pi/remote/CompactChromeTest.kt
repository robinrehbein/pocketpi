package de.joinnoah.pi.remote

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
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

}
