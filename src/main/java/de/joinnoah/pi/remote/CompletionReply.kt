package de.joinnoah.pi.remote

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

// A reply typed into a completion notification: a new prompt for the idle session, sent from the
// background without opening the app. It follows the app's uncertain-send rules. Once the prompt
// may have left the phone it is never sent again on its own: a reply whose acknowledgement does not
// arrive says that it may not have been delivered, and only a reply the host refused, or one that
// never left, counts as failed.

/** The whole reply is bounded like an answer from a notification, below the broadcast deadline. */
internal const val REPLY_TIMEOUT_MILLIS = 8_500L

/** The longest reply a notification sends, like an inline answer. */
private const val MAX_REPLY_BYTES = 32 * 1024

internal enum class ReplyOutcome {
    SENT,

    /** The host refused the prompt, or it never left the phone: nothing reached pi. */
    FAILED,

    /** The prompt may have reached the host, but no acknowledgement arrived. */
    UNCERTAIN,
}

/** The prompt text of an inline reply, or null when it is blank or too long. */
internal fun replyText(text: CharSequence?): String? {
    val value = text?.toString()?.trim().orEmpty()
    if (value.isEmpty() || value.toByteArray().size > MAX_REPLY_BYTES) return null
    return value
}

/**
 * Sends a reply under one mutation ID. The app's own connection is tried first when it is healthy
 * and connected to the reply's Mac ([live] returns null otherwise, without sending); only a reply
 * that never left over it goes on over a short-lived [background] connection, under the same ID.
 * A live attempt that may have reached the host is not sent again: that would rely on the host
 * deduplicating a mutation ID across two sockets of one device, which is not confirmed yet.
 */
internal class CompletionReplier(
    private val pairedHost: suspend (String) -> PairedHost?,
    private val live: suspend (String, suspend (MutationCommands) -> Unit) -> Unit?,
    private val background: suspend (PairedHost, suspend (MutationCommands) -> Unit) -> Unit,
    private val mutationId: () -> String = Wire::random,
    private val timeoutMillis: Long = REPLY_TIMEOUT_MILLIS,
) {
    private sealed interface Live {
        data object Skipped : Live

        data object Sent : Live

        class Failed(val error: Exception) : Live
    }

    suspend fun send(routeId: String, sessionId: String, text: String): ReplyOutcome {
        val encoded =
            try {
                QuoteCodec.encode(text)
            } catch (_: IllegalArgumentException) {
                return ReplyOutcome.FAILED
            }
        val id = mutationId()
        val sent = AtomicBoolean(false)
        val prompt: suspend (MutationCommands) -> Unit = { commands ->
            // Set before sending: a send that throws may still have reached the host.
            sent.set(true)
            commands.prompt(sessionId, encoded, id)
        }
        return withTimeoutOrNull(timeoutMillis) { attempt(routeId, prompt, sent) }
            ?: if (sent.get()) ReplyOutcome.UNCERTAIN else ReplyOutcome.FAILED
    }

    private suspend fun attempt(
        routeId: String,
        prompt: suspend (MutationCommands) -> Unit,
        sent: AtomicBoolean,
    ): ReplyOutcome {
        val viaLive =
            try {
                if (live(routeId, prompt) == null) Live.Skipped else Live.Sent
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diagnostics.caught("reply.live", e)
                Live.Failed(e)
            }
        when (viaLive) {
            Live.Sent -> return ReplyOutcome.SENT
            is Live.Failed -> if (sent.get()) return outcome(viaLive.error)
            Live.Skipped -> Unit
        }
        val host = pairedHost(routeId) ?: return ReplyOutcome.FAILED
        return try {
            background(host, prompt)
            ReplyOutcome.SENT
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diagnostics.caught("reply.background", e)
            if (sent.get()) outcome(e) else ReplyOutcome.FAILED
        }
    }

    /** A reply that may have left: refused when the host answered with an error, else uncertain. */
    private fun outcome(error: Exception): ReplyOutcome =
        if (error is RemoteCommandException || (error as? RemoteRequestException)?.answered == true)
            ReplyOutcome.FAILED
        else ReplyOutcome.UNCERTAIN
}
