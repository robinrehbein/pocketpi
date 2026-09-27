package de.joinnoah.pi.remote

import android.content.Context
import kotlinx.serialization.json.*

data class NavigationSnapshot(
    val routeId: String,
    val projects: List<JsonObject>,
    val projectId: String? = null,
    val sessions: List<JsonObject> = emptyList(),
    val sessionId: String? = null,
    val messages: List<JsonObject> = emptyList(),
    val projectChats: List<JsonObject> = emptyList(),
)

interface NavigationSnapshotStorage {
    fun load(): Map<String, NavigationSnapshot>

    fun save(snapshots: Map<String, NavigationSnapshot>)
}

internal class NavigationSnapshotStore(context: Context) : NavigationSnapshotStorage {
    private val file = EncryptedFileStore(context, "navigation-snapshots.enc", "pi-remote-navigation-v1")

    override fun load(): Map<String, NavigationSnapshot> =
        file.read()?.let { bytes ->
            val root = Wire.json.parseToJsonElement(Wire.utf8(bytes)).jsonObject
            require(root["version"]?.jsonPrimitive?.int == 1)
            root.getValue("hosts").jsonArray.associate { element ->
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
        }.orEmpty()

    override fun save(snapshots: Map<String, NavigationSnapshot>) {
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
        file.write(encoded)
    }
}
