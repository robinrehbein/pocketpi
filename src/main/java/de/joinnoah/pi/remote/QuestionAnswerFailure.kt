package de.joinnoah.pi.remote

/** Why the card for a question keeps its controls disabled although the question is still open. */
enum class QuestionBlock {
    /** The host cannot take this dialog's answer from the phone; answer it on the Mac. */
    LOCAL_ONLY,

    /** The owner did not confirm in time; the answer may have been applied. */
    UNCERTAIN,
}

/** What a failed `question.answer` means for the card and the message under it. */
internal enum class QuestionAnswerFailure {
    /** Closed elsewhere (another view, a timer or the terminal). */
    ANSWERED_ELSEWHERE,
    UNSUPPORTED,
    UNCERTAIN,
    OFFLINE,
    OTHER,
}

/** The host's peer-timeout message starts with this; the code alone (`internal`) is too broad. */
private const val OUTCOME_UNKNOWN = "Command outcome unknown"

internal fun questionAnswerFailure(error: Throwable): QuestionAnswerFailure {
    val request = error as? RemoteRequestException ?: return QuestionAnswerFailure.OTHER
    return when {
        request.code == "already_resolved" || request.code == "not_found" ->
            QuestionAnswerFailure.ANSWERED_ELSEWHERE
        request.code == "unsupported" -> QuestionAnswerFailure.UNSUPPORTED
        request.code == "uncertain" ||
            (request.code == "internal" &&
                (request.reason == "outcome_unknown" ||
                    // Compatibility with hosts predating structured outcome reasons.
                    (request.reason == null && request.hostMessage?.startsWith(OUTCOME_UNKNOWN) == true))) ->
            QuestionAnswerFailure.UNCERTAIN
        request.code == "offline" -> QuestionAnswerFailure.OFFLINE
        else -> QuestionAnswerFailure.OTHER
    }
}
