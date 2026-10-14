package de.joinnoah.pi.remote

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
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

/**
 * The diff of an edit call: pi's own patch when the host forwarded it, else the arguments. The
 * cache serves the edit cards on screen; the chat's line counts use a [TouchedLineCache].
 */
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

/** The file one tool item reads or changes, as [touchedFiles] counts it. */
internal class FileTouch(val path: String, val changed: Boolean, val itemId: String)

internal fun fileTouch(item: ConversationItem): FileTouch? {
    if (item !is ConversationItem.Activity) return null
    val changed =
        when (item.name) {
            "read" -> false
            // A failed edit or write changed nothing.
            "edit",
            "write" -> if (item.state == "error") return null else true
            else -> return null
        }
    val path = toolPath(item) ?: return null
    return FileTouch(path, changed, item.id)
}

internal fun touchedFiles(items: List<ConversationItem>): TouchedFiles =
    groupTouchedFiles { visit -> items.forEachIndexed { index, item -> fileTouch(item)?.let { visit(index, it) } } }

/**
 * Groups the touches that [each] visits, in item order with each one's index in the item list:
 * changed files, then files only read, the most recently used first.
 */
internal fun groupTouchedFiles(each: (visit: (index: Int, touch: FileTouch) -> Unit) -> Unit): TouchedFiles {
    val changed = LinkedHashMap<String, FileUse>()
    val read = LinkedHashMap<String, FileUse>()
    each { index, touch ->
        val use = (if (touch.changed) changed else read).getOrPut(touch.path) { FileUse() }
        use.count++
        use.latestIndex = index
        use.latestItemId = touch.itemId
    }
    fun sorted(uses: Map<String, FileUse>) =
        uses.entries
            .sortedByDescending { it.value.latestIndex }
            .map { TouchedFile(it.key, it.value.count, it.value.latestItemId) }
    return TouchedFiles(sorted(changed), sorted(read.filterKeys { it !in changed }))
}

/** Lines added and removed by the chat's edits, from the same diffs the edit cards show. */
internal data class TouchedLineCounts(val added: Int, val removed: Int)

/**
 * Sums the diffs of the edits that did not fail; null when no edit has a diff. A `write` call
 * carries no old text, so it counts as a touched file but not as lines.
 */
internal fun touchedLineCounts(items: List<ConversationItem>): TouchedLineCounts? =
    sumTouchedLines(items, { computeToolDiff(it.arguments, it.details, it.argumentsTruncated)?.lineCount() }) {}

private fun ToolDiff.lineCount() = TouchedLineCounts(added, removed)

private inline fun sumTouchedLines(
    items: List<ConversationItem>,
    count: (ConversationItem.Activity) -> TouchedLineCounts?,
    checkpoint: () -> Unit,
): TouchedLineCounts? {
    var added = 0
    var removed = 0
    var any = false
    for (item in items) {
        checkpoint()
        if (item !is ConversationItem.Activity || item.state == "error" || item.name != "edit") continue
        val lines = count(item) ?: continue
        added += lines.added
        removed += lines.removed
        any = true
    }
    return if (any) TouchedLineCounts(added, removed) else null
}

/**
 * [touchedLineCounts] for one chat, which keeps each edit's line count by item ID. A streamed delta
 * then diffs only the edits it added or changed, however many the chat holds; the counts of edits
 * no longer in the chat are dropped on the next pass.
 */
internal class TouchedLineCache {
    private class Entry(
        val arguments: String?,
        val details: ToolDetails?,
        val argumentsTruncated: Boolean,
        val lines: TouchedLineCounts?,
    ) {
        fun matches(item: ConversationItem.Activity) =
            arguments == item.arguments && details == item.details && argumentsTruncated == item.argumentsTruncated
    }

    private val mutex = Mutex()
    private var entries = HashMap<String, Entry>()

    /** Diffs computed since this cache was made; a cache hit computes none. */
    @Volatile
    var computed = 0
        private set

    /** Checks for cancellation before each item and yields after each diff it computes. */
    suspend fun counts(items: List<ConversationItem>): TouchedLineCounts? = mutex.withLock {
        val previous = entries
        val next = HashMap<String, Entry>()
        val context = currentCoroutineContext()
        var fresh = false
        var done = false
        try {
            sumTouchedLines(
                items,
                { item ->
                    val entry =
                        previous[item.id]?.takeIf { it.matches(item) }
                            ?: Entry(
                                item.arguments,
                                item.details,
                                item.argumentsTruncated,
                                computeToolDiff(item.arguments, item.details, item.argumentsTruncated)?.lineCount(),
                            ).also {
                                computed++
                                fresh = true
                            }
                    next[item.id] = entry
                    entry.lines
                },
            ) {
                context.ensureActive()
                if (fresh) {
                    fresh = false
                    yield()
                }
            }.also { done = true }
        } finally {
            // A cancelled pass keeps what it diffed; only a complete one forgets edits that left.
            entries = if (done) next else previous.apply { putAll(next) }
        }
    }
}

/** Git-style "+12" and "−4" (a real minus sign) for the touched-files summary. */
internal fun addedLinesText(added: Int): String = "+$added"

internal fun removedLinesText(removed: Int): String = "\u2212$removed"

/** Where a tap on the touched-files summary goes. */
internal enum class TouchedFilesTarget {
    CHANGES,
    SHEET,
}

/**
 * The host's changes view when it offers one and the chat changed a file; otherwise the list of
 * files the chat touched, so a chat that only read files never lands on an empty changes view.
 */
internal fun touchedFilesTarget(changesAvailable: Boolean, filesChanged: Boolean): TouchedFilesTarget =
    if (changesAvailable && filesChanged) TouchedFilesTarget.CHANGES else TouchedFilesTarget.SHEET

// ---- Timeline and errors ------------------------------------------------------------------

internal enum class TimelineMarkerKind {
    /** One of the user's own messages. */
    USER,
    ERROR,
    QUESTION,
    EDIT,
    PLAN,
}

/** A marked item; [index] is its position in the item list the markers were built from. */
internal data class TimelineMarker(val itemId: String, val kind: TimelineMarkerKind, val index: Int)

internal fun isErrorItem(item: ConversationItem): Boolean =
    when (item) {
        is ConversationItem.Activity -> item.state == "error"
        is ConversationItem.Subagent -> item.agents.any { it.state == "failed" }
        is ConversationItem.Bubble -> item.error
        is ConversationItem.Thinking -> false
    }

internal fun onlyErrors(items: List<ConversationItem>): List<ConversationItem> = items.filter(::isErrorItem)

/** What a marker or a history entry calls [item], or null when it is not worth finding again. */
internal fun timelineMarkerKind(item: ConversationItem): TimelineMarkerKind? =
    when {
        isErrorItem(item) -> TimelineMarkerKind.ERROR
        item is ConversationItem.Bubble -> if (item.role == "user") TimelineMarkerKind.USER else null
        item !is ConversationItem.Activity -> null
        item.name == "questionnaire" -> TimelineMarkerKind.QUESTION
        item.name == "edit" || item.name == "write" -> TimelineMarkerKind.EDIT
        item.name == "submit_plan" -> TimelineMarkerKind.PLAN
        else -> null
    }

/** Markers over the visible list, by index. The scrollbar thins them to what its track can show. */
internal fun timelineMarkers(items: List<ConversationItem>): List<TimelineMarker> =
    items.mapIndexedNotNull { index, item ->
        timelineMarkerKind(item)?.let { TimelineMarker(item.id, it, index) }
    }

// ---- Chat history -------------------------------------------------------------------------

/** One row of the chat history: a marked item with a one-line text, when it has one. */
internal data class ChatHistoryEntry(
    val itemId: String,
    val kind: TimelineMarkerKind,
    val text: String?,
    val timestamp: Long? = null,
)

/** Longer texts are cut; a history row shows one line anyway. */
private const val HISTORY_TEXT_CHARS = 160

private val WHITESPACE = Regex("\\s+")

/** [text] on one line: whitespace runs collapse to one space, blank text is null. */
internal fun historyLine(text: String?): String? =
    // Cut first: a long message is not worth collapsing in full for one line.
    text?.trim()?.take(HISTORY_TEXT_CHARS * 2)?.replace(WHITESPACE, " ")?.take(HISTORY_TEXT_CHARS)?.trim()
        ?.takeIf(String::isNotEmpty)

/**
 * Every marked item in conversation order, with no cap: the history is a lazy list. Unlike the
 * scrollbar's markers it carries text, so the user finds a place by what was said there.
 */
internal fun chatHistory(items: List<ConversationItem>): List<ChatHistoryEntry> =
    items.mapNotNull { item ->
        val kind = timelineMarkerKind(item) ?: return@mapNotNull null
        ChatHistoryEntry(
            item.id,
            kind,
            historyText(item, kind),
            (item as? ConversationItem.Bubble)?.timestamp,
        )
    }

/**
 * The history entry to show first: the last one at or before [currentId] in [order], the item at
 * the top of the chat's viewport; 0 when there is none.
 */
internal fun historyAnchor(history: List<ChatHistoryEntry>, order: List<String>, currentId: String?): Int {
    val current = currentId?.let(order::indexOf)?.takeIf { it >= 0 } ?: return 0
    val positions = order.withIndex().associate { it.value to it.index }
    return history.indexOfLast { (positions[it.itemId] ?: Int.MAX_VALUE) <= current }.coerceAtLeast(0)
}

private fun historyText(item: ConversationItem, kind: TimelineMarkerKind): String? =
    when (item) {
        is ConversationItem.Bubble -> historyLine(item.text)
        is ConversationItem.Subagent ->
            historyLine(item.agents.filter { it.state == "failed" }.joinToString(", ") { it.name })
        is ConversationItem.Thinking -> null
        is ConversationItem.Activity ->
            when (kind) {
                TimelineMarkerKind.QUESTION -> historyLine(questionnaireTitle(item.arguments))
                TimelineMarkerKind.PLAN -> historyLine(planTitle(item.arguments))
                TimelineMarkerKind.EDIT -> historyLine(item.summary.detail?.let(::fileName))
                TimelineMarkerKind.ERROR ->
                    historyLine(
                        listOfNotNull(item.name, item.summary.detail ?: item.output?.lineSequence()?.firstOrNull { it.isNotBlank() })
                            .joinToString(": ")
                    )
                TimelineMarkerKind.USER -> null
            }
    }

private fun fileName(path: String): String = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }

private fun JsonObject.firstText(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key ->
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)
    }

/** The first question's prompt of a questionnaire call, or its title. */
private fun questionnaireTitle(arguments: String?): String? {
    val args = arguments?.let(::parsedToolArguments) ?: return null
    val first = (args["questions"] as? JsonArray)?.firstOrNull() as? JsonObject
    return first?.firstText("prompt", "question", "label") ?: args.firstText("title", "prompt", "question")
}

/** The first non-blank line of a submitted plan, without its heading marks. */
private fun planTitle(arguments: String?): String? {
    val plan = arguments?.let(::parsedToolArguments)?.firstText("plan") ?: return null
    return plan.lineSequence().map { it.trim().trimStart('#').trim() }.firstOrNull(String::isNotEmpty)
}

// ---- Subagent children --------------------------------------------------------------------

internal sealed interface ChildResolution {
    data class Exact(val sessionId: String) : ChildResolution

    data class Candidates(val sessions: List<SessionListItem>) : ChildResolution

    data object None : ChildResolution
}

/** Session rows that parse; a malformed row is dropped instead of failing the whole list. */
internal fun sessionListItems(
    sessions: List<JsonObject>,
    connected: Boolean,
    loading: Boolean,
    stale: Boolean = false,
): List<SessionListItem> =
    sessions.mapNotNull { runCatching { sessionListItem(it, connected, loading, stale) }.getOrNull() }

internal fun SessionListItem.live(): Boolean =
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

/**
 * The subagents of a session, including finished children and terminal progress. Live child
 * sessions remain authoritative for running/abortable state; an idle child without terminal
 * progress is simply ended, not assumed successful. Only recent active progress contributes
 * unresolved running agents, so historical progress cannot resurrect a stale running entry.
 */
internal fun subagentStrip(
    items: List<ConversationItem>,
    sessions: List<SessionListItem>,
    parentSessionId: String,
): SubagentStrip {
    val knownIds = sessions.mapTo(HashSet()) { it.id }
    val children = sessions.filter { it.parentSessionId == parentSessionId }
    val childIds = children.mapTo(HashSet()) { it.id }
    val subagents = items.filterIsInstance<ConversationItem.Subagent>()
    val latestBySession = HashMap<String, AgentProgress>()
    for (item in subagents) for (agent in item.agents) agent.sessionId?.let { latestBySession[it] = agent }

    data class Recent(val agent: AgentProgress, val match: String?)
    val recent =
        subagents.flatMapIndexed { itemIndex, item ->
            item.agents.mapIndexedNotNull { index, agent ->
                if (agent.active() && itemIndex < subagents.size - STRIP_RECENT_SUBAGENTS)
                    return@mapIndexedNotNull null
                if (agent.sessionId != null) {
                    if (latestBySession[agent.sessionId] != agent) return@mapIndexedNotNull null
                    // A known session under a different parent must never leak into this overview.
                    if (agent.sessionId in knownIds && agent.sessionId !in childIds) return@mapIndexedNotNull null
                }
                val match =
                    if (agent.sessionId != null) agent.sessionId.takeIf { it in childIds }
                    else
                        (resolveSubagentChild(item, index, parentSessionId, children) as? ChildResolution.Exact)
                            ?.sessionId
                Recent(agent, match)
            }
        }
    val heuristic = HashMap<String, Recent>()
    recent.asReversed().forEach {
        if (it.agent.sessionId == null && it.match != null) heuristic.putIfAbsent(it.match, it)
    }

    val entries = buildList {
        for (child in children) {
            val agent = latestBySession[child.id] ?: heuristic[child.id]?.agent
            add(
                SubagentStripEntry(
                    sessionId = child.id,
                    agent = agent?.name,
                    title = child.title,
                    state =
                        when {
                            child.live() -> agent?.state?.takeIf { it == "queued" } ?: "running"
                            child.availability == SessionAvailability.OFFLINE -> agent?.state ?: "offline"
                            else -> agent?.state?.takeUnless { agent.active() } ?: "ended"
                        },
                    activity = if (agent != null) agent.activity ?: agent.preview else child.preview,
                    abortable = child.live(),
                )
            )
        }
        val seen = HashSet<String>()
        for (record in recent.asReversed()) {
            val (agent, match) = record
            // Matched active progress is represented by the child's authoritative state. Retain
            // distinct id-less terminal tasks, consuming only the record used to enrich the child.
            if (match != null && match in childIds &&
                (agent.active() || agent.sessionId != null ||
                    (latestBySession[match] == null && heuristic[match] === record))) continue
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
    val ordered = entries.sortedBy {
        when (it.state) {
            "running" -> 0
            "queued" -> 1
            else -> 2
        }
    }
    return SubagentStrip(
        entries = ordered,
        running = ordered.count { it.state == "running" },
        missingSessionIds = recent.mapNotNullTo(HashSet()) { r -> r.agent.sessionId?.takeIf { it !in knownIds } },
        unmatchedRunning = recent.count {
            it.agent.state == "running" && it.match == null && it.agent.sessionId !in childIds
        },
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
