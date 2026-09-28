package de.joinnoah.pi.remote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

internal const val BACKGROUND_JOBS_CAPABILITY = "session.background_jobs.v1"

/** A watch leases job events for 60 seconds; the open job view renews it this often. */
internal const val JOB_LEASE_RENEW_MILLIS = 30_000L

/** Automatic list refreshes run at most this often. */
internal const val JOBS_AUTO_REFRESH_MILLIS = 3_000L
private const val JOB_OUTPUT_CHUNK_BYTES = 49_152
private const val MAX_JOB_OUTPUT_DATA_CHARS = 65_536
private const val MAX_BACKGROUND_JOBS = 64
private const val MAX_JOB_TEXT_BYTES = 4096

/** Output text the job view keeps; older text turns into a skipped-bytes marker. */
internal const val MAX_JOB_OUTPUT_CHARS = 262_144

enum class JobStatus(val wire: String) {
    RUNNING("running"),
    EXITED("exited"),
    KILLED("killed"),
    TIMEOUT("timeout"),
}

/** One background shell started by pi's `bash_bg`, as `session.jobs.*` reports it. */
data class BackgroundJob(
    val id: String,
    val command: String,
    val cwd: String,
    /** The host cut [command] or [cwd]. */
    val truncated: Boolean,
    val status: JobStatus,
    /** Null while running and when a signal ended the job. */
    val exitCode: Int?,
    val signal: String?,
    val startedAt: Long,
    val endedAt: Long?,
    val outputBytes: Long,
    val stuck: Boolean,
)

/** One `job.watch` result: [bytes] start at the absolute [offset]. */
internal class JobChunk(val job: BackgroundJob, val offset: Long, val bytes: ByteArray)

/** A validated job `host.event`. They arrive only while this device holds a watch lease. */
internal sealed interface JobEvent {
    val sessionId: String

    /** Advisory: the job's output now ends at [offset]; pull it with a watch. */
    data class Output(override val sessionId: String, val jobId: String, val offset: Long, val bytes: Long) :
        JobEvent

    data class Status(override val sessionId: String, val job: BackgroundJob) : JobEvent

    data class Changed(override val sessionId: String, val items: List<BackgroundJob>) : JobEvent
}

private fun jobId(value: String): Boolean =
    value.isNotEmpty() &&
        value.encodeToByteArray().size <= 256 &&
        value.none { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }

internal fun parseBackgroundJob(value: JsonObject): BackgroundJob {
    Wire.keys(
        value,
        setOf("id", "command", "cwd", "status", "startedAt", "outputBytes"),
        setOf("exitCode", "signal", "endedAt", "stuck", "truncated"),
    )
    val id = value.text("id")
    require(jobId(id))
    val command = value.text("command")
    val cwd = value.text("cwd")
    require(command.encodeToByteArray().size <= MAX_JOB_TEXT_BYTES)
    require(cwd.encodeToByteArray().size <= MAX_JOB_TEXT_BYTES)
    val status = value.text("status").let { wire -> JobStatus.entries.first { it.wire == wire } }
    val exitCode =
        if (value["exitCode"] == null || value["exitCode"] is JsonNull) null
        else value.long("exitCode").also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
    val signal = if ("signal" in value) value.text("signal").also { require(it.length in 1..32) } else null
    val startedAt = value.long("startedAt")
    val endedAt = if ("endedAt" in value) value.long("endedAt") else null
    val outputBytes = value.long("outputBytes")
    require(startedAt >= 0 && (endedAt ?: 0) >= 0 && outputBytes >= 0)
    val stuck = "stuck" in value && value.flag("stuck")
    if ("truncated" in value) require(value.flag("truncated"))
    require(status != JobStatus.RUNNING || ("exitCode" !in value && "endedAt" !in value))
    return BackgroundJob(
        id, command, cwd, "truncated" in value, status, exitCode, signal, startedAt, endedAt, outputBytes, stuck,
    )
}

private fun parseJobItems(value: JsonObject): List<BackgroundJob> {
    val items = value.array("items")
    require(items.size <= MAX_BACKGROUND_JOBS)
    return items.map(::parseBackgroundJob)
}

/** Validates a `jobs` result for [sessionId]. */
internal fun parseJobList(data: JsonObject, sessionId: String): List<BackgroundJob> {
    Wire.keys(data, setOf("kind", "sessionId", "items"))
    require(data.text("kind") == "jobs" && data.text("sessionId") == sessionId)
    return parseJobItems(data)
}

/** Validates a `job.watch` result against the watch that asked from [since]. */
internal fun parseJobWatch(data: JsonObject, sessionId: String, jobId: String, since: Long?): JobChunk {
    Wire.keys(data, setOf("kind", "sessionId", "job", "offset", "data"), setOf("skipped"))
    require(data.text("kind") == "job.watch" && data.text("sessionId") == sessionId)
    val job = parseBackgroundJob(data.obj("job"))
    require(job.id == jobId)
    val offset = data.long("offset")
    require(offset >= (since ?: 0L))
    if ("skipped" in data) data.long("skipped").let { require(it in 1..offset) }
    val encoded = data.text("data")
    require(encoded.length <= MAX_JOB_OUTPUT_DATA_CHARS)
    val bytes = Wire.decode(encoded)
    require(bytes.size <= JOB_OUTPUT_CHUNK_BYTES && offset + bytes.size <= job.outputBytes)
    return JobChunk(job, offset, bytes)
}

/** Validates a job `host.event`. Returns null for a kind that is not a job event. */
internal fun jobEvent(payload: JsonObject): JobEvent? =
    when (payload.text("kind")) {
        "session.job.output" -> {
            Wire.keys(payload, setOf("type", "kind", "sessionId", "jobId", "offset", "bytes"))
            val id = payload.text("jobId")
            require(jobId(id))
            val offset = payload.long("offset")
            val bytes = payload.long("bytes")
            require(offset >= 0 && bytes >= 0)
            JobEvent.Output(payload.text("sessionId"), id, offset, bytes)
        }
        "session.job.status" -> {
            Wire.keys(payload, setOf("type", "kind", "sessionId", "job"))
            JobEvent.Status(payload.text("sessionId"), parseBackgroundJob(payload.obj("job")))
        }
        "session.jobs.changed" -> {
            Wire.keys(payload, setOf("type", "kind", "sessionId", "items"))
            JobEvent.Changed(payload.text("sessionId"), parseJobItems(payload))
        }
        else -> null
    }

enum class JobsFailure {
    UNSUPPORTED,
    OFFLINE,
    FAILED,
}

sealed interface JobOutputPart {
    data class Text(val text: String) : JobOutputPart

    /** Output bytes the host no longer kept, or the view dropped to stay small. */
    data class Skipped(val bytes: Long) : JobOutputPart
}

/** The open job of the jobs view. */
data class JobView(
    val jobId: String,
    val job: BackgroundJob?,
    /** The output as display lines, built as the chunks arrive. */
    val lines: List<JobOutputLine> = emptyList(),
    /** The absolute index of `lines[0]`; lines keep their index while older ones are dropped. */
    val firstLine: Long = 0,
    /** The first watch answered. */
    val loaded: Boolean = false,
    /** The host no longer knows the job. */
    val vanished: Boolean = false,
    val failure: JobsFailure? = null,
    val stopping: Boolean = false,
    val stopFailed: Boolean = false,
)

/** Background jobs of the selected session and the jobs view, when open. */
data class JobsState(
    val sessionId: String,
    val jobs: List<BackgroundJob> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    /** This session answered `unsupported`, as RPC-backed sessions do. */
    val unsupported: Boolean = false,
    val failure: JobsFailure? = null,
    val listOpen: Boolean = false,
    val view: JobView? = null,
)

internal fun canUseBackgroundJobs(state: RemoteState): Boolean =
    BACKGROUND_JOBS_CAPABILITY in state.capabilities &&
        BACKGROUND_JOBS_CAPABILITY !in state.unavailableCapabilities &&
        state.session != null &&
        state.session.optionalText("origin") !in setOf("rpc", "history")

/** The selected session's jobs state, or null when the entry should stay hidden. */
internal fun visibleJobs(state: RemoteState): JobsState? =
    state.jobs?.takeIf {
        canUseBackgroundJobs(state) &&
            it.sessionId == state.selection.sessionId &&
            !it.unsupported &&
            it.jobs.isNotEmpty()
    }

private fun JobsState.withJob(job: BackgroundJob): JobsState =
    copy(
        jobs = if (jobs.any { it.id == job.id }) jobs.map { if (it.id == job.id) job else it } else jobs + job,
        view = view?.let { if (it.jobId == job.id) it.copy(job = job) else it },
    )

private fun JobsState.withJobs(items: List<BackgroundJob>): JobsState =
    copy(
        jobs = items,
        loaded = true,
        view = view?.let { open -> items.find { it.id == open.jobId }?.let { open.copy(job = it) } ?: open },
    )

/**
 * Splits [bytes] into the text of every complete UTF-8 sequence and the incomplete sequence at
 * the end, which waits for the next chunk.
 */
internal fun splitUtf8(bytes: ByteArray): Pair<String, ByteArray> {
    var start = bytes.size
    for (back in 1..minOf(3, bytes.size)) {
        val b = bytes[bytes.size - back].toInt() and 0xff
        if (b and 0xc0 == 0x80) continue
        val length = when {
            b and 0xe0 == 0xc0 -> 2
            b and 0xf0 == 0xe0 -> 3
            b and 0xf8 == 0xf0 -> 4
            else -> 1
        }
        if (length > back) start = bytes.size - back
        break
    }
    return String(bytes, 0, start, Charsets.UTF_8) to bytes.copyOfRange(start, bytes.size)
}

sealed interface JobOutputLine {
    data class Text(val text: String) : JobOutputLine

    data class Skipped(val bytes: Long) : JobOutputLine
}

/** The longest escape sequence held back for the next chunk; a longer one shows as text. */
private const val MAX_PENDING_ESCAPE = 4096

/** Long lines without newlines render in items of this many chars. */
internal const val JOB_LINE_CHUNK_CHARS = 2_000

private val ANSI_ESCAPE =
    Regex(
        // CSI; OSC; DCS, SOS, PM and APC strings; two-byte escapes such as ESC ( B, ESC = or ESC 7.
        // A string without its terminator removes no more than the rest of its line.
        "\u001B\\[[0-?]*[ -/]*[@-~]" +
            "|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|\u001B\\][^\u0007\u001B\n]*" +
            "|\u001B[PX^_][^\u001B]*\u001B\\\\|\u001B[PX^_][^\u001B\n]*" +
            "|\u001B[ -/]*[0-~]"
    )

/** An escape sequence the end of a chunk cut off. */
private val INCOMPLETE_ESCAPE =
    Regex("\u001B(?:\\[[0-?]*[ -/]*|\\][^\u0007\u001B]*\u001B?|[PX^_][^\u001B]*\u001B?|[ -/]*)")

/**
 * The rest of a CSI whose start went with skipped bytes; it needs a parameter to count. A bare
 * color code without a semicolon, like the "2m" of "2m30s", counts only before an escape or a line end.
 */
private val CUT_ESCAPE =
    Regex("^(?:\\[[0-?]*[0-9;][0-?]*[ -/]*[A-Za-z]|(?=[0-9;]*;)[0-9;]*[0-9][0-9;]*m|[0-9]+m(?=\u001B|\r|\n|$))")

private fun utf8Length(text: CharSequence, start: Int = 0, end: Int = text.length): Long {
    var bytes = 0L
    var i = start
    while (i < end) {
        val c = text[i]
        bytes += when {
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            c.isHighSurrogate() && i + 1 < end && text[i + 1].isLowSurrogate() -> 4.also { i++ }
            else -> 3
        }
        i++
    }
    return bytes
}

/**
 * Turns job output into display lines as it arrives, processing only the new text: complete lines
 * stay as they are and the open last line grows. Once the text passes [limit] chars (a newline
 * counts as one), the oldest whole lines turn into a skipped marker. A carriage return keeps only
 * the text after it, terminal escapes are removed, and an escape cut at a chunk's end waits for
 * the next chunk.
 */
internal class JobOutputBuffer(private val limit: Int = MAX_JOB_OUTPUT_CHARS) {
    private val complete = ArrayDeque<JobOutputLine>()

    /** The unfinished last line; a carriage return can only be its last char. */
    private var open: StringBuilder? = null
    private var pending = ""
    private var afterGap = false
    private var chars = 0L

    /**
     * [trim] dropped all of the open line, so its newline adds no empty line. The cut keeps
     * [limit] chars of the line, so only a tiny [limit] such as the tests' 1 can reach this.
     */
    private var openCut = false

    /** The absolute index of the first line in [lines]. */
    var firstLine = 0L
        private set

    val lines: List<JobOutputLine>
        get() = open?.let { complete + JobOutputLine.Text(display(it)) } ?: complete.toList()

    fun append(part: JobOutputPart) {
        when (part) {
            is JobOutputPart.Skipped -> skip(part.bytes)
            is JobOutputPart.Text -> text(part.text)
        }
        trim()
    }

    /** Shows an escape still held back as text, without its ESC bytes, once no output follows. */
    fun finish() {
        if (pending.isEmpty()) return
        val rest = pending.replace("\u001B", "")
        pending = ""
        addLines(rest)
        trim()
    }

    private fun display(line: CharSequence): String =
        (if (line.endsWith('\r')) line.subSequence(0, line.length - 1) else line).toString()

    private fun skip(bytes: Long) {
        if (bytes <= 0) return
        open?.let {
            open = null
            chars -= it.length
            addText(display(it))
        }
        pending = ""
        afterGap = true
        val last = complete.lastOrNull()
        if (last is JobOutputLine.Skipped) complete[complete.lastIndex] = JobOutputLine.Skipped(last.bytes + bytes)
        else complete.addLast(JobOutputLine.Skipped(bytes))
    }

    private fun addText(text: String) {
        complete.addLast(JobOutputLine.Text(text))
        chars += text.length + 1
    }

    private fun heldEscape(input: String): Int {
        val last = input.lastIndexOf('\u001B')
        if (last < 0) return -1
        val previous = if (last > 0) input.lastIndexOf('\u001B', last - 1) else -1
        return listOf(previous, last).firstOrNull { start ->
            start >= 0 && input.length - start <= MAX_PENDING_ESCAPE &&
                INCOMPLETE_ESCAPE.matches(input.subSequence(start, input.length))
        } ?: -1
    }

    private fun text(value: String) {
        var input = pending + value
        pending = ""
        if (input.isEmpty()) return
        if (afterGap) {
            input = input.replaceFirst(CUT_ESCAPE, "")
            afterGap = false
        }
        val held = heldEscape(input)
        if (held >= 0) {
            pending = input.substring(held)
            input = input.substring(0, held)
        }
        addLines(ANSI_ESCAPE.replace(input, ""))
    }

    private fun addLines(input: String) {
        var from = 0
        while (true) {
            val end = input.indexOf('\n', from)
            extend(input, from, if (end < 0) input.length else end)
            if (end < 0) break
            val line = open
            open = null
            chars -= line?.length ?: 0
            if (line != null || !openCut) addText(line?.let(::display) ?: "")
            openCut = false
            from = end + 1
        }
    }

    private fun extend(input: String, from: Int, to: Int) {
        if (from >= to) return
        val line = open ?: StringBuilder().also { open = it }
        val before = line.length
        line.append(input, from, to)
        // Only the new text and a carriage return that ended the old text can start the line over.
        var cr = -1
        for (i in line.length - 2 downTo maxOf(0, before - 1)) if (line[i] == '\r') { cr = i; break }
        if (cr >= 0) line.delete(0, cr + 1)
        chars += line.length - before
    }

    private fun trim() {
        if (chars <= limit) return
        var dropped = 0L
        var removed = 0
        while (chars > limit && complete.isNotEmpty()) {
            when (val line = complete.removeFirst()) {
                is JobOutputLine.Skipped -> dropped += line.bytes
                is JobOutputLine.Text -> {
                    dropped += utf8Length(line.text) + 1
                    chars -= line.text.length + 1
                }
            }
            removed++
        }
        val line = open
        if (chars > limit && line != null) {
            var cut = (chars - limit).coerceAtMost(line.length.toLong()).toInt()
            if (cut < line.length && line[cut - 1].isHighSurrogate()) cut++
            dropped += utf8Length(line, 0, cut)
            line.delete(0, cut)
            chars -= cut
            // A lone carriage return or nothing is left: the line is gone, not blank.
            if (display(line).isEmpty()) {
                dropped += utf8Length(line)
                chars -= line.length
                open = null
                openCut = true
            }
        }
        if (dropped <= 0) return
        val next = complete.firstOrNull()
        if (next is JobOutputLine.Skipped) {
            complete[0] = JobOutputLine.Skipped(next.bytes + dropped)
            firstLine += removed
        } else {
            complete.addFirst(JobOutputLine.Skipped(dropped))
            firstLine += removed - 1
        }
    }
}

/** [parts] as display lines, the way the open job view builds them. */
internal fun jobOutputLines(parts: List<JobOutputPart>, limit: Int = MAX_JOB_OUTPUT_CHARS): List<JobOutputLine> =
    JobOutputBuffer(limit).apply { parts.forEach(::append) }.lines

/** The `session.jobs.*` requests. Implementations throw [RemoteRequestException] on errors. */
internal interface JobSource {
    suspend fun listJobs(sessionId: String): List<BackgroundJob>

    suspend fun watchJob(sessionId: String, jobId: String, since: Long?): JobChunk

    suspend fun killJob(sessionId: String, jobId: String)
}

/**
 * Loads [RemoteState.jobs]. The open job is watched again at least every [renewMillis], which
 * keeps the 60-second event lease alive, and whenever an output notice or a reconnect says there
 * is more to pull. Output notices carry no bytes; each pull starts where the last one ended.
 */
internal class BackgroundJobsController(
    private val scope: CoroutineScope,
    private val source: JobSource,
    private val current: () -> RemoteState,
    private val update: ((RemoteState) -> RemoteState) -> Unit,
    private val renewMillis: Long = JOB_LEASE_RENEW_MILLIS,
) {
    private var listJob: Job? = null
    private var listVersion = 0L
    private var watchJob: Job? = null
    private var watchVersion = 0L
    private var wake = Channel<Unit>(Channel.CONFLATED)
    private var nextOffset: Long? = null
    private var tail = ByteArray(0)
    private var buffer = JobOutputBuffer()
    private var autoRefresh: Job? = null
    private var refreshAgain = false

    private fun write(sessionId: String, block: (JobsState) -> JobsState) =
        update { state ->
            val jobs = state.jobs
            if (jobs == null || jobs.sessionId != sessionId || state.selection.sessionId != sessionId) state
            else state.copy(jobs = block(jobs))
        }

    private fun writeView(sessionId: String, jobId: String, block: (JobView) -> JobView) =
        write(sessionId) { jobs -> jobs.copy(view = jobs.view?.let { if (it.jobId == jobId) block(it) else it }) }

    private fun ensure(sessionId: String) =
        update { state ->
            if (state.selection.sessionId != sessionId || state.jobs?.sessionId == sessionId) state
            else state.copy(jobs = JobsState(sessionId))
        }

    private fun failure(e: Exception): JobsFailure =
        when ((e as? RemoteRequestException)?.code) {
            "unsupported" -> JobsFailure.UNSUPPORTED
            "offline" -> JobsFailure.OFFLINE
            else -> if (!current().connected) JobsFailure.OFFLINE else JobsFailure.FAILED
        }

    /** Lists the selected session's jobs and makes an open job view pull again. */
    fun refresh() {
        val state = current()
        val sessionId = state.selection.sessionId ?: return
        if (!canUseBackgroundJobs(state) || !state.connected) return
        ensure(sessionId)
        write(sessionId) { it.copy(loading = true) }
        listJob?.cancel()
        val version = ++listVersion
        listJob = scope.launch {
            try {
                val items = source.listJobs(sessionId)
                if (version != listVersion) return@launch
                write(sessionId) { it.withJobs(items).copy(loading = false, failure = null, unsupported = false) }
            } catch (e: Exception) {
                // A request the repository cancelled, such as after a session change, counts as a failure.
                if (e is CancellationException && !currentCoroutineContext().isActive) throw e
                // A late answer to a replaced request leaves the newer one's state alone.
                if (version != listVersion) return@launch
                val reason = failure(e)
                write(sessionId) {
                    it.copy(
                        loading = false,
                        unsupported = reason == JobsFailure.UNSUPPORTED,
                        failure = reason.takeIf { r -> r != JobsFailure.UNSUPPORTED },
                    )
                }
            }
        }
        // A reconnect can end the lease; the open view watches again right away.
        wake.trySend(Unit)
    }

    /**
     * Refreshes the list now and at most once per [JOBS_AUTO_REFRESH_MILLIS] after that: job
     * events reach only a device that holds a watch lease, so status changes and finished
     * `bash_bg` or `bash_kill` calls are the list's other hints.
     */
    fun refreshSoon() {
        if (autoRefresh?.isActive == true) {
            refreshAgain = true
            return
        }
        refreshAgain = false
        autoRefresh = scope.launch {
            do {
                refreshAgain = false
                refresh()
                delay(JOBS_AUTO_REFRESH_MILLIS)
            } while (refreshAgain)
        }
    }

    /** Stops listing and watching when another session is selected; a requested kill still runs. */
    fun stop() {
        stopWatch()
        listJob?.cancel()
        listJob = null
        autoRefresh?.cancel()
        autoRefresh = null
        refreshAgain = false
    }

    fun openList() {
        val sessionId = current().selection.sessionId ?: return
        ensure(sessionId)
        write(sessionId) { it.copy(listOpen = true) }
        refresh()
    }

    fun closeList() {
        stopWatch()
        current().selection.sessionId?.let { write(it) { jobs -> jobs.copy(listOpen = false, view = null) } }
    }

    private fun stopWatch() {
        watchVersion++
        watchJob?.cancel()
        watchJob = null
    }

    fun closeJob() {
        stopWatch()
        current().selection.sessionId?.let { write(it) { jobs -> jobs.copy(view = null) } }
    }

    fun openJob(jobId: String) {
        val sessionId = current().selection.sessionId ?: return
        val jobs = current().jobs?.takeIf { it.sessionId == sessionId } ?: return
        stopWatch()
        write(sessionId) { it.copy(view = JobView(jobId, jobs.jobs.find { job -> job.id == jobId })) }
        nextOffset = null
        tail = ByteArray(0)
        buffer = JobOutputBuffer()
        val version = watchVersion
        val signal = Channel<Unit>(Channel.CONFLATED)
        wake = signal
        watchJob = scope.launch {
            while (active(sessionId, jobId, version)) {
                if (!pull(sessionId, jobId, version)) return@launch
                withTimeoutOrNull(renewMillis) { signal.receive() }
            }
        }
    }

    private fun active(sessionId: String, jobId: String, version: Long): Boolean {
        val state = current()
        return version == watchVersion && state.selection.sessionId == sessionId &&
            state.jobs?.sessionId == sessionId && state.jobs.view?.jobId == jobId
    }

    /** Pulls every byte up to the job's end. Returns false once there is nothing left to watch. */
    private suspend fun pull(sessionId: String, jobId: String, version: Long): Boolean {
        try {
            while (true) {
                val since = nextOffset
                val chunk = source.watchJob(sessionId, jobId, since)
                if (!active(sessionId, jobId, version)) return false
                val added = mutableListOf<JobOutputPart>()
                val gap = chunk.offset - (since ?: 0L)
                if (gap > 0) {
                    // The rest of a split character is gone with the skipped bytes.
                    if (tail.isNotEmpty()) added += JobOutputPart.Text(String(tail, Charsets.UTF_8))
                    tail = ByteArray(0)
                    added += JobOutputPart.Skipped(gap)
                }
                val (text, rest) = splitUtf8(tail + chunk.bytes)
                tail = rest
                added += JobOutputPart.Text(text)
                val end = chunk.offset + chunk.bytes.size
                nextOffset = end
                val finished = chunk.job.status != JobStatus.RUNNING && end >= chunk.job.outputBytes
                if (finished && tail.isNotEmpty()) {
                    added += JobOutputPart.Text(String(tail, Charsets.UTF_8))
                    tail = ByteArray(0)
                }
                added.forEach(buffer::append)
                if (finished) buffer.finish()
                val lines = buffer.lines
                val firstLine = buffer.firstLine
                write(sessionId) { jobs ->
                    jobs.withJob(chunk.job).let { next ->
                        next.copy(
                            view = next.view?.let {
                                if (it.jobId != jobId) it
                                else it.copy(lines = lines, firstLine = firstLine, loaded = true, failure = null)
                            }
                        )
                    }
                }
                if (finished) return false
                if (chunk.bytes.isEmpty() || end >= chunk.job.outputBytes) return true
            }
        } catch (e: Exception) {
            if (e is CancellationException && !currentCoroutineContext().isActive) throw e
            if (!active(sessionId, jobId, version)) return false
            if ((e as? RemoteRequestException)?.code == "not_found") {
                writeView(sessionId, jobId) { it.copy(vanished = true, stopping = false) }
                return false
            }
            val reason = failure(e)
            writeView(sessionId, jobId) { it.copy(failure = reason) }
            // Offline or not yet re-advertised after a reconnect: the next wake or renewal retries.
            return true
        }
    }

    /** Stops the open job after the user confirmed. */
    fun kill(jobId: String) {
        val sessionId = current().selection.sessionId ?: return
        val view = current().jobs?.takeIf { it.sessionId == sessionId }?.view ?: return
        if (view.jobId != jobId || view.stopping) return
        writeView(sessionId, jobId) { it.copy(stopping = true, stopFailed = false) }
        scope.launch {
            try {
                source.killJob(sessionId, jobId)
                writeView(sessionId, jobId) { it.copy(stopping = false) }
                wake.trySend(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if ((e as? RemoteRequestException)?.code == "not_found")
                    writeView(sessionId, jobId) { it.copy(vanished = true, stopping = false) }
                else writeView(sessionId, jobId) { it.copy(stopping = false, stopFailed = true) }
            }
        }
    }

    fun onEvent(event: JobEvent) {
        if (event.sessionId != current().selection.sessionId) return
        when (event) {
            is JobEvent.Changed -> write(event.sessionId) { it.withJobs(event.items) }
            is JobEvent.Status -> {
                write(event.sessionId) { it.withJob(event.job) }
                // The job ended: pull its last output now instead of at the next renewal.
                if (event.job.status != JobStatus.RUNNING && current().jobs?.view?.jobId == event.job.id)
                    wake.trySend(Unit)
            }
            is JobEvent.Output ->
                if (current().jobs?.view?.jobId == event.jobId && (nextOffset ?: 0L) < event.offset)
                    wake.trySend(Unit)
        }
    }
}

// ---- UI --------------------------------------------------------------------------------------

@Composable
private fun jobStatusLabel(job: BackgroundJob): String =
    when (job.status) {
        JobStatus.RUNNING ->
            stringResource(if (job.stuck) R.string.remote_jobs_status_stuck else R.string.remote_jobs_status_running)
        JobStatus.EXITED ->
            job.exitCode?.let { stringResource(R.string.remote_jobs_status_exited, it) }
                ?: stringResource(R.string.remote_jobs_status_ended)
        JobStatus.KILLED ->
            job.signal?.let { stringResource(R.string.remote_jobs_status_killed_signal, it) }
                ?: stringResource(R.string.remote_jobs_status_killed)
        JobStatus.TIMEOUT -> stringResource(R.string.remote_jobs_status_timeout)
    }

/** A chip for [SessionControls]: shown only when the host and session have background jobs. */
@Composable
internal fun BackgroundJobsChip(jobs: JobsState, colors: ChipColors, onOpen: () -> Unit) {
    val running = jobs.jobs.count { it.status == JobStatus.RUNNING }
    val label =
        if (running > 0) pluralStringResource(R.plurals.remote_jobs_running_count, running, running)
        else stringResource(R.string.remote_jobs_title)
    AssistChip(
        onClick = onOpen,
        shape = androidx.compose.foundation.shape.CircleShape,
        colors = colors,
        border = null,
        leadingIcon = { Icon(Icons.Outlined.Terminal, null, Modifier.size(AssistChipDefaults.IconSize)) },
        label = { Text(label, maxLines = 1) },
        modifier = Modifier.testTag("jobsChip"),
    )
}

internal class JobsActions(
    val onRefresh: () -> Unit,
    val onOpenJob: (String) -> Unit,
    val onCloseJob: () -> Unit,
    val onClose: () -> Unit,
    val onStop: (String) -> Unit,
)

@Composable
private fun JobsTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onBack, Modifier.testTag("jobsBack")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.remote_back))
        }
        Text(
            title,
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

/** The background jobs of the selected session, or the open job over them. */
@Composable
internal fun BackgroundJobsScreen(state: JobsState, actions: JobsActions, modifier: Modifier = Modifier) {
    val view = state.view
    BackHandler { if (view != null) actions.onCloseJob() else actions.onClose() }
    Surface(modifier.fillMaxSize().testTag("jobsScreen")) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (view == null) JobList(state, actions) else key(view.jobId) { JobDetail(view, actions) }
        }
    }
}

@Composable
private fun JobList(state: JobsState, actions: JobsActions) {
    JobsTopBar(stringResource(R.string.remote_jobs_title), actions.onClose) {
        IconButton(onClick = actions.onRefresh, enabled = !state.loading, modifier = Modifier.testTag("jobsRefresh")) {
            Icon(Icons.Default.Refresh, stringResource(R.string.remote_refresh))
        }
    }
    HorizontalDivider()
    when {
        state.jobs.isEmpty() && state.loading && !state.loaded ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        state.jobs.isEmpty() ->
            JobsMessage(
                stringResource(if (state.failure != null) R.string.remote_jobs_failed else R.string.remote_jobs_empty)
            )
        else ->
            LazyColumn(Modifier.fillMaxSize().testTag("jobsList")) {
                if (state.failure != null)
                    item { JobsMessage(stringResource(R.string.remote_jobs_failed), Modifier) }
                itemsIndexed(state.jobs.sortedByDescending { it.startedAt }, key = { _, job -> job.id }) { _, job ->
                    ListItem(
                        headlineContent = {
                            Text(
                                job.command,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            Text(jobStatusLabel(job), color = jobStatusColor(job))
                        },
                        leadingContent = {
                            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                                if (job.status == JobStatus.RUNNING)
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Outlined.Terminal, null)
                            }
                        },
                        modifier =
                            Modifier.clickable(role = Role.Button) { actions.onOpenJob(job.id) }.testTag("job-${job.id}"),
                    )
                    HorizontalDivider()
                }
            }
    }
}

@Composable
private fun jobStatusColor(job: BackgroundJob) =
    when {
        job.status == JobStatus.RUNNING && job.stuck -> folderWarningColor()
        job.status == JobStatus.RUNNING -> chatStatusColor("running", true)
        job.status == JobStatus.EXITED && job.exitCode == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.error
    }

@Composable
private fun JobsMessage(text: String, modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("jobsMessage"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ColumnScope.JobDetail(view: JobView, actions: JobsActions) {
    val job = view.job
    var confirmStop by remember { mutableStateOf(false) }
    JobsTopBar(job?.command ?: stringResource(R.string.remote_jobs_title), actions.onCloseJob) {
        if (job?.status == JobStatus.RUNNING && !view.vanished)
            TextButton(
                onClick = { confirmStop = true },
                enabled = !view.stopping,
                modifier = Modifier.testTag("jobStop"),
            ) {
                Text(stringResource(if (view.stopping) R.string.remote_jobs_stopping else R.string.remote_jobs_stop))
            }
    }
    HorizontalDivider()
    if (job != null)
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(jobStatusLabel(job), style = MaterialTheme.typography.bodyMedium, color = jobStatusColor(job))
            Text(
                job.cwd,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (job.truncated)
                Text(
                    stringResource(R.string.remote_jobs_truncated),
                    Modifier.testTag("jobTruncated"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
    if (view.stopFailed)
        Text(
            stringResource(R.string.remote_jobs_stop_failed),
            Modifier.padding(horizontal = 16.dp).testTag("jobStopFailed"),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    HorizontalDivider()
    val lines = view.lines
    Box(Modifier.weight(1f).fillMaxWidth()) {
        when {
            view.vanished -> JobsMessage(stringResource(R.string.remote_jobs_vanished))
            !view.loaded && view.failure != null ->
                JobsMessage(
                    stringResource(
                        if (view.failure == JobsFailure.OFFLINE) R.string.remote_jobs_offline
                        else R.string.remote_jobs_failed
                    )
                )
            !view.loaded ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            lines.isEmpty() -> JobsMessage(stringResource(R.string.remote_jobs_no_output))
            else -> JobOutput(lines, view.firstLine)
        }
    }
    if (view.loaded && view.failure != null && !view.vanished)
        Text(
            stringResource(
                if (view.failure == JobsFailure.OFFLINE) R.string.remote_jobs_offline else R.string.remote_jobs_failed
            ),
            Modifier.fillMaxWidth().padding(16.dp).testTag("jobFailure"),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    if (confirmStop && job != null)
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.remote_jobs_stop_title)) },
            text = { Text(job.command, fontFamily = FontFamily.Monospace, maxLines = 4, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmStop = false
                        actions.onStop(job.id)
                    },
                    modifier = Modifier.testTag("jobStopConfirm"),
                ) { Text(stringResource(R.string.remote_jobs_stop)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.remote_cancel)) }
            },
        )
}

@Composable
private fun JobOutput(lines: List<JobOutputLine>, firstLine: Long) {
    // One item per line, and per JOB_LINE_CHUNK_CHARS of a long line; keys are absolute line indexes.
    val items = remember(lines, firstLine) {
        buildList {
            lines.forEachIndexed { index, line ->
                val text = (line as? JobOutputLine.Text)?.text.orEmpty()
                var start = 0
                var part = 0
                do {
                    var end = minOf(text.length, start + JOB_LINE_CHUNK_CHARS)
                    if (end < text.length && text[end - 1].isHighSurrogate()) end++
                    add(JobOutputItem("${firstLine + index}:$part", line, start, end))
                    start = end
                    part++
                } while (start < text.length)
            }
        }
    }
    val listState = rememberLazyListState()
    // Follows new output until the user drags away; scrolling back to the end follows again.
    var follow by remember { mutableStateOf(true) }
    var userScroll by remember { mutableStateOf(false) }
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) {
        if (dragged) {
            userScroll = true
            follow = false
        }
    }
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress && userScroll) {
            userScroll = false
            follow = !listState.canScrollForward
        }
    }
    LaunchedEffect(items.size, lines.lastOrNull()) {
        // A large offset shows the end of a last item taller than the screen; half of Int.MAX_VALUE
        // keeps the offset math clear of overflow.
        if (follow && items.isNotEmpty()) listState.scrollToItem(items.lastIndex, Int.MAX_VALUE / 2)
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp).testTag("jobOutput"),
        state = listState,
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(items, key = { it.key }) { item ->
            when (val line = item.line) {
                is JobOutputLine.Text ->
                    Text(
                        line.text.substring(item.start, item.end),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                is JobOutputLine.Skipped ->
                    Text(
                        pluralStringResource(
                            R.plurals.remote_jobs_skipped,
                            line.bytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                            line.bytes,
                        ),
                        Modifier.padding(vertical = 4.dp).testTag("jobSkipped"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
        }
    }
}

private class JobOutputItem(val key: String, val line: JobOutputLine, val start: Int, val end: Int)
