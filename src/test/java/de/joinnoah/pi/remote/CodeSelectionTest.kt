package de.joinnoah.pi.remote

import androidx.compose.ui.geometry.Rect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CodeSelectionTest {
    @Before fun startWithoutZoom() = CodeZoom.reset()

    @After fun endWithoutZoom() = CodeZoom.reset()

    private val diff =
        listOf(
            DiffLine(DiffKind.HUNK, "@@ -1 +1 @@", null, null),
            DiffLine(DiffKind.REMOVED, "a", 1, null),
            DiffLine(DiffKind.HUNK, "", null, null),
            DiffLine(DiffKind.ADDED, "b", null, 1),
            DiffLine(DiffKind.CONTEXT, "c", 2, 2),
        )

    @Test
    fun aRangeExtendsFromItsAnchorAndAStartWithoutOneIsTheLine() {
        assertEquals(LineSelection(4), (null as LineSelection?).extendTo(4))
        assertEquals(LineSelection(2, 6), LineSelection(2).extendTo(6))
        assertEquals(LineSelection(6, 2), LineSelection(6, 3).extendTo(2))
    }

    @Test
    fun allLinesPicksTheWholeFileOrNothing() {
        assertEquals(LineSelection(1, 3), allLines(3))
        assertEquals(LineSelection(1, 1), allLines(1))
        assertNull(allLines(0))
    }

    @Test
    fun copiedDiffLinesDropTheirPrefixesUnlessAskedToKeepThem() {
        val picked = LineSelection(2, 5)
        // Copy (Ctrl+C): the text alone, the hunk divider left out.
        assertEquals("a\nb\nc", selectedDiffText(diff, picked, prefixed = false))
        // Copy with +/-: each line with its prefix.
        assertEquals("-a\n+b\n c", selectedDiffText(diff, picked, prefixed = true))
    }

    @Test
    fun aPickThatStartsAtAHunkHeaderKeepsTheHeaderText() {
        assertEquals("@@ -1 +1 @@\na", selectedDiffText(diff, LineSelection(1, 2), prefixed = false))
        assertEquals("@@ -1 +1 @@\n-a", selectedDiffText(diff, LineSelection(1, 2), prefixed = true))
    }

    @Test
    fun aPickBeyondTheDiffIsKeptWithinIt() {
        assertEquals("c", selectedDiffText(diff, LineSelection(5, 9), prefixed = false))
        assertEquals("", selectedDiffText(diff, LineSelection(7, 9), prefixed = false))
    }

    @Test
    fun aFilePickCopiesItsLinesWithoutNumbers() {
        val lines = listOf("x", "y", "z")
        assertEquals("y\nz", selectedFileText(lines, LineSelection(2, 3)))
        assertEquals("z", selectedFileText(lines, LineSelection(3, 9)))
        assertEquals("x\ny\nz", selectedFileText(lines, LineSelection(1, 3)))
    }

    @Test
    fun theLineUnderThePointerIsTheRowItHitOrTheNearestOne() {
        val rows = mapOf(1 to Rect(0f, 0f, 10f, 20f), 2 to Rect(0f, 20f, 10f, 40f), 4 to Rect(0f, 60f, 10f, 80f))
        assertEquals(2, lineAtY(rows, 25f))
        // Between rows 2 and 4 (a hunk header has no place): the nearer one.
        assertEquals(2, lineAtY(rows, 45f))
        assertEquals(4, lineAtY(rows, 55f + 10f))
        assertEquals(1, lineAtY(rows, -10f))
        assertEquals(4, lineAtY(rows, 500f))
        assertNull(lineAtY(emptyMap(), 10f))
    }

    @Test
    fun zoomStepsAndStaysWithinItsLimitsPerPane() {
        CodeZoom.step("files", inward = true)
        assertEquals(CodeZoom.STEP, CodeZoom.scale("files"), 0.0001f)
        assertEquals(1f, CodeZoom.scale("changes"), 0f)
        CodeZoom.step("files", inward = false)
        assertEquals(1f, CodeZoom.scale("files"), 0.0001f)
        CodeZoom.zoom("files", 100f)
        assertEquals(CodeZoom.MAX, CodeZoom.scale("files"), 0f)
        CodeZoom.zoom("files", 0.001f)
        assertEquals(CodeZoom.MIN, CodeZoom.scale("files"), 0f)
        CodeZoom.zoom("files", Float.NaN)
        assertEquals(CodeZoom.MIN, CodeZoom.scale("files"), 0f)
    }

    @Test
    fun aDragOfPickedLinesIsAcceptedByTheChatButOtherOwnDragsAreNot() {
        assertTrue(acceptsDrop(CodeLinesDrag("prompt"), setOf("text/plain")))
        assertTrue(acceptsDrop(null, setOf("text/plain")))
        assertEquals(false, acceptsDrop(OwnDrag, setOf("text/plain")))
    }

    @Test
    fun theDiffMenuOffersCopyWithPrefixOnlyWhereItCanCopyIt() {
        assertEquals(
            listOf(DiffLineMenuAction.COMMENT, DiffLineMenuAction.COPY_LINE, DiffLineMenuAction.COPY_WITH_PREFIX, DiffLineMenuAction.COPY_HUNK),
            diffLineMenuActions(commentable = true, copyWithPrefix = true),
        )
        assertEquals(
            listOf(DiffLineMenuAction.COPY_LINE, DiffLineMenuAction.COPY_HUNK),
            diffLineMenuActions(commentable = false),
        )
    }
}
