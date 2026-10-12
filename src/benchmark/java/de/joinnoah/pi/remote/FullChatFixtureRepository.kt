package de.joinnoah.pi.remote

import android.os.Trace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

internal val fullChatKey = RemoteNavKey.Chat("fixture-host", "fixture-project", "fixture-session")
internal const val FULL_CHAT_READY = "FULL_CHAT_TAIL_READY"
internal const val FULL_CHAT_COMPLETE = "FULL_CHAT_STREAM_COMPLETE"
internal const val FULL_CHAT_CHECKPOINT = "FULL_CHAT_STREAM_CHECKPOINT"

/** Relay-free driver: real Timeline ingestion, synthetic repository publication, real chat UI. */
internal class FullChatFixtureRepository : RemoteRepository {
    private val timeline = Timeline(fullChatKey.sessionId)
    private val project = Wire.objectOf("id" to fullChatKey.projectId, "name" to "Fixture project")
    private val session = Wire.objectOf(
        "id" to fullChatKey.sessionId, "projectId" to fullChatKey.projectId,
        "title" to "Full chat fixture", "origin" to "rpc", "status" to "idle",
    )
    override val state: MutableStateFlow<RemoteState>
    val progress = MutableStateFlow("ready")
    private val continueStreaming = CompletableDeferred<Unit>()

    fun continueStream() {
        check(progress.value == "checkpoint")
        continueStreaming.complete(Unit)
    }

    init {
        val messages = buildList {
            repeat(48) { turn ->
                val tool = if (turn % 2 == 0) "read" else "edit"
                val failed = turn % 12 == 11
                val arguments = if (tool == "read") """{"path":"example-$turn.kt"}"""
                else """{"path":"example-$turn.kt","edits":[{"oldText":"return value","newText":"return value * 2"}]}"""
                add(message("user-$turn", "user", "Turn $turn: inspect the example and explain the change."))
                add(Wire.objectOf(
                    "id" to "call-$turn", "role" to "assistant", "text" to "", "state" to "complete",
                    "parts" to JsonArray(listOf(Wire.objectOf(
                        "type" to "toolCall", "id" to "tool-$turn", "name" to tool,
                        "arguments" to arguments,
                    ))),
                ))
                add(Wire.objectOf(
                    "id" to "result-$turn", "role" to "tool", "toolCallId" to "tool-$turn",
                    "toolName" to tool,
                    "text" to if (failed) "The example file changed; edit rejected." else "fun example(value: Int) = value * 2",
                    "state" to if (failed) "error" else "complete",
                    "toolDetails" to if (tool == "edit" && !failed) Wire.objectOf(
                        "kind" to "edit", "firstChangedLine" to 1,
                        "patch" to "--- example-$turn.kt\n+++ example-$turn.kt\n@@ -1 +1 @@\n-return value\n+return value * 2",
                    ) else null,
                ))
                add(message("answer-$turn", "assistant", """
                    ## Turn $turn findings
                    A fixed **Markdown** response with *emphasis* and a [link](https://example.com).

                    | File | Change |
                    | --- | --- |
                    | example-$turn.kt | +12 / -4 |

                    ```kotlin
                    fun example(value: Int): Int = value * 2
                    ```
                    The tool output above is complete; this turn is not streaming.
                """.trimIndent()))
            }
            add(message("tail-ready", "user", FULL_CHAT_READY))
        }
        timeline.snapshot(Wire.objectOf(
            "sessionId" to fullChatKey.sessionId, "revision" to 0, "status" to "idle",
            "messages" to JsonArray(messages), "pendingQuestions" to JsonArray(emptyList()),
        ))
        state = MutableStateFlow(RemoteState(
            selection = fullChatKey.selection(), connected = true, status = timeline.status,
            project = project, projects = listOf(project), session = session,
            sessions = listOf(session), sessionsFresh = true, messages = timeline.messages,
        ))
    }

    private fun message(id: String, role: String, text: String, status: String = "complete") =
        Wire.objectOf("id" to id, "role" to role, "text" to text, "state" to status)

    private fun event(kind: String, message: JsonObject? = null, status: String? = null) {
        Trace.beginSection("PocketPi.fullChat.timelineEvent")
        try {
            val fields = mutableListOf<Pair<String, Any?>>("sessionId" to fullChatKey.sessionId,
                "revision" to timeline.revision + 1, "kind" to kind)
            if (message != null) fields += "message" to message
            if (status != null) fields += "status" to status
            check(!timeline.event(Wire.objectOf(*fields.toTypedArray())))
        } finally {
            Trace.endSection()
        }
        state.value = state.value.copy(
            messages = timeline.messages, questions = timeline.questions, status = timeline.status,
        )
    }

    suspend fun stream() {
        check(progress.value == "ready")
        progress.value = "running"
        event("session.status", status = "running")
        var text = "## Incremental response\n"
        repeat(120) { chunk ->
            delay(16)
            text += "Chunk $chunk: **bold**, `code`, and [a link](https://example.com).\n"
            event("message.upsert", message("stream", "assistant", text, "streaming"))
            if (chunk == 59) {
                // A persistent visible bubble lets the harness verify following mid-stream,
                // rather than accepting a jump that happens only on final completion.
                event("message.upsert", message("stream-checkpoint", "assistant", FULL_CHAT_CHECKPOINT))
                progress.value = "checkpoint"
                continueStreaming.await()
                progress.value = "running"
            }
        }
        event("message.upsert", message("stream", "assistant", text))
        event("message.upsert", message("stream-complete", "assistant", FULL_CHAT_COMPLETE))
        event("session.status", status = "idle")
        check(!timeline.needsSnapshot && timeline.revision == 125L)
        progress.value = "complete"
    }

    override suspend fun activate(selection: RemoteSelection, mode: ActivationMode) = fullChatKey.selection()
    override suspend fun createSession(routeId: String, projectId: String) = fullChatKey.selection()
    override suspend fun openNotification(routeId: String, sessionId: String): RemoteSelection? = null
    override suspend fun pair(text: String): RemoteSelection? = null
    override fun cancelSelection() {}
    override fun remove(routeId: String) {}
    override fun disconnect() {}
    override fun refresh() {}
    override fun older() {}
    override fun draft(text: String) { state.value = state.value.copy(draft = text) }
    override fun quote(messageId: String?) {}
    override fun prompt() {}
    override fun abort() {}
    override fun answer(questionId: String, answer: JsonObject) {}
    override fun dismissError() {}
    override fun reportError(resource: Int) {}
    override fun setPushToken(token: String?) {}
}
