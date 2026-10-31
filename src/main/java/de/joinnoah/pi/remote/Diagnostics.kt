package de.joinnoah.pi.remote

import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A process-wide record of how connections, requests and recoveries went, for the Diagnostics page
 * and macrobenchmark trace sections. It lives in memory; a [DiagnosticsStore] saves a summary of
 * it, so the next run can show what the last one saw.
 *
 * It never holds message text, session, route or device IDs, payloads or secrets: every label and
 * outcome is a code constant or a protocol word, and [safeToken] replaces anything that does not
 * look like one before it is stored. Logcat gets a copy in debug builds only.
 */
internal object Diagnostics {
    const val CAPACITY = 512
    const val CONNECT_HISTORY = 20

    /** Round trips of `remote.ping` that the summary covers. */
    const val RTT_SAMPLES = 64

    /** The kinds a [DiagnosticsStore] keeps, newest [STORED_ENTRIES] of them. */
    const val STORED_ENTRIES = 64

    /** Close codes kept, code 0 (all the others) included. */
    const val CLOSE_CODES = 16

    /** The relay closes a phone that fell too far behind with this code (`flow.v1`). */
    const val FLOW_CLOSE_CODE = 1013
    private const val TAG = "PocketPi"
    private const val REDACTED = "redacted"

    enum class Kind {
        CONNECT,
        FAILURE,
        REQUEST,
        ACTIVATE,
        RECOVERY,
        OFFLINE,
        CAUGHT,
    }

    /** Counters of events that are not worth an entry each. */
    enum class Counter(val key: String) {
        /** Connections that came back after a loss. */
        RECONNECTS("reconnects"),

        /** OkHttp's own ping got no pong, so OkHttp failed the socket. */
        PING_FAILURES("pingFailures"),

        /** A `remote.ping` got no `pong`; it only measures and ends nothing. */
        PING_TIMEOUTS("pingTimeouts"),
        ACKS_SENT("acksSent"),

        /** Resumes the host answered with a `session.events` replay. */
        RESUMES_REPLAYED("resumesReplayed"),

        /** Resumes the host answered with a full snapshot instead. */
        RESUMES_SNAPSHOT("resumesSnapshot"),
    }

    /** Round trips of `remote.ping`: [relayMs] is the host's last ping round trip to the relay. */
    data class Rtt(val count: Int, val p50: Long, val p95: Long, val last: Long, val relayMs: Long? = null)

    /** How the link behaved: round trips, counters and how often the relay closed the socket with each code. */
    data class Link(
        val rtt: Rtt? = null,
        val counters: Map<Counter, Long> = emptyMap(),
        val closeCodes: Map<Int, Long> = emptyMap(),
    ) {
        fun count(counter: Counter): Long = counters[counter] ?: 0

        /** Closes the relay made because this phone could not keep up. */
        val flowCloses: Long
            get() = closeCodes[FLOW_CLOSE_CODE] ?: 0
    }

    /**
     * What the run before this one saved. Every time is wall-clock milliseconds, since the monotonic
     * clock restarts with the process; [savedAt] is when the file was written.
     */
    data class PreviousRun(
        val savedAt: Long,
        /** Newest first. */
        val connects: List<Connect>,
        /** Newest first. */
        val entries: List<Entry>,
        val link: Link,
    )

    /** One record. [durationMs] and [bytes] are -1 when they do not apply. */
    data class Entry(
        val at: Long,
        val kind: Kind,
        val label: String,
        val durationMs: Long = -1,
        val bytes: Long = -1,
        val outcome: String = "",
    )

    /**
     * One connection attempt with the time each handshake phase began, in ms after the start, and
     * the WebSocket extensions the relay accepted (such as `permessage-deflate`).
     */
    data class Connect(
        val at: Long,
        val label: String,
        val phases: List<Pair<HandshakePhase, Long>>,
        val durationMs: Long?,
        val outcome: String?,
        val extensions: List<String> = emptyList(),
    ) {
        /**
         * How long each waiting phase lasted. The end states take no time, and the last phase of
         * an unfinished attempt is left out.
         */
        fun breakdown(): List<Pair<HandshakePhase, Long>> =
            phases.mapIndexedNotNull { index, (phase, start) ->
                if (phase == HandshakePhase.ACTIVE || phase == HandshakePhase.PAIRED) return@mapIndexedNotNull null
                val end = phases.getOrNull(index + 1)?.second ?: durationMs ?: return@mapIndexedNotNull null
                phase to (end - start)
            }
    }

    /**
     * Requests of one type. [p50] and [p95] cover only round trips the host answered (`ok` and
     * `error.*`), so abandoned waits do not skew them; [failures] leaves out timeouts, which
     * [timeouts] counts, and requests the app itself cancelled or retried.
     */
    data class RequestStats(
        val type: String,
        val count: Int,
        val p50: Long,
        val p95: Long,
        val timeouts: Int,
        val failures: Int,
    )

    data class FrameStats(
        val count: Int,
        val p50Micros: Long,
        val p95Micros: Long,
        val p50Bytes: Long,
        val p95Bytes: Long,
        val maxBytes: Long,
    )

    data class Report(
        val now: Long,
        val requests: List<RequestStats>,
        /** Newest first. */
        val connects: List<Connect>,
        /** Newest first. */
        val offline: List<Entry>,
        /** Newest first. */
        val activations: List<Entry>,
        val frames: FrameStats?,
        /** Newest first. */
        val entries: List<Entry>,
        val link: Link = Link(),
        val previous: PreviousRun? = null,
    )

    /**
     * Whether the Android framework is really there. Plain JVM unit tests run against stubs that
     * throw, so the clock, Logcat and trace calls fall back or turn off there.
     */
    private val platform: Boolean = runCatching { SystemClock.elapsedRealtime() }.isSuccess
    private val logging: Boolean = platform && BuildConfig.DEBUG
    private val defaultClock: () -> Long =
        if (platform) { { SystemClock.elapsedRealtime() } } else { { System.nanoTime() / 1_000_000 } }

    /** Monotonic milliseconds; tests replace it to measure in virtual time. */
    @Volatile
    var clock: () -> Long = defaultClock

    private val entries = arrayOfNulls<Entry>(CAPACITY)
    private var next = 0
    private var size = 0
    private val connects = ArrayDeque<ConnectTrace>()
    private val frameMicros = LongArray(CAPACITY)
    private val frameBytes = LongArray(CAPACITY)
    private var frameNext = 0
    private var frameCount = 0
    private val cookies = AtomicInteger()
    private val rttMillis = LongArray(RTT_SAMPLES)
    private var rttNext = 0
    private var rttCount = 0
    private var rttLast = 0L
    private var relayRtt: Long? = null
    private val counts = LongArray(Counter.entries.size)
    private val closeCodeCounts = linkedMapOf<Int, Long>()
    private val changes = AtomicLong()

    /** The run before this one, set by [DiagnosticsStore.load]. */
    @Volatile
    var previousRun: PreviousRun? = null

    /** Grows with every change a [DiagnosticsStore] would save. */
    val revision: Long
        get() = changes.get()

    fun now(): Long = clock()

    @Synchronized
    fun record(
        kind: Kind,
        label: String,
        durationMs: Long = -1,
        bytes: Long = -1,
        outcome: String = "",
    ) {
        val entry =
            Entry(now(), kind, safeToken(label), durationMs, bytes, if (outcome.isEmpty()) "" else safeToken(outcome))
        entries[next] = entry
        next = (next + 1) % CAPACITY
        if (size < CAPACITY) size++
        changes.incrementAndGet()
        if (logging) runCatching { Log.d(TAG, describe(entry, entry.at)) }
    }

    /** A swallowed exception at [site]: only the exception's class is kept, never its message. */
    fun caught(site: String, error: Throwable) {
        record(Kind.CAUGHT, site, outcome = error.javaClass.simpleName.ifEmpty { "Throwable" })
    }

    fun request(type: String, durationMs: Long, bytes: Long, outcome: String) {
        record(Kind.REQUEST, type, durationMs, bytes, outcome)
    }

    /** Decrypt and parse time of one received frame and its plaintext size. */
    @Synchronized
    fun frame(nanos: Long, bytes: Int) {
        frameMicros[frameNext] = nanos / 1_000
        frameBytes[frameNext] = bytes.toLong()
        frameNext = (frameNext + 1) % CAPACITY
        frameCount++
    }

    /**
     * One `remote.ping` round trip of [millis], with the host's own round trip to the relay
     * ([relayMillis]) when it reported one.
     */
    @Synchronized
    fun rtt(millis: Long, relayMillis: Long? = null) {
        rttMillis[rttNext] = millis.coerceAtLeast(0)
        rttNext = (rttNext + 1) % RTT_SAMPLES
        if (rttCount < RTT_SAMPLES) rttCount++
        rttLast = millis.coerceAtLeast(0)
        if (relayMillis != null) relayRtt = relayMillis
        changes.incrementAndGet()
    }

    @Synchronized
    fun count(counter: Counter) {
        counts[counter.ordinal]++
        changes.incrementAndGet()
    }

    /** The relay closed the socket with [code]. */
    @Synchronized
    fun closeCode(code: Int) {
        val key = if (code in closeCodeCounts || closeCodeCounts.size < CLOSE_CODES - 1) code else 0
        closeCodeCounts[key] = (closeCodeCounts[key] ?: 0) + 1
        changes.incrementAndGet()
    }

    /**
     * Whether [error] is OkHttp giving up on a socket whose pong never came, which OkHttp reports
     * as a `SocketTimeoutException` from its ping task. Other timeouts read or write on the socket.
     */
    fun isPingFailure(error: Throwable): Boolean =
        error is java.net.SocketTimeoutException && error.message.orEmpty().contains("didn't receive pong")

    /** Starts a connection attempt; [label] names the connection, such as `foreground.device`. */
    @Synchronized
    fun connectStarted(label: String): ConnectTrace {
        val trace = ConnectTrace(now(), safeToken(label))
        connects.addLast(trace)
        while (connects.size > CONNECT_HISTORY) connects.removeFirst()
        changes.incrementAndGet()
        return trace
    }

    class ConnectTrace internal constructor(val at: Long, val label: String) {
        private val phases = mutableListOf<Pair<HandshakePhase, Long>>()
        private var durationMs: Long? = null
        private var outcome: String? = null
        private var extensions: List<String> = emptyList()

        val finished: Boolean
            get() = synchronized(Diagnostics) { outcome != null }

        fun phase(phase: HandshakePhase) {
            synchronized(Diagnostics) {
                if (outcome == null && phases.lastOrNull()?.first != phase) {
                    phases += phase to (now() - at)
                    changes.incrementAndGet()
                }
            }
        }

        /**
         * Records the upgrade response's `Sec-WebSocket-Extensions` [header]. Only the extension
         * names are kept, never their parameters, and a name that is not a plain token is
         * redacted.
         */
        fun extensions(header: String?) {
            val names =
                header.orEmpty().split(',').map { it.substringBefore(';').trim() }.filter { it.isNotEmpty() }
                    .take(4)
                    .map { if (it.length <= 32 && EXTENSION_NAME.matches(it)) it else REDACTED }
            synchronized(Diagnostics) {
                extensions = names
                changes.incrementAndGet()
            }
        }

        /** Ends the attempt once and returns true; later calls are ignored and return false. */
        fun finish(result: String): Boolean {
            val duration =
                synchronized(Diagnostics) {
                    if (outcome != null) return false
                    outcome = safeToken(result)
                    changes.incrementAndGet()
                    (now() - at).also { durationMs = it }
                }
            record(Kind.CONNECT, label, duration, outcome = result)
            return true
        }

        /**
         * Records why the connection ended: as the attempt's outcome while the handshake runs,
         * and as a separate [Kind.FAILURE] entry once it has finished, so a dropped established
         * connection keeps its close code or exception class.
         */
        fun end(cause: String) {
            if (!finish(cause)) record(Kind.FAILURE, "$label.socket", outcome = cause)
        }

        internal fun snapshot() =
            synchronized(Diagnostics) { Connect(at, label, phases.toList(), durationMs, outcome, extensions) }
    }

    /** A synchronous trace section on this thread; a no-op where the framework is missing. */
    inline fun <T> trace(name: String, block: () -> T): T {
        if (!tracing()) return block()
        Trace.beginSection(name)
        try {
            return block()
        } finally {
            Trace.endSection()
        }
    }

    @PublishedApi
    internal fun tracing() = platform

    /**
     * Starts a trace span that may cross suspension points and returns its cookie for
     * [endAsync]. Async sections exist from API 29; below that both calls do nothing.
     */
    fun beginAsync(name: String): Int {
        val cookie = cookies.incrementAndGet()
        if (platform && Build.VERSION.SDK_INT >= 29) Trace.beginAsyncSection(name, cookie)
        return cookie
    }

    fun endAsync(name: String, cookie: Int) {
        if (platform && Build.VERSION.SDK_INT >= 29) Trace.endAsyncSection(name, cookie)
    }

    @Synchronized
    fun report(): Report {
        val all = (0 until size).map { entries[(next - 1 - it + CAPACITY) % CAPACITY]!! }
        val requests =
            all.filter { it.kind == Kind.REQUEST }
                .groupBy { it.label }
                .map { (type, list) ->
                    val durations = list.filter { answered(it.outcome) }.map { it.durationMs }.sorted()
                    RequestStats(
                        type,
                        list.size,
                        percentile(durations, 50),
                        percentile(durations, 95),
                        list.count { it.outcome == "timeout" },
                        list.count {
                            it.outcome != "ok" && it.outcome != "timeout" && it.outcome != "cancelled" &&
                                !it.outcome.startsWith("retry.")
                        },
                    )
                }
                .sortedByDescending { it.count }
        val frames =
            if (frameCount == 0) null
            else {
                val count = minOf(frameCount, CAPACITY)
                val micros = frameMicros.copyOf(count).sorted()
                val bytes = frameBytes.copyOf(count).sorted()
                FrameStats(
                    frameCount,
                    percentile(micros, 50),
                    percentile(micros, 95),
                    percentile(bytes, 50),
                    percentile(bytes, 95),
                    bytes.last(),
                )
            }
        return Report(
            now(),
            requests,
            connects.reversed().map { it.snapshot() },
            all.filter { it.kind == Kind.OFFLINE },
            all.filter { it.kind == Kind.ACTIVATE },
            frames,
            all,
            link(),
            previousRun,
        )
    }

    /** Round trips, counters and close codes so far. */
    @Synchronized
    fun link(): Link {
        val rtt =
            if (rttCount == 0) null
            else {
                val sorted = rttMillis.copyOf(rttCount).sorted()
                Rtt(rttCount, percentile(sorted, 50), percentile(sorted, 95), rttLast, relayRtt)
            }
        return Link(
            rtt,
            Counter.entries.associateWith { counts[it.ordinal] }.filterValues { it > 0 },
            closeCodeCounts.toMap(),
        )
    }

    /**
     * What a [DiagnosticsStore] saves: the newest connections, failures, recoveries and offline
     * intervals, and the [link] summary, with wall-clock times computed from [wallNow].
     */
    @Synchronized
    fun persistable(wallNow: Long): PreviousRun {
        val monotonicNow = now()
        fun wall(at: Long) = wallNow - (monotonicNow - at).coerceAtLeast(0)
        val all = (0 until size).map { entries[(next - 1 - it + CAPACITY) % CAPACITY]!! }
        return PreviousRun(
            wallNow,
            connects.reversed().map { it.snapshot().let { connect -> connect.copy(at = wall(connect.at)) } },
            all.filter { it.kind == Kind.FAILURE || it.kind == Kind.RECOVERY || it.kind == Kind.OFFLINE }
                .take(STORED_ENTRIES)
                .map { it.copy(at = wall(it.at)) },
            link(),
        )
    }

    private fun answered(outcome: String) = outcome == "ok" || outcome.startsWith("error.")

    /** The plain-text report the Diagnostics page copies. It holds only what [report] holds. */
    fun export(): String {
        val report = report()
        return buildString {
            appendLine("PocketPi diagnostics")
            if (platform) {
                appendLine(
                    "app ${BuildConfig.VERSION_NAME} (${if (BuildConfig.DEBUG) "debug" else "release"}), " +
                        "Android ${Build.VERSION.SDK_INT}, ${safeToken(Build.MANUFACTURER.orEmpty())}"
                )
            }
            appendLine("Times are seconds before this report.")
            appendLine()
            appendLine("Requests (count, p50 and p95 of answered requests, timeouts, failures)")
            if (report.requests.isEmpty()) appendLine("  none")
            report.requests.forEach {
                appendLine("  ${it.type} n=${it.count} p50=${it.p50}ms p95=${it.p95}ms timeouts=${it.timeouts} failures=${it.failures}")
            }
            appendLine()
            appendLine("Connections (newest first)")
            if (report.connects.isEmpty()) appendLine("  none")
            report.connects.forEach { appendLine("  " + describe(it, report.now)) }
            appendLine()
            appendLine("Offline intervals")
            if (report.offline.isEmpty()) appendLine("  none")
            report.offline.forEach {
                appendLine("  ${ago(report.now, it.at)} offline ${it.durationMs}ms (until ${it.label})")
            }
            appendLine()
            appendLine("Link")
            appendLink(report.link)
            appendLine()
            report.frames?.let {
                appendLine(
                    "Frames n=${it.count} decrypt+parse p50=${it.p50Micros}us p95=${it.p95Micros}us " +
                        "size p50=${it.p50Bytes}B p95=${it.p95Bytes}B max=${it.maxBytes}B"
                )
                appendLine()
            }
            appendLine("Events (newest first)")
            report.entries.forEach { appendLine("  " + describe(it, report.now)) }
            report.previous?.let { previous ->
                appendLine()
                appendLine("Before restart (times are seconds before the last save)")
                appendLink(previous.link)
                appendLine("  Connections (newest first)")
                if (previous.connects.isEmpty()) appendLine("    none")
                previous.connects.forEach { appendLine("    " + describe(it, previous.savedAt)) }
                appendLine("  Events (newest first)")
                if (previous.entries.isEmpty()) appendLine("    none")
                previous.entries.forEach { appendLine("    " + describe(it, previous.savedAt)) }
            }
        }
    }

    private fun StringBuilder.appendLink(link: Link) {
        val rtt = link.rtt
        if (rtt == null) appendLine("  rtt none")
        else {
            appendLine(
                "  rtt n=${rtt.count} p50=${rtt.p50}ms p95=${rtt.p95}ms last=${rtt.last}ms" +
                    (rtt.relayMs?.let { " relay=${it}ms" } ?: "")
            )
        }
        appendLine(
            "  reconnects=${link.count(Counter.RECONNECTS)} okhttpPingFailures=${link.count(Counter.PING_FAILURES)} " +
                "pingTimeouts=${link.count(Counter.PING_TIMEOUTS)} acksSent=${link.count(Counter.ACKS_SENT)} " +
                "resumesReplayed=${link.count(Counter.RESUMES_REPLAYED)} resumesSnapshot=${link.count(Counter.RESUMES_SNAPSHOT)}"
        )
        appendLine(
            "  closeCodes " +
                (link.closeCodes.entries.joinToString(" ") { "${it.key}=${it.value}" }.ifEmpty { "none" }) +
                " flowCloses=${link.flowCloses}"
        )
    }

    private fun describe(connect: Connect, now: Long): String {
        val phases = connect.breakdown().joinToString(", ") { (phase, ms) -> "${phase.name.lowercase()} ${ms}ms" }
        return "${ago(now, connect.at)} ${connect.label} ${connect.outcome ?: "pending"}" +
            (connect.durationMs?.let { " ${it}ms" } ?: "") +
            (if (connect.extensions.isEmpty()) "" else " ext=" + connect.extensions.joinToString("+")) +
            (if (phases.isEmpty()) "" else ": $phases")
    }

    private fun describe(entry: Entry, now: Long) =
        buildString {
            append(ago(now, entry.at)).append(' ').append(entry.kind.name.lowercase()).append(' ').append(entry.label)
            if (entry.durationMs >= 0) append(' ').append(entry.durationMs).append("ms")
            if (entry.bytes >= 0) append(' ').append(entry.bytes).append('B')
            if (entry.outcome.isNotEmpty()) append(' ').append(entry.outcome)
        }

    private fun ago(now: Long, at: Long) = "-" + ((now - at).coerceAtLeast(0) / 1000) + "s"

    /** Empties every record; for tests. */
    @Synchronized
    fun clear() {
        entries.fill(null)
        next = 0
        size = 0
        connects.clear()
        frameNext = 0
        frameCount = 0
        rttNext = 0
        rttCount = 0
        rttLast = 0
        relayRtt = null
        counts.fill(0)
        closeCodeCounts.clear()
        previousRun = null
        changes.set(0)
        clock = defaultClock
    }

    /** Nearest-rank percentile of an ascending list; 0 when it is empty. */
    fun percentile(sorted: List<Long>, percent: Int): Long {
        if (sorted.isEmpty()) return 0
        val rank = Math.ceil(percent / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    /** A WebSocket extension name as RFC 6455 registers them, such as `permessage-deflate`. */
    private val EXTENSION_NAME = Regex("[a-z0-9]+(?:[-_][a-z0-9]+)*")

    private val TOKEN = Regex("[A-Za-z0-9]+(?:[._:][A-Za-z0-9]+)*")

    /**
     * [value] when it reads like a code word (`sessions.list`, `timeout.challenge`,
     * `SocketTimeoutException`), otherwise `redacted`. Text with spaces, IDs and keys look
     * different: a random token switches between lower case, upper case and digits far more often
     * than words do.
     */
    fun safeToken(value: String): String {
        if (value.isEmpty() || value.length > 96 || !TOKEN.matches(value)) return REDACTED
        val words = value.split('.', '_', ':')
        val readable =
            words.all { it.length < 4 || switches(it) * 5 <= it.length } &&
                words.sumOf(::switches) * 5 <= words.sumOf { it.length }.coerceAtLeast(4)
        return if (readable) value else REDACTED
    }

    /** How often [word] goes from lower to upper case or between digits and letters. */
    private fun switches(word: String): Int =
        (1 until word.length).count { index ->
            val previous = word[index - 1]
            val current = word[index]
            previous.isDigit() != current.isDigit() || (previous.isLowerCase() && current.isUpperCase())
        }
}

/** Whether [checkMainThread] enforces anything: only debug builds on a real main looper. */
internal fun mainThreadCheckEnabled(debug: Boolean, robolectric: Boolean, hasMainLooper: Boolean) =
    debug && !robolectric && hasMainLooper

private val mainThreadChecked: Boolean by lazy {
    mainThreadCheckEnabled(
        BuildConfig.DEBUG,
        runCatching { Build.FINGERPRINT == "robolectric" }.getOrDefault(true),
        runCatching { Looper.getMainLooper() != null }.getOrDefault(false),
    )
}

/**
 * Fails fast in debug builds when repository state is touched off the main thread. Release builds,
 * plain JVM tests and Robolectric (whose tests may drive the repository from test threads) skip it.
 */
internal fun checkMainThread() {
    if (mainThreadChecked) check(Looper.myLooper() == Looper.getMainLooper()) {
        "Repository state is confined to the main thread"
    }
}
