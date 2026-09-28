package de.joinnoah.pi.remote

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/*
 * A small highlighter for the file browser, without dependencies. It knows a few languages
 * roughly: keywords, strings, comments and numbers; JSON keys; YAML keys, comments and anchors;
 * Markdown headings, fences, inline code and emphasis. It never parses: one pass over the lines,
 * carrying only the state a construct needs across lines (a block comment, a multi-line string,
 * a Markdown fence). Anything it does not recognise stays plain text.
 */

/** Lines longer than this stay plain; a minified bundle would cost a lot and gain nothing. */
internal const val MAX_HIGHLIGHT_LINE_CHARS = 1000

/** [tag] is the Markdown fence language of quoted text. */
enum class SyntaxLanguage(val tag: String) {
    KOTLIN("kotlin"),
    TYPESCRIPT("typescript"),
    JAVASCRIPT("javascript"),
    SWIFT("swift"),
    JSON("json"),
    YAML("yaml"),
    MARKDOWN("markdown"),
}

/** The language of [path] by its extension, or null for plain text. */
internal fun languageFor(path: String): SyntaxLanguage? =
    when (path.substringAfterLast('/').substringAfterLast('.', "").lowercase()) {
        "kt", "kts" -> SyntaxLanguage.KOTLIN
        "ts", "tsx", "mts", "cts" -> SyntaxLanguage.TYPESCRIPT
        "js", "jsx", "mjs", "cjs" -> SyntaxLanguage.JAVASCRIPT
        "swift" -> SyntaxLanguage.SWIFT
        "json" -> SyntaxLanguage.JSON
        "yml", "yaml" -> SyntaxLanguage.YAML
        "md", "markdown" -> SyntaxLanguage.MARKDOWN
        else -> null
    }

@Immutable
internal data class SyntaxColors(
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val key: Color,
    val heading: Color,
)

/** Each of [lines] as styled text, cut at [MAX_CODE_LINE_CHARS] for display. */
internal fun highlightLines(lines: List<String>, language: SyntaxLanguage?, colors: SyntaxColors): List<AnnotatedString> {
    val highlighter: LineHighlighter =
        when (language) {
            null -> return lines.map { AnnotatedString(it.take(MAX_CODE_LINE_CHARS)) }
            SyntaxLanguage.KOTLIN ->
                CLike(colors, KOTLIN_KEYWORDS, nestedComments = true, tripleQuotes = true, backticks = false, dollarTemplates = true)
            SyntaxLanguage.SWIFT ->
                CLike(colors, SWIFT_KEYWORDS, nestedComments = true, tripleQuotes = true, backticks = false, dollarTemplates = false)
            SyntaxLanguage.TYPESCRIPT, SyntaxLanguage.JAVASCRIPT ->
                CLike(colors, SCRIPT_KEYWORDS, nestedComments = false, tripleQuotes = false, backticks = true, dollarTemplates = false)
            SyntaxLanguage.JSON -> JsonLines(colors)
            SyntaxLanguage.YAML -> YamlLines(colors)
            SyntaxLanguage.MARKDOWN -> MarkdownLines(colors)
        }
    return lines.map { line ->
        if (line.length > MAX_HIGHLIGHT_LINE_CHARS) AnnotatedString(line.take(MAX_CODE_LINE_CHARS))
        else AnnotatedString.Builder(line).apply { highlighter.line(line, this) }.toAnnotatedString()
    }
}

private interface LineHighlighter {
    fun line(line: String, out: AnnotatedString.Builder)
}

private fun AnnotatedString.Builder.color(color: Color, start: Int, end: Int) {
    if (end > start) addStyle(SpanStyle(color = color), start, end)
}

private fun identifierStart(char: Char) = char.isLetter() || char == '_' || char == '$'

private fun identifierPart(char: Char) = char.isLetterOrDigit() || char == '_' || char == '$'

/** The end of a number starting at [start]: digits, letters (hex, suffixes), `_` and a decimal point. */
private fun numberEnd(line: String, start: Int): Int {
    var i = start + 1
    while (i < line.length) {
        val char = line[i]
        if (char.isLetterOrDigit() || char == '_' || (char == '.' && line.getOrNull(i + 1)?.isDigit() == true)) i++
        else break
    }
    return i
}

/** Kotlin, Swift, TypeScript and JavaScript. */
private class CLike(
    private val colors: SyntaxColors,
    private val keywords: Set<String>,
    private val nestedComments: Boolean,
    private val tripleQuotes: Boolean,
    private val backticks: Boolean,
    /** Kotlin: `${…}` in any double-quoted string. Template literals always interpolate. */
    private val dollarTemplates: Boolean,
) : LineHighlighter {
    /** Open block comments; above one only where comments nest. */
    private var commentDepth = 0

    /** The closing delimiter of a string that continues on the next line. */
    private var openString: String? = null

    override fun line(line: String, out: AnnotatedString.Builder) {
        var i = 0
        if (commentDepth > 0) i = comment(line, 0, out)
        openString?.let { delimiter -> if (i >= 0) i = string(line, i, delimiter, out) }
        if (i < 0) return
        while (i < line.length) {
            val char = line[i]
            when {
                line.startsWith("//", i) -> {
                    out.color(colors.comment, i, line.length)
                    return
                }
                line.startsWith("/*", i) -> {
                    commentDepth = 1
                    i = comment(line, i + 2, out, start = i)
                    if (i < 0) return
                }
                tripleQuotes && line.startsWith("\"\"\"", i) -> {
                    i = string(line, i + 3, "\"\"\"", out, start = i)
                    if (i < 0) return
                }
                char == '"' || char == '\'' || (backticks && char == '`') -> {
                    i = string(line, i + 1, char.toString(), out, start = i)
                    if (i < 0) return
                }
                char.isDigit() && (i == 0 || !identifierPart(line[i - 1])) -> {
                    val end = numberEnd(line, i)
                    out.color(colors.number, i, end)
                    i = end
                }
                identifierStart(char) -> {
                    var end = i + 1
                    while (end < line.length && identifierPart(line[end])) end++
                    if (line.substring(i, end) in keywords) out.color(colors.keyword, i, end)
                    i = end
                }
                else -> i++
            }
        }
    }

    /** Scans a block comment from [from]; the index after it, or -1 when it continues. */
    private fun comment(line: String, from: Int, out: AnnotatedString.Builder, start: Int = from): Int {
        var i = from
        while (i < line.length) {
            when {
                line.startsWith("*/", i) -> {
                    commentDepth--
                    i += 2
                    if (commentDepth == 0) {
                        out.color(colors.comment, start, i)
                        return i
                    }
                }
                nestedComments && line.startsWith("/*", i) -> {
                    commentDepth++
                    i += 2
                }
                else -> i++
            }
        }
        out.color(colors.comment, start, line.length)
        return -1
    }

    /**
     * Scans a string body from [from] up to [delimiter]; the index after it, or -1 when it
     * continues on the next line (only `"""` and template literals do). Interpolations, `${…}`
     * in Kotlin strings and template literals, are left unstyled.
     */
    private fun string(line: String, from: Int, delimiter: String, out: AnnotatedString.Builder, start: Int = from): Int {
        val multiline = delimiter == "\"\"\"" || delimiter == "`"
        val interpolates = delimiter == "`" || (dollarTemplates && delimiter.startsWith('"'))
        var segment = start
        var i = from
        while (i < line.length) {
            when {
                line[i] == '\\' && delimiter != "\"\"\"" -> i += 2
                line.startsWith(delimiter, i) -> {
                    i += delimiter.length
                    out.color(colors.string, segment, i)
                    openString = null
                    return i
                }
                interpolates && line.startsWith("\${", i) -> {
                    out.color(colors.string, segment, i)
                    var depth = 0
                    var end = i + 1
                    while (end < line.length) {
                        if (line[end] == '{') depth++
                        else if (line[end] == '}' && --depth == 0) break
                        end++
                    }
                    i = minOf(end + 1, line.length)
                    segment = i
                }
                else -> i++
            }
        }
        out.color(colors.string, segment, line.length)
        // A plain string ends with its line even when unterminated.
        openString = if (multiline) delimiter else null
        return if (multiline) -1 else line.length
    }
}

private class JsonLines(private val colors: SyntaxColors) : LineHighlighter {
    override fun line(line: String, out: AnnotatedString.Builder) {
        var i = 0
        while (i < line.length) {
            val char = line[i]
            when {
                char == '"' -> {
                    var end = i + 1
                    while (end < line.length && line[end] != '"') end += if (line[end] == '\\') 2 else 1
                    end = minOf(end + 1, line.length)
                    var next = end
                    while (next < line.length && line[next].isWhitespace()) next++
                    out.color(if (line.getOrNull(next) == ':') colors.key else colors.string, i, end)
                    i = end
                }
                char.isDigit() || (char == '-' && line.getOrNull(i + 1)?.isDigit() == true) -> {
                    val end = numberEnd(line, i)
                    out.color(colors.number, i, end)
                    i = end
                }
                char.isLetter() -> {
                    var end = i + 1
                    while (end < line.length && line[end].isLetter()) end++
                    if (line.substring(i, end) in setOf("true", "false", "null")) out.color(colors.keyword, i, end)
                    i = end
                }
                else -> i++
            }
        }
    }
}

private class YamlLines(private val colors: SyntaxColors) : LineHighlighter {
    override fun line(line: String, out: AnnotatedString.Builder) {
        var i = yamlKey.find(line)?.let { match ->
            val key = match.groups[2]!!.range
            out.color(colors.key, key.first, key.last + 1)
            key.last + 1
        } ?: 0
        while (i < line.length) {
            val char = line[i]
            val tokenStart = i == 0 || line[i - 1].isWhitespace()
            when {
                char == '#' && tokenStart -> {
                    out.color(colors.comment, i, line.length)
                    return
                }
                char == '"' || char == '\'' -> {
                    var end = i + 1
                    while (end < line.length && line[end] != char) end += if (char == '"' && line[end] == '\\') 2 else 1
                    end = minOf(end + 1, line.length)
                    out.color(colors.string, i, end)
                    i = end
                }
                (char == '&' || char == '*') && tokenStart -> {
                    var end = i + 1
                    while (end < line.length && !line[end].isWhitespace()) end++
                    out.color(colors.keyword, i, end)
                    i = end
                }
                else -> i++
            }
        }
    }

    private companion object {
        /** An optional list dash, then a plain or quoted key and its colon. */
        val yamlKey = Regex("""^(\s*(?:-\s+)*)([^\s#'"\-][^:#]*?|"[^"]*"|'[^']*')\s*:(?=\s|$)""")
    }
}

private class MarkdownLines(private val colors: SyntaxColors) : LineHighlighter {
    /** The fence that opened the code block we are in: its character and length. */
    private var fence: Pair<Char, Int>? = null

    override fun line(line: String, out: AnnotatedString.Builder) {
        val trimmed = line.trimStart()
        val marker = fenceMarker.find(trimmed)?.value
        val open = fence
        if (open != null) {
            out.color(colors.string, 0, line.length)
            if (marker != null && marker[0] == open.first && marker.length >= open.second &&
                trimmed.substring(marker.length).isBlank()
            ) fence = null
            return
        }
        if (marker != null) {
            fence = marker[0] to marker.length
            out.color(colors.string, 0, line.length)
            return
        }
        if (heading.containsMatchIn(line)) {
            out.addStyle(SpanStyle(color = colors.heading, fontWeight = FontWeight.Bold), 0, line.length)
            return
        }
        val code = inlineCode.findAll(line).map { it.range }.toList()
        code.forEach { out.color(colors.string, it.first, it.last + 1) }
        fun outsideCode(range: IntRange) = code.none { it.first <= range.last && range.first <= it.last }
        strong.findAll(line).filter { outsideCode(it.range) }.forEach {
            out.addStyle(SpanStyle(fontWeight = FontWeight.Bold), it.range.first, it.range.last + 1)
        }
        emphasis.findAll(line).filter { outsideCode(it.range) }.forEach {
            out.addStyle(SpanStyle(fontStyle = FontStyle.Italic), it.range.first, it.range.last + 1)
        }
    }

    private companion object {
        val fenceMarker = Regex("^(`{3,}|~{3,})")
        val heading = Regex("^ {0,3}#{1,6}(\\s|$)")
        val inlineCode = Regex("(`+)(?!`).+?(?<!`)\\1(?!`)")
        val strong = Regex("(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\1")
        val emphasis = Regex("(?<![*_\\w])([*_])(?=[^\\s*_])(.+?)(?<=[^\\s*_])\\1(?![*_\\w])")
    }
}

private val KOTLIN_KEYWORDS =
    setOf(
        "package", "import", "class", "interface", "object", "fun", "val", "var", "if", "else", "when", "for",
        "while", "do", "return", "break", "continue", "try", "catch", "finally", "throw", "true", "false", "null",
        "this", "super", "in", "is", "as", "typealias", "enum", "data", "sealed", "open", "override", "private",
        "public", "internal", "protected", "abstract", "final", "companion", "lateinit", "const", "suspend",
        "inline", "by", "init", "constructor", "where", "out", "vararg", "operator", "annotation", "reified",
        "crossinline", "noinline", "tailrec", "external", "value",
    )

private val SCRIPT_KEYWORDS =
    setOf(
        "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete", "do", "else",
        "export", "extends", "false", "finally", "for", "function", "if", "import", "in", "instanceof", "let",
        "new", "null", "return", "super", "switch", "this", "throw", "true", "try", "typeof", "undefined", "var",
        "void", "while", "with", "yield", "async", "await", "of", "from", "as", "interface", "type", "enum",
        "implements", "private", "protected", "public", "readonly", "static", "abstract", "declare", "namespace",
        "keyof", "never", "unknown", "any", "satisfies",
    )

private val SWIFT_KEYWORDS =
    setOf(
        "associatedtype", "class", "deinit", "enum", "extension", "fileprivate", "func", "import", "init", "inout",
        "internal", "let", "open", "operator", "private", "protocol", "public", "rethrows", "static", "struct",
        "subscript", "typealias", "var", "break", "case", "continue", "default", "defer", "do", "else",
        "fallthrough", "for", "guard", "if", "in", "repeat", "return", "switch", "where", "while", "as", "catch",
        "false", "is", "nil", "self", "Self", "super", "throw", "throws", "true", "try", "async", "await", "actor",
        "some", "any", "final", "override", "lazy", "weak", "mutating", "convenience", "required",
    )
