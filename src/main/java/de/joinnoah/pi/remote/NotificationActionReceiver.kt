package de.joinnoah.pi.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Answers a question from its notification. `goAsync` keeps the process alive while
 * `question.answer` goes out over the app's own connection when it is healthy, or else over a
 * short-lived authenticated one; the whole exchange, loading the pairing included, is bounded well
 * below the broadcast deadline. The notification shows the result: sent, already answered, or
 * failed with an "Open" action.
 *
 * It also sends a reply typed into a completion notification ([CompletionReplier]), and drops a
 * Mac's group summary once the user dismissed the group's last notification.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            RemoteNotifications.ACTION_REPLY -> return reply(context, intent)
            RemoteNotifications.ACTION_DISMISSED -> return dismissed(context, intent)
        }
        if (intent.action != RemoteNotifications.ACTION_ANSWER) return
        val routeId = intent.getStringExtra(RemoteNotifications.EXTRA_ROUTE)
        val sessionId = intent.getStringExtra(RemoteNotifications.EXTRA_TARGET)
        val questionId = intent.getStringExtra(RemoteNotifications.EXTRA_QUESTION)
        if (!isOpaqueId(routeId) || !isOpaqueId(sessionId) || questionId.isNullOrBlank()) return
        val reply = intent.getBooleanExtra(RemoteNotifications.EXTRA_IS_REPLY, false)
        val typed =
            if (reply)
                RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(RemoteNotifications.REPLY_KEY)
                    ?.toString()
            else null
        val answer: JsonObject? =
            if (reply) inputAnswer(typed)
            else
                intent.getStringExtra(RemoteNotifications.EXTRA_ANSWER)?.let {
                    try {
                        Wire.parse(it, 64 * 1024).jsonObject
                    } catch (swallowed: Exception) {
                        Diagnostics.caught("notificationAction.onReceive", swallowed)
                        null
                    }
                }
        val app = context.applicationContext
        if (answer == null) {
            RemoteNotifications.postEmptyReply(app, routeId!!, sessionId!!)
            return
        }
        RemoteNotifications.postSending(app, routeId!!, sessionId!!)
        val pending = goAsync()
        scope.launch {
            try {
                val outcome = answer(app, routeId, sessionId, questionId, answer)
                RemoteNotifications.postOutcome(
                    app,
                    routeId,
                    sessionId,
                    outcome,
                    if (outcome == AnswerOutcome.FAILED && typed != null)
                        FailedReply(questionId, typed)
                    else null,
                )
                if (outcome != AnswerOutcome.FAILED) {
                    (app as? RemoteApplication)?.onQuestionAnsweredInBackground(
                        routeId,
                        sessionId,
                        answer,
                    )
                }
            } catch (swallowed: Exception) {
                Diagnostics.caught("notificationAction.onReceive.2", swallowed)
                // Never crash the process after the answer may already be sent; the notification
                // keeps its last state and the session shows the truth.
            } finally {
                pending.finish()
            }
        }
    }

    private fun dismissed(context: Context, intent: Intent) {
        val routeId = intent.getStringExtra(RemoteNotifications.EXTRA_ROUTE)
        val sessionId = intent.getStringExtra(RemoteNotifications.EXTRA_TARGET)
        val id = intent.getIntExtra(RemoteNotifications.EXTRA_NOTIFICATION_ID, -1)
        if (!isOpaqueId(routeId) || !isOpaqueId(sessionId) || id < 0) return
        RemoteNotifications.onDismissed(context.applicationContext, routeId!!, sessionId!!, id)
    }

    /**
     * Sends a reply to a completion as the session's next prompt. The notification shows the reply
     * at once while it is being sent, then whether it was sent, failed, or may not have arrived.
     */
    private fun reply(context: Context, intent: Intent) {
        val routeId = intent.getStringExtra(RemoteNotifications.EXTRA_ROUTE)
        val sessionId = intent.getStringExtra(RemoteNotifications.EXTRA_TARGET)
        if (!isOpaqueId(routeId) || !isOpaqueId(sessionId)) return
        val app = context.applicationContext
        val text =
            replyText(
                RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(RemoteNotifications.REPLY_KEY)
            )
        if (text == null) {
            RemoteNotifications.postEmptyCompletionReply(app, routeId!!, sessionId!!)
            return
        }
        // A late name lookup would put the plain completion, with its Reply, back over the reply.
        CompletionUpgrades.cancelCompletion(app, routeId!!, sessionId!!)
        // Read before the reply replaces it, so every later state still shows what it answers.
        val conversation = RemoteNotifications.conversation(app, routeId, sessionId)
        RemoteNotifications.postReply(app, routeId, sessionId, conversation, text, sending = true)
        val pending = goAsync()
        scope.launch {
            var posted = false
            try {
                val outcome = replier(app).send(routeId, sessionId, text)
                RemoteNotifications.postReplyOutcome(app, routeId, sessionId, outcome, conversation, text)
                posted = true
                if (outcome == ReplyOutcome.SENT)
                    (app as? RemoteApplication)?.onRepliedInBackground(routeId, sessionId)
            } catch (swallowed: Exception) {
                Diagnostics.caught("notificationAction.reply", swallowed)
                // Never crash after the reply may already be sent, and never leave "Sending…"
                // behind: without a known outcome the reply may or may not have arrived.
                if (!posted) {
                    try {
                        RemoteNotifications.postReplyOutcome(
                            app,
                            routeId,
                            sessionId,
                            ReplyOutcome.UNCERTAIN,
                            conversation,
                            text,
                        )
                    } catch (alsoSwallowed: Exception) {
                        Diagnostics.caught("notificationAction.reply.outcome", alsoSwallowed)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun replier(app: Context) =
        CompletionReplier(
            pairedHost = { pairedHost(app, it) },
            live = { routeId, block ->
                (app as? RemoteApplication)?.repository?.withHealthyConnection(routeId, block = block)
            },
            background = { host, block -> BackgroundRemoteClient().use(host, block) },
        )

    private suspend fun answer(
        context: Context,
        routeId: String,
        sessionId: String,
        questionId: String,
        answer: JsonObject,
    ): AnswerOutcome =
        NotificationAnswerer(
            pairedHost = { pairedHost(context, it) },
            live = { route, onSent, block ->
                (context as? RemoteApplication)?.repository?.withHealthyConnection(route, onSent, block)
            },
        ).answer(routeId, sessionId, questionId, answer)

    private companion object {
        val scope =
            CoroutineScope(
                SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> }
            )

        /**
         * The pairing of [routeId], or null when it is gone or unreadable. Every action of this
         * receiver resolves its host here, inside its own timeout: the load runs apart and is only
         * awaited, so a Keystore that hangs cannot hold the broadcast past its deadline.
         */
        suspend fun pairedHost(context: Context, routeId: String): PairedHost? =
            awaitApart(scope) {
                try {
                    PairingStore(context).load().find { it.routeId == routeId }
                        ?.also(RemoteNotifications::rememberHost)
                } catch (swallowed: Exception) {
                    Diagnostics.caught("notificationAction.pairedHost", swallowed)
                    null
                }
            }
    }
}

/**
 * Runs the blocking [load] on [scope] and only awaits it, so the caller's timeout still ends the
 * wait while [load] blocks its own thread.
 */
internal suspend fun <T> awaitApart(scope: CoroutineScope, load: () -> T): T = scope.async { load() }.await()

// Broadcast receivers get about ten seconds; leave room to post the result.
internal const val ANSWER_TIMEOUT_MILLIS = 8_500L

/**
 * Sends a notification's answer within [timeoutMillis], pairing load included: over the app's own
 * connection when it is healthy ([live] returns null otherwise, or once its short timeout passed),
 * else over a short-lived connection of its own. A host error maps to the outcome the notification
 * shows. An answer the app's connection may have sent before its timeout is sent once more on the
 * fallback; the host's "already answered" then most likely means this answer, so it counts as sent.
 */
internal class NotificationAnswerer(
    private val timeoutMillis: Long = ANSWER_TIMEOUT_MILLIS,
    private val pairedHost: suspend (String) -> PairedHost?,
    private val live: suspend (String, () -> Unit, suspend (RemoteCommands) -> Unit) -> Unit?,
    private val background: suspend (PairedHost, suspend (RemoteCommands) -> Unit) -> Unit = { host, block ->
        BackgroundRemoteClient().use(host, block)
    },
) {
    suspend fun answer(routeId: String, sessionId: String, questionId: String, answer: JsonObject): AnswerOutcome {
        var error: Throwable? = null
        val finished =
            withTimeoutOrNull(timeoutMillis) {
                val host = pairedHost(routeId) ?: return@withTimeoutOrNull false
                try {
                    val send: suspend (RemoteCommands) -> Unit = { it.answerQuestion(sessionId, questionId, answer) }
                    // Set from the app's connection, on its own thread.
                    val sentLive = AtomicBoolean(false)
                    if (live(routeId, { sentLive.set(true) }, send) == null) {
                        try {
                            background(host, send)
                        } catch (e: RemoteCommandException) {
                            // The app's connection timed out or dropped after the answer went out.
                            if (!sentLive.get() || e.code != "already_resolved") throw e
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Diagnostics.caught("notificationAction.answer", e)
                    error = e
                }
                true
            }
        return if (finished != true) AnswerOutcome.FAILED else answerOutcome(error)
    }
}
