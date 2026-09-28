package de.joinnoah.pi.remote

import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BackgroundJobsTest {
    private fun job(
        id: String = "job",
        status: String = "running",
        outputBytes: Long = 0,
        extra: Map<String, JsonElement> = emptyMap(),
    ) = JsonObject(
        Wire.objectOf(
            "id" to id,
            "command" to "npm run dev",
            "cwd" to "/repo",
            "status" to status,
            "startedAt" to 1,
            "outputBytes" to outputBytes,
        ) + extra
    )

    private fun watch(job: JsonObject, offset: Long, data: String, skipped: Long? = null) =
        JsonObject(
            Wire.objectOf(
                "kind" to "job.watch",
                "sessionId" to "s",
                "job" to job,
                "offset" to offset,
                "data" to Wire.encode(data.toByteArray()),
            ) + (skipped?.let { mapOf("skipped" to JsonPrimitive(it)) } ?: emptyMap())
        )

    @Test
    fun parsesJobsAndRejectsMalformedOnes() {
        val ended = parseBackgroundJob(
            job(status = "killed", extra = mapOf(
                "exitCode" to JsonNull, "signal" to JsonPrimitive("SIGTERM"), "endedAt" to JsonPrimitive(5),
                "truncated" to JsonPrimitive(true), "stuck" to JsonPrimitive(false),
            ))
        )
        assertEquals(JobStatus.KILLED, ended.status)
        assertNull(ended.exitCode)
        assertEquals("SIGTERM", ended.signal)
        assertTrue(ended.truncated)
        assertEquals(3, parseBackgroundJob(job(status = "exited", extra = mapOf("exitCode" to JsonPrimitive(3)))).exitCode)
        listOf(
            job(extra = mapOf("exitCode" to JsonPrimitive(0))),
            job(status = "paused"),
            job(extra = mapOf("truncated" to JsonPrimitive(false))),
            job(extra = mapOf("unknown" to JsonPrimitive(1))),
            job(id = "a b"),
            job(outputBytes = -1),
        ).forEach { value ->
            assertThrows(Exception::class.java) { parseBackgroundJob(value) }
        }
    }

    @Test
    fun validatesListAndWatchResults() {
        val list = JsonObject(Wire.objectOf("kind" to "jobs", "sessionId" to "s", "items" to JsonArray(listOf(job()))))
        assertEquals(listOf("job"), parseJobList(list, "s").map { it.id })
        assertThrows(Exception::class.java) { parseJobList(list, "other") }

        val chunk = parseJobWatch(watch(job(outputBytes = 10), 4, "abc", skipped = 4), "s", "job", 0)
        assertEquals(4, chunk.offset)
        assertEquals("abc", String(chunk.bytes))
        // Before the requested since, past the job's output, or for another job.
        assertThrows(Exception::class.java) { parseJobWatch(watch(job(outputBytes = 10), 2, "abc"), "s", "job", 3) }
        assertThrows(Exception::class.java) { parseJobWatch(watch(job(outputBytes = 5), 4, "abc"), "s", "job", 0) }
        assertThrows(Exception::class.java) { parseJobWatch(watch(job(outputBytes = 10), 0, "abc"), "s", "other", null) }
    }

    @Test
    fun parsesJobEventsAndIgnoresOtherKinds() {
        val output = jobEvent(
            Wire.objectOf("type" to "host.event", "kind" to "session.job.output", "sessionId" to "s",
                "jobId" to "job", "offset" to 12, "bytes" to 4)
        )
        assertEquals(JobEvent.Output("s", "job", 12, 4), output)
        val changed = jobEvent(
            Wire.objectOf("type" to "host.event", "kind" to "session.jobs.changed", "sessionId" to "s",
                "items" to JsonArray(listOf(job())))
        )
        assertTrue(changed is JobEvent.Changed)
        assertNull(jobEvent(Wire.objectOf("type" to "host.event", "kind" to "session.job.future")))
        assertThrows(Exception::class.java) {
            jobEvent(Wire.objectOf("type" to "host.event", "kind" to "session.job.output", "sessionId" to "s",
                "jobId" to "job", "offset" to 12))
        }
    }

    @Test
    fun keepsSplitCharactersForTheNextChunk() {
        val bytes = "aä".toByteArray()
        val (text, rest) = splitUtf8(bytes.copyOf(bytes.size - 1))
        assertEquals("a", text)
        assertEquals(1, rest.size)
        assertEquals("ä", splitUtf8(rest + bytes.last()).first)
    }

    @Test
    fun trimsOldOutputByWholeLinesIntoASkippedMarker() {
        val buffer = JobOutputBuffer(limit = 6)
        buffer.append(JobOutputPart.Text("ab\ncd\nef\n"))
        assertEquals(listOf(JobOutputLine.Skipped(3), JobOutputLine.Text("cd"), JobOutputLine.Text("ef")), buffer.lines)
        assertEquals(0L, buffer.firstLine)
        buffer.append(JobOutputPart.Text("gh\n"))
        // "ef" keeps its absolute index 2.
        assertEquals(listOf(JobOutputLine.Skipped(6), JobOutputLine.Text("ef"), JobOutputLine.Text("gh")), buffer.lines)
        assertEquals(1L, buffer.firstLine)
        // One line longer than the limit loses its head.
        assertEquals(
            listOf(JobOutputLine.Skipped(4), JobOutputLine.Text("efgh")),
            jobOutputLines(listOf(JobOutputPart.Text("abcdefgh")), limit = 4),
        )
    }

    @Test
    fun buildsLinesFromCarriageReturnsAndEscapes() {
        val lines = jobOutputLines(
            listOf(JobOutputPart.Text("one\n\u001B[32mtwo\u001B[0m 10%\r"), JobOutputPart.Text("two 50%\n"), JobOutputPart.Skipped(7))
        )
        assertEquals(
            listOf(JobOutputLine.Text("one"), JobOutputLine.Text("two 50%"), JobOutputLine.Skipped(7)),
            lines,
        )
        val crlf = listOf(JobOutputLine.Text("one"), JobOutputLine.Text("two"))
        assertEquals(crlf, jobOutputLines(listOf(JobOutputPart.Text("one\r\ntwo\r\n"))))
        assertEquals(crlf, jobOutputLines(listOf(JobOutputPart.Text("one\r"), JobOutputPart.Text("\ntwo\r\n"))))
        assertEquals(crlf, jobOutputLines(listOf(JobOutputPart.Text("one\r\ntwo\r"), JobOutputPart.Text("\n"))))
    }

    @Test
    fun stripsEveryEscapeKindAndOnesCutBetweenChunks() {
        val text = "\u001B(Ba\u001B=b\u001B7c\u001BPq#0\u001B\\d\u001B_apc\u001B\\e\u001B]0;title\u0007f\u001B[1;31mg\n"
        assertEquals(listOf(JobOutputLine.Text("abcdefg")), jobOutputLines(listOf(JobOutputPart.Text(text))))
        // Every split point gives the same line.
        for (cut in 1 until text.length)
            assertEquals(
                "cut at $cut",
                listOf(JobOutputLine.Text("abcdefg")),
                jobOutputLines(listOf(JobOutputPart.Text(text.substring(0, cut)), JobOutputPart.Text(text.substring(cut)))),
            )
        // A CSI whose start went with skipped bytes; ordinary text after a gap stays.
        assertEquals(
            listOf(JobOutputLine.Skipped(3), JobOutputLine.Text("red")),
            jobOutputLines(listOf(JobOutputPart.Skipped(3), JobOutputPart.Text("1;31mred"))),
        )
        assertEquals(
            listOf(JobOutputLine.Skipped(3), JobOutputLine.Text("done")),
            jobOutputLines(listOf(JobOutputPart.Skipped(3), JobOutputPart.Text("done"))),
        )
    }

    @Test
    fun keepsAColorCountAfterAGapUnlessAnEscapeOrLineEndFollows() {
        assertEquals(
            listOf(JobOutputLine.Skipped(3), JobOutputLine.Text("2m30s elapsed")),
            jobOutputLines(listOf(JobOutputPart.Skipped(3), JobOutputPart.Text("2m30s elapsed\n"))),
        )
        assertEquals(
            listOf(JobOutputLine.Skipped(3), JobOutputLine.Text("done")),
            jobOutputLines(listOf(JobOutputPart.Skipped(3), JobOutputPart.Text("0m\u001B[1mdone\n"))),
        )
    }

    @Test
    fun finishShowsAHeldEscapeAsText() {
        val buffer = JobOutputBuffer()
        buffer.append(JobOutputPart.Text("a\n\u001B] text\n"))
        assertEquals(listOf(JobOutputLine.Text("a")), buffer.lines)
        buffer.finish()
        assertEquals(listOf(JobOutputLine.Text("a"), JobOutputLine.Text("] text")), buffer.lines)
        buffer.finish()
        assertEquals(listOf(JobOutputLine.Text("a"), JobOutputLine.Text("] text")), buffer.lines)

        // An OSC cut after its first terminator byte loses that ESC too.
        val cut = JobOutputBuffer()
        cut.append(JobOutputPart.Text("done\u001B]0;title\u001B"))
        assertEquals(listOf(JobOutputLine.Text("done")), cut.lines)
        cut.finish()
        assertEquals(listOf(JobOutputLine.Text("done]0;title")), cut.lines)
    }

    @Test
    fun anUnterminatedStringRemovesNoMoreThanItsLine() {
        val long = "x".repeat(5000)
        assertEquals(
            listOf("a", "next", "", "last").map(JobOutputLine::Text),
            jobOutputLines(listOf(JobOutputPart.Text("a\u001B]0;$long\nnext\n\u001BP$long\nlast\n"))),
        )
        // Terminated strings still go whole, and colour codes are still stripped.
        assertEquals(
            listOf("titled", "red").map(JobOutputLine::Text),
            jobOutputLines(listOf(JobOutputPart.Text("\u001B]0;a\nb\u0007titled\n\u001B[31mred\u001B[0m\n"))),
        )
        // An ST (ESC \) terminator removes a multi-line OSC just as whole as a BEL one does.
        assertEquals(
            listOf("titled", "red").map(JobOutputLine::Text),
            jobOutputLines(listOf(JobOutputPart.Text("\u001B]0;a\nb\u001B\\titled\n\u001B[31mred\u001B[0m\n"))),
        )
    }

    @Test
    fun anOpenLineTrimmedAwayLeavesNoBlankLine() {
        val buffer = JobOutputBuffer(limit = 1)
        buffer.append(JobOutputPart.Text("ab\r"))
        assertEquals(listOf(JobOutputLine.Skipped(3)), buffer.lines)
        buffer.append(JobOutputPart.Text("\n"))
        assertEquals(listOf(JobOutputLine.Skipped(3)), buffer.lines)
        buffer.append(JobOutputPart.Text("x"))
        assertEquals(listOf(JobOutputLine.Skipped(3), JobOutputLine.Text("x")), buffer.lines)
        // The cut moves past a split character and takes the whole line.
        assertEquals(
            listOf(JobOutputLine.Skipped(5)),
            jobOutputLines(listOf(JobOutputPart.Text("a\uD83D\uDE00")), limit = 1),
        )
    }

    @Test
    fun incrementalLinesMatchTheWholeOutput() {
        val text = "a\r\nbb\rcc\n\n\u001B[2Kprogress 1\rprogress 2\r\nend"
        val whole = jobOutputLines(listOf(JobOutputPart.Text(text)))
        assertEquals(
            listOf("a", "cc", "", "progress 2", "end").map(JobOutputLine::Text),
            whole,
        )
        val buffer = JobOutputBuffer()
        text.chunked(3).forEach { buffer.append(JobOutputPart.Text(it)) }
        assertEquals(whole, buffer.lines)
    }

    private class Source : JobSource {
        val watches = mutableListOf<Long?>()
        var list: suspend () -> List<BackgroundJob> = { emptyList() }
        var answer: (Long?) -> JobChunk = { error("no answer") }
        var kill: () -> Unit = {}
        var kills = 0

        override suspend fun listJobs(sessionId: String) = list()

        override suspend fun watchJob(sessionId: String, jobId: String, since: Long?): JobChunk {
            watches += since
            return answer(since)
        }

        override suspend fun killJob(sessionId: String, jobId: String) {
            kills++
            kill()
        }
    }

    private fun running(outputBytes: Long) = parseBackgroundJob(job(outputBytes = outputBytes))

    private fun chunk(offset: Long, text: String, total: Long = offset + text.length, status: String = "running") =
        JobChunk(
            parseBackgroundJob(
                job(status = status, outputBytes = total,
                    extra = if (status == "running") emptyMap() else mapOf("exitCode" to JsonPrimitive(0), "endedAt" to JsonPrimitive(2)))
            ),
            offset,
            text.toByteArray(),
        )

    private class Harness(scope: kotlinx.coroutines.CoroutineScope, val source: Source = Source()) {
        var state = RemoteState(
            connected = true,
            capabilities = setOf(BACKGROUND_JOBS_CAPABILITY),
            selection = RemoteSelection("route", "project", "s"),
            session = Wire.objectOf("id" to "s", "origin" to "tui"),
        )
        val controller = BackgroundJobsController(scope, source, { state }, { block -> state = block(state) })
        val view get() = state.jobs?.view
    }

    @Test
    fun listsJobsAndHidesTheEntryForUnsupportedSessions() = runTest {
        val harness = Harness(backgroundScope)
        harness.source.list = { listOf(running(0)) }
        harness.controller.refresh()
        runCurrent()
        assertEquals(listOf("job"), visibleJobs(harness.state)?.jobs?.map { it.id })

        harness.source.list = { throw RemoteRequestException("unsupported") }
        harness.controller.refresh()
        runCurrent()
        assertTrue(harness.state.jobs!!.unsupported)
        assertNull(visibleJobs(harness.state))

        // RPC-backed sessions are never asked.
        harness.state = harness.state.copy(jobs = null, session = Wire.objectOf("id" to "s", "origin" to "rpc"))
        harness.controller.refresh()
        runCurrent()
        assertNull(harness.state.jobs)
    }

    @Test
    fun pullsFromTheLastOffsetOnEachNoticeAndMarksSkippedBytes() = runTest {
        val harness = Harness(backgroundScope)
        harness.source.list = { listOf(running(3)) }
        harness.controller.openList()
        runCurrent()
        harness.source.answer = { chunk(0, "abc") }
        harness.controller.openJob("job")
        runCurrent()
        assertEquals(listOf<Long?>(null), harness.source.watches)
        assertEquals(listOf(JobOutputLine.Text("abc")), harness.view!!.lines)
        assertTrue(harness.view!!.loaded)

        // A notice for bytes already read changes nothing; a newer one pulls from offset 3.
        harness.controller.onEvent(JobEvent.Output("s", "job", 3, 3))
        runCurrent()
        assertEquals(1, harness.source.watches.size)
        harness.source.answer = { chunk(10, "xyz") }
        harness.controller.onEvent(JobEvent.Output("s", "job", 13, 10))
        runCurrent()
        assertEquals(listOf(null, 3L), harness.source.watches)
        assertEquals(
            listOf(JobOutputLine.Text("abc"), JobOutputLine.Skipped(7), JobOutputLine.Text("xyz")),
            harness.view!!.lines,
        )
    }

    @Test
    fun pagesUntilTheJobsEndAndRewatchesEveryThirtySeconds() = runTest {
        val harness = Harness(backgroundScope)
        harness.source.list = { listOf(running(6)) }
        harness.controller.openList()
        runCurrent()
        harness.source.answer = { since -> if (since == null) chunk(0, "abc", total = 6) else chunk(3, "def") }
        harness.controller.openJob("job")
        runCurrent()
        assertEquals(listOf(null, 3L), harness.source.watches)

        harness.source.answer = { chunk(6, "") }
        advanceTimeBy(JOB_LEASE_RENEW_MILLIS - 1)
        runCurrent()
        assertEquals(2, harness.source.watches.size)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(null, 3L, 6L), harness.source.watches)

        // A reconnect re-lists and renews the lease right away.
        harness.controller.refresh()
        runCurrent()
        assertEquals(4, harness.source.watches.size)

        // Once the job ended and every byte is read, the view stops watching.
        harness.source.answer = { chunk(6, "", status = "exited") }
        advanceTimeBy(JOB_LEASE_RENEW_MILLIS + 1)
        runCurrent()
        val count = harness.source.watches.size
        advanceTimeBy(3 * JOB_LEASE_RENEW_MILLIS)
        runCurrent()
        assertEquals(count, harness.source.watches.size)
        assertEquals(JobStatus.EXITED, harness.view!!.job!!.status)
    }

    @Test
    fun aCancelledRequestCountsAsAFailureAndIsRetried() = runTest {
        val harness = Harness(backgroundScope)
        harness.source.list = { throw kotlinx.coroutines.CancellationException("epoch") }
        harness.controller.openList()
        runCurrent()
        assertFalse(harness.state.jobs!!.loading)
        assertEquals(JobsFailure.FAILED, harness.state.jobs!!.failure)

        harness.source.list = { listOf(running(3)) }
        harness.controller.refresh()
        runCurrent()
        harness.source.answer = { throw kotlinx.coroutines.CancellationException("epoch") }
        harness.controller.openJob("job")
        runCurrent()
        assertEquals(JobsFailure.FAILED, harness.view!!.failure)
        harness.source.answer = { chunk(0, "abc") }
        advanceTimeBy(JOB_LEASE_RENEW_MILLIS + 1)
        runCurrent()
        assertEquals(2, harness.source.watches.size)
        assertEquals(listOf(JobOutputLine.Text("abc")), harness.view!!.lines)
        assertNull(harness.view!!.failure)
    }

    @Test
    fun aFinishedJobShowsAnEscapeItsOutputEndedIn() = runTest {
        val harness = Harness(backgroundScope)
        harness.source.list = { listOf(running(0)) }
        harness.controller.openList()
        runCurrent()
        harness.source.answer = { chunk(0, "a\n\u001B] text\n", status = "exited") }
        harness.controller.openJob("job")
        runCurrent()
        assertEquals(listOf(JobOutputLine.Text("a"), JobOutputLine.Text("] text")), harness.view!!.lines)
    }

    @Test
    fun aVanishedJobShowsAMessageAndStopsWatching() = runTest {
        val harness = Harness(backgroundScope)
        harness.source.list = { listOf(running(0)) }
        harness.controller.openList()
        runCurrent()
        harness.source.answer = { throw RemoteRequestException("not_found") }
        harness.controller.openJob("job")
        runCurrent()
        assertTrue(harness.view!!.vanished)
        advanceTimeBy(3 * JOB_LEASE_RENEW_MILLIS)
        runCurrent()
        assertEquals(1, harness.source.watches.size)
    }

    @Test
    fun stoppingWatchesAgainOrReportsTheFailure() = runTest {
        val harness = Harness(backgroundScope)
        harness.source.list = { listOf(running(0)) }
        harness.controller.openList()
        runCurrent()
        harness.source.answer = { chunk(0, "") }
        harness.controller.openJob("job")
        runCurrent()
        harness.controller.kill("job")
        assertTrue(harness.view!!.stopping)
        runCurrent()
        assertEquals(1, harness.source.kills)
        assertFalse(harness.view!!.stopping)
        assertEquals(2, harness.source.watches.size)

        harness.source.kill = { throw RemoteRequestException("failed") }
        harness.controller.kill("job")
        runCurrent()
        assertTrue(harness.view!!.stopFailed)

        harness.source.kill = { throw RemoteRequestException("not_found") }
        harness.controller.kill("job")
        runCurrent()
        assertTrue(harness.view!!.vanished)

        harness.controller.closeList()
        assertNull(harness.view)
        assertFalse(harness.state.jobs!!.listOpen)
    }

    @Test
    fun closingTheJobDoesNotCancelARequestedStop() = runTest {
        val harness = Harness(backgroundScope)
        harness.source.list = { listOf(running(0)) }
        harness.controller.openList()
        runCurrent()
        harness.source.answer = { chunk(0, "") }
        harness.controller.openJob("job")
        runCurrent()
        var killed = false
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val source = object : JobSource by harness.source {
            override suspend fun killJob(sessionId: String, jobId: String) {
                gate.await()
                killed = true
            }
        }
        val controller = BackgroundJobsController(backgroundScope, source, { harness.state }, { block -> harness.state = block(harness.state) })
        controller.openJob("job")
        runCurrent()
        controller.kill("job")
        runCurrent()
        controller.closeJob()
        gate.complete(Unit)
        runCurrent()
        assertTrue(killed)
    }

    @Test
    fun aTerminalStatusPullsTheOpenJobRightAway() = runTest {
        val harness = Harness(backgroundScope)
        harness.source.list = { listOf(running(0)) }
        harness.controller.openList()
        runCurrent()
        harness.source.answer = { chunk(0, "") }
        harness.controller.openJob("job")
        runCurrent()
        assertEquals(1, harness.source.watches.size)
        harness.controller.onEvent(JobEvent.Status("s", running(0)))
        runCurrent()
        assertEquals(1, harness.source.watches.size)
        harness.source.answer = { chunk(0, "", status = "exited") }
        harness.controller.onEvent(JobEvent.Status("s", chunk(0, "", status = "exited").job))
        runCurrent()
        assertEquals(2, harness.source.watches.size)
    }

    @Test
    fun automaticRefreshesRunAtMostOncePerInterval() = runTest {
        val harness = Harness(backgroundScope)
        var lists = 0
        harness.source.list = { lists++; emptyList() }
        harness.controller.refreshSoon()
        runCurrent()
        assertEquals(1, lists)
        harness.controller.refreshSoon()
        harness.controller.refreshSoon()
        runCurrent()
        assertEquals(1, lists)
        advanceTimeBy(JOBS_AUTO_REFRESH_MILLIS + 1)
        runCurrent()
        assertEquals(2, lists)
        advanceTimeBy(3 * JOBS_AUTO_REFRESH_MILLIS)
        runCurrent()
        assertEquals(2, lists)
    }

    @Test
    fun aLateFailureOfAReplacedRefreshKeepsTheNewerOneLoading() = runTest {
        val harness = Harness(backgroundScope)
        var first: kotlin.coroutines.Continuation<Unit>? = null
        // The first request ignores its cancellation and fails once it resumes.
        harness.source.list = {
            kotlin.coroutines.suspendCoroutine<Unit> { first = it }
            throw RemoteRequestException("failed")
        }
        harness.controller.refresh()
        runCurrent()
        harness.source.list = { kotlinx.coroutines.awaitCancellation() }
        harness.controller.refresh()
        runCurrent()
        first!!.resumeWith(Result.success(Unit))
        runCurrent()
        assertTrue(harness.state.jobs!!.loading)
        assertNull(harness.state.jobs!!.failure)
    }

    @Test
    fun aLateSuccessOfAReplacedRefreshKeepsTheNewerOneLoading() = runTest {
        val harness = Harness(backgroundScope)
        var first: kotlin.coroutines.Continuation<List<BackgroundJob>>? = null
        // The first request ignores its cancellation and succeeds once it resumes.
        harness.source.list = { kotlin.coroutines.suspendCoroutine { first = it } }
        harness.controller.refresh()
        runCurrent()
        harness.source.list = { kotlinx.coroutines.awaitCancellation() }
        harness.controller.refresh()
        runCurrent()
        first!!.resumeWith(Result.success(listOf(running(0))))
        runCurrent()
        assertTrue(harness.state.jobs!!.loading)
        assertTrue(harness.state.jobs!!.jobs.isEmpty())
    }

    @Test
    fun eventsUpdateTheListAndIgnoreOtherSessions() = runTest {
        val harness = Harness(backgroundScope)
        harness.source.list = { emptyList() }
        harness.controller.refresh()
        runCurrent()
        assertNull(visibleJobs(harness.state))
        harness.controller.onEvent(JobEvent.Changed("other", listOf(running(0))))
        assertNull(visibleJobs(harness.state))
        harness.controller.onEvent(JobEvent.Changed("s", listOf(running(0))))
        assertEquals(1, visibleJobs(harness.state)?.jobs?.size)
        harness.controller.onEvent(JobEvent.Status("s", chunk(0, "", status = "exited").job))
        assertEquals(JobStatus.EXITED, harness.state.jobs!!.jobs.single().status)
    }
}
