package de.joinnoah.pi.remote

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import org.junit.Assert.*
import org.junit.Test

class ToolDetailTextTest {
    @Test
    fun outputLinesSplitsOnEveryLineBreakAndDropsTheTrailingOne() {
        assertEquals(emptyList<String>(), outputLines(""))
        assertEquals(listOf("one"), outputLines("one"))
        assertEquals(listOf("one", "two"), outputLines("one\ntwo\n"))
        assertEquals(listOf("one", "", "three"), outputLines("one\n\nthree"))
        assertEquals(listOf(""), outputLines("\n"))
    }

    @Test
    fun outputLinesTreatsCrlfAsOneBreakAndKeepsNoCarriageReturn() {
        assertEquals(listOf("a", "b", "c"), outputLines("a\r\nb\r\nc\r\n"))
        assertEquals(listOf("a", "b", "c"), outputLines("a\rb\nc"))
        assertTrue(outputLines("x\r\ny").none { it.contains('\r') })
    }

    @Test
    fun longLinesAreSoftSplitAndKeepTheirSourceLineNumber() {
        val long = "x".repeat(MAX_CODE_LINE_CHARS * 2 + 5)
        val rows = outputRows("first\n$long\nlast")
        assertEquals(listOf(MAX_CODE_LINE_CHARS, MAX_CODE_LINE_CHARS, 5), rows.subList(1, 4).map { it.text.length })
        assertEquals(listOf(1, 2, null, null, 3), rows.map { it.number })
        assertEquals(long, rows.subList(1, 4).joinToString("") { it.text })
    }

    @Test
    fun softSplitNeverCutsASurrogatePair() {
        val emoji = "😀"
        val line = "a".repeat(MAX_CODE_LINE_CHARS - 1) + emoji + "b"
        val parts = outputLines(line)
        assertEquals(2, parts.size)
        assertEquals(MAX_CODE_LINE_CHARS - 1, parts[0].length)
        assertEquals(emoji + "b", parts[1])
    }

    @Test
    fun searchIsCaseInsensitiveAndFindsEveryHitInALine() {
        val lines = listOf("Error: error ERROR", "fine", "an eRRor")
        val matches = searchMatches(lines, "error")
        assertEquals(
            listOf(SearchMatch(0, 0, 5), SearchMatch(0, 7, 12), SearchMatch(0, 13, 18), SearchMatch(2, 3, 8)),
            matches,
        )
    }

    @Test
    fun searchMatchesDoNotOverlapAndAnEmptyQueryFindsNothing() {
        assertEquals(listOf(SearchMatch(0, 0, 2), SearchMatch(0, 2, 4)), searchMatches(listOf("aaaaa"), "aa"))
        assertTrue(searchMatches(listOf("abc"), "").isEmpty())
        assertTrue(searchMatches(listOf("abc"), "zzz").isEmpty())
    }

    @Test
    fun searchStopsAtTheCap() {
        val lines = List(600) { "hit hit" }
        assertEquals(1000, searchMatches(lines, "hit").size)
        assertEquals(3, searchMatches(lines, "HIT", limit = 3).size)
        assertEquals(SearchMatch(1, 0, 3), searchMatches(lines, "hit", limit = 3).last())
    }

    @Test
    fun prettyArgumentsIndentsValidJson() {
        val pretty = prettyArguments("""{"pattern":"TODO","paths":["a","b"],"n":2}""")
        assertNotNull(pretty)
        assertTrue(pretty!!.lines().size > 1)
        assertTrue(pretty.contains("\"pattern\": \"TODO\""))
        assertTrue(pretty.lines().any { it.startsWith("    \"n\": 2") })
    }

    @Test
    fun prettyArgumentsRejectsMissingAndInvalidInput() {
        assertNull(prettyArguments(null))
        assertNull(prettyArguments(""))
        assertNull(prettyArguments("""{"path":"src/a.kt","content":"cut sho"""))
        assertNull(prettyArguments("not json"))
    }

    @Test
    fun matchesFromOlderRowsOrAnOlderQueryAreDropped() {
        val before = listOf("error one", "error two")
        val result = SearchResult(before, "error", searchMatches(before, "error"))
        assertEquals(2, currentMatches(result, before, "error").size)
        // New rows with equal content are still a different list: wait for their own matches.
        val streamed = listOf("ok", "err")
        assertTrue(currentMatches(result, streamed, "error").isEmpty())
        assertTrue(currentMatches(result, before, "err").isEmpty())
        assertTrue(currentMatches(null, before, "error").isEmpty())
    }

    @Test
    fun highlightingClampsMatchesThatDoNotFitTheRow() {
        val current = SpanStyle(background = Color.Black)
        val other = SpanStyle(background = Color.Gray)
        // Offsets from a longer, older row applied to a shorter new one must not throw.
        val stale = listOf(SearchMatch(0, 2, 6), SearchMatch(0, 40, 45)).withIndex().toList()
        val text = highlightedText("abcd", stale, 0, current, other)
        assertEquals("abcd", text.text)
        assertEquals(listOf(2 to 4), text.spanStyles.map { it.start to it.end })
        val fitting = listOf(SearchMatch(0, 0, 2), SearchMatch(0, 3, 5)).withIndex().toList()
        val styled = highlightedText("ab cd e", fitting, 1, current, other)
        assertEquals("ab cd e", styled.text)
        assertEquals(listOf(other, current), styled.spanStyles.map { it.item })
    }

    @Test
    fun theSelectedMatchSurvivesANewMatchList() {
        val first = listOf(SearchMatch(0, 0, 3), SearchMatch(4, 1, 4), SearchMatch(9, 0, 3))
        // More output streamed in: the selection stays on the same match, not on match 1.
        val grown = listOf(SearchMatch(0, 0, 3), SearchMatch(2, 0, 3), SearchMatch(4, 1, 4), SearchMatch(9, 0, 3))
        assertEquals(2, retainedMatchIndex(first[1], 1, grown))
        // The selected match vanished: keep the position, inside the new list.
        assertEquals(1, retainedMatchIndex(SearchMatch(7, 0, 3), 1, grown))
        assertEquals(1, retainedMatchIndex(SearchMatch(7, 0, 3), 5, first.take(2)))
        assertEquals(0, retainedMatchIndex(first[2], 2, emptyList()))
        assertEquals(0, retainedMatchIndex(null, 0, first))
    }

    @Test
    fun codeContentWidthStaysInsideWhatConstraintsCanEncode() {
        // 2000 CJK glyphs of one em at 12sp, font scale 2 and density 3.5.
        val em = 12f * 2f * 3.5f
        assertTrue(em * MAX_CODE_LINE_CHARS <= MAX_CODE_CONTENT_WIDTH_PX)
        assertEquals(MAX_CODE_CONTENT_WIDTH_PX.toFloat(), clampCodeContentWidthPx(em * 4000))
        assertEquals(1234f, clampCodeContentWidthPx(1234f))
        assertEquals(0f, clampCodeContentWidthPx(-1f))
        assertTrue(MAX_CODE_CONTENT_WIDTH_PX < 262_142)
    }

    @Test
    fun argumentRowLimitsShareThePreviewBudgetUntilExpanded() {
        assertEquals(listOf(150, 50, 0, 0), argumentRowLimits(listOf(150, 800, 800, 10), expanded = false))
        assertEquals(listOf(150, 800, 800, 10), argumentRowLimits(listOf(150, 800, 800, 10), expanded = true))
        assertEquals(listOf(2000), argumentRowLimits(listOf(5000), expanded = true))
        assertEquals(listOf(3, 4), argumentRowLimits(listOf(3, 4), expanded = false))
    }

    @Test
    fun clipboardSafeTextPassesShortTextThrough() {
        val (text, truncated) = clipboardSafeText("hello", maxChars = 100)
        assertEquals("hello", text)
        assertFalse(truncated)
    }

    @Test
    fun clipboardSafeTextCutsAtTheCharLimit() {
        val (text, truncated) = clipboardSafeText("0123456789", maxChars = 4)
        assertEquals("0123", text)
        assertTrue(truncated)
        assertEquals(4, text.length)
    }

    @Test
    fun clipboardSafeTextNeverSplitsASurrogatePair() {
        val emoji = "😀" // one surrogate pair, 2 UTF-16 chars
        val (text, truncated) = clipboardSafeText("ab$emoji", maxChars = 3)
        // The emoji does not fit in the 1 remaining char after "ab", so it is dropped whole.
        assertEquals("ab", text)
        assertTrue(truncated)
        for (i in text.indices) assertFalse(text[i].isHighSurrogate() && i == text.lastIndex)
    }

    @Test
    fun clipboardSafeTextDefaultLimitIs256KibOfUtf16() {
        val huge = "x".repeat(CLIPBOARD_MAX_CHARS * 2)
        val (text, truncated) = clipboardSafeText(huge)
        assertTrue(truncated)
        assertEquals(CLIPBOARD_MAX_CHARS, text.length)
    }

    @Test
    fun loadingProgressStepIsNullWithoutAKnownTotalAndCoarseOtherwise() {
        assertNull(loadingProgressStep(500, null))
        assertNull(loadingProgressStep(500, 0))
        assertEquals(0, loadingProgressStep(0, 1000))
        assertEquals(20, loadingProgressStep(250, 1000))
        assertEquals(100, loadingProgressStep(1000, 1000))
        // Every byte in the same decile reports the same step, not a per-chunk value.
        assertEquals(loadingProgressStep(201, 1000), loadingProgressStep(209, 1000))
    }
}
