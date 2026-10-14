package de.joinnoah.pi.remote

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * [text] styled, with its links. With [files], a `path:line` the agent wrote (in code or prose)
 * links to the file in Files too, if it has a known source extension or a tool used that path.
 * Inline code gets [codeBackground] behind it when one is given.
 */
internal fun inlineMarkdown(
    text: String,
    linkColor: Color,
    files: FileOpener? = null,
    codeBackground: Color = Color.Unspecified,
): AnnotatedString = parseInline(text, linkColor, 0, true, files, codeBackground)

/** Characters that end a `path:line` in prose; the reference itself never holds them. */
private const val FILE_REFERENCE_END = "`*[]<>()\"',;"

/** Where a `path:line` may start in prose: after a space, an opening bracket or a quote. */
private fun fileReferenceBoundary(text: String, index: Int): Boolean =
    index == 0 || text[index - 1].isWhitespace() || text[index - 1] in "([{\"'"

/** The `path:line` at [index] of [text] in prose, as its text and what it names. */
private fun proseFileReference(text: String, index: Int, files: FileOpener): Pair<String, FileReference>? {
    if (!fileReferenceBoundary(text, index)) return null
    val first = text[index]
    if (!first.isLetterOrDigit() && first !in "._~/@") return null
    var end = index
    while (end < text.length && !text[end].isWhitespace() && text[end] !in FILE_REFERENCE_END) end++
    val candidate = text.substring(index, end).trimEnd('.', '!', '?', ':')
    if (':' !in candidate) return null
    return fileReference(candidate, files::knows)?.let { candidate to it }
}

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

private fun parseInline(
    text: String,
    color: Color,
    depth: Int,
    links: Boolean,
    files: FileOpener? = null,
    codeBackground: Color = Color.Unspecified,
): AnnotatedString = buildAnnotatedString {
    if (depth >= 32) { append(text); return@buildAnnotatedString }
    fun link(label: AnnotatedString, url: String) {
        withLink(LinkAnnotation.Url(url)) {
            withStyle(SpanStyle(color = color, textDecoration = TextDecoration.Underline)) { append(label) }
        }
    }
    val fileLinks = files?.takeIf { links }
    fun fileLink(label: String, reference: FileReference, code: Boolean) {
        val opener = checkNotNull(fileLinks)
        val target = LinkAnnotation.Clickable("file:${reference.path}:${reference.lines.first}") {
            opener.perform(opener.action(reference.path, reference.lines))
        }
        withLink(target) {
            withStyle(
                SpanStyle(
                    color = color,
                    textDecoration = TextDecoration.Underline,
                    fontFamily = if (code) FontFamily.Monospace else null,
                    // Inline code keeps its background when it is also a file link.
                    background = if (code) codeBackground else Color.Unspecified,
                )
            ) { append(label) }
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
                val code = text.substring(index + run.length, end)
                val reference = fileLinks?.let { opener -> fileReference(code.trim(), opener::knows) }
                if (reference != null) fileLink(code, reference, code = true)
                else withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(code) }
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
                    parseInline(image.alt, color, depth + 1, false, null, codeBackground).takeIf { it.isNotEmpty() }
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
                        link(parseInline(text.substring(index + 1, end), color, depth + 1, false, null, codeBackground), url)
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
                withStyle(style) { append(parseInline(text.substring(index + delimiter.length, end), color, depth + 1, links, files, codeBackground)) }
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
        if (fileLinks != null) {
            val found = proseFileReference(text, index, fileLinks)
            if (found != null) {
                fileLink(found.first, found.second, code = false)
                index += found.first.length; continue
            }
        }
        append(char)
        index++
    }
}

/** The URL of a link that covers the character at [offset] of [text], if any. */
internal fun linkAt(text: AnnotatedString, offset: Int): String? =
    if (offset !in text.indices) null
    else text.getLinkAnnotations(offset, offset + 1).firstNotNullOfOrNull { (it.item as? LinkAnnotation.Url)?.url }

/**
 * [text] as the chat shows it, for "Copy": inline marks dropped, links as their labels, headings
 * without their #, bullets as •, fenced code as the code alone, table rows as tab-separated
 * cells, images as their description and rules (---) left out.
 */
internal fun markdownPlainText(text: String): String =
    markdownSegments(text).joinToString("\n") { segment ->
        if (segment.code) segment.text
        else
            markdownBlocks(segment.text).mapNotNull { block ->
                when (block) {
                    is MarkdownBlock.Table ->
                        (listOf(block.header) + block.rows).joinToString("\n") { row ->
                            row.joinToString("\t") { parseInline(it, Color.Unspecified, 0, true).text }
                        }
                    is MarkdownBlock.Image -> markdownImageAltText(block)
                    is MarkdownBlock.Artifact -> plainLine(block.line)
                    is MarkdownBlock.Line -> if (block.text.trim() == "---") null else plainLine(block.text)
                }
            }.joinToString("\n")
    }

private fun plainLine(line: String): String {
    headingLevel(line)?.let { return parseInline(line.drop(it + 1), Color.Unspecified, 0, true).text }
    listItem(line)?.let { item ->
        return "  ".repeat(item.depth) + item.marker + " " + parseInline(item.text, Color.Unspecified, 0, true).text
    }
    val content = quoteText(line) ?: line
    return parseInline(content, Color.Unspecified, 0, true).text
}
