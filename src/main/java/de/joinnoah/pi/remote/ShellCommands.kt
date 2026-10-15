package de.joinnoah.pi.remote

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Shell commands in a host-owned session (`session.bash`), like `!` and `!!` in pi's terminal. The
 * host advertises it on the `capabilities.v2:` route only, and only while the Mac's switch is on.
 */
internal const val SESSION_BASH_CAPABILITY = "session.bash.v1"

/** The longest command the host accepts, in UTF-8 bytes. */
internal const val MAX_BASH_COMMAND_BYTES = 16_384
private const val MAX_BASH_RESULT_OUTPUT_BYTES = 65_536
private const val MAX_BASH_OUTPUT_TEXT_BYTES = 32_768

/**
 * The host sets no timeout on `session.bash`: a command runs until it ends, is stopped or pi exits.
 * [withTimeoutOrNull][kotlinx.coroutines.withTimeoutOrNull] never schedules a timer this long, so
 * the request waits for its result or a lost connection.
 */
internal const val BASH_REQUEST_TIMEOUT_MILLIS = Long.MAX_VALUE

/** The host's `unsupported` messages; only they tell its three refusals apart. */
internal const val BASH_SWITCHED_OFF_MESSAGE = "Shell commands are turned off on this Mac"
internal const val BASH_TERMINAL_SESSION_MESSAGE = "Shell commands are only available for sessions started from the phone"
internal const val BASH_UPDATE_PI_MESSAGE = "Update pi to run shell commands"

/** Why a shell command did not run to its result. */
enum class BashFailure {
    /** The Mac's `bashCommands` switch is off. */
    SWITCHED_OFF,

    /** A terminal pi registered the session; only phone-started sessions run commands. */
    TERMINAL_SESSION,

    /** pi on the Mac does not know the `bash` RPC command. */
    UPDATE_PI,

    /** Another `unsupported` refusal, such as a host without [SESSION_BASH_CAPABILITY]. */
    UNSUPPORTED,

    /** A turn, another command or a change is in progress in the session. */
    BUSY,

    /** pi exited or the connection ended; the command may not have finished. */
    CONNECTION,

    FAILED,
}

/** A `bash` result: [output] is the last at most 64 KiB of the output, the authoritative end. */
data class BashResult(val exitCode: Int?, val cancelled: Boolean, val truncated: Boolean, val output: String)

/**
 * The shell command run from this phone in [sessionId], live or finished. Only one runs at a
 * time; a finished one stays until the user closes it or another session opens.
 */
data class BashRun(
    val sessionId: String,
    /** The `session.bash` request ID, which its output events name. */
    val requestId: String,
    val command: String,
    val excludeFromContext: Boolean,
    /** The output as display lines: streamed while it runs, the result's output once it ended. */
    val lines: List<JobOutputLine> = emptyList(),
    /** The absolute index of `lines[0]`, as in [JobView.firstLine]. */
    val firstLine: Long = 0,
    /** UTF-8 bytes of the streamed output the lines account for, shown or skipped. */
    val shownBytes: Long = 0,
    /** Stop was accepted or is on its way; the result follows with `cancelled`. */
    val stopping: Boolean = false,
    val result: BashResult? = null,
    val failure: BashFailure? = null,
) {
    val running: Boolean
        get() = result == null && failure == null
}

/** A validated `session.bash.output` event: [text] starts at the UTF-8 byte [offset] of the output. */
internal data class BashOutput(val sessionId: String, val requestId: String, val offset: Long, val text: String)

/** A command the host takes: not empty, and at most [MAX_BASH_COMMAND_BYTES] as UTF-8. */
internal fun bashCommandFits(command: String): Boolean =
    command.isNotEmpty() && Wire.fitsUtf8(command, MAX_BASH_COMMAND_BYTES)

/** The fields of a `session.bash` request; `excludeFromContext` is sent only when true. */
internal fun bashFields(sessionId: String, command: String, excludeFromContext: Boolean): Array<Pair<String, Any?>> {
    require(bashCommandFits(command))
    return if (excludeFromContext)
        arrayOf("sessionId" to sessionId, "command" to command, "excludeFromContext" to true)
    else arrayOf("sessionId" to sessionId, "command" to command)
}

/** Validates a `bash` result against the session it was requested for. */
internal fun parseBashResult(data: JsonObject, sessionId: String): BashResult {
    Wire.keys(data, setOf("kind", "sessionId", "exitCode", "cancelled", "truncated", "output"))
    require(data.text("kind") == "bash" && data.text("sessionId") == sessionId)
    val exitCode =
        if (data["exitCode"] is JsonNull) null
        else data.long("exitCode").also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
    val output = data.text("output")
    require(Wire.fitsUtf8(output, MAX_BASH_RESULT_OUTPUT_BYTES))
    return BashResult(exitCode, data.flag("cancelled"), data.flag("truncated"), output)
}

/** Validates a `session.bash.abort` result. */
internal fun validatedBashAborted(data: JsonObject, sessionId: String) {
    Wire.keys(data, setOf("kind", "sessionId"))
    require(data.text("kind") == "accepted" && data.text("sessionId") == sessionId)
}

/** Validates a `session.bash.output` `host.event`. Returns null for another kind. */
internal fun bashOutputEvent(payload: JsonObject): BashOutput? {
    if (payload.text("kind") != "session.bash.output") return null
    Wire.keys(payload, setOf("type", "kind", "sessionId", "requestId", "offset", "text"))
    val requestId = payload.text("requestId")
    require(requestId.isNotEmpty())
    val offset = payload.long("offset")
    require(offset >= 0)
    val text = payload.text("text")
    require(text.isNotEmpty() && Wire.fitsUtf8(text, MAX_BASH_OUTPUT_TEXT_BYTES))
    return BashOutput(payload.text("sessionId"), requestId, offset, text)
}

/**
 * Whether the selected session runs shell commands: the host advertises [SESSION_BASH_CAPABILITY],
 * no refusal took it back, the session is host-owned and the Mac is connected. An older host
 * closes the connection on a command it does not know, so nothing is sent without it.
 */
internal fun canRunShellCommands(state: RemoteState): Boolean =
    SESSION_BASH_CAPABILITY in state.capabilities &&
        SESSION_BASH_CAPABILITY !in state.unavailableCapabilities &&
        state.connected &&
        state.session != null &&
        state.session.optionalText("origin") == "rpc" &&
        state.session.optionalText("id") == state.selection.sessionId

/** A composer draft that runs as a shell command: `!command`, or `!!command` kept out of pi's context. */
internal data class ShellDraft(val command: String, val excludeFromContext: Boolean)

/**
 * The shell command the draft names, or null when Send prompts pi as usual: without
 * [canRunShellCommands], for a draft not starting with `!`, and for one with a quote or
 * attachments, which only a prompt carries. The command may be empty; it is then not sendable.
 */
internal fun shellDraft(state: RemoteState): ShellDraft? {
    if (state.attachments.isNotEmpty() || state.quote != null || !canRunShellCommands(state)) return null
    val text = state.draft.trimStart()
    if (!text.startsWith("!")) return null
    val exclude = text.startsWith("!!")
    return ShellDraft(text.substring(if (exclude) 2 else 1).trim(), exclude)
}

/** The shell draft can run now: it names a command the host takes and no other one runs. */
internal fun shellDraftSendable(shell: ShellDraft, state: RemoteState): Boolean =
    bashCommandFits(shell.command) && state.bash?.running != true

/**
 * Runs the composer's [text] as a shell command when it is one in [state], through [run], and
 * clears the draft once [run] started it. True whenever [text] is a shell draft, even one that did
 * not start: such a draft never goes to pi as a prompt. False leaves it for the normal send.
 */
internal fun runShellDraft(
    state: RemoteState,
    text: String,
    run: (command: String, excludeFromContext: Boolean) -> Boolean,
    clear: () -> Unit,
): Boolean {
    val current = state.copy(draft = text)
    val shell = shellDraft(current) ?: return false
    if (shellDraftSendable(shell, current) && run(shell.command, shell.excludeFromContext)) clear()
    return true
}

/** Maps a failed `session.bash` request. */
internal fun bashFailure(e: Exception): BashFailure =
    when (e) {
        is RemoteRequestException ->
            when (e.kind) {
                RemoteRequestException.Kind.UNSUPPORTED -> {
                    val message = e.hostMessage.orEmpty()
                    when {
                        message.startsWith(BASH_SWITCHED_OFF_MESSAGE) -> BashFailure.SWITCHED_OFF
                        message.startsWith(BASH_TERMINAL_SESSION_MESSAGE) -> BashFailure.TERMINAL_SESSION
                        message.startsWith(BASH_UPDATE_PI_MESSAGE) -> BashFailure.UPDATE_PI
                        else -> BashFailure.UNSUPPORTED
                    }
                }
                RemoteRequestException.Kind.TIMEOUT, RemoteRequestException.Kind.CONNECTION_LOST -> BashFailure.CONNECTION
                RemoteRequestException.Kind.HOST_ERROR ->
                    when (e.code) {
                        "busy" -> BashFailure.BUSY
                        "offline" -> BashFailure.CONNECTION
                        else -> BashFailure.FAILED
                    }
            }
        else -> BashFailure.CONNECTION
    }

@StringRes
internal fun bashFailureText(failure: BashFailure): Int =
    when (failure) {
        BashFailure.SWITCHED_OFF -> R.string.remote_shell_switched_off
        BashFailure.TERMINAL_SESSION -> R.string.remote_shell_terminal_session
        BashFailure.UPDATE_PI -> R.string.remote_shell_update_pi
        BashFailure.UNSUPPORTED -> R.string.remote_shell_unsupported
        BashFailure.BUSY -> R.string.remote_shell_busy
        BashFailure.CONNECTION -> R.string.remote_shell_connection
        BashFailure.FAILED -> R.string.remote_shell_failed
    }

/**
 * Builds [BashRun.lines] from the streamed events. An event whose [BashOutput.offset] lies past the
 * bytes shown follows a gap the host dropped while the relay was backed up: the gap shows as
 * skipped bytes. An event that starts inside what is shown repeats it and is dropped.
 */
internal class BashOutputStream {
    private val buffer = JobOutputBuffer()

    /** UTF-8 bytes accounted for, shown or skipped. */
    var bytes = 0L
        private set

    val lines: List<JobOutputLine>
        get() = buffer.lines

    val firstLine: Long
        get() = buffer.firstLine

    /** False when [event] added nothing. */
    fun append(event: BashOutput): Boolean {
        if (event.offset < bytes) return false
        if (event.offset > bytes) buffer.append(JobOutputPart.Skipped(event.offset - bytes))
        buffer.append(JobOutputPart.Text(event.text))
        bytes = event.offset + event.text.encodeToByteArray().size
        return true
    }
}

/** The finished command's display lines: its result's output, the authoritative end. */
internal fun bashResultLines(result: BashResult): List<JobOutputLine> =
    JobOutputBuffer().apply {
        append(JobOutputPart.Text(result.output))
        finish()
    }.lines

// ---- UI --------------------------------------------------------------------------------------

/** Says above the field that Send runs the draft as a shell command, and whether pi sees its output. */
@Composable
internal fun ShellModeLabel(excludeFromContext: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("shellMode"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Outlined.Terminal, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Text(
            stringResource(
                if (excludeFromContext) R.string.remote_shell_command_private else R.string.remote_shell_command
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** What ended [run]: its exit code, a stop or a refusal; null while it runs. */
@Composable
private fun shellStatus(run: BashRun): String? {
    run.failure?.let { return stringResource(bashFailureText(it)) }
    val result = run.result ?: return null
    return when {
        result.cancelled -> stringResource(R.string.remote_shell_cancelled)
        result.exitCode != null -> stringResource(R.string.remote_shell_exit_code, result.exitCode)
        else -> stringResource(R.string.remote_shell_ended)
    }
}

/**
 * The shell command run from this phone, above the composer: its command line, the live output,
 * Stop while it runs, and once it ended its status and Close.
 */
@Composable
internal fun ShellCommandPanel(run: BashRun, onStop: () -> Unit, onClose: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).testTag("shellPanel"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (run.running) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.Terminal, null, Modifier.size(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "$ " + run.command,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (run.excludeFromContext)
                        Text(
                            stringResource(R.string.remote_shell_command_private),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
                if (run.running)
                    TextButton(onClick = onStop, enabled = !run.stopping, modifier = Modifier.testTag("shellStop")) {
                        Text(stringResource(if (run.stopping) R.string.remote_jobs_stopping else R.string.remote_jobs_stop))
                    }
                else
                    TextButton(onClick = onClose, modifier = Modifier.testTag("shellClose")) {
                        Text(stringResource(R.string.remote_shell_close))
                    }
            }
            if (run.lines.isNotEmpty())
                Box(Modifier.fillMaxWidth().heightIn(max = 240.dp).testTag("shellOutput")) {
                    JobOutput(run.lines, run.firstLine, Modifier.fillMaxWidth())
                }
            else if (run.result != null)
                Text(
                    stringResource(R.string.remote_shell_no_output),
                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            val status = shellStatus(run)
            if (status != null)
                Text(
                    status,
                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag("shellStatus"),
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (run.failure != null || run.result?.let { it.cancelled || it.exitCode != 0 } == true)
                            MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            if (run.result?.truncated == true)
                Text(
                    stringResource(R.string.remote_shell_truncated),
                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp).testTag("shellTruncated"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
    }
}
