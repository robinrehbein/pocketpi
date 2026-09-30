package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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

    private val chatState = RemoteState(
        connected = true,
        status = "idle",
        capabilities = setOf(CONFIGURATION_CAPABILITY, COMPACT_CAPABILITY, RENAME_CAPABILITY),
        session = Wire.objectOf("id" to "s1", "title" to "T", "origin" to "rpc"),
    )

    private fun settingsJson(vararg overrides: Pair<String, Any?>) =
        Wire.objectOf("autoCompaction" to true, "steeringMode" to "one-at-a-time", "followUpMode" to "all", *overrides)

    private fun configurationWith(settings: JsonElement?) =
        configuration(
            Wire.objectOf(
                "kind" to "configuration",
                "sessionId" to "session",
                "thinkingLevel" to "high",
                "thinkingLevels" to JsonArray(listOf(JsonPrimitive("high"))),
                "models" to JsonArray(emptyList()),
                "modelsTruncated" to false,
                *(if (settings == null) emptyArray() else arrayOf("settings" to settings)),
            ),
            "session",
        )

    @Test
    fun configurationReadsOptionalSessionSettings() {
        assertEquals(SessionSettings(true, "one-at-a-time", "all"), configurationWith(settingsJson()).settings)
        assertNull(configurationWith(null).settings)
        assertNull(configurationWith(JsonNull).settings)
        assertNull(configurationWith(JsonPrimitive("on")).settings)
        assertNull(configurationWith(settingsJson("steeringMode" to "sometimes")).settings)
        assertNull(configurationWith(settingsJson("followUpMode" to 1)).settings)
        assertNull(configurationWith(settingsJson("autoCompaction" to "true")).settings)
        assertNull(configurationWith(settingsJson("autoCompaction" to null)).settings)
        assertNull(configurationWith(Wire.objectOf("autoCompaction" to true)).settings)
        // Invalid settings do not invalidate the rest of the configuration.
        assertEquals("high", configurationWith(settingsJson("steeringMode" to "x")).thinkingLevel)
    }

    @Test
    fun sessionSettingsNeedTheCapabilityAndReportedSettings() {
        val withSettings = chatState.copy(
            capabilities = chatState.capabilities + SETTINGS_CAPABILITY,
            configuration = configurationWith(settingsJson()),
        )
        assertTrue(sessionSettingsAvailable(withSettings))
        assertFalse(sessionSettingsAvailable(withSettings.copy(configuration = configurationWith(null))))
        assertFalse(sessionSettingsAvailable(withSettings.copy(configuration = null)))
        assertFalse(sessionSettingsAvailable(withSettings.copy(capabilities = chatState.capabilities)))
        assertFalse(sessionSettingsAvailable(withSettings.copy(unavailableCapabilities = setOf(SETTINGS_CAPABILITY))))
        assertFalse(sessionSettingsAvailable(withSettings.copy(unavailableCapabilities = setOf(CONFIGURATION_CAPABILITY))))
        assertEquals(
            listOf(LocalCommand.NEW, LocalCommand.COMPACT, LocalCommand.MODEL, LocalCommand.SETTINGS, LocalCommand.NAME),
            availableLocalCommands(withSettings, true, 0),
        )
        assertFalse(LocalCommand.SETTINGS in availableLocalCommands(withSettings.copy(status = "running"), true, 0))
        assertFalse(LocalCommand.SETTINGS in availableLocalCommands(chatState, true, 0))
    }

    @Test
    fun localCommandsFollowTheControlsTheyStandFor() {
        val all = listOf(LocalCommand.NEW, LocalCommand.COMPACT, LocalCommand.MODEL, LocalCommand.NAME)
        assertEquals(all, availableLocalCommands(chatState, true, 0))
        assertFalse(LocalCommand.NEW in availableLocalCommands(chatState, false, 0))
        assertTrue(availableLocalCommands(chatState.copy(status = "running"), true, 0).isEmpty())
        assertTrue(availableLocalCommands(chatState.copy(sending = true), true, 0).isEmpty())
        val bare = chatState.copy(capabilities = emptySet())
        assertEquals(listOf(LocalCommand.NEW), availableLocalCommands(bare, true, 0))
        assertTrue(availableLocalCommands(chatState.copy(loading = true), true, 0).isEmpty())
        assertEquals(
            listOf(LocalCommand.NEW, LocalCommand.MODEL, LocalCommand.NAME),
            availableLocalCommands(chatState.copy(unavailableCapabilities = setOf(COMPACT_CAPABILITY)), true, 0),
        )
        val tuiOffline = chatState.copy(session = Wire.objectOf("id" to "s1", "title" to "T", "origin" to "web"))
        assertFalse(LocalCommand.NAME in availableLocalCommands(tuiOffline, true, 0))
    }

    @Test
    fun localInvocationParsesTheNameAndArgument() {
        val all = availableLocalCommands(chatState, true, 0)
        assertEquals(
            LocalInvocation(LocalCommand.NAME, "My new title"),
            localInvocation(chatState.copy(draft = "/name   My  new title \n"), all),
        )
        assertEquals(LocalInvocation(LocalCommand.NAME, ""), localInvocation(chatState.copy(draft = "/name"), all))
        assertEquals(LocalInvocation(LocalCommand.NEW, ""), localInvocation(chatState.copy(draft = "/new"), all))
        assertEquals(LocalInvocation(LocalCommand.NEW, ""), localInvocation(chatState.copy(draft = "/New "), all))
        assertNull(localInvocation(chatState.copy(draft = "/compact keep the plan"), all))
        assertNull(localInvocation(chatState.copy(draft = "/name " + "x".repeat(4097)), all))
        assertNull(localInvocation(chatState.copy(draft = "/newer"), all))
        assertNull(localInvocation(chatState.copy(draft = "/review"), all))
        assertNull(localInvocation(chatState.copy(draft = "hello /new"), all))
        assertNull(localInvocation(chatState.copy(draft = "/compact"), availableLocalCommands(chatState.copy(status = "running"), true, 0)))
        assertNull(localInvocation(chatState.copy(draft = "/new", quote = MessageQuote("m", "user", "x")), all))
    }

    @Test
    fun localCommandsShadowHostCommandsOfTheSameName() {
        val host = listOf(
            RemoteCommand("compact", "host compact", "extension"),
            RemoteCommand("review", null, "prompt"),
            RemoteCommand("news", null, "skill"),
        )
        val (local, remote) =
            mergeCommandSuggestions(listOf(LocalCommand.NEW, LocalCommand.COMPACT), host, "")
        assertEquals(listOf(LocalCommand.NEW, LocalCommand.COMPACT), local)
        assertEquals(listOf("review", "news"), remote.map { it.name })
        val (filteredLocal, filteredRemote) =
            mergeCommandSuggestions(listOf(LocalCommand.NEW, LocalCommand.COMPACT), host, "NE")
        assertEquals(listOf(LocalCommand.NEW), filteredLocal)
        assertEquals(listOf("news"), filteredRemote.map { it.name })
        assertEquals("/name  x", selectCommandName("/na  x", "name"))
    }
}
