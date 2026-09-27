package de.joinnoah.pi.remote

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlinx.serialization.json.*

// ---- Edit diffs ---------------------------------------------------------------------------

internal enum class DiffKind {
    HUNK,
    CONTEXT,
    REMOVED,
    ADDED,
}

internal data class DiffLine(val kind: DiffKind, val text: String, val oldLine: Int?, val newLine: Int?)

internal data class EditChange(val oldText: String, val newText: String)

internal data class EditArguments(val path: String?, val changes: List<EditChange>)

internal data class ToolDiff(
    val lines: List<DiffLine>,
    val fromDetails: Boolean,
    val truncated: Boolean,
    val added: Int,
    val removed: Int,
)

private fun JsonObject.stringField(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun editChange(source: JsonObject): EditChange? {
    val oldText = source.stringField("oldText") ?: source.stringField("old_string") ?: return null
    val newText = source.stringField("newText") ?: source.stringField("new_string") ?: return null
    return EditChange(oldText, newText)
}

/**
 * Reads every edit argument shape pi 0.87 accepts: `edits` as an array, a JSON string holding
 * that array or a single object, and the legacy top-level `oldText`/`newText` pair. Values of the
 * wrong type are skipped.
 */
internal fun editArguments(arguments: String?): EditArguments? {
    val args = arguments?.let(::parsedToolArguments) ?: return null
    val path =
        (args.stringField("path") ?: args.stringField("file_path"))?.takeIf(String::isNotBlank)
    val edits =
        when (val raw = args["edits"]) {
            is JsonPrimitive ->
                if (raw.isString) runCatching { Json.parseToJsonElement(raw.content) }.getOrNull()
                else null
            else -> raw
        }
    val changes = buildList {
        when (edits) {
            is JsonArray -> edits.forEach { (it as? JsonObject)?.let(::editChange)?.let(::add) }
            is JsonObject -> editChange(edits)?.let(::add)
            else -> Unit
        }
        editChange(args)?.let(::add)
    }
    if (path == null && changes.isEmpty()) return null
    return EditArguments(path, changes)
}

private val truncatedPathPattern = Regex("""^\{\s*"(?:path|file_path)"\s*:\s*"((?:[^"\\]|\\.)*)"""")

/**
 * The file a tool call works on. Host-truncated arguments are an invalid JSON prefix, so the path
 * is then recovered from the start of that prefix.
 */
internal fun toolPath(item: ConversationItem.Activity): String? {
    val arguments = item.arguments ?: return null
    val args = parsedToolArguments(arguments)
    val path =
        if (args != null) args.stringField("path") ?: args.stringField("file_path")
        else
            truncatedPathPattern.find(arguments)?.groupValues?.get(1)?.let { capture ->
                runCatching { Json.decodeFromString<String>("\"$capture\"") }.getOrNull()
            }
    return path?.takeIf(String::isNotBlank)
}

private val hunkHeader = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")

/**
 * Parses a unified patch. Everything before the first hunk is skipped, and a hunk cut short by
 * the host's size limit simply ends early.
 */
internal fun patchDiff(patch: String): List<DiffLine> {
    val lines = patch.split("\n").let { if (it.lastOrNull()?.isEmpty() == true) it.dropLast(1) else it }
    return buildList {
        var inHunk = false
        var oldLine = 0
        var newLine = 0
        var oldLeft = 0
        var newLeft = 0
        for (raw in lines) {
            val line = raw.removeSuffix("\r")
            val header = hunkHeader.find(line)
            if (header != null) {
                val (a, b, c, d) = header.destructured
                val oldStart = a.toIntOrNull()
                val newStart = c.toIntOrNull()
                val oldCount = if (b.isEmpty()) 1 else b.toIntOrNull()
                val newCount = if (d.isEmpty()) 1 else d.toIntOrNull()
                if (oldStart == null || newStart == null || oldCount == null || newCount == null) {
                    inHunk = false
                    continue
                }
                inHunk = true
                oldLine = oldStart
                newLine = newStart
                oldLeft = oldCount
                newLeft = newCount
                add(DiffLine(DiffKind.HUNK, line, null, null))
                continue
            }
            if (!inHunk) continue
            if (line.startsWith("\\")) continue
            if (oldLeft <= 0 && newLeft <= 0) {
                inHunk = false
                continue
            }
            when (line.firstOrNull()) {
                '-' -> {
                    add(DiffLine(DiffKind.REMOVED, line.substring(1), oldLine++, null))
                    oldLeft--
                }
                '+' -> {
                    add(DiffLine(DiffKind.ADDED, line.substring(1), null, newLine++))
                    newLeft--
                }
                ' ',
                null -> {
                    add(DiffLine(DiffKind.CONTEXT, line.drop(1), oldLine++, newLine++))
                    oldLeft--
                    newLeft--
                }
                else -> inHunk = false
            }
        }
    }
}

private const val MAX_LCS_CELLS = 250_000L

private fun textLines(text: String): List<String> = if (text.isEmpty()) emptyList() else text.split("\n")

/** A line diff of each change, with a hunk separator between changes; line numbers are unknown. */
internal fun changeDiff(changes: List<EditChange>): List<DiffLine> = buildList {
    for ((index, change) in changes.withIndex()) {
        if (index > 0) add(DiffLine(DiffKind.HUNK, "", null, null))
        val old = textLines(change.oldText)
        val new = textLines(change.newText)
        if (old.size.toLong() * new.size.toLong() > MAX_LCS_CELLS) {
            old.forEach { add(DiffLine(DiffKind.REMOVED, it, null, null)) }
            new.forEach { add(DiffLine(DiffKind.ADDED, it, null, null)) }
            continue
        }
        val width = new.size + 1
        val lcs = IntArray((old.size + 1) * width)
        for (i in old.indices.reversed()) {
            for (j in new.indices.reversed()) {
                lcs[i * width + j] =
                    if (old[i] == new[j]) lcs[(i + 1) * width + j + 1] + 1
                    else maxOf(lcs[(i + 1) * width + j], lcs[i * width + j + 1])
            }
        }
        var i = 0
        var j = 0
        while (i < old.size || j < new.size) {
            when {
                i < old.size && j < new.size && old[i] == new[j] -> {
                    add(DiffLine(DiffKind.CONTEXT, old[i], null, null))
                    i++
                    j++
                }
                j >= new.size || (i < old.size && lcs[(i + 1) * width + j] >= lcs[i * width + j + 1]) ->
                    add(DiffLine(DiffKind.REMOVED, old[i++], null, null))
                else -> add(DiffLine(DiffKind.ADDED, new[j++], null, null))
            }
        }
    }
}

private val toolDiffCache =
    object : LinkedHashMap<Pair<String?, ToolDetails?>, ToolDiff?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String?, ToolDetails?>, ToolDiff?>) =
            size > 64
    }

/** The diff of an edit call: pi's own patch when the host forwarded it, else the arguments. */
internal fun toolDiff(item: ConversationItem.Activity): ToolDiff? {
    if (item.name != "edit") return null
    val key = item.arguments to item.details
    synchronized(toolDiffCache) {
        if (toolDiffCache.containsKey(key)) return toolDiffCache[key]
    }
    val diff = computeToolDiff(item.arguments, item.details, item.argumentsTruncated)
    synchronized(toolDiffCache) { toolDiffCache[key] = diff }
    return diff
}

private fun computeToolDiff(arguments: String?, details: ToolDetails?, argumentsTruncated: Boolean): ToolDiff? {
    fun usable(lines: List<DiffLine>) = lines.any { it.kind != DiffKind.HUNK }
    fun result(lines: List<DiffLine>, fromDetails: Boolean, truncated: Boolean) =
        ToolDiff(
            lines,
            fromDetails,
            truncated,
            lines.count { it.kind == DiffKind.ADDED },
            lines.count { it.kind == DiffKind.REMOVED },
        )
    if (details != null) {
        val lines = patchDiff(details.patch)
        if (usable(lines)) return result(lines, true, details.truncated)
    }
    val changes = editArguments(arguments)?.changes.orEmpty()
    val lines = changeDiff(changes)
    return if (usable(lines)) result(lines, false, argumentsTruncated) else null
}

// ---- Touched files ------------------------------------------------------------------------

internal data class TouchedFile(val path: String, val count: Int, val latestItemId: String)

internal data class TouchedFiles(val changed: List<TouchedFile>, val read: List<TouchedFile>) {
    val total: Int
        get() = changed.size + read.size
}

private class FileUse(var count: Int = 0, var latestIndex: Int = -1, var latestItemId: String = "")

internal fun touchedFiles(items: List<ConversationItem>): TouchedFiles {
    val changed = LinkedHashMap<String, FileUse>()
    val read = LinkedHashMap<String, FileUse>()
    for ((index, item) in items.withIndex()) {
        if (item !is ConversationItem.Activity) continue
        val target =
            when (item.name) {
                "read" -> read
                "edit",
                "write" -> changed
                else -> continue
            }
        val path = toolPath(item) ?: continue
        val use = target.getOrPut(path) { FileUse() }
        use.count++
        use.latestIndex = index
        use.latestItemId = item.id
    }
    fun sorted(uses: Map<String, FileUse>) =
        uses.entries
            .sortedByDescending { it.value.latestIndex }
            .map { TouchedFile(it.key, it.value.count, it.value.latestItemId) }
    return TouchedFiles(sorted(changed), sorted(read.filterKeys { it !in changed }))
}

// ---- Timeline and errors ------------------------------------------------------------------

internal enum class TimelineMarkerKind {
    ERROR,
    QUESTION,
    EDIT,
    PLAN,
}

internal data class TimelineMarker(val itemId: String, val kind: TimelineMarkerKind, val position: Float)

private const val MAX_TIMELINE_MARKERS = 200

internal fun isErrorItem(item: ConversationItem): Boolean =
    when (item) {
        is ConversationItem.Activity -> item.state == "error"
        is ConversationItem.Subagent -> item.agents.any { it.state == "failed" }
        is ConversationItem.Bubble -> item.error
        is ConversationItem.Thinking -> false
    }

internal fun onlyErrors(items: List<ConversationItem>): List<ConversationItem> = items.filter(::isErrorItem)

/** Markers over the visible list, positioned 0..1 by index; only the most recent 200 are kept. */
internal fun timelineMarkers(items: List<ConversationItem>): List<TimelineMarker> {
    val last = items.lastIndex
    val markers = items.mapIndexedNotNull { index, item ->
        val kind =
            when {
                isErrorItem(item) -> TimelineMarkerKind.ERROR
                item !is ConversationItem.Activity -> null
                item.name == "questionnaire" -> TimelineMarkerKind.QUESTION
                item.name == "edit" || item.name == "write" -> TimelineMarkerKind.EDIT
                item.name == "submit_plan" -> TimelineMarkerKind.PLAN
                else -> null
            } ?: return@mapIndexedNotNull null
        TimelineMarker(item.id, kind, if (last <= 0) 0f else index.toFloat() / last)
    }
    return markers.takeLast(MAX_TIMELINE_MARKERS)
}

// ---- Subagent children --------------------------------------------------------------------

internal sealed interface ChildResolution {
    data class Exact(val sessionId: String) : ChildResolution

    data class Candidates(val sessions: List<SessionListItem>) : ChildResolution

    data object None : ChildResolution
}

/** Session rows that parse; a malformed row is dropped instead of failing the whole list. */
internal fun sessionListItems(sessions: List<JsonObject>, connected: Boolean, loading: Boolean): List<SessionListItem> =
    sessions.mapNotNull { runCatching { sessionListItem(it, connected, loading) }.getOrNull() }

private fun SessionListItem.live(): Boolean =
    availability == SessionAvailability.RUNNING || availability == SessionAvailability.WAITING

private fun AgentProgress.active(): Boolean = state == "running" || state == "queued"

private const val MAX_CHILD_CANDIDATES = 8

internal fun resolveSubagentChild(
    item: ConversationItem.Subagent,
    index: Int,
    parentSessionId: String,
    sessions: List<SessionListItem>,
): ChildResolution {
    val agent = item.agents.getOrNull(index) ?: return ChildResolution.None
    // A reported id opens only once the session list knows it; until then the caller refreshes.
    agent.sessionId?.let { id ->
        return if (sessions.any { it.id == id }) ChildResolution.Exact(id) else ChildResolution.None
    }
    val children = sessions.filter { it.parentSessionId == parentSessionId }
    if (children.isEmpty()) return ChildResolution.None
    val task = agent.task?.take(40)?.trim()?.takeIf(String::isNotEmpty)
    val scored =
        children
            .map { child ->
                var score = 0
                if (task != null && child.title.contains(task, ignoreCase = true)) score += 2
                if (child.title.contains(agent.name, ignoreCase = true)) score += 1
                if (child.live() && agent.active()) score += 1
                child to score
            }
            .sortedWith(
                compareByDescending<Pair<SessionListItem, Int>> { it.second }
                    .thenByDescending { it.first.updatedAt ?: Long.MIN_VALUE }
            )
    val top = scored.first()
    if (top.second > 0 && scored.getOrNull(1)?.second != top.second) return ChildResolution.Exact(top.first.id)
    return ChildResolution.Candidates(scored.take(MAX_CHILD_CANDIDATES).map { it.first })
}

internal data class SubagentStripEntry(
    val sessionId: String?,
    val agent: String?,
    val title: String,
    val state: String,
    val activity: String?,
    val abortable: Boolean,
    /** Tappable only when the session list knows [sessionId]; otherwise opening it would fail. */
    val openable: Boolean = sessionId != null,
)

internal data class SubagentStrip(
    val entries: List<SubagentStripEntry>,
    val running: Int,
    val missingSessionIds: Set<String>,
    val unmatchedRunning: Int,
)

private const val STRIP_RECENT_SUBAGENTS = 3
private const val MAX_STRIP_ENTRIES = 16

/**
 * The running subagents of a session: its live child sessions (abortable), enriched with the
 * latest progress, plus running or queued progress agents that have no live child yet.
 */
internal fun subagentStrip(
    items: List<ConversationItem>,
    sessions: List<SessionListItem>,
    parentSessionId: String,
): SubagentStrip {
    val knownIds = sessions.mapTo(HashSet()) { it.id }
    val runningChildren = sessions.filter { it.parentSessionId == parentSessionId && it.live() }
    val runningChildIds = runningChildren.mapTo(HashSet()) { it.id }
    val subagents = items.filterIsInstance<ConversationItem.Subagent>()
    val latestBySession = HashMap<String, AgentProgress>()
    for (item in subagents) for (agent in item.agents) agent.sessionId?.let { latestBySession[it] = agent }

    data class Recent(val agent: AgentProgress, val match: String?)
    val recent =
        subagents.takeLast(STRIP_RECENT_SUBAGENTS).flatMap { item ->
            item.agents.mapIndexedNotNull { index, agent ->
                if (!agent.active()) return@mapIndexedNotNull null
                val match =
                    if (agent.sessionId != null) agent.sessionId.takeIf { it in knownIds }
                    else
                        (resolveSubagentChild(item, index, parentSessionId, sessions) as? ChildResolution.Exact)
                            ?.sessionId
                Recent(agent, match)
            }
        }
    val heuristic = HashMap<String, AgentProgress>()
    recent.forEach { if (it.agent.sessionId == null && it.match != null) heuristic.putIfAbsent(it.match, it.agent) }

    val entries = buildList {
        for (child in runningChildren) {
            val agent = latestBySession[child.id] ?: heuristic[child.id]
            add(
                SubagentStripEntry(
                    sessionId = child.id,
                    agent = agent?.name,
                    title = child.title,
                    state = agent?.state?.takeIf { it == "queued" } ?: "running",
                    activity = if (agent != null) agent.activity ?: agent.preview else child.preview,
                    abortable = true,
                )
            )
        }
        val seen = HashSet<String>()
        for ((agent, match) in recent) {
            if (match != null && match in runningChildIds) continue
            if (agent.sessionId != null && !seen.add(agent.sessionId)) continue
            add(
                SubagentStripEntry(
                    sessionId = agent.sessionId,
                    agent = agent.name,
                    title = agent.task ?: agent.name,
                    state = agent.state,
                    activity = agent.activity ?: agent.preview,
                    abortable = false,
                    openable = agent.sessionId != null && agent.sessionId in knownIds,
                )
            )
        }
    }
    val ordered = entries.sortedBy { if (it.state == "queued") 1 else 0 }.take(MAX_STRIP_ENTRIES)
    return SubagentStrip(
        entries = ordered,
        running = ordered.count { it.state == "running" },
        missingSessionIds = recent.mapNotNullTo(HashSet()) { r -> r.agent.sessionId?.takeIf { it !in knownIds } },
        unmatchedRunning = recent.count { it.agent.state == "running" && it.match == null },
    )
}

// ---- Formatting ---------------------------------------------------------------------------

private fun decimalFormat(pattern: String, locale: Locale) =
    DecimalFormat(pattern, DecimalFormatSymbols.getInstance(locale)).apply {
        roundingMode = RoundingMode.HALF_UP
        isGroupingUsed = false
    }

/** "950", "1.2k", "34k" or "3.4M", with the locale's decimal separator. */
internal fun formatTokenCount(value: Long, locale: Locale): String {
    val count = value.coerceAtLeast(0)
    if (count < 1000) return count.toString()
    fun scaled(amount: Double, suffix: String): String =
        decimalFormat(if (amount < 9.95) "0.#" else "0", locale).format(amount) + suffix
    val thousands = count / 1_000.0
    return if (thousands < 999.5) scaled(thousands, "k") else scaled(count / 1_000_000.0, "M")
}

/** "$0.012" below a dollar (at most 3 significant digits), "$1.25" above. */
internal fun formatCost(value: Double, locale: Locale): String {
    val cost = if (value.isFinite()) value.coerceAtLeast(0.0) else 0.0
    if (cost >= 1.0) return "$" + decimalFormat("0.00", locale).format(cost)
    if (cost == 0.0) return "$0"
    val rounded = BigDecimal.valueOf(cost).round(MathContext(3, RoundingMode.HALF_UP))
    if (rounded >= BigDecimal.ONE) return "$" + decimalFormat("0.00", locale).format(rounded)
    return "$" + decimalFormat("0.#########", locale).format(rounded)
}
