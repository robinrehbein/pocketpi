package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PromptPredictionTest {
    private var next = 0

    private fun user(text: String = "Do it") = Wire.objectOf("id" to "m${next++}", "role" to "user", "text" to text, "state" to "complete")

    private fun call(tool: String, arguments: String = "{}", timestamp: Long? = null): Pair<JsonObject, String> {
        val id = "c${next++}"
        return Wire.objectOf(
            "id" to "m${next++}",
            "role" to "assistant",
            "text" to "",
            "state" to "complete",
            "timestamp" to timestamp,
            "parts" to JsonArray(listOf(Wire.objectOf("type" to "toolCall", "id" to id, "name" to tool, "arguments" to arguments))),
        ) to id
    }

    private fun tool(tool: String, arguments: String = "{}", failed: Boolean = false, timestamp: Long? = null): List<JsonObject> {
        val (assistant, id) = call(tool, arguments, timestamp)
        return listOf(
            assistant,
            Wire.objectOf(
                "id" to "m${next++}",
                "role" to "tool",
                "text" to "output",
                "state" to if (failed) "error" else "complete",
                "toolName" to tool,
                "toolCallId" to id,
            ),
        )
    }

    private fun edit(failed: Boolean = false, timestamp: Long? = null) = tool("edit", failed = failed, timestamp = timestamp)

    private fun test(failed: Boolean = false) = tool("bash", """{"command":"npm test"}""", failed)

    private fun reply() = Wire.objectOf("id" to "m${next++}", "role" to "assistant", "text" to "Done.", "state" to "complete")

    private fun rule(rule: PredictionRule) = PromptPrediction.Rule(rule)

    @Test
    fun freshSessionGetsNoSuggestion() {
        assertNull(predictPrompt(emptyList(), "idle", false))
        assertNull(predictPrompt(emptyList(), "idle", false, frequentPrompts = listOf("Run it")))
    }

    @Test
    fun runningWaitingOrQuestionedSessionsGetNoSuggestion() {
        val messages = listOf(user()) + edit()
        assertNull(predictPrompt(messages, "running", false))
        assertNull(predictPrompt(messages, "waiting", false))
        assertNull(predictPrompt(messages, "idle", true))
    }

    @Test
    fun streamingReplyGetsNoSuggestion() {
        val streaming = Wire.objectOf("id" to "s", "role" to "assistant", "text" to "…", "state" to "streaming")
        assertNull(predictPrompt(listOf(user()) + edit() + streaming, "idle", false))
    }

    @Test
    fun failedToolInTheLatestTurnSuggestsAFix() {
        assertEquals(rule(PredictionRule.FIX_ERROR), predictPrompt(listOf(user()) + tool("bash", """{"command":"ls"}""", failed = true) + reply(), "idle", false))
        assertEquals(rule(PredictionRule.FIX_TESTS), predictPrompt(listOf(user()) + edit() + test(failed = true), "idle", false))
    }

    @Test
    fun failureBeforeTheLastUserMessageIsNotRepeated() {
        val messages = listOf(user()) + tool("bash", failed = true) + user("Ignore that") + reply()
        assertNull(predictPrompt(messages, "idle", false))
    }

    @Test
    fun approvedPlanWithoutEditsSuggestsImplementing() {
        val messages = listOf(user("Plan it"), reply())
        assertEquals(rule(PredictionRule.IMPLEMENT_PLAN), predictPrompt(messages, "idle", false, planApprovedAt = 100))
    }

    @Test
    fun editsAfterTheApprovalMoveOnToTests() {
        val messages = listOf(user()) + edit(timestamp = 200)
        assertEquals(rule(PredictionRule.RUN_TESTS), predictPrompt(messages, "idle", false, planApprovedAt = 100))
        // Edits from before the approval don't count as implementing it.
        val earlier = listOf(user()) + edit(timestamp = 50)
        assertEquals(rule(PredictionRule.IMPLEMENT_PLAN), predictPrompt(earlier, "idle", false, planApprovedAt = 100))
    }

    @Test
    fun editsWithoutATestRunSinceSuggestRunningTests() {
        assertEquals(rule(PredictionRule.RUN_TESTS), predictPrompt(listOf(user()) + edit() + reply(), "idle", false))
        assertEquals(rule(PredictionRule.RUN_TESTS), predictPrompt(listOf(user()) + test() + edit(), "idle", false))
    }

    @Test
    fun failedEditsDoNotCountAsChanges() {
        val messages = listOf(user()) + edit(failed = true) + tool("read") + reply()
        assertNull(predictPrompt(messages, "idle", false))
    }

    @Test
    fun manyEditsAndGreenTestsInTheLatestTurnSuggestAPr() {
        val messages = listOf(user()) + edit() + edit() + edit() + test() + reply()
        assertEquals(rule(PredictionRule.PREPARE_PR), predictPrompt(messages, "idle", false))
    }

    @Test
    fun fewEditsOrAnOlderTestRunDoNotSuggestAPr() {
        assertNull(predictPrompt(listOf(user()) + edit() + test(), "idle", false))
        val older = listOf(user()) + edit() + edit() + edit() + test() + user("Thanks") + reply()
        assertNull(predictPrompt(older, "idle", false))
    }

    @Test
    fun testCommandsAreRecognizedFromTheBashArguments() {
        val gradle = tool("bash", """{"command":"./gradlew testDebugUnitTest"}""", failed = true)
        assertEquals(rule(PredictionRule.FIX_TESTS), predictPrompt(listOf(user()) + gradle, "idle", false))
        val deno = tool("bash", """{"command":"deno task test"}""", failed = true)
        assertEquals(rule(PredictionRule.FIX_TESTS), predictPrompt(listOf(user()) + deno, "idle", false))
        val build = tool("bash", """{"command":"./gradlew assembleDebug"}""", failed = true)
        assertEquals(rule(PredictionRule.FIX_ERROR), predictPrompt(listOf(user()) + build, "idle", false))
    }

    @Test
    fun frequentPromptIsTheFallbackButNotARepeatOfTheLastOne() {
        val messages = listOf(user("Summarize"), reply())
        assertEquals(
            PromptPrediction.History("Check the logs"),
            predictPrompt(messages, "idle", false, frequentPrompts = listOf("Summarize", "Check the logs")),
        )
        assertNull(predictPrompt(messages, "idle", false, frequentPrompts = listOf(" summarize ".trim())))
    }

    @Test
    fun rulesWinOverFrequentPrompts() {
        assertEquals(
            rule(PredictionRule.RUN_TESTS),
            predictPrompt(listOf(user()) + edit(), "idle", false, frequentPrompts = listOf("Something")),
        )
    }

    @Test
    fun historyCountsPromptsPerProjectCaseInsensitively() {
        val history = PromptHistory()
            .record("r/p", "Run the linter", 1)
            .record("r/p", "run  the linter ", 2)
            .record("r/p", "Once only", 3)
            .record("r/q", "Other project", 4)
            .record("r/q", "Other project", 5)
        assertEquals(listOf("Run the linter"), history.frequent("r/p"))
        assertEquals(listOf("Other project"), history.frequent("r/q"))
        assertEquals(emptyList<String>(), history.frequent("r/none"))
    }

    @Test
    fun historyIgnoresCommandsLongAndMultilinePrompts() {
        val history = PromptHistory()
            .record("k", "/compact", 1)
            .record("k", "x".repeat(MAX_PROMPT_LENGTH + 1), 1)
            .record("k", "line one\nline two", 1)
            .record("k", "ok", 1)
        assertEquals(PromptHistory(), history)
    }

    @Test
    fun historyIsCapped() {
        var history = PromptHistory()
        repeat(MAX_PROMPTS_PER_PROJECT + 10) { history = history.record("k", "Prompt number $it", it.toLong()) }
        assertEquals(MAX_PROMPTS_PER_PROJECT, history.projects.getValue("k").size)
        repeat(MAX_PROMPT_PROJECTS + 5) { history = history.record("project $it", "Hello there", 1_000L + it) }
        assertEquals(MAX_PROMPT_PROJECTS, history.projects.size)
        repeat(MAX_PLAN_APPROVALS + 5) { history = history.approvePlan("s$it", it.toLong()) }
        assertEquals(MAX_PLAN_APPROVALS, history.planApprovals.size)
        assertEquals((MAX_PLAN_APPROVALS + 4).toLong(), history.planApprovals["s${MAX_PLAN_APPROVALS + 4}"])
    }

    @Test
    fun forgettingAHostDropsOnlyItsPromptsAndApprovals() {
        val history = PromptHistory()
            .record("r/p", "Run it", 1).record("r2/p", "Run it", 1)
            .approvePlan("r/s", 1).approvePlan("r2/s", 1)
            .approvePlan("rr/s", 1)
        val forgotten = history.forgetRoute("r")
        assertEquals(setOf("r2/p"), forgotten.projects.keys)
        assertEquals(setOf("r2/s", "rr/s"), forgotten.planApprovals.keys)
    }

    @Test
    fun startupKeepsOnlyPairedHosts() {
        val history = PromptHistory().record("r/p", "Run it", 1).record("old/p", "Run it", 1)
            .approvePlan("r/s", 1).approvePlan("old/s", 1)
        val kept = history.onlyRoutes(setOf("r"))
        assertEquals(setOf("r/p"), kept.projects.keys)
        assertEquals(setOf("r/s"), kept.planApprovals.keys)
    }

    @Test
    fun historyRoundTripsThroughJson() {
        val history = PromptHistory().record("r/p", "Run it", 1).record("r/p", "Run it", 2).approvePlan("r/s", 9)
        assertEquals(history, promptHistory(Wire.parse(history.json().toString())))
    }
}
