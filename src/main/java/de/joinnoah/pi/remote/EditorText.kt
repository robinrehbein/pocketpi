package de.joinnoah.pi.remote

import androidx.compose.ui.text.SpanStyle

/*
 * Text operations of the code editor that need no Compose: indenting lines, finding matches and
 * placing highlight spans. CodeEditor applies their results to its TextFieldState.
 */

/** Replace [start] until [end] of the text with [replacement], then select [selectionStart] to [selectionEnd]. */
internal data class LineEdit(
    val start: Int,
    val end: Int,
    val replacement: String,
    val selectionStart: Int,
    val selectionEnd: Int,
) {
    fun applyTo(text: String): String = text.substring(0, start) + replacement + text.substring(end)
}

/**
 * One level of indentation for [text]: a tab when most indented lines start with one, otherwise
 * four spaces.
 */
internal fun indentUnit(text: String): String {
    var tabs = 0
    var spaces = 0
    var lineStart = true
    for (char in text) {
        if (lineStart) {
            if (char == '\t') tabs++ else if (char == ' ') spaces++
        }
        lineStart = char == '\n'
    }
    return if (tabs > spaces) "\t" else "    "
}

/**
 * Indents ([outdent] false) or outdents the lines touched by the selection [selectionStart] to
 * [selectionEnd] by [unit]. A collapsed selection indents by inserting [unit] at the cursor. A
 * selection that ends at the start of a line leaves that line alone. Outdent removes one tab, or
 * up to one unit of leading spaces, from each line. Empty lines are not indented. Null when
 * nothing changes.
 */
internal fun indentLines(text: String, selectionStart: Int, selectionEnd: Int, unit: String, outdent: Boolean): LineEdit? {
    val from = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
    val to = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)
    if (!outdent && from == to) {
        val cursor = from + unit.length
        return LineEdit(from, from, unit, cursor, cursor)
    }
    val first = text.lastIndexOf('\n', from - 1) + 1
    // A selection that ends at a line start (a whole line picked with Shift+Down) stops before it.
    val lastPosition = if (to > from && to > 0 && text[to - 1] == '\n') to - 1 else to
    val lastEnd = text.indexOf('\n', lastPosition).let { if (it < 0) text.length else it }
    val region = text.substring(first, lastEnd)
    val width = unit.replace("\t", "    ").length
    // Offsets (relative to [first]) and signed lengths of each change, in order.
    val changes = ArrayList<Pair<Int, Int>>()
    val out = StringBuilder(region.length + unit.length * 8)
    var offset = 0
    for (line in region.split('\n')) {
        when {
            !outdent && line.isNotEmpty() -> {
                changes += offset to unit.length
                out.append(unit).append(line)
            }
            outdent -> {
                val remove =
                    if (line.startsWith('\t')) 1
                    else line.take(width).takeWhile { it == ' ' }.length
                if (remove > 0) changes += offset to -remove
                out.append(line, remove, line.length)
            }
            else -> out.append(line)
        }
        offset += line.length + 1
        out.append('\n')
    }
    out.setLength(out.length - 1)
    if (changes.isEmpty()) return null
    // Where an old offset ends up; [moveAtInsert] decides whether a position at an insertion moves.
    fun map(position: Int, moveAtInsert: Boolean): Int {
        var shift = 0
        val relative = position - first
        for ((at, length) in changes) {
            if (length > 0) {
                if (at < relative || (at == relative && moveAtInsert)) shift += length
            } else {
                val removed = -length
                if (relative >= at + removed) shift -= removed
                else if (relative > at) shift -= relative - at
            }
        }
        return (position + shift).coerceAtLeast(first)
    }
    val collapsed = selectionStart == selectionEnd
    val newStart = map(selectionStart, moveAtInsert = collapsed || selectionStart > selectionEnd)
    val newEnd = map(selectionEnd, moveAtInsert = collapsed || selectionEnd > selectionStart)
    return LineEdit(first, lastEnd, out.toString(), newStart, newEnd)
}

/** Every non-overlapping occurrence of [query] in [text], ignoring case; empty for a blank query. */
internal fun findMatches(text: CharSequence, query: String, limit: Int = 10_000): List<IntRange> {
    if (query.isEmpty()) return emptyList()
    val matches = ArrayList<IntRange>()
    var from = 0
    while (from <= text.length - query.length && matches.size < limit) {
        val at = text.indexOf(query, from, ignoreCase = true)
        if (at < 0) break
        matches += at until at + query.length
        from = at + query.length
    }
    return matches
}

/** The index of the first of [matches] at or after [position], wrapping to the first; -1 when none. */
internal fun matchAtOrAfter(matches: List<IntRange>, position: Int): Int {
    if (matches.isEmpty()) return -1
    val index = matches.indexOfFirst { it.first >= position }
    return if (index < 0) 0 else index
}

/** A colour span of the whole editor text. */
internal data class TextSpan(val start: Int, val end: Int, val style: SpanStyle)

/** The highlight spans of [text] and the text they were computed for. */
internal class TextHighlight(val text: String, val spans: List<TextSpan>)

/** Highlights [text] line by line ([highlightLines]) and returns the spans in whole-text offsets. */
internal fun highlightSpans(text: String, language: SyntaxLanguage, colors: SyntaxColors): List<TextSpan> {
    val lines = text.split('\n')
    val styled = highlightLines(lines.map { it.removeSuffix("\r") }, language, colors)
    val spans = ArrayList<TextSpan>()
    var start = 0
    for ((index, line) in lines.withIndex()) {
        val annotated = styled.getOrNull(index)
        if (annotated != null)
            for (range in annotated.spanStyles) {
                val end = minOf(range.end, line.length)
                if (range.start < end) spans += TextSpan(start + range.start, start + end, range.item)
            }
        start += line.length + 1
    }
    return spans
}

/**
 * The spans of [highlight] that still fit [current]: those before the first changed character
 * keep their place, those after the last changed one move with it, and those touching the change
 * are dropped until the next highlight. Never returns a span outside [current].
 */
internal fun spansFor(highlight: TextHighlight, current: CharSequence): List<TextSpan> {
    val old = highlight.text
    if (old.length == current.length && contentEquals(old, current)) return highlight.spans
    val limit = minOf(old.length, current.length)
    var prefix = 0
    while (prefix < limit && old[prefix] == current[prefix]) prefix++
    var suffix = 0
    while (suffix < limit - prefix && old[old.length - 1 - suffix] == current[current.length - 1 - suffix]) suffix++
    val delta = current.length - old.length
    val oldSuffixStart = old.length - suffix
    val kept = ArrayList<TextSpan>(highlight.spans.size)
    for (span in highlight.spans) {
        when {
            span.end <= prefix -> kept += span
            span.start >= oldSuffixStart -> kept += span.copy(start = span.start + delta, end = span.end + delta)
        }
    }
    return kept.filter { it.start >= 0 && it.end <= current.length && it.start < it.end }
}

private fun contentEquals(a: String, b: CharSequence): Boolean {
    for (index in a.indices) if (a[index] != b[index]) return false
    return true
}
