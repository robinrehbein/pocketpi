package de.joinnoah.pi.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class TaskStateTest {
    private fun parse(value: String) = parseSessionTasks(Json.parseToJsonElement(value))

    @Test fun validAndEmpty() {
        assertEquals(SessionTasks(freshness = TaskFreshness.LIVE), parse("[]"))
        assertEquals(
            SessionTasks(listOf(SessionTask(1, "Build", TaskStatus.IN_PROGRESS, "Building")), TaskFreshness.LIVE),
            parse("""[{"id":1,"text":"Build","status":"in_progress","activeForm":"Building"}]"""),
        )
    }

    @Test fun expectedSnapshotFixture() {
        val fixture = checkNotNull(javaClass.getResource("/tasks/session-snapshot.json")).readText()
        val snapshot = Json.parseToJsonElement(fixture).jsonObject
        assertEquals(TaskFreshness.LIVE, parseSessionTasks(snapshot["tasks"]).freshness)
        assertEquals(listOf("Write tests", "Review"), parseSessionTasks(snapshot["tasks"]).items.map { it.text })
    }

    @Test fun malformedIsNotEmpty() {
        val unavailable = SessionTasks(freshness = TaskFreshness.UNAVAILABLE)
        assertEquals(unavailable, parseSessionTasks(null))
        for (value in listOf(
            "null", "{}", "[{}]", """[{"id":0,"text":"x","status":"pending"}]""",
            """[{"id":9007199254740992,"text":"x","status":"pending"}]""",
            """[{"id":1,"text":"","status":"pending"}]""",
            """[{"id":1,"text":"x","status":"bad"}]""",
            """[{"id":1,"text":"x","status":"pending","activeForm":null}]""",
            """[{"id":1,"text":"x","status":"pending"},{"id":1,"text":"x","status":"pending"}]""",
            "[{\"id\":1,\"text\":\"${"é".repeat(257)}\",\"status\":\"pending\"}]",
        )) assertEquals(value, unavailable, parse(value))
        val oversized = "[" + List(64) {
            "{\"id\":${it + 1},\"text\":\"${"x".repeat(512)}\",\"status\":\"pending\",\"activeForm\":\"${"y".repeat(512)}\"}"
        }.joinToString(",") + "]"
        assertEquals(unavailable, parse(oversized))
        assertEquals(unavailable, parse("[" + List(65) { "{\"id\":${it + 1},\"text\":\"x\",\"status\":\"pending\"}" }.joinToString(",") + "]"))
    }

    @Test fun stalePreservesKnownTasks() {
        val live = parse("""[{"id":1,"text":"x","status":"completed"}]""")
        assertEquals(live.copy(freshness = TaskFreshness.STALE), staleTasks(live))
        assertEquals(SessionTasks(), staleTasks(SessionTasks()))
    }
}
