package de.joinnoah.pi.remote

internal enum class ResultKind { APPROVED, REVISION, NEUTRAL }
internal data class ResultCard(val timestamp: String?, val text: String, val kind: ResultKind)

// Only explicit log phrases are classified; arbitrary command output stays neutral.
// Each source line remains in exactly one card. Bounded groups keep long outputs lazy.
internal fun resultCards(output: String): List<ResultCard> {
    if (output.isEmpty()) return emptyList()
    val timestamp = Regex("^---\\s+(\\d{4}-\\d{2}-\\d{2}T\\S+).*")
    val groups = mutableListOf<MutableList<String>>()
    output.split(Regex("\\r\\n|\\n|\\r")).forEach { line ->
        if (groups.isEmpty() || timestamp.matches(line) || groups.last().size >= 24) groups.add(mutableListOf())
        groups.last().add(line)
    }
    return groups.map { lines ->
        val body = lines.firstOrNull { it.isNotBlank() && !timestamp.matches(it) }.orEmpty()
        val kind = when {
            body.startsWith("Plan approved:", ignoreCase = true) ||
                body.matches(Regex("(?i)plan approved for .+")) -> ResultKind.APPROVED
            body.startsWith("Revision required:", ignoreCase = true) ||
                body.startsWith("Revised authorization failure plan required", ignoreCase = true) -> ResultKind.REVISION
            else -> ResultKind.NEUTRAL
        }
        ResultCard(timestamp.matchEntire(lines.first())?.groupValues?.get(1), lines.joinToString("\n"), kind)
    }
}


internal const val RESULT_CARD_PREVIEW_CHARS = 4000

internal fun resultCardPreview(text: String): String {
    if (text.length <= RESULT_CARD_PREVIEW_CHARS) return text
    val end = if (text[RESULT_CARD_PREVIEW_CHARS - 1].isHighSurrogate()) RESULT_CARD_PREVIEW_CHARS - 1 else RESULT_CARD_PREVIEW_CHARS
    return text.take(end) + "…"
}
