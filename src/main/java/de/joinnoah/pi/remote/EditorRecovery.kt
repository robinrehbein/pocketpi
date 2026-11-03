package de.joinnoah.pi.remote

enum class EditorRecoveryKind { DRAFT, UNCERTAIN_ATTEMPT }

data class EditorRecoveryEntry(
    val key: PagedJournalKey,
    val kind: EditorRecoveryKind,
    val text: String,
    val savedAt: Long,
    val attemptId: String? = null,
    val hostName: String? = null,
) {
    val id: String
        get() = listOf(key.routeId, key.sessionId, key.questionId, kind.name)
            .joinToString("") { "${it.length}:$it" } +
            (attemptId?.let { "1${it.length}:$it" } ?: "0")
}

sealed interface EditorRecoveryLoad {
    data class Ready(val entries: List<EditorRecoveryEntry>) : EditorRecoveryLoad
    data object Unavailable : EditorRecoveryLoad
    data object Failed : EditorRecoveryLoad
}
