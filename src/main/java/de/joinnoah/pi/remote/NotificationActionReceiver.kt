package de.joinnoah.pi.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Answers a question from its notification. `goAsync` keeps the process alive while a short-lived
 * authenticated connection sends `question.answer`; the whole exchange is bounded well below the
 * broadcast deadline. The notification shows the result: sent, already answered, or failed with an
 * "Open" action.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
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
                    } catch (_: Exception) {
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
            } catch (_: Exception) {
                // Never crash the process after the answer may already be sent; the notification
                // keeps its last state and the session shows the truth.
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun answer(
        context: Context,
        routeId: String,
        sessionId: String,
        questionId: String,
        answer: JsonObject,
    ): AnswerOutcome {
        val host =
            try {
                PairingStore(context).load().find { it.routeId == routeId }
            } catch (_: Exception) {
                null
            } ?: return AnswerOutcome.FAILED
        var error: Throwable? = null
        val finished =
            withTimeoutOrNull(ANSWER_TIMEOUT_MILLIS) {
                try {
                    BackgroundRemoteClient().use(host) {
                        it.answerQuestion(sessionId, questionId, answer)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e
                }
                true
            }
        return if (finished == null) AnswerOutcome.FAILED else answerOutcome(error)
    }

    private companion object {
        // Broadcast receivers get about ten seconds; leave room to post the result.
        const val ANSWER_TIMEOUT_MILLIS = 8_500L
        val scope =
            CoroutineScope(
                SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> }
            )
    }
}
