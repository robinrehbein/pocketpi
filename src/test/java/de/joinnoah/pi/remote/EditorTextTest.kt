package de.joinnoah.pi.remote

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorTextTest {
    /** Applies the edit and returns the text with `[` and `]` around the new selection. */
    private fun indent(marked: String, outdent: Boolean = false, unit: String = "    "): String? {
        val start = marked.indexOf('[')
        val end = marked.indexOf(']') - 1
        val text = marked.replace("[", "").replace("]", "")
        val edit = indentLines(text, start, end, unit, outdent) ?: return null
        val result = edit.applyTo(text)
        val (from, to) = minOf(edit.selectionStart, edit.selectionEnd) to maxOf(edit.selectionStart, edit.selectionEnd)
        return result.substring(0, from) + "[" + result.substring(from, to) + "]" + result.substring(to)
    }

    @Test
    fun tabAtACursorInsertsOneUnit() {
        assertEquals("ab    []c", indent("ab[]c"))
        assertEquals("\t[]x", indent("[]x", unit = "\t"))
    }

    @Test
    fun indentsEverySelectedLineButNotEmptyOnesAndKeepsALineStartSelected() {
        assertEquals("[    one\n\n    two]\nthree", indent("[one\n\ntwo]\nthree"))
    }

    @Test
    fun aSelectionInsideALineIndentsThatLineAndMovesWithIt() {
        assertEquals("    o[ne\n    tw]o", indent("o[ne\ntw]o"))
    }

    @Test
    fun aSelectionEndingAtALineStartLeavesThatLine() {
        assertEquals("[    one\n]two", indent("[one\n]two"))
    }

    @Test
    fun outdentRemovesOneUnitOrATabAndFewerSpaces() {
        assertEquals("[one\n  two\n\tthree\nfour]", indent("[    one\n      two\n\t\tthree\n  four]", outdent = true))
    }

    @Test
    fun outdentAtACursorWorksOnItsLineAndKeepsTheCursorInPlace() {
        assertEquals("a\nb[]c\nd", indent("a\n    b[]c\nd", outdent = true))
        // A cursor inside the removed spaces ends at the line start.
        assertEquals("[]x", indent("  []  x", outdent = true))
    }

    @Test
    fun outdentWithNothingToRemoveChangesNothing() {
        assertNull(indent("[one\ntwo]", outdent = true))
    }

    @Test
    fun aReversedSelectionStaysReversed() {
        val edit = checkNotNull(indentLines("one\ntwo", 7, 1, "  ", outdent = false))
        assertEquals("  one\n  two", edit.applyTo("one\ntwo"))
        assertEquals(11, edit.selectionStart)
        assertEquals(3, edit.selectionEnd)
    }

    @Test
    fun theIndentUnitFollowsTheFile() {
        assertEquals("\t", indentUnit("fun a() {\n\tb()\n\tc()\n  d\n}\n"))
        assertEquals("    ", indentUnit("fun a() {\n    b()\n\tc()\n    d\n}\n"))
        assertEquals("    ", indentUnit(""))
        // Two-space files, CRLF ones included; a two-level close (a step of four) does not outvote them.
        assertEquals("  ", indentUnit("a {\n  b {\n    c\n  }\n  d {\n    e\n  }\n}\n"))
        assertEquals("  ", indentUnit("a {\r\n  b {\r\n    c\r\n}\r\n"))
        assertEquals("    ", indentUnit("a {\n    b {\n        c\n    }\n}\n"))
    }

    @Test
    fun findsEveryMatchIgnoringCase() {
        assertEquals(listOf(0..2, 8..10), findMatches("Foo bar foo", "foo"))
        assertEquals(listOf(0..1, 2..3), findMatches("aaaaa", "aa"))
        assertTrue(findMatches("abc", "").isEmpty())
        assertTrue(findMatches("abc", "abcd").isEmpty())
    }

    @Test
    fun theMatchAtOrAfterAPositionWraps() {
        val matches = listOf(0..2, 8..10)
        assertEquals(0, matchAtOrAfter(matches, 0))
        assertEquals(1, matchAtOrAfter(matches, 3))
        assertEquals(0, matchAtOrAfter(matches, 11))
        assertEquals(-1, matchAtOrAfter(emptyList(), 0))
    }

    private val colors = SyntaxColors(Color.Red, Color.Green, Color.Gray, Color.Blue, Color.Cyan, Color.Magenta)

    @Test
    fun highlightSpansUseWholeTextOffsets() {
        val text = "val a = 1\r\n// note\n"
        val spans = highlightSpans(text, SyntaxLanguage.KOTLIN, colors)
        val keyword = spans.single { it.style.color == Color.Red }
        assertEquals("val", text.substring(keyword.start, keyword.end))
        val comment = spans.single { it.style.color == Color.Gray }
        assertEquals("// note", text.substring(comment.start, comment.end))
    }

    @Test
    fun staleSpansKeepUnchangedTextAndDropTheChange() {
        val style = SpanStyle(color = Color.Red)
        val highlight = TextHighlight("aa bb cc", listOf(TextSpan(0, 2, style), TextSpan(3, 5, style), TextSpan(6, 8, style)))
        assertEquals(highlight.spans, spansFor(highlight, "aa bb cc"))
        // "bb" became "bxb": the first span stays, the last moves by one, the changed one goes.
        assertEquals(listOf(TextSpan(0, 2, style), TextSpan(7, 9, style)), spansFor(highlight, "aa bxb cc"))
        // Never outside the text, even when it shrank to nothing.
        assertTrue(spansFor(highlight, "").isEmpty())
        assertEquals(listOf(TextSpan(0, 2, style)), spansFor(highlight, "aa"))
    }

    @Test
    fun blankCrlfLinesAreNotIndented() {
        val text = "a\r\n\r\nb\r\n"
        val edit = checkNotNull(indentLines(text, 0, text.length, "  ", outdent = false))
        assertEquals("  a\r\n\r\n  b\r\n", edit.applyTo(text))
    }

    @Test
    fun aLineStartsWhereItsLineBreakEnds() {
        assertEquals(0, lineStartOffset("one\ntwo\n", 1))
        assertEquals(4, lineStartOffset("one\ntwo\n", 2))
        assertEquals(8, lineStartOffset("one\ntwo\n", 3))
        assertEquals(8, lineStartOffset("one\ntwo\n", 9))
    }
}
