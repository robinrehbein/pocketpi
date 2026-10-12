package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonObject

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

/** What a host `fork` result carries beyond the new session; [mode] is its wire mode. */
internal class ForkResult(
    val session: JsonObject,
    val mode: String,
    val text: String?,
    val resend: String?,
)

private val FORK_RESENDS = setOf("accepted", "failed", "uncertain")

/**
 * Validates a `fork` result for [sourceId] in [projectId]. [modes] are the wire modes the request
 * may produce: `edit` carries `text`, `retry` carries `resend`, and `at` (a fork from a tree node)
 * carries nothing more. Throws [IllegalArgumentException] for anything else.
 */
internal fun validatedFork(
    data: JsonObject,
    sourceId: String,
    projectId: String,
    modes: Set<String>,
): ForkResult {
    val mode = data.text("mode")
    Wire.keys(
        data,
        setOf(
            "kind",
            "session",
            "sourceSessionId",
            "mode",
            *when (mode) {
                "edit" -> arrayOf("text")
                "retry" -> arrayOf("resend")
                else -> emptyArray()
            },
        ),
    )
    require(data.text("kind") == "fork" && data.text("sourceSessionId") == sourceId)
    require(mode in modes)
    val session = data.obj("session")
    require(session.text("projectId") == projectId)
    val text = if (mode == "edit") data.text("text").also { require(it.encodeToByteArray().size <= 128 * 1024) } else null
    val resend = if (mode == "retry") data.text("resend").also { require(it in FORK_RESENDS) } else null
    return ForkResult(session, mode, text, resend)
}
