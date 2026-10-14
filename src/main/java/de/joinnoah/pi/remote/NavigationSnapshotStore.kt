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
        file.read()?.let(NavigationSnapshotCodec::decode).orEmpty()

    override fun save(snapshots: Map<String, NavigationSnapshot>) {
        file.write(NavigationSnapshotCodec.encode(snapshots))
    }
}

/**
 * The stored form of the navigation snapshots: `{"version":1,"hosts":[...]}`, at most
 * [MAX_BYTES]. Readers require version 1 and ignore keys they do not know, so a new key may be
 * added but nothing may be renamed or removed.
 */
internal object NavigationSnapshotCodec {
    const val MAX_BYTES = 4 * 1024 * 1024
    private val PREFIX = """{"version":1,"hosts":[""".toByteArray()
    private val SUFFIX = "]}".toByteArray()
    private val COMMA = ",".toByteArray()

    fun decode(bytes: ByteArray): Map<String, NavigationSnapshot> {
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

    /**
     * Encodes each host once and joins them. Above [MAX_BYTES] it trims, as before, ten messages at
     * a time from the oldest host that has any, then whole hosts, then the last host's lists, but
     * re-encodes only the host it trimmed, and drops as many messages at once as the sizes say.
     */
    fun encode(snapshots: Map<String, NavigationSnapshot>): ByteArray {
        val bounded = snapshots.values.toList().takeLast(8).map {
            it.copy(
                projects = it.projects.take(100),
                sessions = it.sessions.take(300),
                messages = it.messages.takeLast(80),
                projectChats = it.projectChats.take(12),
            )
        }.toMutableList()
        val hosts = bounded.map(::encodeHost).toMutableList()
        fun size() = PREFIX.size + hosts.sumOf { it.size } + (hosts.size - 1).coerceAtLeast(0) + SUFFIX.size
        var size = size()
        while (size > MAX_BYTES) {
            val messages = bounded.indexOfFirst { it.messages.isNotEmpty() }
            if (messages >= 0) {
                val snapshot = bounded[messages]
                bounded[messages] = snapshot.copy(messages = snapshot.messages.drop(dropCount(snapshot.messages, size)))
                hosts[messages] = encodeHost(bounded[messages])
            } else if (bounded.size > 1) {
                bounded.removeAt(0)
                hosts.removeAt(0)
            } else if (bounded.singleOrNull()?.sessions?.isNotEmpty() == true) {
                val snapshot = bounded.single()
                bounded[0] = snapshot.copy(sessions = snapshot.sessions.dropLast(25))
                hosts[0] = encodeHost(bounded[0])
            } else if (bounded.singleOrNull()?.projectChats?.isNotEmpty() == true) {
                val snapshot = bounded.single()
                bounded[0] = snapshot.copy(projectChats = snapshot.projectChats.dropLast(1))
                hosts[0] = encodeHost(bounded[0])
            } else if (bounded.singleOrNull()?.projects?.isNotEmpty() == true) {
                val snapshot = bounded.single()
                bounded[0] = snapshot.copy(projects = snapshot.projects.dropLast(10))
                hosts[0] = encodeHost(bounded[0])
            } else {
                throw IllegalArgumentException("Navigation snapshot exceeds storage limit")
            }
            size = size()
        }
        val encoded = ByteArray(size)
        var at = 0
        fun put(part: ByteArray) {
            part.copyInto(encoded, at)
            at += part.size
        }
        put(PREFIX)
        hosts.forEachIndexed { index, host ->
            if (index > 0) put(COMMA)
            put(host)
        }
        put(SUFFIX)
        return encoded
    }

    private fun encodeHost(snapshot: NavigationSnapshot): ByteArray =
        Wire.objectOf(
            "routeId" to snapshot.routeId,
            "projects" to JsonArray(snapshot.projects),
            "projectId" to snapshot.projectId,
            "sessions" to JsonArray(snapshot.sessions),
            "sessionId" to snapshot.sessionId,
            "messages" to JsonArray(snapshot.messages),
            "projectChats" to JsonArray(snapshot.projectChats),
        ).toString().toByteArray()

    /**
     * How many of the oldest [messages] to drop, in steps of ten, so that [size] fits: each
     * message takes its encoded bytes and a comma. It never drops more than the step-by-step trim
     * would, since the estimate counts one comma too many when all of them go; when it falls short,
     * the next round drops more.
     */
    private fun dropCount(messages: List<JsonObject>, size: Int): Int {
        var saved = 0L
        var dropped = 0
        while (dropped < messages.size) {
            val step = minOf(dropped + 10, messages.size)
            for (index in dropped until step) saved += messages[index].toString().toByteArray().size + 1
            dropped = step
            if (size - saved <= MAX_BYTES) break
        }
        return dropped
    }
}
