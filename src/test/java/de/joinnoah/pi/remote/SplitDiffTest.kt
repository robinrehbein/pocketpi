package de.joinnoah.pi.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SplitDiffTest {
    /** The split rows of [patch] as "old | new" texts, with "·" for an empty side. */
    private fun split(patch: String): List<String>? {
        val lines = patchDiff(patch)
        return splitDiff(patch, lines)?.map { row ->
            val old = row.old?.let { lines[it] }
            val new = row.new?.let { lines[it] }
            if (old != null && old === new && old.kind == DiffKind.HUNK) "@@"
            else "${old?.let { side(it) } ?: "·"} | ${new?.let { side(it) } ?: "·"}"
        }
    }

    private fun side(line: DiffLine): String = diffPrefix(line.kind) + line.text

    @Test
    fun aRemovedRunPairsWithTheAddedRunAfterIt() {
        val patch = "@@ -1,3 +1,3 @@\n keep\n-old one\n-old two\n+new one\n+new two\n"
        assertEquals(
            listOf("@@", " keep |  keep", "-old one | +new one", "-old two | +new two"),
            split(patch),
        )
    }

    @Test
    fun unevenRunsLeaveTheShorterSideEmpty() {
        val patch = "@@ -1,4 +1,3 @@\n-a\n-b\n-c\n+A\n context\n+added\n"
        assertEquals(
            listOf("@@", "-a | +A", "-b | ·", "-c | ·", " context |  context", "· | +added"),
            split(patch),
        )
    }

    @Test
    fun contextLinesFillBothSidesWithTheirOwnNumbers() {
        val patch = "@@ -10,2 +20,3 @@\n same\n+inserted\n also same\n"
        val lines = patchDiff(patch)
        val rows = checkNotNull(splitDiff(patch, lines))
        val context = rows[1]
        assertEquals(context.old, context.new)
        assertEquals(10, lines[checkNotNull(context.old)].oldLine)
        assertEquals(20, lines[checkNotNull(context.new)].newLine)
        assertEquals(SplitRow(null, 2), rows[2])
    }

    @Test
    fun aRemovedLineAfterAddedOnesStartsTheNextPair() {
        val patch = "@@ -1,2 +1,2 @@\n-x\n+X\n+Y\n-z\n"
        assertEquals(listOf("@@", "-x | +X", "· | +Y", "-z | ·"), split(patch))
    }

    @Test
    fun eachHunkKeepsItsOwnHeaderAndPairs() {
        val patch = "@@ -1 +1 @@\n-a\n+b\n@@ -9 +9 @@\n-c\n+d\n"
        assertEquals(listOf("@@", "-a | +b", "@@", "-c | +d"), split(patch))
    }

    @Test
    fun noNewlineMarkersDoNotSplitAPair() {
        val patch = "@@ -1 +1 @@\n-last\n\\ No newline at end of file\n+last line\n\\ No newline at end of file\n"
        assertEquals(listOf("@@", "-last | +last line"), split(patch))
    }

    @Test
    fun theHeadersBeforeTheFirstHunkAreLeftOut() {
        val patch = "diff --git a/x.kt b/x.kt\nindex 1..2 100644\n--- a/x.kt\n+++ b/x.kt\n@@ -1 +1 @@\n-a\n+b\n"
        assertEquals(listOf("@@", "-a | +b"), split(patch))
    }

    @Test
    fun renamesCopiesAndBinaryPatchesStayUnified() {
        val rename = "diff --git a/old.kt b/new.kt\nsimilarity index 90%\nrename from old.kt\nrename to new.kt\n@@ -1 +1 @@\n-a\n+b\n"
        assertNull(split(rename))
        val copy = "diff --git a/a.kt b/b.kt\ncopy from a.kt\ncopy to b.kt\n@@ -1 +1 @@\n-a\n+b\n"
        assertNull(split(copy))
        assertNull(split("diff --git a/logo.png b/logo.png\nBinary files a/logo.png and b/logo.png differ\n"))
        assertNull(split("diff --git a/logo.png b/logo.png\nGIT binary patch\nliteral 12\n"))
    }

    @Test
    fun anEmptyOrHugePatchStaysUnified() {
        assertNull(split(""))
        assertNull(split("diff --git a/x b/x\nold mode 100644\nnew mode 100755\n"))
        val huge = "@@ -0,0 +1,$MAX_SPLIT_DIFF_LINES @@\n" + (1..MAX_SPLIT_DIFF_LINES).joinToString("") { "+line $it\n" }
        assertNull(split(huge))
        val large = "@@ -0,0 +1,100 @@\n" + (1..100).joinToString("") { "+line $it\n" }
        assertNotNull(split(large))
    }

    @Test
    fun aRemovedLineThatLooksLikeAHeaderIsContent() {
        // "-rename from" in a hunk is a removed line reading "rename from", not a header.
        val patch = "@@ -1 +1 @@\n-rename from x\n+rename to y\n"
        assertEquals(listOf("@@", "-rename from x | +rename to y"), split(patch))
    }
}
