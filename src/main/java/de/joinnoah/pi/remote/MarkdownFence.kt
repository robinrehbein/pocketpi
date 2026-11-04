package de.joinnoah.pi.remote

/**
 * A run of Markdown. For a fenced block, [language] is the first word of the info string (null when
 * the info string is empty) and [closed] says whether the closing fence arrived; text is always closed.
 */
internal data class MarkdownSegment(
    val text: String,
    val code: Boolean,
    val language: String? = null,
    val closed: Boolean = true,
) {
    /** A closed `mermaid` fence; a fence still streaming stays an ordinary code block. */
    val isMermaid: Boolean get() = code && closed && language.equals("mermaid", ignoreCase = true)
}

/** Fences are line-based, so backticks in inline code never open a code block. */
internal fun markdownSegments(text: String): List<MarkdownSegment> {
    val segments = mutableListOf<MarkdownSegment>()
    val buffer = mutableListOf<String>()
    var fence: String? = null
    var language: String? = null
    val opening = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
    fun flush(code: Boolean, closed: Boolean = true) {
        if (buffer.isNotEmpty())
            segments.add(MarkdownSegment(buffer.joinToString("\n"), code, if (code) language else null, closed))
        buffer.clear()
    }
    text.lines().forEach { line ->
        val active = fence
        if (active == null) {
            val match = opening.matchEntire(line)
            if (match != null && !(match.groupValues[1][0] == '`' && '`' in match.groupValues[2])) {
                flush(false)
                fence = match.groupValues[1]
                language = match.groupValues[2].trim().split(Regex("\\s+"), limit = 2).first().takeIf { it.isNotEmpty() }
            } else buffer.add(line)
        } else {
            val trimmed = line.trimStart(' ')
            val indent = line.length - trimmed.length
            val run = trimmed.takeWhile { it == active[0] }
            if (indent <= 3 && run.length >= active.length && trimmed.drop(run.length).isBlank()) {
                flush(true)
                fence = null
                language = null
            } else buffer.add(line)
        }
    }
    flush(fence != null, closed = fence == null)
    return segments
}
