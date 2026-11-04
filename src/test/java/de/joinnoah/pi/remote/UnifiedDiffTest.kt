package de.joinnoah.pi.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class UnifiedDiffTest {
    private val header = "--- a/f.txt\n+++ b/f.txt\n"

    private fun diff(original: String, edited: String, context: Int = 3) = unifiedDiff("f.txt", original, edited, context)

    @Test
    fun unchangedTextHasNoDiff() {
        assertNull(diff("a\nb\n", "a\nb\n"))
        assertNull(diff("", ""))
    }

    @Test
    fun insertedLine() {
        assertEquals(header + "@@ -1,2 +1,3 @@\n a\n+x\n b\n", diff("a\nb\n", "a\nx\nb\n"))
    }

    @Test
    fun insertionWithoutContextNamesTheLineBefore() {
        assertEquals(header + "@@ -1,0 +2 @@\n+x\n", diff("a\nb\n", "a\nx\nb\n", context = 0))
    }

    @Test
    fun deletedLine() {
        assertEquals(header + "@@ -1,3 +1,2 @@\n a\n-x\n b\n", diff("a\nx\nb\n", "a\nb\n"))
    }

    @Test
    fun replacedLinesListDeletionsBeforeInsertions() {
        assertEquals(
            header + "@@ -1,4 +1,4 @@\n a\n-b\n-c\n+B\n+C\n d\n",
            diff("a\nb\nc\nd\n", "a\nB\nC\nd\n"),
        )
    }

    @Test
    fun distantChangesGetTwoHunks() {
        // Seven unchanged lines between the changes: one more than two contexts.
        val original = "a\nb\nc\nd\ne\nf\ng\nh\ni\nj\n"
        val edited = "a\nX\nc\nd\ne\nf\ng\nh\ni\nY\n"
        assertEquals(
            header + "@@ -1,5 +1,5 @@\n a\n-b\n+X\n c\n d\n e\n@@ -7,4 +7,4 @@\n g\n h\n i\n-j\n+Y\n",
            diff(original, edited),
        )
    }

    @Test
    fun nearbyChangesShareAHunk() {
        // Six unchanged lines between the changes: the two contexts touch.
        val original = "a\nb\nc\nd\ne\nf\ng\nh\ni\n"
        val edited = "a\nX\nc\nd\ne\nf\ng\nh\nY\n"
        assertEquals(
            header + "@@ -1,9 +1,9 @@\n a\n-b\n+X\n c\n d\n e\n f\n g\n h\n-i\n+Y\n",
            diff(original, edited),
        )
    }

    @Test
    fun emptyToText() {
        assertEquals(header + "@@ -0,0 +1,2 @@\n+a\n+b\n", diff("", "a\nb\n"))
    }

    @Test
    fun textToEmpty() {
        assertEquals(header + "@@ -1,2 +0,0 @@\n-a\n-b\n", diff("a\nb\n", ""))
    }

    @Test
    fun missingFinalNewlineOnTheOriginalSide() {
        assertEquals(
            header + "@@ -1,2 +1,2 @@\n a\n-b\n\\ No newline at end of file\n+b\n",
            diff("a\nb", "a\nb\n"),
        )
    }

    @Test
    fun missingFinalNewlineOnTheEditedSide() {
        assertEquals(
            header + "@@ -1,2 +1,2 @@\n a\n-b\n+b\n\\ No newline at end of file\n",
            diff("a\nb\n", "a\nb"),
        )
    }

    @Test
    fun missingFinalNewlineOnBothSides() {
        assertEquals(
            header + "@@ -1,2 +1,2 @@\n a\n-b\n\\ No newline at end of file\n+c\n\\ No newline at end of file\n",
            diff("a\nb", "a\nc"),
        )
        assertEquals(
            header + "@@ -1,2 +1,2 @@\n-x\n+y\n b\n\\ No newline at end of file\n",
            diff("x\nb", "y\nb"),
        )
    }

    @Test
    fun crlfLinesKeepTheirLineEndings() {
        assertEquals(header + "@@ -1,3 +1,3 @@\n x\r\n-y\r\n+Y\r\n z\r\n", diff("x\r\ny\r\nz\r\n", "x\r\nY\r\nz\r\n"))
    }

    @Test
    fun changedLineEndingIsAChangedLine() {
        assertEquals(header + "@@ -1,2 +1,2 @@\n a\n-b\r\n+b\n", diff("a\nb\r\n", "a\nb\n"))
    }

    @Test
    fun matchesGitDiffNoIndex() {
        val original = (1..20).joinToString("") { "$it\n" }
        val edited = original.replace("\n3\n", "\nthree\n").replace("\n10\n", "\nten\n") + "21"
        // `git diff --no-index a b` on these two files, without the `diff --git` and `index` lines.
        val git = """
            --- a/a
            +++ b/b
            @@ -1,13 +1,13 @@
             1
             2
            -3
            +three
             4
             5
             6
             7
             8
             9
            -10
            +ten
             11
             12
             13
            @@ -18,3 +18,4 @@
             18
             19
             20
            +21
            \ No newline at end of file

        """.trimIndent()
        assertEquals(git, unifiedDiff("a", original, edited)!!.replace("+++ b/a", "+++ b/b"))
    }

    @Test
    fun aFullyChangedLongFileIsTooLargeQuicklyInsteadOfBlowingUp() {
        val original = (1..5_000).joinToString("") { "old line $it\n" }
        val edited = (1..5_000).joinToString("") { "new line $it\n" }
        val started = System.nanoTime()
        assertEquals(LineDiff.TooLarge, boundedUnifiedDiff("a", original, edited))
        assertEquals(EditorPrompt.TooLarge, editorPrompt("a", original, edited))
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $millis ms", millis < 1_000)
    }

    @Test
    fun onlyInsertionsNeedNoSearchAndStayExact() {
        val original = "a\nb\n"
        val edited = "a\n" + (1..3_000).joinToString("") { "$it\n" } + "b\n"
        val diff = boundedUnifiedDiff("f", original, edited, maxEdits = 10) as LineDiff.Text
        assertEquals(unifiedDiff("f", original, edited), diff.text)
        assertTrue(diff.text.startsWith("--- a/f\n+++ b/f\n@@ -1,2 +1,3002 @@\n a\n+1\n"))
    }

    @Test
    fun aSmallBoundStillDiffsSmallChanges() {
        val original = (1..100).joinToString("") { "$it\n" }
        val edited = original.replace("50\n", "fifty\n")
        assertEquals(LineDiff.Text(unifiedDiff("f", original, edited)!!), boundedUnifiedDiff("f", original, edited, maxEdits = 2))
        assertEquals(LineDiff.TooLarge, boundedUnifiedDiff("f", original, edited, maxEdits = 1))
        assertEquals(LineDiff.Same, boundedUnifiedDiff("f", original, original))
    }
}
