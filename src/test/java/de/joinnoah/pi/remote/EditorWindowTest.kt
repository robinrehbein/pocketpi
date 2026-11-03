package de.joinnoah.pi.remote

import org.junit.Assert.*
import org.junit.Test

class EditorWindowTest {
    private fun invalid(action: () -> Unit) {
        try {
            action()
            fail("Expected invalid argument to be rejected")
        } catch (_: IllegalArgumentException) {
            // Invalid ranges must never be coerced into an edit.
        }
    }

    private fun safe(text: String, offset: Int): Boolean =
        offset == 0 || offset == text.length ||
            !(Character.isHighSurrogate(text[offset - 1]) && Character.isLowSurrogate(text[offset]))

    private fun checkWindow(text: String, window: EditorWindow) {
        assertTrue(window.start in 0..text.length)
        assertTrue(window.end in window.start..text.length)
        assertTrue(window.end - window.start <= 2048)
        assertTrue("start splits surrogate pair", safe(text, window.start))
        assertTrue("end splits surrogate pair", safe(text, window.end))
    }

    @Test
    fun emptyAndShortTextHaveValidEndpoints() {
        assertEquals(1, EditorWindows.sectionCount(""))
        assertEquals(EditorWindow(0, 0), EditorWindows.atOffset("", 0))
        assertEquals(EditorWindow(0, 0), EditorWindows.atSection("", 1))
        assertEquals(EditorWindow(0, 0), EditorWindows.last(""))
        assertNull(EditorWindows.previous("", EditorWindow(0, 0)))
        assertNull(EditorWindows.next("", EditorWindow(0, 0)))
        assertEquals(EditorWindow(0, 3), EditorWindows.atOffset("abc", 0))
        assertEquals(EditorWindow(3, 3), EditorWindows.atOffset("abc", 3))
        assertEquals(EditorWindow(0, 3), EditorWindows.last("abc"))
    }

    @Test
    fun offsetSectionNextPreviousAndLastNeverSplitPairs() {
        val text = "a".repeat(2047) + "😀" + "b".repeat(2046) + "😀" + "c"
        val first = EditorWindows.atOffset(text, 0)
        assertEquals(EditorWindow(0, 2047), first)
        val second = checkNotNull(EditorWindows.next(text, first))
        assertEquals(2047, second.start)
        checkWindow(text, second)
        assertEquals(first, EditorWindows.previous(text, second))
        assertEquals(2047, EditorWindows.atOffset(text, 2048).start)
        assertEquals(2047, EditorWindows.atSection(text, 2).start)
        for (offset in listOf(0, 2047, 2048, 2049, 4095, 4096, text.length)) {
            checkWindow(text, EditorWindows.atOffset(text, offset))
        }
        val last = EditorWindows.last("a😀" + "x".repeat(2047))
        assertEquals(EditorWindow(3, 2050), last)
        checkWindow("a😀" + "x".repeat(2047), last)
        assertNull(EditorWindows.next(text, EditorWindows.last(text)))
    }

    @Test
    fun sectionsAndSequentialNavigationCoverLongLineAndNewlinesExactly() {
        for (text in listOf("x".repeat(1_048_576), ("line\n" + "e\u0301😀").repeat(30_000))) {
            assertEquals(maxOf(1, (text.length + 2047) / 2048), EditorWindows.sectionCount(text))
            for (section in 1..EditorWindows.sectionCount(text)) {
                val window = EditorWindows.atSection(text, section)
                checkWindow(text, window)
                val expected = (section - 1) * 2048
                assertTrue(window.start == expected || window.start == expected - 1)
            }
            val result = StringBuilder()
            var window = EditorWindows.atOffset(text, 0)
            while (true) {
                checkWindow(text, window)
                result.append(text.substring(window.start, window.end))
                val next = EditorWindows.next(text, window) ?: break
                assertEquals(window.end, next.start)
                assertEquals(next.start, checkNotNull(EditorWindows.previous(text, next)).end)
                window = next
            }
            assertEquals(text, result.toString())
            assertEquals(text.length, EditorWindows.last(text).end)
        }
    }

    @Test
    fun replacementChangesOnlySelectedRangeAndAcceptsLargePasteWithoutNormalization() {
        val text = "a".repeat(2048) + "e\u0301\n😀" + "z".repeat(2050)
        val first = EditorWindows.atOffset(text, 0)
        assertEquals("FIRST" + text.substring(first.end), EditorWindows.replace(text, first, "FIRST"))
        val middle = EditorWindows.atOffset(text, 2048)
        assertEquals(text.substring(0, middle.start) + "中\n" + text.substring(middle.end), EditorWindows.replace(text, middle, "中\n"))
        val last = EditorWindows.last(text)
        assertEquals(text.substring(0, last.start) + "END", EditorWindows.replace(text, last, "END"))
        assertEquals("", EditorWindows.replace("abc", EditorWindow(0, 3), ""))
        val paste = "e\u0301😀\r\u0000" + "x".repeat(1_048_576)
        val result = EditorWindows.replace("beforeAFTER", EditorWindow(6, 11), paste)
        assertEquals("before" + paste, result)
        assertFalse(validEditorText(result, MAX_EDITOR_UPLOAD_BYTES))
        assertEquals(paste, result.substring(6))
        val oversize = "x".repeat(1_048_577)
        val oversizedResult = EditorWindows.replace("", EditorWindow(0, 0), oversize)
        assertEquals(oversize, oversizedResult)
        assertFalse(validEditorText(oversizedResult, MAX_EDITOR_UPLOAD_BYTES))
        val invalidControl = EditorWindows.replace("ok", EditorWindow(0, 2), "x\ry")
        assertEquals("x\ry", invalidControl)
        assertFalse(validEditorText(invalidControl, MAX_EDITOR_UPLOAD_BYTES))
    }

    @Test
    fun invalidSectionsOffsetsAndRangesAreRejectedWithoutChangingText() {
        val text = "a😀b"
        for (offset in listOf(-1, text.length + 1, Int.MAX_VALUE)) invalid { EditorWindows.atOffset(text, offset) }
        for (section in listOf(0, -1, 2, Int.MAX_VALUE)) invalid { EditorWindows.atSection(text, section) }
        for (window in listOf(EditorWindow(-1, 1), EditorWindow(3, 2), EditorWindow(0, 5), EditorWindow(2, 4), EditorWindow(0, 2))) {
            invalid { EditorWindows.replace(text, window, "x") }
            invalid { EditorWindows.previous(text, window) }
            invalid { EditorWindows.next(text, window) }
        }
        invalid { EditorWindows.replace("a".repeat(2049), EditorWindow(0, 2049), "x") }
        assertEquals("a😀b", text)
    }

    @Test
    fun isolatedSurrogatesAreRetainedExactlyAndRemainInvalidForSend() {
        for (surrogate in listOf("\uD83D", "\uDE00")) {
            val original = "left" + surrogate + "right"
            val window = EditorWindows.atOffset(original, 0)
            assertEquals(original, EditorWindows.replace(original, window, original))
            val replaced = EditorWindows.replace("AB", EditorWindow(1, 1), surrogate)
            assertEquals("A" + surrogate + "B", replaced)
            assertEquals(surrogate[0], replaced[1])
            assertFalse(validEditorText(replaced, MAX_EDITOR_UPLOAD_BYTES))
        }
    }

    @Test
    fun rewindowingAfterEditRespectsNewPairsAndPreservesBrokenOnes() {
        val high = "\uD83D"
        val low = "\uDE00"
        val original = "a".repeat(2047) + high + "x" + low + "tail"
        assertFalse(validEditorText(original, MAX_EDITOR_UPLOAD_BYTES))
        // Removing x makes the previously isolated units adjacent as one valid pair.
        val joined = EditorWindows.replace(original, EditorWindow(2048, 2049), "")
        assertEquals("a".repeat(2047) + "😀tail", joined)
        val aroundPair = EditorWindows.atOffset(joined, 2048)
        assertEquals(2047, aroundPair.start)
        checkWindow(joined, aroundPair)
        assertTrue(validEditorText(joined, MAX_EDITOR_UPLOAD_BYTES))
        // Replace the whole pair with its high half: keep it, even though Send rejects it.
        val broken = EditorWindows.replace(joined, EditorWindow(2047, 2049), high)
        assertEquals("a".repeat(2047) + high + "tail", broken)
        assertEquals(high[0], broken[2047])
        assertFalse(validEditorText(broken, MAX_EDITOR_UPLOAD_BYTES))
        val bounded = EditorWindows.atOffset(broken, 2048)
        checkWindow(broken, bounded)
        assertEquals(broken, EditorWindows.replace(broken, bounded, broken.substring(bounded.start, bounded.end)))
    }

}
