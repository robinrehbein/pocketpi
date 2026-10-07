package de.joinnoah.pi.remote

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

internal fun inlineMarkdown(text: String, linkColor: Color): AnnotatedString =
    parseInline(text, linkColor, 0, true)

private val escapable = "\\`*_{}[]<>()#+-.!|"

internal fun unescapeMarkdown(text: String): String = buildString {
    var index = 0
    while (index < text.length) {
        if (text[index] == '\\' && text.getOrNull(index + 1)?.let { it in escapable } == true) index++
        append(text[index++])
    }
}

private fun delimiterRun(text: String, start: Int): String {
    var end = start
    while (end < text.length && text[end] == text[start]) end++
    return text.substring(start, end)
}

private fun closingDelimiter(text: String, start: Int, delimiter: String, depth: Int = 0): Int {
    if (depth >= 32) return -1
    var index = start
    while (index < text.length) {
        if (text[index] == '\\' && text.getOrNull(index + 1)?.let { it in escapable } == true) { index += 2; continue }
        if (text[index] == '`') {
            val run = delimiterRun(text, index)
            val end = codeEnd(text, index + run.length, run)
            if (end >= 0) { index = end + run.length; continue }
        }
        if (text[index] == '*') {
            val run = delimiterRun(text, index)
            if (run.length >= delimiter.length && index > start && !text[index - 1].isWhitespace()) return index
            if (run != delimiter && run.length <= 3 && text.getOrNull(index + run.length)?.isWhitespace() == false) {
                val end = closingDelimiter(text, index + run.length, run, depth + 1)
                if (end >= 0) { index = end + run.length; continue }
            }
            index += run.length
        } else index++
    }
    return -1
}

private fun codeEnd(text: String, start: Int, run: String): Int {
    var index = start
    while (index < text.length) {
        if (text[index] != '`') { index++; continue }
        val begin = index
        while (index < text.length && text[index] == '`') index++
        if (index - begin == run.length) return begin
    }
    return -1
}

private fun parseInline(text: String, color: Color, depth: Int, links: Boolean): AnnotatedString = buildAnnotatedString {
    if (depth >= 32) { append(text); return@buildAnnotatedString }
    fun link(label: AnnotatedString, url: String) {
        withLink(LinkAnnotation.Url(url)) {
            withStyle(SpanStyle(color = color, textDecoration = TextDecoration.Underline)) { append(label) }
        }
    }
    var index = 0
    while (index < text.length) {
        val char = text[index]
        if (char == '\\' && text.getOrNull(index + 1)?.let { it in escapable } == true) {
            append(text[index + 1]); index += 2; continue
        }
        if (char == '`') {
            val run = delimiterRun(text, index)
            val end = codeEnd(text, index + run.length, run)
            if (end >= 0) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(text.substring(index + run.length, end)) }
                index = end + run.length; continue
            }
            append(run); index += run.length; continue
        }
        if (char == '!' && text.getOrNull(index + 1) == '[') {
            // An image inside running text is never fetched: it reads as its description, or as a
            // link for an http(s) address.
            val image = scanMarkdownImage(text, index)
            if (image != null) {
                val target = markdownImageTarget(image.destination)
                val label =
                    parseInline(image.alt, color, depth + 1, false).takeIf { it.isNotEmpty() }
                        ?: (target as? MarkdownImageTarget.Local)?.path?.substringAfterLast('/')?.let(::AnnotatedString)
                        ?: AnnotatedString("")
                if (target is MarkdownImageTarget.Remote && links) link(label.takeIf { it.isNotEmpty() } ?: AnnotatedString(target.url), target.url)
                else append(label)
                index = image.end; continue
            }
        }
        if (char == '[' && links) {
            // Balance brackets in labels and parentheses in URLs instead of truncating destinations.
            var end = index + 1
            var brackets = 1
            while (end < text.length && brackets > 0) {
                when (text[end]) {
                    '\\' -> { end += 2; continue }
                    '[' -> brackets++
                    ']' -> brackets--
                }
                if (brackets > 0) end++
            }
            if (brackets == 0 && text.getOrNull(end + 1) == '(') {
                var close = end + 2
                var parentheses = 1
                while (close < text.length && parentheses > 0) {
                    when (text[close]) {
                        '\\' -> { close += 2; continue }
                        '(' -> parentheses++
                        ')' -> parentheses--
                    }
                    if (parentheses > 0) close++
                }
                if (parentheses == 0) {
                    val url = unescapeMarkdown(text.substring(end + 2, close))
                    if ((url.startsWith("https://") || url.startsWith("http://")) && url.none { it.isWhitespace() }) {
                        link(parseInline(text.substring(index + 1, end), color, depth + 1, false), url)
                        index = close + 1; continue
                    }
                }
            }
        }
        if (char == '*') {
            val runLength = delimiterRun(text, index).length
            val delimiter = "*".repeat(runLength.coerceAtMost(3))
            val end = closingDelimiter(text, index + delimiter.length, delimiter)
            if (end > index + delimiter.length && !text[index + delimiter.length].isWhitespace() && !text[end - 1].isWhitespace()) {
                val style = SpanStyle(
                    fontWeight = if (delimiter.length >= 2) FontWeight.Bold else null,
                    fontStyle = if (delimiter.length != 2) FontStyle.Italic else null,
                )
                withStyle(style) { append(parseInline(text.substring(index + delimiter.length, end), color, depth + 1, links)) }
                index = end + delimiter.length; continue
            }
        }
        if (links && (text.startsWith("https://", index) || text.startsWith("http://", index))) {
            var end = index
            var parentheses = 0
            while (end < text.length && !text[end].isWhitespace() && text[end] !in "<>`*[]") {
                if (text[end] == '(') parentheses++
                if (text[end] == ')') {
                    if (parentheses == 0) break
                    parentheses--
                }
                end++
            }
            val url = text.substring(index, end).trimEnd('.', ',', '!', '?', ';', ':')
            link(AnnotatedString(url), url)
            index += url.length; continue
        }
        append(char)
        index++
    }
}
