package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class SessionControlsTest {
    @Test
    fun contextUsageAcceptsUnknownOccupancyAndChecksSession() {
        val data = Wire.objectOf(
            "kind" to "context",
            "sessionId" to "session",
            "model" to Wire.objectOf("provider" to "p", "id" to "m"),
            "usedTokens" to JsonPrimitive(50000),
            "contextWindow" to 200000,
            "percent" to JsonPrimitive(25.0),
        )
        assertEquals(50000L, contextUsage(data, "session").usedTokens)
        assertEquals(25.0, contextUsage(data, "session").percent!!, 0.01)
        val unknown = kotlinx.serialization.json.JsonObject(data +
            ("usedTokens" to kotlinx.serialization.json.JsonNull) +
            ("percent" to kotlinx.serialization.json.JsonNull))
        assertNull(contextUsage(unknown, "session").usedTokens)
        assertNull(contextUsage(unknown, "session").percent)
        assertThrows(IllegalArgumentException::class.java) { contextUsage(data, "other") }
    }
    @Test
    fun configurationControlsRequireAdvertisedAndAvailableCapability() {
        assertFalse(configurationControlsAvailable(emptySet(), emptySet()))
        assertTrue(configurationControlsAvailable(setOf(CONFIGURATION_CAPABILITY), emptySet()))
        assertFalse(
            configurationControlsAvailable(
                setOf(CONFIGURATION_CAPABILITY),
                setOf(CONFIGURATION_CAPABILITY),
            )
        )
    }

    @Test
    fun configurationControlsUnavailableNoticeAppearsAfterConnectionSettles() {
        assertTrue(configurationControlsNoticeVisible(RemoteState(connected = true)))
        assertTrue(
            configurationControlsNoticeVisible(
                RemoteState(
                    connected = true,
                    capabilities = setOf(CONFIGURATION_CAPABILITY),
                    unavailableCapabilities = setOf(CONFIGURATION_CAPABILITY),
                )
            )
        )
        assertFalse(configurationControlsNoticeVisible(RemoteState(loading = true, connected = true)))
        assertFalse(configurationControlsNoticeVisible(RemoteState(connected = false)))
        assertFalse(
            configurationControlsNoticeVisible(
                RemoteState(connected = true, capabilities = setOf(CONFIGURATION_CAPABILITY))
            )
        )
    }

    @Test
    fun thinkingControlRequiresASelectedLevelAndAvailableLevels() {
        val model = RemoteModel("provider", "id", "Model", emptySet())
        assertFalse(thinkingControlAvailable(null))
        assertFalse(
            thinkingControlAvailable(SessionConfiguration(model, "", listOf("high"), emptyList(), false))
        )
        assertFalse(
            thinkingControlAvailable(SessionConfiguration(model, "high", emptyList(), emptyList(), false))
        )
        assertTrue(
            thinkingControlAvailable(
                SessionConfiguration(model, "high", listOf("high"), emptyList(), false)
            )
        )
    }

    @Test
    fun configurationDropsEmptyThinkingLevels() {
        val configuration =
            configuration(
                Wire.objectOf(
                    "kind" to "configuration",
                    "sessionId" to "session",
                    "thinkingLevel" to "high",
                    "thinkingLevels" to
                        JsonArray(listOf(JsonPrimitive(""), JsonPrimitive("high"), JsonPrimitive(" "))),
                    "models" to JsonArray(emptyList()),
                    "modelsTruncated" to false,
                ),
                "session",
            )

        assertEquals(listOf("high"), configuration.thinkingLevels)
    }

    @Test
    fun commandSelectionPreservesArgumentsAndWhitespace() {
        val command = RemoteCommand("review", null, "skill")
        assertEquals(
            "/review  file.kt\n  detail",
            selectCommand("/rev  file.kt\n  detail", command),
        )
        assertEquals("review", commandName("/review  file.kt"))
        assertNull(commandName("normal text /review"))
    }

    @Test
    fun commandReceiptAdvancesTimelineWithoutSnapshot() {
        val timeline = Timeline("session")
        assertFalse(
            timeline.event(
                Wire.objectOf(
                    "type" to "event",
                    "sessionId" to "session",
                    "revision" to 0,
                    "kind" to "command.status",
                    "requestId" to "request",
                    "status" to "accepted",
                )
            )
        )
        assertEquals(0L, timeline.revision)
        assertFalse(timeline.needsSnapshot)
    }

    private fun contextFixture(): kotlinx.serialization.json.JsonObject =
        Wire.json.parseToJsonElement(javaClass.getResource("/insights-v1.json")!!.readText())
            .let { it as kotlinx.serialization.json.JsonObject }
            .getValue("valid").let { it as JsonArray }
            .map { it as kotlinx.serialization.json.JsonObject }
            .single { it.text("name") == "context-totals" }
            .obj("payload").obj("data")

    @Test
    fun contextTotalsAreParsedFromTheSharedFixture() {
        val data = contextFixture()
        val usage = contextUsage(data, data.text("sessionId"))
        assertEquals(
            SessionUsageTotals(120000, 9000, 800000, 30000, 959000, 1.2345),
            usage.totals,
        )
        assertEquals(50000L, usage.usedTokens)
    }

    @Test
    fun invalidTotalsAreDroppedWithoutFailingTheResult() {
        val data = contextFixture()
        val sessionId = data.text("sessionId")
        val totals = data.obj("totals")
        val broken = listOf<kotlinx.serialization.json.JsonElement>(
            kotlinx.serialization.json.JsonObject(totals - "cacheRead"),
            kotlinx.serialization.json.JsonObject(totals - "cost"),
            kotlinx.serialization.json.JsonObject(totals + ("output" to JsonPrimitive(-1))),
            kotlinx.serialization.json.JsonObject(totals + ("input" to JsonPrimitive("12"))),
            kotlinx.serialization.json.JsonObject(totals + ("cost" to JsonPrimitive(Double.NaN))),
            kotlinx.serialization.json.JsonObject(totals + ("cost" to JsonPrimitive(-0.5))),
            kotlinx.serialization.json.JsonObject(totals + ("cost" to Wire.objectOf("total" to 1.0))),
            kotlinx.serialization.json.JsonObject(totals + ("totalTokens" to JsonPrimitive(1.5))),
            JsonPrimitive("totals"),
            JsonArray(emptyList()),
            kotlinx.serialization.json.JsonNull,
        )
        for (value in broken) {
            val usage = contextUsage(kotlinx.serialization.json.JsonObject(data + ("totals" to value)), sessionId)
            assertNull(value.toString(), usage.totals)
            assertEquals(200000L, usage.contextWindow)
        }
    }

    @Test
    fun contextWithoutTotalsKeepsTheOldShape() {
        val data = contextFixture()
        val usage = contextUsage(kotlinx.serialization.json.JsonObject(data - "totals"), data.text("sessionId"))
        assertNull(usage.totals)
        assertEquals(
            SessionContextUsage(data.text("sessionId"), "anthropic", "model-a", 50000, 200000, 25.0),
            usage,
        )
    }
}
