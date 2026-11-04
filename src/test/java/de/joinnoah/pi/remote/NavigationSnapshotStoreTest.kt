package de.joinnoah.pi.remote

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NavigationSnapshotStoreTest {
    private fun message(id: String, text: String) =
        Wire.objectOf("id" to id, "role" to "assistant", "text" to text, "state" to "complete")

    private fun snapshot(routeId: String, messages: List<JsonObject> = listOf(message("m", "Answer"))) =
        NavigationSnapshot(
            routeId = routeId,
            projects = listOf(Wire.objectOf("id" to "project", "name" to "Project")),
            projectId = "project",
            sessions = listOf(Wire.objectOf("id" to "session", "status" to "idle")),
            sessionId = "session",
            messages = messages,
            projectChats = listOf(
                Wire.objectOf("projectId" to "project", "projectName" to "Project", "session" to Wire.objectOf("id" to "session")),
            ),
        )

    /** The reader of the releases before this one, kept verbatim: it requires version 1. */
    private fun releasedRead(bytes: ByteArray): Map<String, NavigationSnapshot> {
        val root = Wire.json.parseToJsonElement(Wire.utf8(bytes)).jsonObject
        require(root["version"]?.jsonPrimitive?.int == 1)
        return root.getValue("hosts").jsonArray.associate { element ->
            val host = element.jsonObject
            val routeId = host.text("routeId")
            require(routeId.isNotBlank() && routeId.length <= 256)
            routeId to NavigationSnapshot(
                routeId = routeId,
                projects = host.getValue("projects").jsonArray.map { it.jsonObject },
                projectId = host.optionalText("projectId"),
                sessions = host.getValue("sessions").jsonArray.map { it.jsonObject },
                sessionId = host.optionalText("sessionId"),
                messages = host.getValue("messages").jsonArray.map { it.jsonObject },
                projectChats = (host["projectChats"] as? JsonArray)
                    ?.take(12)?.mapNotNull { it as? JsonObject }.orEmpty(),
            )
        }
    }

    /** The writer of the releases before this one, kept verbatim: it re-encoded every host per trim. */
    private fun releasedWrite(snapshots: Map<String, NavigationSnapshot>): ByteArray {
        val bounded = snapshots.values.toList().takeLast(8).map {
            it.copy(
                projects = it.projects.take(100),
                sessions = it.sessions.take(300),
                messages = it.messages.takeLast(80),
                projectChats = it.projectChats.take(12),
            )
        }.toMutableList()
        fun encode(): ByteArray {
            val hosts = bounded.map { snapshot ->
                Wire.objectOf(
                    "routeId" to snapshot.routeId,
                    "projects" to JsonArray(snapshot.projects),
                    "projectId" to snapshot.projectId,
                    "sessions" to JsonArray(snapshot.sessions),
                    "sessionId" to snapshot.sessionId,
                    "messages" to JsonArray(snapshot.messages),
                    "projectChats" to JsonArray(snapshot.projectChats),
                )
            }
            return Wire.objectOf("version" to 1, "hosts" to JsonArray(hosts)).toString().toByteArray()
        }
        var encoded = encode()
        while (encoded.size > 4 * 1024 * 1024) {
            val messages = bounded.indexOfFirst { it.messages.isNotEmpty() }
            if (messages >= 0) {
                val snapshot = bounded[messages]
                bounded[messages] = snapshot.copy(messages = snapshot.messages.drop(10))
            } else if (bounded.size > 1) {
                bounded.removeAt(0)
            } else if (bounded.singleOrNull()?.sessions?.isNotEmpty() == true) {
                val snapshot = bounded.single()
                bounded[0] = snapshot.copy(sessions = snapshot.sessions.dropLast(25))
            } else if (bounded.singleOrNull()?.projectChats?.isNotEmpty() == true) {
                val snapshot = bounded.single()
                bounded[0] = snapshot.copy(projectChats = snapshot.projectChats.dropLast(1))
            } else if (bounded.singleOrNull()?.projects?.isNotEmpty() == true) {
                val snapshot = bounded.single()
                bounded[0] = snapshot.copy(projects = snapshot.projects.dropLast(10))
            } else {
                throw IllegalArgumentException("Navigation snapshot exceeds storage limit")
            }
            encoded = encode()
        }
        return encoded
    }

    @Test
    fun aFileTheCurrentReleaseWroteStillLoads() {
        val stored = linkedMapOf("a" to snapshot("a"), "b" to snapshot("b"))
        val file = releasedWrite(stored)

        assertEquals(stored, NavigationSnapshotCodec.decode(file))
        // An older file without project chats loads too.
        val older =
            """{"version":1,"hosts":[{"routeId":"a","projects":[{"id":"project"}],"projectId":null,""" +
                """"sessions":[],"sessionId":null,"messages":[]}]}"""
        assertEquals(
            mapOf("a" to NavigationSnapshot("a", listOf(Wire.objectOf("id" to "project")))),
            NavigationSnapshotCodec.decode(older.toByteArray()),
        )
    }

    @Test
    fun aFileThisReleaseWritesLoadsInTheReleasedReader() {
        val stored = linkedMapOf("a" to snapshot("a"), "b" to snapshot("b", emptyList()))
        val file = NavigationSnapshotCodec.encode(stored)

        assertEquals(1, Wire.json.parseToJsonElement(file.decodeToString()).jsonObject.long("version"))
        assertEquals(stored, releasedRead(file))
        // Byte for byte what the released writer wrote.
        assertArrayEquals(releasedWrite(stored), file)
        assertArrayEquals(releasedWrite(emptyMap()), NavigationSnapshotCodec.encode(emptyMap()))
    }

    @Test
    fun anOversizedSetIsTrimmedAsTheReleasedWriterTrimmedIt() {
        // Ten hosts of 80 messages of about 8 KB: about 6.4 MB before the cap of eight hosts.
        val stored = linkedMapOf<String, NavigationSnapshot>()
        for (host in 0 until 10) {
            stored["host$host"] = snapshot(
                "host$host",
                (0 until 80).map { message("m$it", "ä".repeat(2_000) + "x".repeat(4_000 + host * 10 + it)) },
            )
        }
        val file = NavigationSnapshotCodec.encode(stored)

        assertTrue(file.size <= NavigationSnapshotCodec.MAX_BYTES)
        assertArrayEquals(releasedWrite(stored), file)
        assertEquals(8, releasedRead(file).size)
    }

    @Test
    fun aSingleHostOverTheCapLosesItsMessagesFirst() {
        val huge = "y".repeat(200_000)
        val stored = mapOf(
            "a" to snapshot("a", (0 until 30).map { message("m$it", huge) }),
        )
        val file = NavigationSnapshotCodec.encode(stored)

        assertArrayEquals(releasedWrite(stored), file)
        val loaded = releasedRead(file).getValue("a")
        assertEquals(20, loaded.messages.size)
        assertEquals("m10", loaded.messages.first().text("id"))
    }
}
