package de.joinnoah.pi.remote

/*
 * A line diff in the unified format `git diff` prints, without dependencies. Lines keep their
 * terminators, so a changed line ending (CRLF, a missing final newline) is a changed line. The
 * edit script comes from Myers' O(ND) algorithm after trimming the common prefix and suffix.
 */

/**
 * Most changed lines [boundedUnifiedDiff] searches for. Myers keeps one row per edit, so time and
 * memory grow with the square of this; a diff this long is far above [MAX_FILE_QUOTE_BYTES] for
 * any ordinary code.
 */
internal const val MAX_DIFF_EDITS = 1_000

/** What [boundedUnifiedDiff] found. */
internal sealed interface LineDiff {
    data object Same : LineDiff

    /** More than the bound of changed lines; no diff was built. */
    data object TooLarge : LineDiff

    data class Text(val text: String) : LineDiff
}

/**
 * A unified diff of [original] to [edited] for [path] with [context] lines around each change, or
 * null when both are equal. It has no bound; the editor uses [boundedUnifiedDiff].
 */
fun unifiedDiff(path: String, original: String, edited: String, context: Int = 3): String? =
    (boundedUnifiedDiff(path, original, edited, context, maxEdits = Int.MAX_VALUE) as? LineDiff.Text)?.text

/**
 * [unifiedDiff], or [LineDiff.TooLarge] as soon as the edit script needs more than [maxEdits]
 * changed lines (a run of only insertions or only deletions is never too large: it needs no search).
 */
internal fun boundedUnifiedDiff(
    path: String,
    original: String,
    edited: String,
    context: Int = 3,
    maxEdits: Int = MAX_DIFF_EDITS,
): LineDiff {
    if (original == edited) return LineDiff.Same
    val edits = diffLines(splitLines(original), splitLines(edited), maxEdits) ?: return LineDiff.TooLarge
    // oldBefore[i] and newBefore[i]: lines of each side before edits[i].
    val oldBefore = IntArray(edits.size + 1)
    val newBefore = IntArray(edits.size + 1)
    for ((i, edit) in edits.withIndex()) {
        oldBefore[i + 1] = oldBefore[i] + if (edit.op == Op.INSERT) 0 else 1
        newBefore[i + 1] = newBefore[i] + if (edit.op == Op.DELETE) 0 else 1
    }
    val changes = edits.indices.filter { edits[it].op != Op.KEEP }
    val out = StringBuilder("--- a/").append(path).append("\n+++ b/").append(path).append('\n')
    var c = 0
    while (c < changes.size) {
        val first = changes[c]
        var last = first
        // Changes at most two contexts apart share a hunk, as in git.
        while (c + 1 < changes.size && changes[c + 1] - last - 1 <= 2 * context) last = changes[++c]
        c++
        hunk(out, edits, oldBefore, newBefore, maxOf(0, first - context), minOf(edits.size, last + context + 1))
    }
    return LineDiff.Text(out.toString())
}

private enum class Op(val prefix: Char) { KEEP(' '), DELETE('-'), INSERT('+') }

private class Edit(val op: Op, val line: String)

private fun hunk(out: StringBuilder, edits: List<Edit>, oldBefore: IntArray, newBefore: IntArray, start: Int, end: Int) {
    out.append("@@ -").append(range(oldBefore[start], oldBefore[end] - oldBefore[start]))
        .append(" +").append(range(newBefore[start], newBefore[end] - newBefore[start])).append(" @@\n")
    for (i in start until end) {
        val line = edits[i].line
        out.append(edits[i].op.prefix).append(line)
        if (!line.endsWith('\n')) out.append("\n\\ No newline at end of file\n")
    }
}

/** A hunk range as git prints it: an empty side names the line before it, a count of one is implied. */
private fun range(before: Int, count: Int): String = when (count) {
    0 -> "$before,0"
    1 -> "${before + 1}"
    else -> "${before + 1},$count"
}

/** The lines of [text], each with its `\n` (and so any `\r` before it); the last may lack one. */
private fun splitLines(text: String): List<String> {
    val lines = ArrayList<String>()
    var from = 0
    while (from < text.length) {
        val newline = text.indexOf('\n', from)
        val to = if (newline < 0) text.length else newline + 1
        lines += text.substring(from, to)
        from = to
    }
    return lines
}

/**
 * The edit script from [a] to [b], with deletions before insertions in each run of changes; null
 * when it needs more than [maxEdits] changed lines.
 */
private fun diffLines(a: List<String>, b: List<String>, maxEdits: Int): List<Edit>? {
    var prefix = 0
    while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
    var suffix = 0
    while (suffix < a.size - prefix && suffix < b.size - prefix && a[a.size - 1 - suffix] == b[b.size - 1 - suffix]) suffix++
    val middleA = a.subList(prefix, a.size - suffix)
    val middleB = b.subList(prefix, b.size - suffix)

    val edits = ArrayList<Edit>(a.size + b.size)
    for (i in 0 until prefix) edits += Edit(Op.KEEP, a[i])
    val deleted = ArrayList<Edit>()
    val inserted = ArrayList<Edit>()
    var x = 0
    var y = 0
    val ops =
        when {
            // Only insertions or only deletions: nothing to search.
            middleA.isEmpty() -> List(middleB.size) { Op.INSERT }
            middleB.isEmpty() -> List(middleA.size) { Op.DELETE }
            else -> myers(middleA, middleB, maxEdits) ?: return null
        }
    for (op in ops) {
        when (op) {
            Op.DELETE -> deleted += Edit(op, middleA[x++])
            Op.INSERT -> inserted += Edit(op, middleB[y++])
            Op.KEEP -> {
                edits += deleted; edits += inserted
                deleted.clear(); inserted.clear()
                edits += Edit(op, middleA[x++]).also { y++ }
            }
        }
    }
    edits += deleted
    edits += inserted
    for (i in a.size - suffix until a.size) edits += Edit(Op.KEEP, a[i])
    return edits
}

/**
 * Myers' greedy shortest edit script from [a] to [b], one [Op] per step; null when it needs more
 * than [maxEdits] edits. Each step keeps only the diagonals it can reach, so the trace holds
 * about D² numbers for D edits, not D × (N + M).
 */
private fun myers(a: List<String>, b: List<String>, maxEdits: Int): List<Op>? {
    val n = a.size
    val m = b.size
    val offset = n + m + 1
    val v = IntArray(2 * offset + 1)
    // trace[d] is v[-d-1..d+1] as it was before step d; backtracking reads no further out.
    val trace = ArrayList<IntArray>()
    search@ for (d in 0..n + m) {
        if (d > maxEdits) return null
        trace += v.copyOfRange(offset - d - 1, offset + d + 2)
        for (k in -d..d step 2) {
            var x = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) v[offset + k + 1] else v[offset + k - 1] + 1
            var y = x - k
            while (x < n && y < m && a[x] == b[y]) { x++; y++ }
            v[offset + k] = x
            if (x >= n && y >= m) break@search
        }
    }
    val ops = ArrayList<Op>()
    var x = n
    var y = m
    for (d in trace.indices.reversed()) {
        val before = trace[d]
        val shift = d + 1
        val k = x - y
        val prevK = if (k == -d || (k != d && before[shift + k - 1] < before[shift + k + 1])) k + 1 else k - 1
        val prevX = before[shift + prevK]
        val prevY = prevX - prevK
        while (x > prevX && y > prevY) { ops += Op.KEEP; x--; y-- }
        if (d > 0) ops += if (x == prevX) Op.INSERT else Op.DELETE
        x = prevX
        y = prevY
    }
    return ops.asReversed()
}
