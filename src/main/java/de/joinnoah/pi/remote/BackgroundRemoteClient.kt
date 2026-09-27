package de.joinnoah.pi.remote

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient

// One client for all background connections, so each notification doesn't start new thread pools.
private val backgroundHttp by lazy {
    OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
}

/** A host rejected a command with this protocol error code (for example `already_resolved`). */
internal class RemoteCommandException(val code: String) : Exception(code)

internal class RemoteConnectionException : Exception("Connection failed")

internal fun interface RemoteCommands {
    suspend fun request(type: String, vararg fields: Pair<String, Any?>): JsonObject
}

/**
 * One short-lived, authenticated connection for work outside the UI, such as reading a pending
 * question for a notification or answering it from a notification action. It is independent of the
 * app's foreground connection: the host accepts several peers per device, and each request carries
 * a fresh request ID so the host's ledger never confuses it with foreground traffic.
 *
 * Callers bound the whole call with a timeout; the transport's own handshake timeout is longer.
 */
internal class BackgroundRemoteClient(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val transportFactory: (CoroutineScope) -> RemoteTransport = {
        SecureRemoteTransport(it, backgroundHttp)
    },
) {
    suspend fun <T> use(host: PairedHost, block: suspend (RemoteCommands) -> T): T {
        // The transport is not thread-safe: it and its listener only ever run on [dispatcher].
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val ready = CompletableDeferred<Unit>()
        val pending = mutableMapOf<String, CompletableDeferred<JsonObject>>()
        fun failAll(error: Throwable) {
            ready.completeExceptionally(error)
            pending.values.forEach { it.completeExceptionally(error) }
            pending.clear()
        }
        val transport = transportFactory(scope)
        transport.listener =
            object : RemoteTransport.Listener {
                override fun ready(capabilities: Set<String>) {
                    ready.complete(Unit)
                }

                override fun approval() = failAll(RemoteConnectionException())

                override suspend fun persistPairing(host: PairedHost) =
                    failAll(RemoteConnectionException())

                override fun paired(host: PairedHost) = failAll(RemoteConnectionException())

                override fun message(payload: JsonObject) {
                    try {
                        if (payload.text("type") != "result") return
                        val completion = pending.remove(payload.text("requestId")) ?: return
                        if (payload.flag("ok")) completion.complete(payload.obj("data"))
                        else
                            completion.completeExceptionally(
                                RemoteCommandException(payload.obj("error").text("code"))
                            )
                    } catch (_: Exception) {
                        // Events and malformed frames are irrelevant to a one-shot command.
                    }
                }

                override fun failed(reconnect: Boolean, error: Int) =
                    failAll(RemoteConnectionException())
            }
        try {
            withContext(dispatcher) { transport.connect(host) }
            ready.await()
            return block(
                RemoteCommands { type, fields ->
                    val completion = CompletableDeferred<JsonObject>()
                    withContext(dispatcher) {
                        val id = Wire.random()
                        pending[id] = completion
                        try {
                            transport.send(
                                Wire.objectOf("type" to type, "requestId" to id, *fields)
                            )
                        } catch (_: Exception) {
                            pending.remove(id)
                            completion.completeExceptionally(RemoteConnectionException())
                        }
                    }
                    completion.await()
                }
            )
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + dispatcher) { transport.close() }
            scope.cancel()
        }
    }
}

/** Reads the pending questions of a live session. */
internal suspend fun RemoteCommands.pendingQuestions(sessionId: String): List<JsonObject> =
    request("session.snapshot", "sessionId" to sessionId).let { data ->
        require(data.text("kind") == "snapshot" && data.text("sessionId") == sessionId)
        data.array("pendingQuestions").take(16)
    }

internal suspend fun RemoteCommands.answerQuestion(
    sessionId: String,
    questionId: String,
    answer: JsonObject,
) {
    request(
        "question.answer",
        "sessionId" to sessionId,
        "questionId" to questionId,
        "answer" to answer,
    )
}
