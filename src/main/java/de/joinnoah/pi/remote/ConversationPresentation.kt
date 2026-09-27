package de.joinnoah.pi.remote

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
        val summary: ToolSummary = toolSummary(name, arguments),
        val toolCallId: String? = null,
        val outputMessageId: String? = null,
        val argumentsTruncated: Boolean = false,
        val details: ToolDetails? = null,
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
    ) : ConversationItem
}

internal data class AgentProgress(
    val name: String,
    val state: String,
    val preview: String?,
    val activity: String?,
    val task: String? = null,
    val sessionId: String? = null,
    val step: Int? = null,
    val usage: AgentUsage? = null,
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

private fun subagentProgress(message: JsonObject): ConversationItem.Subagent {
    val progress = message["subagentProgress"] as? JsonObject
    val mode = progress?.safeText("mode")?.takeIf { it in setOf("single", "parallel", "chain") }
    val entries = (progress?.get("agents") as? JsonArray)?.takeIf { it.size <= 8 }
    val agents = entries?.mapNotNull { entry ->
        val agent = entry as? JsonObject ?: return@mapNotNull null
        val name = agent.safeText("agent")?.takeIf { it.isNotBlank() && it.length <= 80 }
            ?: return@mapNotNull null
        val state = agent.safeText("state")?.takeIf { it in agentStates } ?: return@mapNotNull null
        AgentProgress(
            name,
            state,
            agent.safeText("preview")?.take(240),
            agent.safeText("activity")?.take(80),
            task = agent.safeText("task")?.takeIf(String::isNotBlank)?.take(240),
            sessionId = agent.safeText("sessionId")?.takeIf(::isOpaqueId),
            step = agent.safeCount("step")?.takeIf { it in 1L..10000L }?.toInt(),
            usage = agentUsage(agent),
        )
    }.orEmpty()
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

/** A readable header for a tool card; a null [labelRes] means "show the raw tool name". */
internal data class ToolSummary(val labelRes: Int?, val detail: String?)

internal fun toolSummary(name: String?, arguments: String?): ToolSummary {
    val label =
        when (name) {
            "read" -> R.string.remote_tool_read
            "edit" -> R.string.remote_tool_edit
            "write" -> R.string.remote_tool_write
            "bash" -> R.string.remote_tool_bash
            else -> return ToolSummary(null, null)
        }
    val args = arguments?.let(::parsedToolArguments)
    fun field(key: String): String? =
        (args?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)
    val detail =
        if (name == "bash") field("command")?.trim()?.lineSequence()?.first()?.trim()
        else field("path") ?: field("file_path")
    return ToolSummary(label, detail?.takeIf(String::isNotEmpty))
}

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
    thinkingDisplay: String,
    thinkingActive: Boolean,
): Boolean =
    when (item) {
        is ConversationItem.Thinking ->
            (thinkingDisplay == "text" && item.text.isNotEmpty()) ||
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

internal fun conversationItems(
    messages: List<JsonObject>,
    sessionActive: Boolean = false,
): List<ConversationItem> {
    val lastUser = messages.indexOfLast { it.text("role") == "user" }
    val lastAssistant = messages.indexOfLast { it.text("role") == "assistant" }
    val pendingAssistant =
        if (sessionActive && lastAssistant > lastUser) messages.getOrNull(lastAssistant)?.text("id")
        else null
    val calls =
        messages
            .flatMap { it.parts() }
            .filter { it.optionalText("type") == "toolCall" }
            .map { it.text("id") }
            .toSet()
    val outputs =
        messages
            .filter { it.text("role") == "tool" && it.optionalText("toolCallId") != null }
            .associateBy { it.text("toolCallId") }
    return buildList {
        for ((messageIndex, message) in messages.withIndex()) {
            val id = message.text("id")
            val role = message.text("role")
            val truncated = message["truncated"]?.jsonPrimitive?.booleanOrNull == true
            val timestamp = message.messageTimestamp()
            if (role == "tool") {
                if (message.optionalText("toolCallId") in calls) continue
                if (message.optionalText("toolName") == "subagent" && message["subagentProgress"] != null) {
                    val progress = subagentProgress(message)
                    if (progress.agents.isNotEmpty()) {
                        add(progress)
                        continue
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
                    )
                )
                continue
            }
            val attached = AttachmentCodec.decode(message.text("text"))
            val attachments =
                (message["attachments"] as? JsonArray)?.mapNotNull {
                    runCatching { remoteAttachment(it.jsonObject) }.getOrNull()
                } ?: attached.attachments
            val decoded = QuoteCodec.decode(attached.body)
            val quote = (message["quote"] as? JsonObject)?.let(QuoteCodec::parse) ?: decoded.quote
            val parts = message.parts()
            val error = message.safeText("state") == "error"
            val usage = messageUsage(message)
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
                        )
                    )
                }
                continue
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
                                    messageIndex == messages.lastIndex &&
                                    index == lastMeaningfulPart,
                            )
                        )
                    "toolCall" -> {
                        val output = outputs[part.text("id")]
                        if (part.text("name") == "subagent" && output?.get("subagentProgress") != null) {
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
                                    ?: if (id == pendingAssistant) "streaming" else "unavailable",
                                output?.get("truncated")?.jsonPrimitive?.booleanOrNull == true,
                                toolCallId = part.text("id"),
                                outputMessageId = output?.text("id"),
                                argumentsTruncated = parsedToolArguments(arguments) == null,
                                details =
                                    output
                                        ?.takeIf { part.text("name") == "edit" }
                                        ?.let(::toolDetails),
                            )
                        )
                    }
                }
            }
        }
    }
}
