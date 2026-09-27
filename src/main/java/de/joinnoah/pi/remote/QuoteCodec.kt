package de.joinnoah.pi.remote

import kotlinx.serialization.json.*

data class MessageQuote(
    val messageId: String,
    val role: String,
    val excerpt: String,
    val author: String? = null,
) {
    fun json(): JsonObject = buildJsonObject {
        put("messageId", messageId)
        put("role", role)
        author?.let { put("author", it) }
        put("excerpt", excerpt)
    }
}

data class QuotedPrompt(val body: String, val quote: MessageQuote? = null)

object QuoteCodec {
    private const val PREFIX = "[PocketPi quote v1]\n"
    private const val SEPARATOR = "\n[/PocketPi quote]\n\n"

    fun parse(value: JsonObject): MessageQuote? = runCatching {
        Wire.keys(value, setOf("messageId", "role", "excerpt"), setOf("author"))
        fun text(key: String, limit: Int): String =
            value.text(key).also { require(it.isNotEmpty() && it.toByteArray().size <= limit) }
        val id = text("messageId", 256)
        require(
            id.none {
                it.isWhitespace() ||
                    Character.isSpaceChar(it) ||
                    Character.isISOControl(it) ||
                    it == '\uFEFF'
            }
        )
        val role = text("role", 16)
        require(role in setOf("user", "assistant", "tool"))
        MessageQuote(
            id,
            role,
            text("excerpt", 2048),
            if ("author" in value) text("author", 256) else null,
        )
    }
        .getOrNull()

    fun encode(body: String, quote: MessageQuote? = null): String {
        val encoded =
            if (quote == null) body
            else {
                require(parse(quote.json()) == quote)
                PREFIX + quote.json().toString() + SEPARATOR + body
            }
        require(encoded.toByteArray().size <= 128 * 1024)
        return encoded
    }

    fun decode(text: String): QuotedPrompt {
        val literal = QuotedPrompt(text)
        if (!text.startsWith(PREFIX) || text.toByteArray().size > 128 * 1024) return literal
        val end = text.indexOf('\n', PREFIX.length)
        if (end < 0 || !text.startsWith(SEPARATOR, end)) return literal
        val line = text.substring(PREFIX.length, end)
        if ('\r' in line) return literal
        val value =
            runCatching { Wire.json.parseToJsonElement(line).jsonObject }.getOrNull()
                ?: return literal
        val quote = parse(value) ?: return literal
        return QuotedPrompt(text.substring(end + SEPARATOR.length), quote)
    }
}

internal fun boundedUtf8(text: String, limit: Int): String {
    var end = 0
    var bytes = 0
    while (end < text.length) {
        val codePoint = text.codePointAt(end)
        val next = end + Character.charCount(codePoint)
        val size = text.substring(end, next).toByteArray().size
        if (bytes + size > limit) break
        bytes += size
        end = next
    }
    return text.substring(0, end)
}
