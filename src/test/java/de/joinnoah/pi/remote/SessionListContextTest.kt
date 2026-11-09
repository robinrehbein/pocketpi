package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionListContextTest {
    private val fixture: JsonObject by lazy {
        Wire.json.parseToJsonElement(javaClass.getResource("/session-list-context-v1.json")!!.readText()).jsonObject
    }

    private fun entries(group: String) = fixture.getValue(group).jsonArray.map { it.jsonObject }

    private fun name(entry: JsonObject) = entry.getValue("name").jsonPrimitive.content

    private fun payload(entry: JsonObject) = entry.getValue("payload").jsonObject

    private fun items(payload: JsonObject) =
        payload.getValue("data").jsonObject.getValue("items").jsonArray.map { it.jsonObject }

    private fun listResults(group: String) = entries(group).filter {
        val data = payload(it)["data"] as? JsonObject
        payload(it)["type"] == JsonPrimitive("result") && data?.get("kind") == JsonPrimitive("sessions")
    }

    private fun validItem(name: String) = items(payload(entries("wireValid").single { name(it) == name })).single()

    private fun usage(percent: Double?, window: Long = 200_000, used: Long? = percent?.let { (it * window / 100).toLong() }) =
        SessionContextUsage("s", null, null, used, window, percent)

    @Test
    fun fixturePinsTheCapability() {
        assertEquals(SESSION_LIST_CONTEXT_CAPABILITY, fixture.getValue("capability").jsonPrimitive.content)
        assertEquals("session.list.context.v1", SESSION_LIST_CONTEXT_CAPABILITY)
    }

    @Test
    fun everyValidListItemParses() {
        val results = listResults("wireValid")
        assertEquals(4, results.size)
        results.flatMap { items(payload(it)) }.forEach { parseSessionListContext(it["context"], it.text("id")) }
    }

    @Test
    fun validItemsReadAsTheHostMeantThem() {
        assertEquals(SessionContextUsage("chat", null, null, 5000, 200_000, 2.5), sessionListContext(validItem("row-with-context")))
        val compacted = checkNotNull(sessionListContext(validItem("row-after-compaction")))
        assertNull(compacted.usedTokens)
        assertNull(compacted.percent)
        assertNull(contextFill(compacted))
        val over = checkNotNull(sessionListContext(validItem("row-over-window")))
        assertTrue(checkNotNull(over.usedTokens) > over.contextWindow)
        assertTrue(checkNotNull(over.percent) > 100)
        assertEquals(1f, checkNotNull(contextFill(over)).fraction)
        assertEquals(ContextFillLevel.CRITICAL, contextFill(over)?.level)
        // Absent means unknown, never zero.
        assertNull(sessionListContext(validItem("row-without-context")))
    }

    @Test
    fun everyInvalidListItemIsRefused() {
        val results = listResults("wireInvalid")
        assertTrue(results.size >= 10)
        results.forEach { entry ->
            val item = items(payload(entry)).single()
            assertThrows(name(entry), IllegalArgumentException::class.java) {
                parseSessionListContext(item["context"], item.text("id"))
            }
            // A row shows a malformed fill as unknown rather than failing.
            assertNull(name(entry), sessionListContext(item))
        }
    }

    @Test
    fun requestsMatchTheFixtureAndNeverSendAFalseFlag() {
        fun fields(compaction: Boolean, context: Boolean) =
            Wire.objectOf("type" to "sessions.list", *sessionListFields("project", compaction, context))
        entries("wireValid").map(::payload).filter { it.text("type") == "sessions.list" }.also { assertEquals(2, it.size) }
            .forEach { request ->
                assertEquals(JsonObject(request - "requestId"), fields(request.containsKey("includeCompaction"), true))
            }
        val sendable = listOf(false, true).flatMap { a -> listOf(false, true).map { b -> fields(a, b) } }
        entries("wireInvalid").map(::payload).filter { it["type"] != JsonPrimitive("result") }.also { assertEquals(2, it.size) }
            .forEach { request -> assertFalse(JsonObject(request - "requestId") in sendable) }
        assertFalse(fields(compaction = true, context = false).containsKey("includeContext"))
    }

    @Test
    fun fillLevelsFollowTheContextOffer() {
        assertNull(contextFill(null))
        assertNull(contextFill(usage(null)))
        assertEquals(ContextFill(0, 0f, ContextFillLevel.NORMAL), contextFill(usage(0.0)))
        assertEquals(ContextFillLevel.NORMAL, contextFill(usage(74.9))?.level)
        assertEquals(ContextFillLevel.WARNING, contextFill(usage(75.0))?.level)
        assertEquals(ContextFillLevel.WARNING, contextFill(usage(89.9))?.level)
        assertEquals(ContextFillLevel.CRITICAL, contextFill(usage(90.0))?.level)
        assertEquals(ContextFill(64, (64.4 / 100).toFloat(), ContextFillLevel.NORMAL), contextFill(usage(64.4)))
        assertEquals(ContextFill(130, 1f, ContextFillLevel.CRITICAL), contextFill(usage(130.0)))
    }

    @Test
    fun largeWindowsWarnFromTheTokenRule() {
        // 210k of 300k (70%) passes the 200k-token rule, like the chat's offer.
        assertEquals(ContextFillLevel.WARNING, contextFill(usage(70.0, window = 300_000, used = 210_000))?.level)
        // Below the 50% floor a large window stays neutral.
        assertEquals(ContextFillLevel.NORMAL, contextFill(usage(45.0, window = 1_000_000, used = 450_000))?.level)
        // A 200k window keeps the plain 75% rule.
        assertEquals(ContextFillLevel.NORMAL, contextFill(usage(70.0, window = 200_000, used = 140_000))?.level)
    }

    @Test
    fun offlineRowsCarryNoFill() {
        val session = validItem("row-with-context")
        assertEquals(2.5, sessionListItem(session, connected = true, loading = false, confirmed = true).context?.percent)
        assertNull(sessionListItem(session, connected = false, loading = false, confirmed = true).context)
        assertNull(sessionListItem(JsonObject(session + ("status" to JsonPrimitive("offline"))), connected = true, loading = false, confirmed = true).context)
    }
}
