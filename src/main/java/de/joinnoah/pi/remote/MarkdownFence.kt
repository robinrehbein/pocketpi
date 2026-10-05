package de.joinnoah.pi.remote

internal data class MarkdownSegment(val text: String, val code: Boolean)

/** Fences are line-based, so backticks in inline code never open a code block. */
internal fun markdownSegments(text: String): List<MarkdownSegment> {
    val segments = mutableListOf<MarkdownSegment>()
    val buffer = mutableListOf<String>()
    var fence: String? = null
    val opening = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
    fun flush(code: Boolean) {
        if (buffer.isNotEmpty()) segments.add(MarkdownSegment(buffer.joinToString("\n"), code))
        buffer.clear()
    }
    text.lines().forEach { line ->
        val active = fence
        if (active == null) {
            val match = opening.matchEntire(line)
            if (match != null && !(match.groupValues[1][0] == '`' && '`' in match.groupValues[2])) {
                flush(false)
                fence = match.groupValues[1]
            } else buffer.add(line)
        } else {
            val trimmed = line.trimStart(' ')
            val indent = line.length - trimmed.length
            val run = trimmed.takeWhile { it == active[0] }
            if (indent <= 3 && run.length >= active.length && trimmed.drop(run.length).isBlank()) {
                flush(true)
                fence = null
            } else buffer.add(line)
        }
    }
    flush(fence != null)
    return segments
}
