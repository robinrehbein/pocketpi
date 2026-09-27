package de.joinnoah.pi.remote

/** The composer content an edit fork starts with; attachments are not carried over. */
internal data class ForkDraft(
    val text: String,
    val quote: MessageQuote?,
    val droppedAttachments: Boolean,
)

/** Splits the wire text of a forked user message into composer text and quote. */
internal fun forkDraft(text: String): ForkDraft {
    val attached = AttachmentCodec.decode(text)
    val quoted = QuoteCodec.decode(attached.body)
    return ForkDraft(quoted.body, quoted.quote, attached.attachments.isNotEmpty())
}

/** The error shown when the host refuses a fork with [code]. */
internal fun forkError(code: String?): Int =
    when (code) {
        "busy" -> R.string.remote_fork_busy
        else -> R.string.remote_request_error
    }

/**
 * IDs of the first bubble of each user message in [items]. A message split into several bubbles
 * (attachments, text parts) offers the rewind control once.
 */
internal fun forkableBubbleIds(items: List<ConversationItem>): Set<String> =
    items
        .filterIsInstance<ConversationItem.Bubble>()
        .filter { it.role == "user" }
        .distinctBy { it.sourceId }
        .mapTo(mutableSetOf()) { it.id }

/** The notice after a retry fork, by its `resend` outcome; none when the message was resent. */
internal fun forkResendNotice(resend: String?): Int? =
    when (resend) {
        "accepted" -> null
        "uncertain" -> R.string.remote_fork_resend_uncertain
        else -> R.string.remote_fork_resend_failed
    }

/** Whether the rewind control is offered in the chat at all. */
internal fun canFork(state: RemoteState): Boolean =
    SESSION_FORK_CAPABILITY in state.capabilities &&
        state.session != null &&
        state.session.optionalText("parentSessionId") == null

/** Whether the source is stopped, so it can be forked now. */
internal fun forkStopped(state: RemoteState): Boolean =
    state.status == "idle" ||
        state.status == "offline" ||
        state.session?.optionalText("origin") == "history"

/** Whether the source runs or waits; only then the control asks to stop it first. */
internal fun forkRunning(state: RemoteState): Boolean =
    state.status in setOf("running", "waiting") &&
        state.session?.optionalText("origin") != "history"

/** Rewind state of one user bubble. [ready] is false while the chat runs or loads. */
internal class MessageFork(
    val ready: Boolean,
    val stopFirst: Boolean,
    val onFork: (messageId: String, mode: ForkMode) -> Unit,
)
