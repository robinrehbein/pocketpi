package de.joinnoah.pi.remote

/** A bullet or task line: [depth] counts two-space steps of indentation, [marker] is what it shows. */
internal data class MarkdownListItem(val depth: Int, val marker: String, val text: String)

/**
 * A run of neighbouring prose blocks that one text, list or quote shows together, so consecutive
 * lines share a paragraph instead of each getting its own spacing.
 */
internal sealed interface MarkdownRun {
    /** A table, an image or an artifact card, drawn as it is. */
    data class Block(val block: MarkdownBlock) : MarkdownRun

    data class Heading(val level: Int, val text: String) : MarkdownRun

    data object Rule : MarkdownRun

    /** Lines of prose; each stays on its own line inside the run. */
    data class Paragraph(val lines: List<String>) : MarkdownRun

    data class Items(val items: List<MarkdownListItem>) : MarkdownRun

    /** The quoted text of each `>` line. */
    data class Quote(val lines: List<String>) : MarkdownRun
}

private val listLine = Regex("^([ \\t]*)[-+*] (.*)$")
private val taskStart = Regex("^\\[([ xX])\\](?: (.*))?$")
private val quoteLine = Regex("^ {0,3}>(?: ?)(.*)$")

internal const val TASK_OPEN_MARKER = "☐"
internal const val TASK_DONE_MARKER = "☑"

/** The bullet or task a line starts with, or null for a line that is not a list item. */
internal fun listItem(line: String): MarkdownListItem? {
    val match = listLine.matchEntire(line) ?: return null
    val indent = match.groupValues[1].sumOf { if (it == '\t') 4 else 1 }
    val depth = indent / 2
    val body = match.groupValues[2]
    val task = taskStart.matchEntire(body)
    return if (task != null) {
        val marker = if (task.groupValues[1] == " ") TASK_OPEN_MARKER else TASK_DONE_MARKER
        MarkdownListItem(depth, marker, task.groupValues[2])
    } else MarkdownListItem(depth, bulletMarker(depth), body)
}

private fun bulletMarker(depth: Int): String =
    when (depth) {
        0 -> "•"
        1 -> "◦"
        else -> "▪"
    }

/** The text of a `>` quote line, or null when the line is not quoted. */
internal fun quoteText(line: String): String? = quoteLine.matchEntire(line)?.groupValues?.get(1)

/** The level of an ATX heading line (`# ` to `###### `), or null. */
internal fun headingLevel(line: String): Int? =
    line.takeWhile { it == '#' }.length.takeIf { it in 1..6 && line.getOrNull(it) == ' ' }

/**
 * [blocks] of one prose segment as runs. With [artifactCards] false, an artifact link reads as the
 * line it is, like any other prose line.
 */
internal fun markdownRuns(blocks: List<MarkdownBlock>, artifactCards: Boolean): List<MarkdownRun> {
    val runs = mutableListOf<MarkdownRun>()
    var paragraph = mutableListOf<String>()
    var items = mutableListOf<MarkdownListItem>()
    var quote = mutableListOf<String>()
    // Closes every open run except [keep], the one the current line continues.
    fun flush(keep: Any? = null) {
        if (paragraph.isNotEmpty() && paragraph !== keep) {
            runs += MarkdownRun.Paragraph(paragraph.toList())
            paragraph = mutableListOf()
        }
        if (items.isNotEmpty() && items !== keep) {
            runs += MarkdownRun.Items(items.toList())
            items = mutableListOf()
        }
        if (quote.isNotEmpty() && quote !== keep) {
            runs += MarkdownRun.Quote(quote.toList())
            quote = mutableListOf()
        }
    }
    for (block in blocks) {
        val line =
            when {
                block is MarkdownBlock.Line -> block.text
                block is MarkdownBlock.Artifact && !artifactCards -> block.line
                else -> {
                    flush()
                    runs += MarkdownRun.Block(block)
                    continue
                }
            }
        val level = headingLevel(line)
        val quoted = quoteText(line)
        val item = listItem(line)
        when {
            line.trim() == "---" -> {
                flush()
                runs += MarkdownRun.Rule
            }
            level != null -> {
                flush()
                line.drop(level + 1).takeIf { it.isNotEmpty() }?.let { runs += MarkdownRun.Heading(level, it) }
            }
            quoted != null -> {
                flush(keep = quote)
                quote += quoted
            }
            item != null -> {
                flush(keep = items)
                items += item
            }
            line.isBlank() -> flush()
            else -> {
                flush(keep = paragraph)
                paragraph += line
            }
        }
    }
    flush()
    return runs
}
