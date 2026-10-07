package de.joinnoah.pi.remote

/** Where a Markdown image points. */
internal sealed interface MarkdownImageTarget {
    /** A file of the session folder: [path] is relative and normalised, or POSIX absolute. */
    data class Local(val path: String) : MarkdownImageTarget

    /** An http(s) address; the phone never fetches it, the text becomes a link. */
    data class Remote(val url: String) : MarkdownImageTarget

    /** Anything else: another scheme, a query, a path that climbs out, an unsupported extension. */
    data object Rejected : MarkdownImageTarget
}

/** One `![alt](destination)`; [end] is the index after the closing parenthesis. */
internal data class MarkdownImageToken(val alt: String, val destination: String, val end: Int)

private val imageExtensions = setOf("png", "jpg", "jpeg", "webp", "gif", "svg")
/** A scan never looks further than this past the `!`, so a long text of unclosed `![` stays linear. */
private const val MAX_IMAGE_SCAN = 2048
private val trailingImageExtension = Regex("\\.(png|jpe?g|webp|gif|svg)$", RegexOption.IGNORE_CASE)
private val urlScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

/**
 * Scans the image that starts at [start] (the `!`), balancing brackets in the label and
 * parentheses in the destination. Returns null when no complete image starts there.
 */
internal fun scanMarkdownImage(text: String, start: Int): MarkdownImageToken? {
    if (text.getOrNull(start) != '!' || text.getOrNull(start + 1) != '[') return null
    val limit = minOf(text.length, start + MAX_IMAGE_SCAN)
    var end = start + 2
    var brackets = 1
    while (end < limit && brackets > 0) {
        when (text[end]) {
            '\\' -> { end += 2; continue }
            '[' -> brackets++
            ']' -> brackets--
        }
        if (brackets > 0) end++
    }
    if (brackets != 0 || text.getOrNull(end + 1) != '(') return null
    var close = end + 2
    var parentheses = 1
    var angle = false
    while (close < limit && parentheses > 0) {
        when (text[close]) {
            '\\' -> { close += 2; continue }
            '<' -> if (close == end + 2) angle = true
            '>' -> angle = false
            '(' -> if (!angle) parentheses++
            ')' -> if (!angle) parentheses--
        }
        if (parentheses > 0) close++
    }
    if (parentheses != 0 || close >= limit) return null
    return MarkdownImageToken(text.substring(start + 2, end), text.substring(end + 2, close), close + 1)
}

/** The destination of an image token, without an optional title. Null when it is malformed. */
private fun destinationWithoutTitle(raw: String): String? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    val destination: String
    val rest: String
    if (value.startsWith('<')) {
        val close = value.indexOf('>')
        if (close < 0) return null
        destination = value.substring(1, close)
        rest = value.substring(close + 1)
    } else if (trailingImageExtension.containsMatchIn(value)) {
        // Spaces without angle brackets: a title always ends in a quote or parenthesis, so a
        // value that ends in an image extension is one destination.
        destination = value
        rest = ""
    } else {
        val space = value.indexOfFirst { it.isWhitespace() }
        destination = if (space < 0) value else value.substring(0, space)
        rest = if (space < 0) "" else value.substring(space)
    }
    val title = rest.trim()
    val validTitle =
        title.isEmpty() ||
            (title.length >= 2 &&
                ((title.first() == '"' && title.last() == '"') ||
                    (title.first() == '\'' && title.last() == '\'') ||
                    (title.first() == '(' && title.last() == ')')))
    return destination.takeIf { validTitle && it.isNotEmpty() }
}

/** Decodes `%XX` escapes once as UTF-8; null for a malformed or non-UTF-8 sequence. */
private fun percentDecodeOnce(value: String): String? {
    if ('%' !in value) return value
    val bytes = java.io.ByteArrayOutputStream()
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char == '%') {
            val hex = value.substring(index + 1, minOf(index + 3, value.length))
            if (hex.length != 2 || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
            bytes.write(hex.toInt(16))
            index += 3
        } else {
            val codePoint = value.codePointAt(index)
            bytes.write(String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8))
            index += Character.charCount(codePoint)
        }
    }
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray()))
            .toString()
    } catch (e: java.nio.charset.CharacterCodingException) {
        null
    }
}

/**
 * Classifies the destination of an image token. A local path is percent-decoded once, then
 * normalised lexically (`./` dropped, `..` resolved); a relative path that climbs above the
 * session folder is rejected, as is any query, fragment, backslash, control character, empty
 * segment or extension other than png, jpg, jpeg, webp, gif and svg. The host decides whether the
 * file is inside the folder, so an absolute path is sent as it is.
 */
internal fun markdownImageTarget(raw: String): MarkdownImageTarget {
    val destination = destinationWithoutTitle(unescapeMarkdown(raw)) ?: return MarkdownImageTarget.Rejected
    if (urlScheme.containsMatchIn(destination)) {
        val lower = destination.lowercase()
        return if ((lower.startsWith("https://") || lower.startsWith("http://")) && destination.none { it.isWhitespace() })
            MarkdownImageTarget.Remote(destination)
        else MarkdownImageTarget.Rejected
    }
    if (destination.startsWith("//") || '?' in destination || '#' in destination || '\\' in destination)
        return MarkdownImageTarget.Rejected
    val decoded = percentDecodeOnce(destination) ?: return MarkdownImageTarget.Rejected
    if (decoded.any { it.code < 0x20 || it.code == 0x7f || it == '\\' }) return MarkdownImageTarget.Rejected
    val absolute = decoded.startsWith("/")
    val segments = mutableListOf<String>()
    for (segment in decoded.removePrefix("/").split('/')) {
        when (segment) {
            "" -> return MarkdownImageTarget.Rejected
            "." -> Unit
            ".." -> if (segments.isEmpty()) return MarkdownImageTarget.Rejected else segments.removeAt(segments.lastIndex)
            else -> segments += segment
        }
    }
    if (segments.isEmpty()) return MarkdownImageTarget.Rejected
    val extension = segments.last().substringAfterLast('.', "").lowercase()
    if (extension !in imageExtensions) return MarkdownImageTarget.Rejected
    val path = (if (absolute) "/" else "") + segments.joinToString("/")
    return if (validMediaPath(path)) MarkdownImageTarget.Local(path) else MarkdownImageTarget.Rejected
}

/**
 * The images of a line made of nothing but images separated by blanks, or null for any other
 * line or when one of them does not point at a local file. Indented four spaces or more is code.
 */
internal fun imageOnlyLine(line: String): List<Pair<String, String>>? {
    if (line.takeWhile { it == ' ' }.length > 3 || '\t' in line.takeWhile { it.isWhitespace() }) return null
    var index = line.indexOfFirst { !it.isWhitespace() }
    if (index < 0) return null
    val images = mutableListOf<Pair<String, String>>()
    while (index < line.length) {
        val token = scanMarkdownImage(line, index) ?: return null
        val target = markdownImageTarget(token.destination) as? MarkdownImageTarget.Local ?: return null
        images += token.alt to target.path
        index = token.end
        while (index < line.length && line[index].isWhitespace()) index++
    }
    return images.takeIf { it.isNotEmpty() }
}
