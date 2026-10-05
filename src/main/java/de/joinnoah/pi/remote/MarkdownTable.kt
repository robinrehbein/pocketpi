package de.joinnoah.pi.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

internal sealed interface MarkdownBlock {
    data class Line(val text: String) : MarkdownBlock
    data class Table(val header: List<String>, val alignment: List<TextAlign>, val rows: List<List<String>>) : MarkdownBlock
}

/** Split only structural pipes; matched backtick runs protect code span contents. */
internal fun tableCells(line: String): List<String>? {
    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    var index = 0
    var pipes = 0
    var leading = false
    var trailing = false
    while (index < line.length) {
        val char = line[index]
        when {
            char == '\\' && index + 1 < line.length -> {
                val next = line[index + 1]
                if (next == '|') cell.append(next) else cell.append(char).append(next)
                index += 2
                trailing = false
            }
            char == '`' -> {
                val start = index
                while (index < line.length && line[index] == '`') index++
                val run = line.substring(start, index)
                var end = index
                var close = -1
                while (end < line.length) {
                    if (line[end] != '`') { end++; continue }
                    val runStart = end
                    while (end < line.length && line[end] == '`') end++
                    if (end - runStart == run.length) { close = runStart; break }
                }
                if (close >= 0) {
                    cell.append(line.substring(start, close + run.length))
                    index = close + run.length
                } else cell.append(run)
                trailing = false
            }
            char == '|' -> {
                if (pipes == 0 && cell.isBlank()) leading = true
                cells.add(cell.toString().trim())
                cell.clear()
                pipes++
                trailing = true
                index++
            }
            else -> {
                cell.append(char)
                if (!char.isWhitespace()) trailing = false
                index++
            }
        }
    }
    if (pipes == 0) return null
    cells.add(cell.toString().trim())
    if (trailing) cells.removeAt(cells.lastIndex)
    if (leading) cells.removeAt(0)
    return cells.takeIf { it.isNotEmpty() }
}

/** Called only for non-fenced segments, leaving incomplete candidates as ordinary lines. */
internal fun markdownBlocks(text: String): List<MarkdownBlock> {
    val lines = text.lines()
    val blocks = mutableListOf<MarkdownBlock>()
    var index = 0
    while (index < lines.size) {
        val header = tableCells(lines[index])
        val delimiter = lines.getOrNull(index + 1)?.let(::tableCells)
        if (header != null && delimiter != null && header.size == delimiter.size &&
            delimiter.all { it.matches(Regex(":?-{3,}:?")) }
        ) {
            val alignment = delimiter.map {
                when {
                    it.startsWith(':') && it.endsWith(':') -> TextAlign.Center
                    it.endsWith(':') -> TextAlign.End
                    else -> TextAlign.Start
                }
            }
            index += 2
            val rows = mutableListOf<List<String>>()
            while (index < lines.size) {
                val row = tableCells(lines[index]) ?: break
                rows.add(row)
                index++
            }
            blocks.add(MarkdownBlock.Table(header, alignment, rows))
        } else {
            blocks.add(MarkdownBlock.Line(lines[index++]))
        }
    }
    return blocks
}

@Composable
internal fun MarkdownTable(table: MarkdownBlock.Table) {
    val columns = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Keep two columns readable on narrow phones; larger tables scroll as one grid.
        val width = (maxWidth / columns).coerceIn(144.dp, 240.dp)
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            (listOf(table.header) + table.rows).forEachIndexed { rowIndex, row ->
                Row(Modifier.height(IntrinsicSize.Min)) {
                    repeat(columns) { column ->
                        Text(
                            inlineMarkdown(row.getOrElse(column) { "" }, MaterialTheme.colorScheme.primary),
                            modifier = Modifier.width(width).fillMaxHeight()
                                .background(if (rowIndex == 0) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface)
                                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                                .padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (rowIndex == 0) FontWeight.SemiBold else FontWeight.Normal,
                            textAlign = table.alignment.getOrElse(column) { TextAlign.Start },
                        )
                    }
                }
            }
        }
    }
}
