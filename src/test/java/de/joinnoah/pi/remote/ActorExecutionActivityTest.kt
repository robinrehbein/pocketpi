package de.joinnoah.pi.remote

import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Test

class ActorExecutionActivityTest {
    private fun message(spawn: JsonElement, state: String) = buildJsonObject {
        put("role", "tool")
        put("toolCallId", "1")
        putJsonObject("subagentProgress") {
            putJsonArray("agents") {
                addJsonObject {
                    put("spawnToolCallId", spawn)
                    put("instanceId", "inst-1")
                    put("state", state)
                }
            }
        }
    }

    @Test
    fun actorKeysNamedRequiresStringSpawnToolCallId() {
        for (bad in listOf(JsonPrimitive(1), JsonPrimitive(true), JsonNull)) {
            assertEquals(emptySet<ActivityActorKey>(), actorKeysNamed("s", message(bad, "succeeded")))
        }
        val expected = setOf(ActivityActorKey("s", "1", "inst-1"))
        assertEquals(expected, actorKeysNamed("s", message(JsonPrimitive("1"), "succeeded")))
        assertEquals(expected, actorKeysNamed("s", message(JsonPrimitive("1"), "queued")))
    }
}
