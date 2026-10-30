package de.joinnoah.pi.remote

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Runs the shared `session-bash-v1.json` wire entries, the failure mapping and the composer's `!` rule. */
class ShellCommandTest {
    private val fixture =
        Wire.json.parseToJsonElement(javaClass.getResource("/session-bash-v1.json")!!.readText()).jsonObject

    private fun payloads(list: String) = fixture.array(list).map { it.text("name") to it.obj("payload") }

    private fun valid(name: String) = payloads("wireValid").single { it.first == name }.second

    @Test
    fun theValidRequestsAreExactlyWhatTheAppSends() {
        for (name in listOf("bash", "bash-exclude-from-context", "bash-command-at-limit")) {
            val request = valid(name)
            val exclude = "excludeFromContext" in request && request.flag("excludeFromContext")
            val built =
                Wire.objectOf(
                    "type" to "session.bash",
                    "requestId" to request.text("requestId"),
                    *bashFields(request.text("sessionId"), request.text("command"), exclude),
                )
            assertEquals(name, request, built)
        }
        val abort = valid("abort")
        assertEquals(
            abort,
            Wire.objectOf("type" to "session.bash.abort", "requestId" to abort.text("requestId"), "sessionId" to abort.text("sessionId")),
        )
    }

    @Test
    fun theAppNeverBuildsAnInvalidCommand() {
        // The other invalid requests carry keys or types the builder cannot produce.
        val names = setOf("bash-empty-command", "bash-oversized-command", "bash-oversized-command-multibyte")
        val commands = payloads("wireInvalid").filter { it.first in names }.map { it.second }
        assertEquals(3, commands.size)
        for (request in commands) {
            assertFalse(bashCommandFits(request.text("command")))
            assertTrue(runCatching { bashFields("live-session", request.text("command"), false) }.isFailure)
        }
    }

    @Test
    fun theValidResultsPassAndEveryInvalidOneIsRefused() {
        assertEquals(BashResult(0, false, false, "On branch dev\n"), parseBashResult(valid("result").obj("data"), "live-session"))
        assertEquals(BashResult(null, true, true, ""), parseBashResult(valid("result-signal").obj("data"), "live-session"))
        assertEquals(2, parseBashResult(valid("result-failed").obj("data"), "live-session").exitCode)
        validatedBashAborted(valid("abort-result").obj("data"), "live-session")
        assertTrue(runCatching { parseBashResult(valid("result").obj("data"), "other") }.isFailure)
        assertTrue(runCatching { validatedBashAborted(valid("abort-result").obj("data"), "other") }.isFailure)
        val results = payloads("wireInvalid").filter { it.second.text("type") == "result" }
        assertEquals(5, results.size)
        for ((name, payload) in results)
            assertTrue(name, runCatching { parseBashResult(payload.obj("data"), "live-session") }.isFailure)
    }

    @Test
    fun theValidOutputEventsPassAndEveryInvalidOneIsRefused() {
        assertEquals(BashOutput("live-session", "bash:1", 0, "On branch dev\n"), bashOutputEvent(valid("output")))
        assertEquals(BashOutput("live-session", "bash:1", 32768, "é"), bashOutputEvent(valid("output-later")))
        val events = payloads("wireInvalid").filter { it.second.text("type") == "host.event" }
        assertEquals(6, events.size)
        for ((name, payload) in events) assertTrue(name, runCatching { bashOutputEvent(payload) }.isFailure)
        // Another host event is no shell output.
        assertNull(bashOutputEvent(Wire.objectOf("type" to "host.event", "kind" to "session.job.output")))
    }

    @Test
    fun aGapInTheStreamShowsAsSkippedBytesAndARepeatIsDropped() {
        val stream = BashOutputStream()
        assertTrue(stream.append(BashOutput("s", "r", 0, "one\n")))
        assertFalse(stream.append(BashOutput("s", "r", 0, "one\n")))
        assertTrue(stream.append(BashOutput("s", "r", 10, "té\n")))
        assertEquals(
            listOf(JobOutputLine.Text("one"), JobOutputLine.Skipped(6), JobOutputLine.Text("té")),
            stream.lines,
        )
        assertEquals(14L, stream.bytes)
    }

    @Test
    fun errorsMapToTheirFailures() {
        fun failure(code: String, message: String? = null) = bashFailure(RemoteRequestException(code, hostMessage = message))
        assertEquals(BashFailure.SWITCHED_OFF, failure("unsupported", BASH_SWITCHED_OFF_MESSAGE))
        assertEquals(BashFailure.TERMINAL_SESSION, failure("unsupported", BASH_TERMINAL_SESSION_MESSAGE))
        assertEquals(BashFailure.UPDATE_PI, failure("unsupported", BASH_UPDATE_PI_MESSAGE))
        assertEquals(BashFailure.UNSUPPORTED, failure("unsupported", "unsupported"))
        assertEquals(BashFailure.UNSUPPORTED, bashFailure(RemoteRequestException.unsupported()))
        assertEquals(BashFailure.BUSY, failure("busy", "A shell command is already running"))
        assertEquals(BashFailure.CONNECTION, failure("offline"))
        assertEquals(BashFailure.FAILED, failure("forbidden"))
        assertEquals(BashFailure.FAILED, failure("not_found"))
        assertEquals(BashFailure.CONNECTION, bashFailure(RemoteRequestException.connectionLost()))
        assertEquals(BashFailure.CONNECTION, bashFailure(RemoteRequestException.timeout()))
        assertEquals(BashFailure.CONNECTION, bashFailure(IllegalStateException()))
        val turnedOff = valid("turned-off").obj("error")
        assertEquals(BashFailure.SWITCHED_OFF, failure(turnedOff.text("code"), turnedOff.text("message")))
    }

    private fun shellState(origin: String = "rpc", capable: Boolean = true, worker: Boolean = false) =
        RemoteState(
            selection = RemoteSelection("host", "project", "session"),
            connected = true,
            status = "idle",
            session = Wire.objectOf("id" to "session", "origin" to origin, "status" to "idle"),
            capabilities =
                if (!capable) emptySet()
                else if (worker) setOf(SESSION_BASH_CAPABILITY, SESSION_BASH_WORKER_CAPABILITY)
                else setOf(SESSION_BASH_CAPABILITY),
        )

    @Test
    fun aTerminalSessionRunsCommandsOnlyWithTheWorkerCapability() {
        // A marked host worker and an unmarked terminal session look alike to the gate.
        val marked = shellState(origin = "tui", worker = true)
        val markedSession = withHostWorker(Wire.objectOf("id" to "session", "origin" to "worker", "status" to "idle"))
        assertTrue(isHostWorker(markedSession))
        assertTrue(canRunShellCommands(marked))
        assertTrue(canRunShellCommands(marked.copy(session = markedSession)))
        assertFalse(canRunShellCommands(shellState(origin = "tui")))
        assertFalse(canRunShellCommands(shellState(origin = "tui", capable = false)))
        // The worker capability alone is not enough, and a refusal takes it back.
        assertFalse(canRunShellCommands(marked.copy(capabilities = setOf(SESSION_BASH_WORKER_CAPABILITY))))
        assertFalse(canRunShellCommands(marked.copy(unavailableCapabilities = setOf(SESSION_BASH_WORKER_CAPABILITY))))
        assertFalse(canRunShellCommands(marked.copy(unavailableCapabilities = setOf(SESSION_BASH_CAPABILITY))))
        assertFalse(canRunShellCommands(marked.copy(connected = false)))
        // A subagent child never runs commands.
        val child =
            Wire.objectOf("id" to "session", "origin" to "tui", "status" to "idle", "parentSessionId" to "parent")
        assertFalse(canRunShellCommands(marked.copy(session = child)))
        // Phone-started sessions are unchanged, with or without the worker capability.
        assertTrue(canRunShellCommands(shellState()))
        assertTrue(canRunShellCommands(shellState(worker = true)))
        assertFalse(canRunShellCommands(shellState(origin = "worker", worker = true)))
        assertTrue(shellDraft(marked.copy(draft = "!echo hi")) != null)
    }

    @Test
    fun theReloadRefusalHasItsOwnFailureAndText() {
        val failure = bashFailure(RemoteRequestException("unsupported", hostMessage = "Reload the pi remote extension to use this control"))
        assertEquals(BashFailure.RELOAD_EXTENSION, failure)
        assertEquals(R.string.remote_shell_reload_extension, bashFailureText(failure))
        assertNotEquals(R.string.remote_shell_terminal_session, bashFailureText(failure))
        val outside = valid("outside-gate").obj("error")
        assertEquals(BashFailure.TERMINAL_SESSION, bashFailure(RemoteRequestException(outside.text("code"), hostMessage = outside.text("message"))))
        val tooOld = valid("extension-too-old").obj("error")
        assertEquals(BashFailure.RELOAD_EXTENSION, bashFailure(RemoteRequestException(tooOld.text("code"), hostMessage = tooOld.text("message"))))
    }

    @Test
    fun aBangDraftRunsAsAShellCommandOnlyWhenTheGatePasses() {
        val runs = mutableListOf<Pair<String, Boolean>>()
        var cleared = 0
        val run = { command: String, exclude: Boolean -> runs += command to exclude; true }
        assertTrue(runShellDraft(shellState(), "  !! git status ", run) { cleared++ })
        assertTrue(runShellDraft(shellState(), "!ls", run) { cleared++ })
        assertEquals(listOf("git status" to true, "ls" to false), runs)
        assertEquals(2, cleared)
        // Without the capability, for a terminal session, offline or after a refusal it is a prompt.
        assertFalse(runShellDraft(shellState(capable = false), "!!x", run) {})
        assertFalse(runShellDraft(shellState(origin = "tui"), "!!x", run) {})
        assertTrue(runShellDraft(shellState(origin = "tui", worker = true), "!!x", run) {})
        runs.removeAt(runs.lastIndex)
        assertFalse(runShellDraft(shellState().copy(connected = false), "!!x", run) {})
        assertFalse(
            runShellDraft(shellState().copy(unavailableCapabilities = setOf(SESSION_BASH_CAPABILITY)), "!!x", run) {}
        )
        assertFalse(runShellDraft(shellState(), "hello !x", run) {})
        assertEquals(2, runs.size)
        // An empty command, or one while another runs, is no prompt either, and keeps the draft.
        assertTrue(runShellDraft(shellState(), "!! ", run) { cleared++ })
        val running = shellState().copy(bash = BashRun("session", "r", "sleep 9", false))
        assertTrue(runShellDraft(running, "!ls", run) { cleared++ })
        assertEquals(2, runs.size)
        assertEquals(2, cleared)
    }

    @Test
    fun theSendButtonFollowsTheShellRule() {
        assertTrue(canSendDraft(shellState().copy(draft = "!ls")))
        assertFalse(canSendDraft(shellState().copy(draft = "!")))
        assertFalse(canSendDraft(shellState().copy(draft = "!ls", bash = BashRun("session", "r", "sleep 9", false))))
        // Without the gate, `!` is ordinary text.
        assertTrue(canSendDraft(shellState(capable = false).copy(draft = "!")))
    }

    @Test
    fun noBusyActionSendsAShellDraft() {
        val running = shellState().copy(status = "running", draft = "!ls")
        assertNotNull(shellDraft(running))
        assertFalse(canQueueDraft(running))
        assertFalse(canSendDraft(running))
        // Ordinary text still queues while pi runs.
        assertTrue(canQueueDraft(running.copy(draft = "ls")))
    }

    @Test
    fun nothingGoesOutWhileAShellCommandRuns() {
        val busy = shellState().copy(draft = "hello", bash = BashRun("session", "r", "sleep 9", false))
        assertFalse(canSendDraft(busy))
        assertFalse(canQueueDraft(busy.copy(status = "running")))
        assertTrue(canSendDraft(busy.copy(bash = busy.bash?.copy(result = BashResult(0, false, false, "")))))
    }

    @Test
    fun aChildThatOffersAResumeTakesABangDraftAsText() {
        val child =
            shellState().copy(
                draft = "!ls",
                capabilities = setOf(SESSION_BASH_CAPABILITY, SUBAGENT_CONTROL_CAPABILITY),
                session = Wire.objectOf("id" to "session", "origin" to "rpc", "status" to "idle", "parentSessionId" to "parent"),
            )
        assertTrue(offersChildResume(child))
        assertNull(shellDraft(child))
        assertFalse(runShellDraft(child, "!ls", { _, _ -> error("not a shell command") }) {})
        assertTrue(canSendDraft(child))
    }

    @Test
    fun aShellDraftFeedsNoPromptPrediction() {
        assertFalse(recordsPrompt(shellState().copy(draft = "!ls")))
        assertTrue(recordsPrompt(shellState().copy(draft = "list the files")))
        // Without the gate `!ls` is a prompt like any other.
        assertTrue(recordsPrompt(shellState(capable = false).copy(draft = "!ls")))
    }
}
