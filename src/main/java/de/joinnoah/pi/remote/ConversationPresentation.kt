package de.joinnoah.pi.remote

import java.util.IdentityHashMap
import kotlinx.serialization.json.*

internal sealed interface ConversationItem {
    val id: String
    val sourceId: String

    data class Bubble(
        override val id: String,
        override val sourceId: String,
        val role: String,
        val author: String?,
        val text: String,
        val quote: MessageQuote?,
        val truncated: Boolean,
        val timestamp: Long?,
        val attachments: List<RemoteAttachment> = emptyList(),
        val error: Boolean = false,
        val usage: MessageUsage? = null,
        /** True while the host is still producing this message; its text is not complete yet. */
        val streaming: Boolean = false,
        /** Set only on the pending prompt at the chat's tail; its label shows below the bubble. */
        val pendingState: PendingSendState? = null,
        /** A terminal interruption, without implying who initiated it. */
        val interrupted: Boolean = false,
    ) : ConversationItem

    /** A host-identified async answer, either a jump marker or a standalone history fallback. */
    data class QuestionnaireAnswer(
        override val id: String,
        override val sourceId: String,
        val presentation: QuestionnairePresentation,
        val targetId: String?,
        val timestamp: Long?,
        val rawText: String,
    ) : ConversationItem

    data class Thinking(
        override val id: String,
        override val sourceId: String,
        val text: String,
        val streaming: Boolean,
    ) : ConversationItem

    data class Activity(
        override val id: String,
        override val sourceId: String,
        val name: String?,
        val arguments: String?,
        val output: String?,
        val state: String,
        val truncated: Boolean,
        val summary: ToolSummary = toolSummary(name, arguments, output),
        val toolCallId: String? = null,
        val outputMessageId: String? = null,
        val argumentsTruncated: Boolean = false,
        val details: ToolDetails? = null,
        val questionnaire: QuestionnairePresentation? = null,
    ) : ConversationItem

    data class Subagent(
        override val id: String,
        override val sourceId: String,
        val mode: String?,
        val agents: List<AgentProgress>,
        val legacy: Boolean,
        val output: String?,
        val truncated: Boolean,
        val toolCallId: String? = null,
        val outputMessageId: String? = null,
        /** Original cards from one assistant tool-call batch; never includes chains. */
        val constituents: List<Subagent> = emptyList(),
    ) : ConversationItem
}

/** The exact originating card and its local row index, for navigation and actor lookup. */
internal fun ConversationItem.Subagent.rowSource(index: Int): Pair<ConversationItem.Subagent, Int> {
    require(index in agents.indices)
    if (constituents.isEmpty()) return this to index
    var remaining = index
    for (card in constituents) {
        if (remaining < card.agents.size) return card.rowSource(remaining)
        remaining -= card.agents.size
    }
    error("Missing constituent subagent row")
}

internal fun ConversationItem.Subagent.originalCards(): List<ConversationItem.Subagent> =
    if (constituents.isEmpty()) listOf(this) else constituents.flatMap { it.originalCards() }

internal data class AgentProgress(
    val name: String,
    val state: String,
    val preview: String?,
    val activity: String?,
    val task: String? = null,
    val sessionId: String? = null,
    val step: Int? = null,
    val usage: AgentUsage? = null,
    /** Stable ID of this agent run (`session_activity.v1`); the actor ledger's key part. */
    val instanceId: String? = null,
    /** The parent tool call that spawned this agent (`session_activity.v1`). */
    val spawnToolCallId: String? = null,
    /**
     * The structured activity the transcript recorded. Historical: it never proves the agent is
     * live and nothing animates from it; only the repository's actor ledger does.
     */
    val executionActivity: ActivitySnapshot? = null,
    /** The agent's own `needsInput: true` as recorded; like [executionActivity], not live proof. */
    val needsInput: Boolean = false,
)

/** Token usage of one assistant message; [cost] is USD when the host knew it. */
internal data class MessageUsage(
    val input: Long,
    val output: Long,
    val cacheRead: Long,
    val cacheWrite: Long,
    val totalTokens: Long,
    val cost: Double?,
)

internal data class AgentUsage(
    val input: Long,
    val output: Long,
    val cacheRead: Long,
    val cacheWrite: Long,
    val contextTokens: Long,
    val turns: Long,
    val cost: Double,
)

/** The allowlisted details of an edit result: pi's unified patch, possibly cut by the host. */
internal data class ToolDetails(val patch: String, val firstChangedLine: Int?, val truncated: Boolean)

internal const val MAX_TOOL_DETAILS_PATCH_BYTES = 16384

// Safe readers: a malformed value of the wrong JSON type yields null instead of throwing.
private fun JsonObject.safeText(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.safeCount(key: String): Long? =
    (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 }

private fun JsonObject.safeCost(key: String): Double? =
    (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 }

private fun JsonObject.safeFlag(key: String): Boolean? =
    (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

internal fun isOpaqueId(value: String): Boolean =
    value.isNotEmpty() &&
        value.toByteArray(Charsets.UTF_8).size <= 256 &&
        value.none { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }

internal fun messageUsage(message: JsonObject): MessageUsage? {
    if (message.safeText("role") != "assistant") return null
    val usage = message["usage"] as? JsonObject ?: return null
    val cost =
        when (usage["cost"]) {
            null,
            JsonNull -> null
            else -> usage.safeCost("cost") ?: return null
        }
    return MessageUsage(
        usage.safeCount("input") ?: return null,
        usage.safeCount("output") ?: return null,
        usage.safeCount("cacheRead") ?: return null,
        usage.safeCount("cacheWrite") ?: return null,
        usage.safeCount("totalTokens") ?: return null,
        cost,
    )
}

internal fun toolDetails(message: JsonObject): ToolDetails? {
    if (message.safeText("state") == "error") return null
    val details = message["toolDetails"] as? JsonObject ?: return null
    if (details.safeText("kind") != "edit") return null
    val patch =
        details.safeText("patch")?.takeIf {
            it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= MAX_TOOL_DETAILS_PATCH_BYTES
        } ?: return null
    val firstChangedLine =
        when (details["firstChangedLine"]) {
            null,
            JsonNull -> null
            else ->
                details.safeCount("firstChangedLine")?.takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt()
                    ?: return null
        }
    val truncated =
        when (details["truncated"]) {
            null,
            JsonNull -> false
            else -> details.safeFlag("truncated") ?: return null
        }
    return ToolDetails(patch, firstChangedLine, truncated)
}

private fun agentUsage(agent: JsonObject): AgentUsage? {
    val usage = agent["usage"] as? JsonObject ?: return null
    return AgentUsage(
        usage.safeCount("input") ?: return null,
        usage.safeCount("output") ?: return null,
        usage.safeCount("cacheRead") ?: return null,
        usage.safeCount("cacheWrite") ?: return null,
        usage.safeCount("contextTokens") ?: return null,
        usage.safeCount("turns") ?: return null,
        usage.safeCost("cost") ?: return null,
    )
}

private val agentStates = setOf("queued", "running", "succeeded", "failed", "cancelled")

private class StructuredAgentFields(
    val instanceId: String?,
    val spawnToolCallId: String?,
    val executionActivity: ActivitySnapshot?,
    val needsInput: Boolean,
)

/**
 * `instanceId`, `spawnToolCallId`, `executionActivity` and `needsInput` of one progress agent, or
 * null when one is present but invalid (parse.ts: opaque IDs, a valid snapshot, `needsInput` only
 * `true`). `needsInput` counts only on a running agent, as the host only ever sends it there.
 */
private fun structuredAgentFields(agent: JsonObject): StructuredAgentFields? {
    fun id(key: String): Result<String?> =
        if (!agent.containsKey(key)) Result.success(null)
        else agent.safeText(key)?.takeIf(::validActivityId)?.let { Result.success(it) }
            ?: Result.failure(IllegalArgumentException(key))
    val instanceId = id("instanceId").getOrElse { return null }
    val spawnToolCallId = id("spawnToolCallId").getOrElse { return null }
    val executionActivity =
        if (!agent.containsKey("executionActivity")) null
        else parseActivitySnapshot(agent["executionActivity"]) ?: return null
    val needsInput =
        if (!agent.containsKey("needsInput")) false
        else if (agent.safeFlag("needsInput") == true) agent.safeText("state") == "running"
        else return null
    return StructuredAgentFields(instanceId, spawnToolCallId, executionActivity, needsInput)
}

/** Tools rendered as a subagent card: the retired `subagent` tool and the fork's `Agent` tool. */
private val SUBAGENT_TOOLS = setOf("subagent", "Agent")
private val QUESTIONNAIRE_TOOLS = setOf("AskUserQuestion", "questionnaire", "questionnaire_async")

private fun subagentProgress(message: JsonObject): ConversationItem.Subagent {
    val progress = message["subagentProgress"] as? JsonObject
    val mode = progress?.safeText("mode")?.takeIf { it in setOf("single", "parallel", "chain") }
    val entries = (progress?.get("agents") as? JsonArray)?.takeIf { it.size <= 8 }
    val agents = entries?.mapNotNull { entry ->
        val agent = entry as? JsonObject ?: return@mapNotNull null
        val name = agent.safeText("agent")?.takeIf { it.isNotBlank() && it.length <= 80 }
            ?: return@mapNotNull null
        val state = agent.safeText("state")?.takeIf { it in agentStates } ?: return@mapNotNull null
        // `session_activity.v1` fields as the host's parse.ts checks them: a present field that is
        // invalid there rejects the message on the host, so here it drops the agent, never just the
        // field, so a bad instance or a `needsInput: false` cannot leave a live-looking row.
        val structured = structuredAgentFields(agent) ?: return@mapNotNull null
        AgentProgress(
            name,
            state,
            agent.safeText("preview")?.take(240),
            agent.safeText("activity")?.take(80),
            task = agent.safeText("task")?.takeIf(String::isNotBlank)?.take(240),
            sessionId = agent.safeText("sessionId")?.takeIf(::isOpaqueId),
            step = agent.safeCount("step")?.takeIf { it in 1L..10000L }?.toInt(),
            usage = agentUsage(agent),
            instanceId = structured.instanceId,
            spawnToolCallId = structured.spawnToolCallId,
            executionActivity = structured.executionActivity,
            needsInput = structured.needsInput,
        )
    }.orEmpty().let { agents ->
        // An instance ID listed twice names no single run: neither row keeps its structured fields.
        val counts = agents.mapNotNull { it.instanceId }.groupingBy { it }.eachCount()
        agents.map {
            if (it.instanceId != null && counts.getValue(it.instanceId) > 1)
                it.copy(instanceId = null, spawnToolCallId = null, executionActivity = null, needsInput = false)
            else it
        }
    }
    return ConversationItem.Subagent(
        message.text("id"),
        message.text("id"),
        mode,
        agents,
        mode == null || entries == null,
        message.optionalText("text")?.takeIf { message.optionalText("state") != "streaming" && it.isNotBlank() },
        message["truncated"]?.jsonPrimitive?.booleanOrNull == true,
        toolCallId = message.safeText("toolCallId"),
        outputMessageId = message.text("id"),
    )
}

/** The tool name of a shell command the user ran with `!` in pi or from the phone. */
internal const val USER_BASH_TOOL = "user_bash"

/** A readable header for a tool card; a null [labelRes] means "show the raw tool name". */
internal data class ToolSummary(val labelRes: Int?, val detail: String?)

/**
 * The card header of tool [name]. A shell command typed by the user (`user_bash`) has no arguments:
 * its [output] starts with `$ `, the command and a newline, so the command comes from there.
 */
internal fun toolSummary(name: String?, arguments: String?, output: String? = null): ToolSummary {
    if (name == USER_BASH_TOOL)
        return ToolSummary(
            R.string.remote_shell_command,
            output?.takeIf { it.startsWith("$ ") }?.substring(2)?.lineSequence()?.first()?.trim()?.takeIf(String::isNotEmpty),
        )
    val args = arguments?.let(::parsedToolArguments)
    val label =
        when (name) {
            "read" -> R.string.remote_tool_read
            "edit" -> R.string.remote_tool_edit
            "write" -> R.string.remote_tool_write
            "bash" ->
                if (runsInBackground(args)) R.string.remote_tool_bash_background
                else R.string.remote_tool_bash
            // Older sessions start background jobs with `bash_bg`, which also takes `command`.
            "bash_bg" -> R.string.remote_tool_bash_background
            // The advisor tool; sessions saved before its rename hold calls as `consult_advisor`.
            "advisor", "consult_advisor" -> R.string.remote_tool_advisor
            "grep" -> R.string.remote_tool_search
            "find" -> R.string.remote_tool_find
            "ls" -> R.string.remote_tool_list
            "web_search" -> R.string.remote_tool_web_search
            "web_fetch", "fetch" -> R.string.remote_tool_web_fetch
            else -> return ToolSummary(null, null)
        }
    fun field(key: String): String? =
        (args?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)
    val detail =
        when (name) {
            "bash", "bash_bg" -> field("command")
            "advisor", "consult_advisor" -> field("question")
            "grep", "find" -> field("pattern")
            "web_search" -> field("query")
            "web_fetch", "fetch" -> field("url")
            else -> field("path") ?: field("file_path")
        }?.trim()?.lineSequence()?.first()?.trim()
    return ToolSummary(label, detail?.takeIf(String::isNotEmpty))
}

/** Whether a `bash` call's arguments carry the JSON boolean `run_in_background: true`. */
internal fun runsInBackground(args: JsonObject?): Boolean =
    (args?.get("run_in_background") as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == true } == true

private val toolArgumentsCache =
    object : LinkedHashMap<String, JsonObject?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JsonObject?>) =
            size > 256
    }

// conversationItems rebuilds the whole list on every streamed delta, so parse each call's
// arguments once instead of reparsing large write bodies on the main thread.
internal fun parsedToolArguments(arguments: String): JsonObject? =
    synchronized(toolArgumentsCache) {
        if (toolArgumentsCache.containsKey(arguments)) toolArgumentsCache[arguments]
        else
            runCatching { Json.parseToJsonElement(arguments) as? JsonObject }
                .getOrNull()
                .also { toolArgumentsCache[arguments] = it }
    }

/** False for items that render nothing, so the list does not reserve a slot for them. */
internal fun conversationItemVisible(
    item: ConversationItem,
    thinkingDisplay: ThinkingDisplay,
    thinkingActive: Boolean,
): Boolean =
    when (item) {
        is ConversationItem.Thinking ->
            (thinkingDisplay == ThinkingDisplay.TEXT && item.text.isNotEmpty()) ||
                (item.streaming && thinkingActive)
        else -> true
    }

private fun JsonObject.parts(): List<JsonObject> =
    (get("parts") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

private fun JsonObject.messageTimestamp(): Long? =
    (get("timestamp") as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 }

internal fun messageAuthor(message: JsonObject): String? =
    when (message.text("role")) {
        "assistant" ->
            (message["model"] as? JsonObject)?.let {
                it.optionalText("name")?.takeIf(String::isNotBlank)
                    ?: it.optionalText("id")?.takeIf(String::isNotBlank)
            }
        "tool" -> message.optionalText("toolName")?.takeIf(String::isNotBlank)
        else -> null
    }

internal fun quoteFromMessage(message: JsonObject): MessageQuote? {
    val parts = message.parts()
    val visible =
        if (parts.isNotEmpty())
            parts
                .filter { it.optionalText("type") == "text" }
                .joinToString("\n") { it.text("text") }
        else QuoteCodec.decode(AttachmentCodec.decode(message.text("text")).body).body
    if (visible.isBlank()) return null
    return MessageQuote(
            message.text("id"),
            message.text("role"),
            boundedUtf8(visible, 2048),
            messageAuthor(message)?.let { boundedUtf8(it, 256) },
        )
        .takeIf { QuoteCodec.parse(it.json()) != null }
}

/**
 * The text of a user message as its bubbles show it: the body after the quote and attachment
 * envelopes, one line per text part. [PendingSend] matches the host's copy of a prompt by it.
 */
internal fun userMessageBody(message: JsonObject): String {
    fun body(encoded: String) = QuoteCodec.decode(AttachmentCodec.decode(encoded).body).body
    val parts = message.parts().filter { it.optionalText("type") == "text" }
    return if (message.parts().isEmpty()) body(message.text("text"))
    else parts.joinToString("\n") { body(it.text("text")) }
}

/**
 * The chat's tail bubble for a plain prompt that the timeline does not show yet. Attachment names
 * stand in for the text of an attachments-only prompt, so the bubble is never empty.
 */
internal fun pendingSendItem(pending: PendingSend): ConversationItem.Bubble {
    val id = "pending-${pending.mutationId}"
    return ConversationItem.Bubble(
        id = id,
        sourceId = id,
        role = "user",
        author = null,
        text = pending.text.ifEmpty { pending.attachments.joinToString("\n") { it.name } },
        quote = pending.quote,
        truncated = false,
        timestamp = null,
        pendingState = pending.state,
    )
}

/** What the items of one message read from the message itself to pair it with the others. */
private class MessageLinks(val message: JsonObject) {
    val id = message.text("id")
    val role = message.text("role")

    /** The call a tool output answers; null for other messages. */
    val toolCallId = if (role == "tool") message.optionalText("toolCallId") else null

    /** The IDs of the message's tool calls, in part order. */
    val callIds =
        message.parts().filter { it.optionalText("type") == "toolCall" }.map { it.text("id") }

    val thinks = message.parts().any { it.optionalText("type") == "thinking" }
    val questionnaire = questionnairePresentation(message)
}

/**
 * What the items of one message read from the rest of the conversation. Only what the message
 * can use is set, so a delta elsewhere leaves it equal and the message's items are kept.
 */
private class MessageContext(
    /** The conversation's last message, whose streaming thinking part shows as active. */
    val last: Boolean,
    /** The assistant message the running session is still answering: calls without output stream. */
    val pending: Boolean,
    /** A tool output that one of the conversation's calls shows, so it adds no item of its own. */
    val paired: Boolean,
    /** The outputs of the message's calls, in the order of [MessageLinks.callIds]. */
    val outputs: List<JsonObject?>,
    val questionnaireResponses: List<JsonObject?>,
    val questionnaireTarget: String?,
) {
    // Outputs compare by identity: Timeline keeps an unchanged message's instance.
    fun sameAs(other: MessageContext): Boolean =
        last == other.last &&
            pending == other.pending &&
            paired == other.paired &&
            questionnaireTarget == other.questionnaireTarget &&
            questionnaireResponses.size == other.questionnaireResponses.size &&
            questionnaireResponses.indices.all { questionnaireResponses[it] === other.questionnaireResponses[it] } &&
            outputs.size == other.outputs.size &&
            outputs.indices.all { outputs[it] === other.outputs[it] }
}

/** The pairing across a whole conversation: its calls, their outputs and the pending reply. */
private class ConversationLinks(private val links: List<MessageLinks>, sessionActive: Boolean) {
    private val pendingAssistant: String? =
        run {
            val lastUser = links.indexOfLast { it.role == "user" }
            val lastAssistant = links.indexOfLast { it.role == "assistant" }
            if (sessionActive && lastAssistant > lastUser) links[lastAssistant].id else null
        }
    private val calls = links.flatMapTo(HashSet()) { it.callIds }
    private val outputs = HashMap<String, JsonObject>().apply {
        for (link in links) link.toolCallId?.let { put(it, link.message) }
    }

    private val questionnaireCalls = buildMap<String, String> {
        for (link in links) for (part in link.message.parts()) {
            if (part.optionalText("type") == "toolCall" &&
                part.optionalText("name") in QUESTIONNAIRE_TOOLS)
                put(part.text("id"), "${link.id}-call-${part.text("id")}")
        }
    }
    private fun matchesQuestionnaireCall(question: QuestionnairePresentation): Boolean {
        if (question.toolCallId !in questionnaireCalls) return false
        val output = outputs[question.toolCallId] ?: return true
        if (output["questionnaire"] == null || output["questionnaire"] == JsonNull) return true
        val original = questionnairePresentation(output) ?: return false
        return (original.requestId == null || original.requestId == question.requestId) &&
            original.questions.map { it.id to it.prompt } == question.questions.map { it.id to it.prompt }
    }
    private val questionnaireResponses = buildMap<String, JsonObject> {
        for (link in links) {
            val question = link.questionnaire ?: continue
            if (link.role == "user" && matchesQuestionnaireCall(question)) put(question.toolCallId, link.message)
        }
    }

    fun context(index: Int): MessageContext {
        val link = links[index]
        return MessageContext(
            last = link.thinks && index == links.lastIndex,
            pending = link.callIds.isNotEmpty() && link.id == pendingAssistant,
            paired = link.toolCallId in calls,
            outputs = link.callIds.map { outputs[it] },
            questionnaireResponses = link.callIds.map { questionnaireResponses[it] },
            questionnaireTarget = if (link.role == "user") link.questionnaire?.let {
                if (questionnaireResponses[it.toolCallId] === link.message) questionnaireCalls[it.toolCallId] else null
            } else null,
        )
    }
}

internal fun conversationItems(
    messages: List<JsonObject>,
    sessionActive: Boolean = false,
): List<ConversationItem> = Diagnostics.trace("PocketPi.conversationItems") {
    val links = messages.map(::MessageLinks)
    val conversation = ConversationLinks(links, sessionActive)
    links.flatMapIndexed { index, link -> messageItems(link, conversation.context(index)) }
}

/**
 * A chat's [conversationItems] with what the chat screen derives from them. [ConversationPresenter]
 * builds it; a derived list or set stays the previous instance while its value is the same.
 */
internal class ConversationPresentation(
    val items: List<ConversationItem>,
    val touchedFiles: TouchedFiles,
    val errorCount: Int,
    /** [forkableBubbleIds] of [items]. */
    val forkableBubbleIds: Set<String>,
    /** The subagent cards of [items], in order. */
    val subagents: List<ConversationItem.Subagent>,
)

/** One message's items and what the derived values need from them, computed once per message. */
private class Segment(val links: MessageLinks, val context: MessageContext, val items: List<ConversationItem>) {
    val errors = items.count(::isErrorItem)

    /** The bubble that offers the rewind control for this message, when it is the user's. */
    val forkable = items.firstOrNull { it is ConversationItem.Bubble && it.role == "user" } as ConversationItem.Bubble?

    /** File touches with their index in [items]. */
    val touches = items.withIndex().mapNotNull { (index, item) -> fileTouch(item)?.let { index to it } }

    val subagents = items.filterIsInstance<ConversationItem.Subagent>().flatMap { it.originalCards() }
}

/**
 * Builds one chat's [ConversationPresentation] incrementally. Each message's items are kept while
 * the message is the same instance (Timeline replaces only the message an upsert names) and what
 * it reads from the others is unchanged, so a streamed delta rebuilds the items of the message it
 * touched. The derived values reuse each message's share of them. Not thread-safe: the chat
 * screen calls it from composition.
 */
internal class ConversationPresenter {
    private var segments = IdentityHashMap<JsonObject, Segment>()
    private var order = emptyList<Segment>()
    private var presentation: ConversationPresentation? = null

    /** Messages whose items were built rather than kept, since this presenter was made. */
    var built = 0
        private set

    fun present(messages: List<JsonObject>, sessionActive: Boolean): ConversationPresentation =
        Diagnostics.trace("PocketPi.conversationItems") {
            val previous = segments
            val links = messages.map { previous[it]?.links ?: MessageLinks(it) }
            val conversation = ConversationLinks(links, sessionActive)
            val next = IdentityHashMap<JsonObject, Segment>(messages.size)
            val shown = links.mapIndexed { index, link ->
                val context = conversation.context(index)
                val segment =
                    previous[link.message]?.takeIf { it.context.sameAs(context) }
                        ?: Segment(link, context, messageItems(link, context)).also { built++ }
                next[link.message] = segment
                segment
            }
            segments = next
            val last = presentation
            if (last != null && shown.size == order.size && shown.indices.all { shown[it] === order[it] })
                return@trace last
            order = shown
            derive(shown, last).also { presentation = it }
        }

    private fun derive(shown: List<Segment>, last: ConversationPresentation?): ConversationPresentation {
        val items = ArrayList<ConversationItem>(shown.sumOf { it.items.size })
        shown.forEach { items.addAll(it.items) }
        val touched = groupTouchedFiles { visit ->
            var offset = 0
            for (segment in shown) {
                for ((index, touch) in segment.touches) visit(offset + index, touch)
                offset += segment.items.size
            }
        }
        // A message split into several bubbles offers the rewind control once.
        val forkable =
            shown.mapNotNull { it.forkable }.distinctBy { it.sourceId }.mapTo(HashSet()) { it.id }
        val subagents = shown.flatMap { it.subagents }
        return ConversationPresentation(
            items,
            kept(touched, last?.touchedFiles) { a, b -> a == b },
            shown.sumOf { it.errors },
            kept(forkable, last?.forkableBubbleIds) { a, b -> a == b },
            kept(subagents, last?.subagents, ::sameItems),
        )
    }

    private var stripSubagents: List<ConversationItem.Subagent>? = null
    private var stripChildren: List<SessionListItem>? = null
    private var stripParent: String? = null
    private var strip: SubagentStrip? = null

    /** [subagentStrip] of [presentation], recomputed only when its subagents or [children] change. */
    fun subagentStrip(
        presentation: ConversationPresentation,
        children: List<SessionListItem>,
        parentSessionId: String,
    ): SubagentStrip {
        val kept = strip
        if (kept != null && stripSubagents === presentation.subagents && stripChildren == children &&
            stripParent == parentSessionId) return kept
        return de.joinnoah.pi.remote.subagentStrip(presentation.subagents, children, parentSessionId).also {
            strip = it
            stripSubagents = presentation.subagents
            stripChildren = children
            stripParent = parentSessionId
        }
    }

    private var markerItems = emptyList<ConversationItem>()
    private var markers = emptyList<TimelineMarker>()

    /**
     * [timelineMarkers] of [items]. The markers of the leading items that are the same instances as
     * last time are kept; only those from the first changed item on are classified again.
     */
    fun timelineMarkers(items: List<ConversationItem>): List<TimelineMarker> {
        val before = markerItems
        var common = 0
        val limit = minOf(before.size, items.size)
        while (common < limit && before[common] === items[common]) common++
        if (common == items.size && common == before.size) return markers
        val keep = markers.binarySearchBy(common) { it.index }.let { if (it < 0) -it - 1 else it }
        val result = ArrayList<TimelineMarker>(keep + 8)
        result.addAll(markers.subList(0, keep))
        for (index in common until items.size) {
            val item = items[index]
            timelineMarkerKind(item)?.let { result.add(TimelineMarker(item.id, it, index)) }
        }
        markerItems = items
        markers = result
        return result
    }
}

private fun <T> sameItems(a: List<T>, b: List<T>): Boolean = a.size == b.size && a.indices.all { a[it] === b[it] }

/** [previous] when it is the [same] as [value], so whoever holds it sees no change. */
private inline fun <T : Any> kept(value: T, previous: T?, same: (T, T) -> Boolean): T =
    if (previous != null && same(value, previous)) previous else value

/** The items of one message; every input besides the message itself comes in [context]. */
private fun messageItems(links: MessageLinks, context: MessageContext): List<ConversationItem> = buildList {
    val message = links.message
    val outputs = links.callIds.zip(context.outputs).toMap()
    val id = links.id
    val role = links.role
    val truncated = message["truncated"]?.jsonPrimitive?.booleanOrNull == true
    val timestamp = message.messageTimestamp()
    if (role == "user" && links.questionnaire != null) {
        add(ConversationItem.QuestionnaireAnswer(id, id, links.questionnaire, context.questionnaireTarget, timestamp, userMessageBody(message)))
        return@buildList
    }
    if (role == "tool") {
        if (context.paired) return@buildList
        if (message.optionalText("toolName") in SUBAGENT_TOOLS && message["subagentProgress"] != null) {
            val progress = subagentProgress(message)
            if (progress.agents.isNotEmpty()) {
                add(progress)
                return@buildList
            }
        }
        add(
            ConversationItem.Activity(
                id,
                id,
                messageAuthor(message),
                null,
                message.text("text"),
                message.optionalText("state") ?: "complete",
                truncated,
                toolCallId = message.safeText("toolCallId"),
                outputMessageId = id,
                details = if (messageAuthor(message) == "edit") toolDetails(message) else null,
                questionnaire = links.questionnaire.takeIf { message.optionalText("toolName") in QUESTIONNAIRE_TOOLS },
            )
        )
        return@buildList
    }
    // Every bubble split off this message carries the same usage; only the last one keeps
    // it so a multi-part reply does not repeat the token count on each of its bubbles.
    val attached = AttachmentCodec.decode(message.text("text"))
    val attachments =
        (message["attachments"] as? JsonArray)?.mapNotNull {
            runCatching { remoteAttachment(it.jsonObject) }.getOrNull()
        } ?: attached.attachments
    val decoded = QuoteCodec.decode(attached.body)
    val quote = (message["quote"] as? JsonObject)?.let(QuoteCodec::parse) ?: decoded.quote
    val parts = message.parts()
    val interrupted = role == "assistant" && message.safeText("state") == "error" &&
        message.optionalText("terminalOutcome") == "interrupted"
    val error = message.safeText("state") == "error" && !interrupted
    val usage = messageUsage(message)
    val streaming = message.optionalText("state") == "streaming"
    if (parts.isEmpty()) {
        if (decoded.body.isNotEmpty() || quote != null || attachments.isNotEmpty()) {
            add(
                ConversationItem.Bubble(
                    id,
                    id,
                    role,
                    messageAuthor(message),
                    decoded.body,
                    quote,
                    truncated,
                    timestamp,
                    attachments,
                    error,
                    usage,
                    streaming,
                    interrupted = interrupted,
                )
            )
        }
        return@buildList
    }
    val lastMeaningfulPart = parts.indexOfLast {
        it.optionalText("type") == "toolCall" || !it.optionalText("text").isNullOrBlank()
    }
    if (parts.none { it.optionalText("type") == "text" } && attachments.isNotEmpty()) {
        add(
            ConversationItem.Bubble(
                "$id-attachments",
                id,
                role,
                messageAuthor(message),
                "",
                quote,
                truncated,
                timestamp,
                attachments,
                error,
                usage,
                streaming,
            )
        )
    }
    var quoteShown = false
    for ((index, part) in parts.withIndex()) {
        when (part.text("type")) {
            "text" -> {
                val text =
                    QuoteCodec.decode(AttachmentCodec.decode(part.text("text")).body).body
                if (
                    text.isNotEmpty() ||
                        quote != null ||
                        (!quoteShown && attachments.isNotEmpty())
                ) {
                    add(
                        ConversationItem.Bubble(
                            "$id-text-$index",
                            id,
                            role,
                            messageAuthor(message),
                            text,
                            if (!quoteShown) quote else null,
                            truncated,
                            timestamp,
                            if (!quoteShown) attachments else emptyList(),
                            error,
                            usage,
                            streaming,
                        )
                    )
                }
                quoteShown = true
            }
            "thinking" ->
                add(
                    ConversationItem.Thinking(
                        "$id-thinking-$index",
                        id,
                        part.text("text"),
                        message.optionalText("state") == "streaming" &&
                            context.last &&
                            index == lastMeaningfulPart,
                    )
                )
            "toolCall" -> {
                val output = outputs[part.text("id")]
                if (part.text("name") in SUBAGENT_TOOLS && output?.get("subagentProgress") != null) {
                    val progress = subagentProgress(output)
                    if (progress.agents.isNotEmpty()) {
                        add(
                            progress.copy(
                                id = "$id-call-${part.text("id")}",
                                toolCallId = part.text("id"),
                            )
                        )
                        continue
                    }
                }
                val arguments = part.text("arguments")
                add(
                    ConversationItem.Activity(
                        "$id-call-${part.text("id")}",
                        output?.text("id") ?: id,
                        part.text("name"),
                        arguments,
                        output?.text("text"),
                        output?.optionalText("state")
                            ?: if (context.pending) "streaming" else "unavailable",
                        output?.get("truncated")?.jsonPrimitive?.booleanOrNull == true,
                        toolCallId = part.text("id"),
                        outputMessageId = output?.text("id"),
                        argumentsTruncated = parsedToolArguments(arguments) == null,
                        questionnaire = if (part.text("name") in QUESTIONNAIRE_TOOLS) {
                            context.questionnaireResponses.getOrNull(links.callIds.indexOf(part.text("id")))
                                ?.let(::questionnairePresentation)
                                ?: output?.let(::questionnairePresentation)
                        } else null,
                        details =
                            output
                                ?.takeIf { part.text("name") == "edit" }
                                ?.let(::toolDetails),
                    )
                )
            }
        }
    }
    // Only this assistant message proves batch membership. Existing parallel/chain cards keep
    // their own semantics; only single-agent calls share a common header.
    if (role == "assistant") {
        val members = filterIsInstance<ConversationItem.Subagent>().filter {
            !it.legacy && it.mode == "single" && it.agents.size == 1
        }
        // Reserve the batch identity from the first progress result, even while other calls
        // still render as placeholders. Otherwise the second result replaces the first card.
        if (members.isNotEmpty()) {
            val first = members.first()
            val memberIds = members.mapTo(HashSet()) { it.id }
            val grouped = first.copy(
                // Progress may arrive out of order; identity belongs to the batch, not its first result.
                id = "$id-agent-batch",
                mode = "parallel",
                agents = members.flatMap { it.agents },
                output = null,
                truncated = members.any { it.truncated },
                constituents = members,
            )
            val combined = mapNotNull { entry ->
                when {
                    entry.id == first.id -> grouped
                    entry.id in memberIds -> null
                    else -> entry
                }
            }
            clear()
            addAll(combined)
        }
    }
    if (usage != null || interrupted) {
        val lastBubble = indices.lastOrNull { this[it] is ConversationItem.Bubble }
        for (i in indices) {
            val entry = this[i]
            if (entry is ConversationItem.Bubble)
                this[i] = entry.copy(usage = entry.usage.takeIf { i == lastBubble }, interrupted = interrupted && i == lastBubble)
        }
    }
}

/** Prompt text as the host and the phone compare it: line endings and surrounding whitespace aside. */
internal fun promptKey(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n').trim()

/**
 * The ID of the host's copy of [pending] in [messages], or null while the timeline lacks it. Only
 * messages after [PendingSend.afterMessageId] count; a baseline the timeline no longer holds
 * leaves every message a candidate.
 */
internal fun pendingSendCopyId(pending: PendingSend, messages: List<JsonObject>): String? {
    val baseline = pending.afterMessageId?.let { id -> messages.indexOfFirst { it.optionalText("id") == id } }
    val sentSince = messages.drop(if (baseline == null || baseline < 0) 0 else baseline + 1)
    val wanted = promptKey(pending.text)
    return sentSince.firstOrNull { it.text("role") == "user" && promptKey(userMessageBody(it)) == wanted }?.optionalText("id")
}

/**
 * The pending prompt the chat shows at its tail: none once the host's copy is in the timeline,
 * whether or not the host acknowledged the send yet, so the prompt never shows twice.
 */
internal fun shownPendingSend(pending: PendingSend?, messages: List<JsonObject>): PendingSend? =
    pending?.takeIf { pendingSendCopyId(it, messages) == null }

/**
 * Carries the chat list's key of a pending prompt's bubble over to the host's copy of that prompt
 * when the copy replaces it. The list then sees one row whose content changes, rather than one row
 * leaving and another arriving: with item animations those two rows overlap while one fades out
 * and the other fades in, which reads as the prompt shown twice.
 */
internal class PendingBubbleKeys {
    /** Host message item ID to the key of the pending bubble it replaced. */
    private val adopted = HashMap<String, String>()
    private var pending: PendingSend? = null
    private var knownIds: Set<String> = emptySet()

    /**
     * Call with each new list of [items], the [shown] pending prompt at its tail (if any) and the
     * timeline's [messages].
     */
    fun update(items: List<ConversationItem>, shown: PendingSend?, messages: List<JsonObject>) {
        val previous = pending
        val before = knownIds
        pending = shown
        knownIds = items.mapTo(HashSet()) { it.id }
        // A key in use by a pending bubble again is never also a host row's.
        shown?.let { current -> adopted.values.removeAll { it == pendingSendItem(current).id } }
        if (shown != null || previous == null) return
        val copy = pendingSendCopyId(previous, messages) ?: return
        // The first row of the host's copy takes the key, whatever its text: one prompt may show as
        // several rows (one per text part) or as attachments alone.
        items.firstOrNull { it.sourceId == copy && it.id !in before && it.id !in adopted }
            ?.let { adopted[it.id] = pendingSendItem(previous).id }
    }

    fun keyOf(item: ConversationItem): String = adopted[item.id] ?: item.id

    /** The item ID behind a list [key], for a key read back from the list's layout. */
    fun idOf(key: String): String = adopted.entries.firstOrNull { it.value == key }?.key ?: key
}
