package de.joinnoah.pi.remote

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ConversationPresentationTest {
    private fun message(
        id: String,
        role: String = "assistant",
        text: String = "legacy",
        vararg extra: Pair<String, Any?>,
    ) = Wire.objectOf("id" to id, "role" to role, "text" to text, "state" to "streaming", *extra)

    @Test
    fun sourceTimestampReachesEveryBubbleWithoutInventingLegacyTimes() {
        val parts = JsonArray(listOf(
            Wire.objectOf("type" to "text", "text" to "First"),
            Wire.objectOf("type" to "text", "text" to "Second"),
        ))
        val stamped = message("assistant", extra = arrayOf("timestamp" to 1_757_777_777_000L, "parts" to parts))
        val bubbles = conversationItems(listOf(stamped)).filterIsInstance<ConversationItem.Bubble>()
        assertEquals(listOf("First", "Second"), bubbles.map { it.text })
        assertEquals(listOf(1_757_777_777_000L, 1_757_777_777_000L), bubbles.map { it.timestamp })

        for (value in listOf(null, "1757777777000", -1L, 1.5)) {
            val legacy = message("legacy", "user", "Older message", "timestamp" to value)
            assertNull((conversationItems(listOf(legacy)).single() as ConversationItem.Bubble).timestamp)
        }
        assertNull(
            (conversationItems(listOf(message("missing", "user", "Older message"))).single()
                as ConversationItem.Bubble).timestamp
        )
    }

    @Test
    fun subagentCallUsesProgressAndKeepsCompletedMarkdownOutput() {
        val call = Wire.objectOf("type" to "toolCall", "id" to "call", "name" to "subagent", "arguments" to "private task")
        val assistant = message("assistant", "assistant", "", "parts" to JsonArray(listOf(call)))
        val progress = Wire.objectOf("mode" to "parallel", "agents" to JsonArray(listOf(
            Wire.objectOf("agent" to "one", "state" to "succeeded", "preview" to "visible"),
            Wire.objectOf("agent" to "two", "state" to "running"),
        )))
        val result = message("tool-call", "tool", "### one\n**Result**", "toolCallId" to "call",
            "toolName" to "subagent", "subagentProgress" to progress, "state" to "complete")
        val item = conversationItems(listOf(assistant, result)).single() as ConversationItem.Subagent
        assertEquals(listOf("one", "two"), item.agents.map { it.name })
        assertEquals(listOf("succeeded", "running"), item.agents.map { it.state })
        assertFalse(item.legacy)
        assertEquals("### one\n**Result**", item.output)
        val legacy = conversationItems(listOf(assistant, message("tool-call", "tool", "private output",
            "toolCallId" to "call", "toolName" to "subagent"))).single() as ConversationItem.Activity
        assertEquals("private output", legacy.output)
        assertEquals("private task", legacy.arguments)
        val orphan = conversationItems(listOf(result)).single() as ConversationItem.Subagent
        assertEquals(listOf("one", "two"), orphan.agents.map { it.name })
        val streaming = conversationItems(listOf(message("progress", "tool", "partial",
            "toolName" to "subagent", "subagentProgress" to progress))).single() as ConversationItem.Subagent
        assertNull(streaming.output)
    }

    @Test
    fun incompletePreviewLinkDoesNotShowRawMarkdownUrl() {
        assertEquals(
            "Passed for Questionnaire…",
            subagentPreviewText("Passed for [Questionnaire](https://plane.join-noah.de/noah/browse"),
        )
    }

    @Test
    fun emptyOrMalformedSubagentProgressFallsBackToToolOutput() {
        val call = Wire.objectOf("type" to "toolCall", "id" to "call", "name" to "subagent", "arguments" to "task context")
        val assistant = message("assistant", "assistant", "", "parts" to JsonArray(listOf(call)))
        val empty = Wire.objectOf("mode" to "single", "agents" to JsonArray(emptyList()))
        val malformed = Wire.objectOf("mode" to "chain", "agents" to JsonArray(listOf(
            Wire.objectOf("agent" to "", "state" to "failed"),
            Wire.objectOf("agent" to "agent", "state" to "unknown"),
        )))
        for (progress in listOf(empty, malformed)) {
            val result = message("result", "tool", "Invalid parameters or approval declined",
                "toolCallId" to "call", "toolName" to "subagent", "state" to "error",
                "subagentProgress" to progress)
            val paired = conversationItems(listOf(assistant, result)).single() as ConversationItem.Activity
            assertEquals("task context", paired.arguments)
            assertEquals("Invalid parameters or approval declined", paired.output)
            assertEquals("error", paired.state)
            val orphan = conversationItems(listOf(result)).single() as ConversationItem.Activity
            assertEquals("Invalid parameters or approval declined", orphan.output)
            assertNull(orphan.arguments)
        }
    }

    @Test
    fun toolCallPendingFollowsActiveCurrentTurnRatherThanAssistantMessageState() {
        val user =
            Wire.objectOf(
                "id" to "user",
                "role" to "user",
                "text" to "Read file",
                "state" to "complete",
            )
        val call =
            Wire.objectOf(
                "id" to "assistant",
                "role" to "assistant",
                "text" to "",
                "state" to "complete",
                "parts" to
                    JsonArray(
                        listOf(
                            Wire.objectOf(
                                "type" to "toolCall",
                                "id" to "call",
                                "name" to "read",
                                "arguments" to "{}",
                            )
                        )
                    ),
            )
        fun state(messages: List<JsonObject>, active: Boolean) =
            conversationItems(messages, active)
                .filterIsInstance<ConversationItem.Activity>()
                .single()
                .state
        assertEquals("streaming", state(listOf(user, call), true))
        assertEquals("unavailable", state(listOf(user, call), false))
        assertEquals("unavailable", state(listOf(call, user), true))
        val answer =
            Wire.objectOf(
                "id" to "answer",
                "role" to "assistant",
                "text" to "Continuing",
                "state" to "streaming",
            )
        assertEquals("unavailable", state(listOf(user, call, answer), true))
    }

    @Test
    fun completedToolCallWithoutResultIsNotPending() {
        val call =
            Wire.objectOf(
                "type" to "toolCall",
                "id" to "call",
                "name" to "read",
                "arguments" to "{}",
            )
        val message =
            Wire.objectOf(
                "id" to "a",
                "role" to "assistant",
                "text" to "",
                "state" to "complete",
                "parts" to JsonArray(listOf(call)),
            )
        assertEquals(
            "unavailable",
            (conversationItems(listOf(message)).single() as ConversationItem.Activity).state,
        )
    }

    @Test
    fun onlyTheLastMeaningfulThinkingPartIsActiveDuringStreaming() {
        val thinking = Wire.objectOf("type" to "thinking", "text" to "Actual thought")
        val active =
            conversationItems(
                    listOf(message("a", extra = arrayOf("parts" to JsonArray(listOf(thinking)))))
                )
                .single() as ConversationItem.Thinking
        assertTrue(active.streaming)
        val items =
            conversationItems(
                listOf(
                    message(
                        "a",
                        extra =
                            arrayOf(
                                "parts" to
                                    JsonArray(
                                        listOf(
                                            thinking,
                                            Wire.objectOf("type" to "text", "text" to "Answer"),
                                        )
                                    )
                            ),
                    )
                )
            )
        assertFalse((items[0] as ConversationItem.Thinking).streaming)
    }

    @Test
    fun thinkingStreamsOnlyAtTheChronologicalTailOfTheConversation() {
        val thought = Wire.objectOf("type" to "thinking", "text" to "Earlier thought")
        val answer = Wire.objectOf("type" to "text", "text" to "A later answer")
        val items =
            conversationItems(
                listOf(
                    message("thinking", extra = arrayOf("parts" to JsonArray(listOf(thought)))),
                    message(
                        "answer",
                        extra = arrayOf("parts" to JsonArray(listOf(answer))),
                    ),
                )
            )

        assertFalse((items.single { it is ConversationItem.Thinking } as ConversationItem.Thinking).streaming)
    }

    @Test
    fun structuredPartsDoNotDuplicateLegacyAndToolsPairByCallId() {
        val call =
            Wire.objectOf(
                "type" to "toolCall",
                "id" to "call",
                "name" to "read",
                "arguments" to "{\"path\":\"a.kt\"}",
            )
        val messages =
            listOf(
                message(
                    "a",
                    extra =
                        arrayOf(
                            "parts" to
                                JsonArray(
                                    listOf(
                                        Wire.objectOf(
                                            "type" to "thinking",
                                            "text" to "Actual reasoning",
                                        ),
                                        Wire.objectOf("type" to "text", "text" to "Answer"),
                                        call,
                                    )
                                ),
                            "model" to
                                Wire.objectOf(
                                    "provider" to "provider",
                                    "id" to "model-id",
                                    "name" to "Actual model",
                                ),
                        ),
                ),
                message(
                    "tool",
                    "tool",
                    "File output",
                    "toolCallId" to "call",
                    "toolName" to "read",
                ),
            )
        val items = conversationItems(messages)
        assertEquals(3, items.size)
        assertEquals("Actual reasoning", (items[0] as ConversationItem.Thinking).text)
        assertFalse((items[0] as ConversationItem.Thinking).streaming)
        assertEquals("Answer", (items[1] as ConversationItem.Bubble).text)
        assertEquals("Actual model", (items[1] as ConversationItem.Bubble).author)
        assertEquals("File output", (items[2] as ConversationItem.Activity).output)
        assertFalse(items.any { it is ConversationItem.Bubble && it.text == "legacy" })
    }

    @Test
    fun oldHostQuoteFallbackAndStandaloneToolOutputRemainReadable() {
        val quote = MessageQuote("old", "assistant", "An answer", "Old model")
        val bubble =
            conversationItems(
                    listOf(message("user", "user", QuoteCodec.encode("Follow up", quote)))
                )
                .single() as ConversationItem.Bubble
        assertEquals("Follow up", bubble.text)
        assertEquals(quote, bubble.quote)
        assertNull(
            conversationItems(listOf(message("unknown"))).filterIsInstance<ConversationItem.Bubble>()
                .single()
                .author
        )
        assertTrue(
            conversationItems(
                    listOf(message("tool", "tool", "Older output", "toolCallId" to "missing"))
                )
                .single() is ConversationItem.Activity
        )
    }

    @Test
    fun missingModelMetadataFallsBackInTheAuthorLineWithoutExtraItems() {
        val rawModel =
            message(
                "raw",
                extra = arrayOf("model" to Wire.objectOf("provider" to "provider", "id" to "model-id")),
            )
        val missingMetadata = message("missing")
        val items = conversationItems(listOf(rawModel, missingMetadata))

        assertEquals("model-id", messageAuthor(rawModel))
        assertNull(messageAuthor(missingMetadata))
        assertEquals(2, items.size)
        assertTrue(items.all { it is ConversationItem.Bubble })
    }

    @Test
    fun quoteDerivationNeverIncludesHiddenThinking() {
        val parts =
            JsonArray(listOf(Wire.objectOf("type" to "thinking", "text" to "Private thought")))
        assertNull(quoteFromMessage(message("thinking", extra = arrayOf("parts" to parts))))
        val textParts =
            JsonArray(parts + Wire.objectOf("type" to "text", "text" to "Visible answer"))
        assertEquals(
            "Visible answer",
            quoteFromMessage(message("visible", extra = arrayOf("parts" to textParts)))!!.excerpt,
        )
    }

    @Test
    fun toolSummaryReadsPathFromReadArguments() {
        assertEquals(
            ToolSummary(R.string.remote_tool_read, "src/Main.kt"),
            toolSummary("read", """{"path":"src/Main.kt","offset":10}"""),
        )
    }

    @Test
    fun toolSummaryKeepsOnlyTheFirstCommandLine() {
        assertEquals(
            ToolSummary(R.string.remote_tool_bash, "cd apps/pocketpi &&"),
            toolSummary("bash", """{"command":"  cd apps/pocketpi &&\n  ./gradlew test\n"}"""),
        )
    }

    @Test
    fun toolSummaryAcceptsFilePathForEdit() {
        assertEquals(
            ToolSummary(R.string.remote_tool_edit, "README.md"),
            toolSummary("edit", """{"file_path":"README.md","oldText":"a","newText":"b"}"""),
        )
    }

    @Test
    fun toolSummaryUsesTheRawNameForUnknownTools() {
        val none = ToolSummary(null, null)
        assertEquals(none, toolSummary("grep", """{"pattern":"x","path":"src"}"""))
        assertEquals(none, toolSummary("submit_plan", """{"plan":"# Plan"}"""))
        assertEquals(none, toolSummary(null, """{"path":"src"}"""))
    }

    @Test
    fun toolSummaryKeepsTheLabelWhenArgumentsAreUnreadable() {
        assertEquals(ToolSummary(R.string.remote_tool_read, null), toolSummary("read", "{not json"))
        assertEquals(ToolSummary(R.string.remote_tool_read, null), toolSummary("read", "[\"src\"]"))
        assertEquals(ToolSummary(R.string.remote_tool_read, null), toolSummary("read", null))
        assertEquals(
            ToolSummary(R.string.remote_tool_write, null),
            toolSummary("write", """{"content":"hello"}"""),
        )
        assertEquals(ToolSummary(R.string.remote_tool_bash, null), toolSummary("bash", """{"command":42}"""))
        assertEquals(ToolSummary(R.string.remote_tool_bash, null), toolSummary("bash", """{"command":"   "}"""))
    }

    @Test
    fun activityCarriesItsToolSummary() {
        val call =
            Wire.objectOf("type" to "toolCall", "id" to "call", "name" to "read", "arguments" to """{"path":"a.kt"}""")
        val activity =
            conversationItems(listOf(message("assistant", "assistant", "", "parts" to JsonArray(listOf(call)))))
                .single() as ConversationItem.Activity
        assertEquals(ToolSummary(R.string.remote_tool_read, "a.kt"), activity.summary)
    }

    @Test
    fun thinkingIsVisibleOnlyWhenItRendersSomething() {
        val done = ConversationItem.Thinking("t", "s", "Reasoning", streaming = false)
        val empty = done.copy(text = "")
        val live = empty.copy(streaming = true)
        assertTrue(conversationItemVisible(done, "text", thinkingActive = false))
        assertFalse(conversationItemVisible(empty, "text", thinkingActive = false))
        assertFalse(conversationItemVisible(done, "status", thinkingActive = false))
        assertFalse(conversationItemVisible(done, "status", thinkingActive = true))
        assertTrue(conversationItemVisible(live, "status", thinkingActive = true))
        assertTrue(conversationItemVisible(live, "text", thinkingActive = true))
        assertFalse(conversationItemVisible(live, "status", thinkingActive = false))
        val bubble = ConversationItem.Bubble("b", "b", "user", null, "Hi", null, false, null)
        assertTrue(conversationItemVisible(bubble, "status", thinkingActive = false))
    }

    private val insights: Map<String, JsonObject> by lazy {
        Json.parseToJsonElement(javaClass.getResource("/insights-v1.json")!!.readText())
            .jsonObject
            .getValue("valid")
            .jsonArray
            .associate { it.jsonObject.text("name") to it.jsonObject.obj("payload") }
    }

    private fun fixtureMessage(name: String) = insights.getValue(name).obj("message")

    @Test
    fun pairedCallsCarryToolCallAndOutputMessageIds() {
        val calls = JsonArray(listOf(
            Wire.objectOf("type" to "toolCall", "id" to "c1", "name" to "read", "arguments" to """{"path":"a.kt"}"""),
            Wire.objectOf("type" to "toolCall", "id" to "c2", "name" to "write", "arguments" to """{"path":"b.kt","content":"cut"""),
        ))
        val assistant = message("assistant", "assistant", "", "parts" to calls, "state" to "complete")
        val output = message("out-1", "tool", "file", "toolCallId" to "c1", "toolName" to "read", "state" to "complete")
        val orphan = message("orphan", "tool", "lost", "toolCallId" to "c9", "toolName" to "bash", "state" to "complete")
        val items = conversationItems(listOf(assistant, output, orphan)).filterIsInstance<ConversationItem.Activity>()
        assertEquals(listOf("c1", "c2", "c9"), items.map { it.toolCallId })
        assertEquals(listOf("out-1", null, "orphan"), items.map { it.outputMessageId })
        assertEquals(listOf("out-1", "assistant", "orphan"), items.map { it.sourceId })
        assertEquals(listOf(false, true, false), items.map { it.argumentsTruncated })
        assertTrue(items.all { it.details == null })
    }

    @Test
    fun subagentItemsCarryTheirIds() {
        val call = Wire.objectOf("type" to "toolCall", "id" to "toolu_subagent_01", "name" to "subagent", "arguments" to "{}")
        val assistant = message("assistant", "assistant", "", "parts" to JsonArray(listOf(call)))
        val result = fixtureMessage("subagent-progress-v2")
        val paired = conversationItems(listOf(assistant, result)).single() as ConversationItem.Subagent
        assertEquals("toolu_subagent_01", paired.toolCallId)
        assertEquals("msg-tool-subagent-1", paired.outputMessageId)
        val orphan = conversationItems(listOf(result)).single() as ConversationItem.Subagent
        assertEquals("toolu_subagent_01", orphan.toolCallId)
        assertEquals("msg-tool-subagent-1", orphan.outputMessageId)
    }

    @Test
    fun subagentProgressV2FieldsAreRead() {
        val item = conversationItems(listOf(fixtureMessage("subagent-progress-v2"))).single() as ConversationItem.Subagent
        val (explorer, planner) = item.agents
        assertEquals("Find where the wire parser validates messages", explorer.task)
        assertEquals("019a2f3d-0000-7000-8000-000000000001", explorer.sessionId)
        assertEquals(1, explorer.step)
        assertEquals(AgentUsage(12000, 850, 40000, 1200, 53850, 4, 0.0421), explorer.usage)
        assertEquals(AgentProgress("planner", "running", null, "read"), planner)
    }

    @Test
    fun malformedProgressKeysBecomeNullWithoutDroppingTheEntry() {
        val bad = Wire.objectOf(
            "agent" to "explorer",
            "state" to "running",
            "preview" to JsonObject(emptyMap()),
            "activity" to JsonArray(emptyList()),
            "task" to JsonObject(emptyMap()),
            "sessionId" to "has space",
            "step" to 0,
            "usage" to Wire.objectOf("input" to 1, "output" to 1, "cacheRead" to 1, "cacheWrite" to 1,
                "contextTokens" to 1, "turns" to 1),
        )
        val other = Wire.objectOf("agent" to "planner", "state" to "queued", "task" to "x".repeat(300),
            "sessionId" to JsonObject(emptyMap()), "step" to "2", "usage" to "cheap")
        val progress = Wire.objectOf("mode" to JsonObject(emptyMap()), "agents" to JsonArray(listOf(bad, other)))
        val item = conversationItems(listOf(message("p", "tool", "", "toolName" to "subagent",
            "subagentProgress" to progress))).single() as ConversationItem.Subagent
        assertEquals(AgentProgress("explorer", "running", null, null), item.agents[0])
        assertEquals(AgentProgress("planner", "queued", null, null, task = "x".repeat(240)), item.agents[1])
        assertTrue(item.legacy)
        val longId = "a".repeat(257)
        val tooLong = Wire.objectOf("mode" to "single", "agents" to JsonArray(listOf(
            Wire.objectOf("agent" to "a", "state" to "running", "sessionId" to longId, "step" to 10001))))
        val single = conversationItems(listOf(message("q", "tool", "", "toolName" to "subagent",
            "subagentProgress" to tooLong))).single() as ConversationItem.Subagent
        assertNull(single.agents.single().sessionId)
        assertNull(single.agents.single().step)
    }

    @Test
    fun assistantUsageIsParsedFromTheFixture() {
        val bubble = conversationItems(listOf(fixtureMessage("message-usage"))).single() as ConversationItem.Bubble
        assertEquals(MessageUsage(1520, 310, 20480, 512, 22822, 0.01875), bubble.usage)
        assertFalse(bubble.error)
    }

    @Test
    fun usageIsIgnoredOffAssistantsAndWhenInvalid() {
        val usage = Wire.objectOf("input" to 1, "output" to 2, "cacheRead" to 3, "cacheWrite" to 4, "totalTokens" to 10)
        val user = conversationItems(listOf(message("u", "user", "hi", "usage" to usage))).single() as ConversationItem.Bubble
        assertNull(user.usage)
        val tool = conversationItems(listOf(message("t", "tool", "out", "toolName" to "bash", "usage" to usage)))
            .single() as ConversationItem.Activity
        assertEquals("t", tool.outputMessageId)
        val noCost = conversationItems(listOf(message("a", "assistant", "x", "usage" to usage))).single() as ConversationItem.Bubble
        assertEquals(MessageUsage(1, 2, 3, 4, 10, null), noCost.usage)
        for (invalid in listOf(
            JsonObject(usage + ("cost" to JsonPrimitive(-1))),
            JsonObject(usage + ("cost" to JsonPrimitive("0.1"))),
            JsonObject(usage + ("input" to JsonPrimitive(-1))),
            JsonObject(usage + ("output" to JsonObject(emptyMap()))),
            JsonObject(usage - "totalTokens"),
            JsonPrimitive("usage"),
        )) {
            val bubble = conversationItems(listOf(message("a", "assistant", "x", "usage" to invalid))).single()
                as ConversationItem.Bubble
            assertNull(bubble.usage)
        }
    }

    @Test
    fun errorStateMarksBubbles() {
        val bubble = conversationItems(listOf(message("a", "assistant", "failed", "state" to "error"))).single()
            as ConversationItem.Bubble
        assertTrue(bubble.error)
        assertTrue(isErrorItem(bubble))
    }

    @Test
    fun editDetailsReachThePairedActivity() {
        val result = fixtureMessage("message-edit-details")
        val call = Wire.objectOf("type" to "toolCall", "id" to "toolu_edit_01", "name" to "edit",
            "arguments" to """{"path":"src/a.kt","edits":[{"oldText":"a","newText":"b"}]}""")
        val assistant = message("assistant", "assistant", "", "parts" to JsonArray(listOf(call)), "state" to "complete")
        val activity = conversationItems(listOf(assistant, result)).single() as ConversationItem.Activity
        assertEquals(2, activity.details?.firstChangedLine)
        assertTrue(activity.details!!.patch.startsWith("--- src/a.kt"))
        assertTrue(toolDiff(activity)!!.fromDetails)
        val orphan = conversationItems(listOf(result)).single() as ConversationItem.Activity
        assertEquals(activity.details, orphan.details)

        val details = result.obj("toolDetails")
        fun withDetails(value: JsonElement, vararg extra: Pair<String, JsonElement>) =
            JsonObject(result + ("toolDetails" to value) + extra.toMap())
        for (invalid in listOf(
            withDetails(JsonObject(details + ("kind" to JsonPrimitive("bash")))),
            withDetails(JsonObject(details + ("patch" to JsonPrimitive("")))),
            withDetails(JsonObject(details + ("patch" to JsonPrimitive("x".repeat(16385))))),
            withDetails(JsonObject(details + ("firstChangedLine" to JsonPrimitive(0)))),
            withDetails(JsonObject(details + ("truncated" to JsonPrimitive("yes")))),
            withDetails(JsonPrimitive("patch")),
            withDetails(details, "state" to JsonPrimitive("error")),
            withDetails(details, "toolName" to JsonPrimitive("bash")),
        )) {
            val item = conversationItems(listOf(invalid)).single() as ConversationItem.Activity
            assertNull(item.details)
        }
        val cut = conversationItems(listOf(withDetails(JsonObject(details + ("truncated" to JsonPrimitive(true))))))
            .single() as ConversationItem.Activity
        assertTrue(cut.details!!.truncated)
    }
}
