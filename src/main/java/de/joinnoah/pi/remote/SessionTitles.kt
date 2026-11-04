package de.joinnoah.pi.remote

/** The file a session's first prompt carried, as far as its title still tells. */
internal data class TitleAttachment(val name: String?, val image: Boolean)

/** [text] is what the user typed; [attachment] names what came with it, if the title says so. */
internal data class DisplayTitle(val text: String, val attachment: TitleAttachment? = null)

private const val ATTACHMENTS_OPEN = "[PocketPi attachments v1]"
private const val ATTACHMENTS_CLOSE = "[/PocketPi attachments]"
private const val QUOTE_OPEN = "[PocketPi quote v1]"
private const val QUOTE_CLOSE = "[/PocketPi quote]"
private val NAME_PATTERN = Regex("\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
private val IMAGE_PATTERN = Regex("\"kind\"\\s*:\\s*\"image\"")

/**
 * The title the host derived from a session's first prompt, without the markers the app wraps
 * around attachments and quoted messages ([AttachmentCodec], [QuoteCodec]). The host cuts the
 * title to a few characters, so a marker is often truncated and does not decode; those are read
 * leniently. Never throws: on any failure the raw text comes back.
 */
internal fun displayTitle(raw: String): DisplayTitle =
    runCatching {
            val strict = AttachmentCodec.decode(raw)
            var attachment: TitleAttachment? = null
            val rest: String
            if (strict.attachments.isNotEmpty()) {
                rest = strict.body
                strict.attachments.first().let { attachment = TitleAttachment(it.name, it.kind == "image") }
            } else if (raw.trimStart().startsWith(ATTACHMENTS_OPEN)) {
                val marker = raw.trimStart()
                val close = marker.indexOf(ATTACHMENTS_CLOSE)
                val manifest = if (close >= 0) marker.substring(0, close) else marker
                rest = if (close >= 0) marker.substring(close + ATTACHMENTS_CLOSE.length) else ""
                val name =
                    NAME_PATTERN.find(manifest)?.groupValues?.get(1)?.let {
                        runCatching { Wire.json.decodeFromString<String>("\"$it\"") }.getOrNull()
                    }
                attachment = TitleAttachment(name, IMAGE_PATTERN.containsMatchIn(manifest))
            } else {
                rest = raw
            }
            DisplayTitle(stripQuote(rest).trim(), attachment)
        }
        .getOrDefault(DisplayTitle(raw))

private fun stripQuote(text: String): String {
    val body = QuoteCodec.decode(text.trimStart()).body.trimStart()
    if (!body.startsWith(QUOTE_OPEN)) return body
    val close = body.indexOf(QUOTE_CLOSE)
    return if (close >= 0) body.substring(close + QUOTE_CLOSE.length) else ""
}
