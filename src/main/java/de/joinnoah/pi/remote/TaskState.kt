package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

enum class TaskStatus { PENDING, IN_PROGRESS, COMPLETED }
data class SessionTask(val id: Long, val text: String, val status: TaskStatus, val activeForm: String? = null)
enum class TaskFreshness { UNKNOWN, LIVE, STALE, UNAVAILABLE }
data class SessionTasks(val items: List<SessionTask> = emptyList(), val freshness: TaskFreshness = TaskFreshness.UNKNOWN)

internal const val SESSION_TASKS_CAPABILITY = "session.tasks.v1"

internal fun staleTasks(tasks: SessionTasks): SessionTasks =
    if (tasks.freshness == TaskFreshness.LIVE) tasks.copy(freshness = TaskFreshness.STALE) else tasks

/** [tasks] current again: a resume replay showed that nothing changed them. */
internal fun liveTasks(tasks: SessionTasks): SessionTasks =
    if (tasks.freshness == TaskFreshness.STALE) tasks.copy(freshness = TaskFreshness.LIVE) else tasks

internal fun parseSessionTasks(value: kotlinx.serialization.json.JsonElement?): SessionTasks {
    val unavailable = SessionTasks(freshness = TaskFreshness.UNAVAILABLE)
    val array = value as? JsonArray ?: return unavailable
    if (array.size > 64 || array.toString().encodeToByteArray().size > 65536) return unavailable
    val ids = mutableSetOf<Long>()
    val tasks = array.map { element ->
        val task = element as? JsonObject ?: return unavailable
        if (task.keys.any { it !in setOf("id", "text", "status", "activeForm") }) return unavailable
        val idValue = task["id"] as? JsonPrimitive ?: return unavailable
        val id = idValue.takeIf { !it.isString }?.longOrNull ?: return unavailable
        if (id <= 0 || id > 9007199254740991L || !ids.add(id)) return unavailable
        fun text(key: String): String? = (task[key] as? JsonPrimitive)
            ?.takeIf { it.isString && it.content.isNotEmpty() && it.content.encodeToByteArray().size <= 512 }
            ?.content
        val text = text("text") ?: return unavailable
        val activeForm = if ("activeForm" in task) text("activeForm") ?: return unavailable else null
        val status = when (text("status")) {
            "pending" -> TaskStatus.PENDING
            "in_progress" -> TaskStatus.IN_PROGRESS
            "completed" -> TaskStatus.COMPLETED
            else -> return unavailable
        }
        SessionTask(id, text, status, activeForm)
    }
    return SessionTasks(tasks, TaskFreshness.LIVE)
}
