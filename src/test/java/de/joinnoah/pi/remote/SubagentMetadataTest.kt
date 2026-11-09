package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

/** `subagent_metadata.v1` card metadata against the noah fixtures, and its display rules. */
class SubagentMetadataTest {
    private val fixture = Wire.parse(javaClass.getResource("/subagent-metadata-v1.json")!!.readText(), Int.MAX_VALUE)

    private fun cases(key: String) = fixture.getValue(key).jsonArray.map { it.jsonObject }

    private fun card(message: JsonObject): ConversationItem.Subagent? =
        conversationItems(listOf(message)).filterIsInstance<ConversationItem.Subagent>().singleOrNull()

    private fun agentOf(payload: JsonObject): AgentProgress? = card(payload.getValue("message").jsonObject)?.agents?.single()

    private fun metadata(json: String): AgentMetadata? = parseAgentMetadata(Wire.parse("""{"m":$json}""").getValue("m"))

    @Test fun theFixtureNamesTheCapability() {
        assertEquals(SUBAGENT_METADATA_CAPABILITY, fixture.text("capability"))
    }

    @Test fun validWireCardsKeepExactlyTheKnownValues() {
        val agents = cases("wireValid").associate { it.text("name") to agentOf(it.getValue("payload").jsonObject) }
        assertEquals(AgentMetadata(run = 1), agents.getValue("omitted-stats-run-1")?.metadata)
        assertEquals(
            AgentMetadata(run = 2, turns = 0, durationMs = 0, toolUses = 0, tokens = MetadataTokens(0, null, null, null), costUsd = 0.0),
            agents.getValue("known-zero-run-2")?.metadata,
        )
        assertEquals(
            AgentMetadata(
                run = 2,
                model = MetadataModel("p", "selected", "Selected"),
                responseModel = MetadataModel("p", "served", null),
                thinkingLevel = "max",
                requestedModel = "alias",
                requestedThinkingLevel = "high",
                maxTurns = 1,
                tokens = MetadataTokens(null, null, 3, null),
            ),
            agents.getValue("selected-and-response-models")?.metadata,
        )
        assertEquals("é".repeat(128), agents.getValue("utf8-256")?.metadata?.requestedModel)
        // The activity fields beside it survive as before.
        val omitted = agents.getValue("omitted-stats-run-1")!!
        assertEquals("inst-1", omitted.instanceId)
        assertEquals("call-sub", omitted.spawnToolCallId)
        assertTrue(omitted.needsInput)
    }

    @Test fun invalidMetadataDropsTheAgentAsTheHostRejectsTheMessage() {
        val invalid = cases("wireInvalid")
        assertEquals(14, invalid.size)
        for (case in invalid) {
            val payload = case.getValue("payload").jsonObject
            assertNull(case.text("name"), parseAgentMetadata(payload.agentJson().getValue("metadata")))
            assertNull(case.text("name"), card(payload.getValue("message").jsonObject))
        }
    }

    @Test fun theLegacyProjectionIsTheSameCardWithoutMetadata() {
        for (case in cases("legacyProjection")) {
            val input = agentOf(case.getValue("input").jsonObject)!!
            val output = agentOf(case.getValue("output").jsonObject)!!
            assertNotNull(input.metadata)
            assertNull(output.metadata)
            assertEquals(input.copy(metadata = null), output)
        }
    }

    @Test fun anOldHostCardWithoutMetadataIsUnchanged() {
        val payload = cases("wireValid").first().getValue("payload").jsonObject
        val agent = agentOf(payload.withoutMetadata())!!
        assertNull(agent.metadata)
        assertEquals("scout", agent.name)
        assertEquals("running", agent.state)
    }

    @Test fun strictBoundsBeyondTheFixture() {
        assertNull(metadata("""{"run":"1"}"""))
        assertNull(metadata("""{"run":1.5}"""))
        assertNull(metadata("""{"run":1,"turns":null}"""))
        assertNull(metadata("""{"run":1,"tokens":{"input":null}}"""))
        assertNull(metadata("""{"run":1,"model":{"provider":"p","id":"i","name":null}}"""))
        assertNull(metadata("""{"run":1,"model":{"provider":"p"}}"""))
        assertNull(metadata("""{"run":1,"model":{"provider":" ","id":"i"}}"""))
        assertNull(metadata("""{"run":1,"requestedModel":"   "}"""))
        assertNull(metadata("""{"run":1,"costUsd":-0.01}"""))
        assertNull(metadata("""{"run":1,"costUsd":"0"}"""))
        assertNull(metadata("""{"run":1,"maxTurns":-1}"""))
        assertNull(metadata("""{"run":1,"requestedThinkingLevel":"OFF"}"""))
        assertNull(metadata("""[1]"""))
        assertEquals(AgentMetadata(run = 9_007_199_254_740_991), metadata("""{"run":9007199254740991}"""))
        assertEquals(1_000_000.0, metadata("""{"run":0,"costUsd":1000000}""")?.costUsd)
        assertEquals(AgentMetadata(run = 0, turns = 0), metadata("""{"run":-0,"turns":-0}"""))
        assertEquals("off", metadata("""{"run":0,"thinkingLevel":"off"}""")?.thinkingLevel)
    }

    @Test fun aDuplicatedInstanceKeepsNoMetadata() {
        val payload = cases("wireValid").first().getValue("payload").jsonObject
        val message = payload.getValue("message").jsonObject
        val progress = message.getValue("subagentProgress").jsonObject
        val agent = progress.getValue("agents").jsonArray.single()
        val doubled = JsonObject(message + ("subagentProgress" to JsonObject(progress + ("mode" to JsonPrimitive("parallel")) + ("agents" to JsonArray(listOf(agent, agent))))))
        val agents = card(doubled)!!.agents
        assertEquals(2, agents.size)
        assertTrue(agents.all { it.metadata == null && it.instanceId == null })
    }

    @Test fun missingValuesStayHiddenAndKnownZerosShow() {
        val empty = agentMetadataDisplay(AgentMetadata(run = 0))
        assertFalse(empty.hasModelLine)
        assertFalse(empty.hasStatsLine)
        assertNull(empty.costUsd)
        assertNull(empty.maxTurns)
        assertNull(empty.thinkingLevel)
        assertNull(empty.totalTokens)
        assertNull(empty.tokenParts)
        val zeros = agentMetadataDisplay(AgentMetadata(run = 2, turns = 0, durationMs = 0, toolUses = 0, costUsd = 0.0, thinkingLevel = "off"))
        assertEquals(0L, zeros.turns)
        assertEquals(0L, zeros.durationMs)
        assertEquals(0L, zeros.toolUses)
        assertEquals(0.0, zeros.costUsd!!, 0.0)
        assertEquals("off", zeros.thinkingLevel)
        assertEquals(2L, zeros.resume)
        assertNull(agentMetadataDisplay(AgentMetadata(run = 0, turns = 3)).resume)
    }

    @Test fun onlyACompleteTokenSetReadsAsATotal() {
        val complete = agentMetadataDisplay(AgentMetadata(run = 0, tokens = MetadataTokens(1, 2, 3, 4)))
        assertEquals(10L, complete.totalTokens)
        assertNull(complete.tokenParts)
        val partial = agentMetadataDisplay(AgentMetadata(run = 0, tokens = MetadataTokens(null, null, 3, null)))
        assertNull(partial.totalTokens)
        assertEquals(MetadataTokens(null, null, 3, null), partial.tokenParts)
    }

    @Test fun selectedAndAnsweringModelsAndRequestsAreDistinct() {
        val selected = MetadataModel("p", "selected", "Selected")
        val same = agentMetadataDisplay(AgentMetadata(run = 0, model = selected, responseModel = MetadataModel("p", "selected", "Other name")))
        assertEquals("Selected", same.model)
        assertNull(same.responseModel)
        val routed = agentMetadataDisplay(AgentMetadata(run = 0, model = selected, responseModel = MetadataModel("p", "served", null)))
        assertEquals("served", routed.responseModel)
        val answeredOnly = agentMetadataDisplay(AgentMetadata(run = 0, responseModel = MetadataModel("p", "served", null)))
        assertNull(answeredOnly.model)
        assertEquals("served", answeredOnly.responseModel)
        val levels = agentMetadataDisplay(AgentMetadata(run = 0, thinkingLevel = "max", requestedThinkingLevel = "high", requestedModel = "alias"))
        assertEquals("max", levels.thinkingLevel)
        assertEquals("high", levels.requestedThinkingLevel)
        assertEquals("alias", levels.requestedModel)
        assertNull(agentMetadataDisplay(AgentMetadata(run = 0, thinkingLevel = "high", requestedThinkingLevel = "high")).requestedThinkingLevel)
        // A request without a known effective level is still the request, never the effective level.
        val requestOnly = agentMetadataDisplay(AgentMetadata(run = 0, requestedThinkingLevel = "low"))
        assertNull(requestOnly.thinkingLevel)
        assertEquals("low", requestOnly.requestedThinkingLevel)
    }

    @Test fun optInStringsAreReadAsTheHostReadsThem() {
        val optIn = fixture.getValue("optIn").jsonObject
        for (id in optIn.getValue("granted").jsonArray.map { (it as JsonPrimitive).content }) assertTrue(id, subagentMetadataOptedIn(id))
        for (id in optIn.getValue("refused").jsonArray.map { (it as JsonPrimitive).content }) assertFalse(id, subagentMetadataOptedIn(id))
    }

    @Test fun modelsSharingALabelShowProviderAndId() {
        val display = agentMetadataDisplay(AgentMetadata(run = 0, model = MetadataModel("p1", "x", "X"), responseModel = MetadataModel("p2", "x", "X")))
        assertEquals("p1/x", display.model)
        assertEquals("p2/x", display.responseModel)
    }

    @Test fun onlyMetadataWithTurnsTokensOrCostReplacesTheLegacyUsage() {
        assertFalse(agentMetadataDisplay(AgentMetadata(run = 1)).coversUsage)
        assertFalse(agentMetadataDisplay(AgentMetadata(run = 1, durationMs = 5, toolUses = 2)).coversUsage)
        assertTrue(agentMetadataDisplay(AgentMetadata(run = 1, turns = 0)).coversUsage)
        assertTrue(agentMetadataDisplay(AgentMetadata(run = 1, costUsd = 0.0)).coversUsage)
        assertTrue(agentMetadataDisplay(AgentMetadata(run = 1, tokens = MetadataTokens(null, 1, null, null))).coversUsage)
    }

    @Test fun aLimitWithoutTurnsIsStillOnlyALimit() {
        val display = agentMetadataDisplay(AgentMetadata(run = 0, maxTurns = 10))
        assertNull(display.turns)
        assertEquals(10L, display.maxTurns)
        assertTrue(display.hasStatsLine)
    }

    private fun JsonObject.agentJson(): JsonObject =
        getValue("message").jsonObject.getValue("subagentProgress").jsonObject.getValue("agents").jsonArray.single().jsonObject

    private fun JsonObject.withoutMetadata(): JsonObject {
        val message = getValue("message").jsonObject
        val progress = message.getValue("subagentProgress").jsonObject
        val agent = JsonObject(agentJson() - "metadata")
        return JsonObject(this + ("message" to JsonObject(message + ("subagentProgress" to JsonObject(progress + ("agents" to JsonArray(listOf(agent))))))))
    }
}
