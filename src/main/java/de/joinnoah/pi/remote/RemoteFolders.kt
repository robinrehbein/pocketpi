package de.joinnoah.pi.remote

import java.net.URI
import java.net.URLDecoder
import kotlinx.serialization.json.JsonObject

/** Browse, create, clone and open folders on the Mac (`project.open.v1`). */
internal const val PROJECT_OPEN_CAPABILITY = "project.open.v1"

private const val MAX_FOLDER_ENTRIES = 500
private const val MAX_FOLDER_NAME_BYTES = 100
private const val MAX_RELATIVE_PATH_BYTES = 4096
private const val MAX_CLONE_URL_BYTES = 2048

/**
 * An error result with the host's `details` object. Of the folder errors only `trust_required`
 * carries one (`{piConfig}`); every other code has none.
 */
internal class RemoteRequestException(val code: String, val details: JsonObject? = null) :
    IllegalStateException(code)

data class FolderEntry(
    val name: String,
    val git: Boolean,
    val piConfig: Boolean,
    val shared: Boolean,
    val trusted: Boolean,
)

data class FolderListing(
    val root: String,
    val path: String,
    val entries: List<FolderEntry>,
    val truncated: Boolean,
)

enum class ClonePhase(val wire: String) {
    CONNECTING("connecting"),
    COUNTING("counting"),
    RECEIVING("receiving"),
    RESOLVING("resolving"),
    CHECKOUT("checkout"),
}

/** Why a clone failed. [UNKNOWN] means the host no longer knows the clone (`not_found`). */
enum class CloneFailure(val wire: String) {
    AUTH_FAILED("auth_failed"),
    HOST_KEY("host_key"),
    REPO_NOT_FOUND("repo_not_found"),
    NETWORK("network"),
    TIMEOUT("timeout"),
    CANCELLED("cancelled"),
    FAILED("failed"),
    UNKNOWN("not_found"),
}

enum class CloneStage {
    RUNNING,
    SUCCEEDED,
    FAILED,
}

/** The one clone this device started and still shows. [path] is where the repository lands. */
data class CloneProgress(
    val routeId: String,
    val cloneId: String,
    val parent: String,
    val path: String,
    val stage: CloneStage = CloneStage.RUNNING,
    val phase: ClonePhase? = null,
    val percent: Int? = null,
    val failure: CloneFailure? = null,
)

/**
 * The host's `trust_required` for [path], shown to the user; confirming resends with `trust`.
 * [piConfig]: the folder brings project resources pi would load, so the dialog warns about them.
 */
data class FolderTrustPrompt(val routeId: String, val path: String, val piConfig: Boolean)

/** The folder command an error came from; the same code can mean different things. */
enum class FolderCommand {
    BROWSE,
    MKDIR,
    CLONE,
    OPEN,
}

/**
 * The folder browser of one host. [path] is relative to the browse root, "" being the root itself.
 * [root] is the host's display label for the root (`~`, `~/Code`) once a listing arrived.
 */
data class FolderBrowserState(
    val routeId: String? = null,
    val root: String? = null,
    val path: String = "",
    val entries: List<FolderEntry> = emptyList(),
    val truncated: Boolean = false,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: Int? = null,
    val notice: Int? = null,
    val working: Boolean = false,
    /** Clones this device started, by route, so a clone never shows on another host. */
    val clones: Map<String, CloneProgress> = emptyMap(),
    val trust: FolderTrustPrompt? = null,
) {
    /** The clone of the shown host. */
    val clone: CloneProgress?
        get() = routeId?.let(clones::get)
}

internal fun canOpenFolders(state: RemoteState): Boolean =
    state.connected && PROJECT_OPEN_CAPABILITY in state.capabilities

internal fun parentFolderPath(path: String): String = path.substringBeforeLast('/', "")

internal fun joinFolderPath(parent: String, name: String): String =
    if (parent.isEmpty()) name else "$parent/$name"

private val CONTROL = Regex("[\\u0000-\\u001f\\u007f]")
private val WHITESPACE_OR_CONTROL =
    Regex(
        "[\\s\\u0000-\\u001f\\u007f-\\u009f\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029" +
            "\\u202f\\u205f\\u3000\\ufeff]"
    )
private val SCP_LIKE =
    Regex("^([A-Za-z0-9_][A-Za-z0-9._-]*)@([A-Za-z0-9][A-Za-z0-9.-]*):(?!//)(.+)$")
private val BRACKETED_HOST = Regex("\\[[0-9A-Fa-f:.]*]")

/** Mirrors `relativePathSegments` in the host's `folders.ts`; null for a path the host rejects. */
internal fun relativePathSegments(path: String): List<String>? {
    if (path.toByteArray().size > MAX_RELATIVE_PATH_BYTES) return null
    if (path.isEmpty()) return emptyList()
    val segments = path.split('/')
    return segments.takeIf {
        it.none { segment ->
            segment.isEmpty() || segment.startsWith('.') || '\u0000' in segment || '\\' in segment
        }
    }
}

enum class FolderNameProblem {
    EMPTY,
    TOO_LONG,
    LEADING_DOT,
    SEPARATOR,
    CONTROL,
}

/** Mirrors `validFolderName` in the host's `folders.ts`. The host stays authoritative. */
internal fun folderNameProblem(name: String): FolderNameProblem? =
    when {
        name.isEmpty() -> FolderNameProblem.EMPTY
        name.toByteArray().size > MAX_FOLDER_NAME_BYTES -> FolderNameProblem.TOO_LONG
        name.startsWith('.') -> FolderNameProblem.LEADING_DOT
        '/' in name || '\\' in name -> FolderNameProblem.SEPARATOR
        CONTROL.containsMatchIn(name) -> FolderNameProblem.CONTROL
        else -> null
    }

internal fun validFolderName(name: String): Boolean = folderNameProblem(name) == null

private fun cloneNameFromPath(path: String): String? {
    var name = path.split('/').lastOrNull { it.isNotEmpty() } ?: return null
    if (name.endsWith(".git")) name = name.dropLast(4)
    return name.ifEmpty { null }
}

/** Mirrors `validCloneUrl` in the host's `folders.ts`: https, ssh and scp-like addresses only. */
internal fun validCloneUrl(url: String): Boolean {
    if (
        url.isEmpty() ||
            url.toByteArray().size > MAX_CLONE_URL_BYTES ||
            url.startsWith('-') ||
            WHITESPACE_OR_CONTROL.containsMatchIn(url) ||
            "::" in url.replaceFirst(BRACKETED_HOST, "")
    )
        return false
    SCP_LIKE.matchEntire(url)?.let {
        val path = it.groupValues[3]
        return !path.startsWith('-') && cloneNameFromPath(path) != null
    }
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase()
    if (scheme != "https" && scheme != "ssh") return false
    val host = uri.host
    if (host.isNullOrEmpty() || host.startsWith('-')) return false
    val userInfo = uri.rawUserInfo
    if (userInfo != null && (userInfo.startsWith('-') || userInfo.substringAfter(':', "").isNotEmpty()))
        return false
    if (scheme == "https" && (!uri.rawQuery.isNullOrEmpty() || !uri.rawFragment.isNullOrEmpty()))
        return false
    return cloneNameFromPath(uri.rawPath.orEmpty()) != null
}

/** The folder name the host derives when a clone has no explicit name. */
internal fun cloneNameFromUrl(url: String): String? {
    SCP_LIKE.matchEntire(url)?.let { return cloneNameFromPath(it.groupValues[3]) }
    val name = runCatching { URI(url) }.getOrNull()?.rawPath?.let(::cloneNameFromPath) ?: return null
    return runCatching { URLDecoder.decode(name.replace("+", "%2B"), "UTF-8") }.getOrNull()
}

internal fun parseFolderListing(data: JsonObject, requested: String): FolderListing {
    require(data.text("kind") == "fs.listing")
    val path = data.text("path")
    require(path == requested)
    val entries =
        data.array("entries").map {
            FolderEntry(
                name = it.text("name").also { name ->
                    require(relativePathSegments(name)?.size == 1)
                },
                git = it.flag("git"),
                piConfig = it.flag("piConfig"),
                shared = it.flag("shared"),
                trusted = it.flag("trusted"),
            )
        }
    require(entries.size <= MAX_FOLDER_ENTRIES)
    require(entries.mapTo(mutableSetOf()) { it.name }.size == entries.size)
    return FolderListing(data.text("root").also { require(it.isNotBlank()) }, path, entries, data.flag("truncated"))
}

/** A phase a newer host may add shows as no phase rather than failing the channel. */
private fun clonePhase(value: String): ClonePhase? =
    ClonePhase.entries.firstOrNull { it.wire == value }

/** An error a newer host may add shows as a plain failure. */
private fun cloneFailure(value: String): CloneFailure =
    CloneFailure.entries.firstOrNull { it.wire == value && it != CloneFailure.UNKNOWN }
        ?: CloneFailure.FAILED

private fun clonePercent(value: JsonObject): Int? =
    if ("percent" in value) value.long("percent").also { require(it in 0..100) }.toInt() else null

/** Applies a `clone` status result or a `host.event` to [clone]; the IDs must already match. */
internal fun CloneProgress.updatedBy(value: JsonObject): CloneProgress {
    val stage =
        when (value.text("kind")) {
            "clone.progress" -> "running"
            "clone.finished", "clone" -> value.text("state")
            else -> error("Unknown clone update")
        }
    return when (stage) {
        "running" ->
            copy(
                stage = CloneStage.RUNNING,
                phase = value.optionalText("phase")?.let(::clonePhase)
                    ?: phase.takeIf { value.text("kind") == "clone" },
                percent = clonePercent(value),
                failure = null,
            )
        "succeeded" -> {
            val landed = value.optionalText("path") ?: path
            require(relativePathSegments(landed)?.isNotEmpty() == true)
            copy(stage = CloneStage.SUCCEEDED, path = landed, phase = null, percent = null, failure = null)
        }
        "failed" ->
            copy(
                stage = CloneStage.FAILED,
                phase = null,
                percent = null,
                failure = cloneFailure(value.text("error")),
            )
        // A state a newer host may add counts as a plain failure.
        else -> copy(stage = CloneStage.FAILED, phase = null, percent = null, failure = CloneFailure.FAILED)
    }
}

/** Validates a `host.event` payload. Returns null for a kind this app does not know. */
internal fun cloneEvent(payload: JsonObject): JsonObject? {
    when (payload.text("kind")) {
        "clone.progress" -> {
            Wire.keys(payload, setOf("type", "kind", "cloneId", "phase"), setOf("percent"))
            payload.text("phase")
            clonePercent(payload)
        }
        "clone.finished" ->
            when (payload.text("state")) {
                "succeeded" -> {
                    Wire.keys(payload, setOf("type", "kind", "cloneId", "state", "path"))
                    require(relativePathSegments(payload.text("path"))?.isNotEmpty() == true)
                }
                "failed" -> {
                    Wire.keys(payload, setOf("type", "kind", "cloneId", "state", "error"))
                    payload.text("error")
                }
                else -> Unit
            }
        else -> return null
    }
    require(payload.text("cloneId").isNotEmpty() && payload.text("cloneId").length <= 64)
    return payload
}

/** The user-facing message for a folder command's error code. */
internal fun folderErrorMessage(error: Throwable, command: FolderCommand): Int =
    when (error.message) {
        "busy" ->
            when (command) {
                FolderCommand.BROWSE -> R.string.remote_folders_error_browse_busy
                FolderCommand.CLONE -> R.string.remote_folders_error_busy
                FolderCommand.MKDIR, FolderCommand.OPEN -> R.string.remote_folders_error_host_busy
            }
        "internal" ->
            if (command == FolderCommand.OPEN) R.string.remote_folders_error_internal
            else R.string.remote_request_error
        "trust_scope" -> R.string.remote_folders_error_trust_scope
        "trust_denied" -> R.string.remote_folders_error_trust_denied
        "exists" -> R.string.remote_folders_error_exists
        "outside_root" -> R.string.remote_folders_error_outside_root
        "root_missing" -> R.string.remote_folders_error_root_missing
        "invalid_path" -> R.string.remote_folders_error_invalid_path
        "invalid_name" -> R.string.remote_folders_error_invalid_name
        "invalid_url" -> R.string.remote_folders_error_invalid_url
        "not_found" -> R.string.remote_folders_error_not_found
        "offline" -> R.string.remote_folders_error_offline
        "forbidden" -> R.string.remote_folders_error_forbidden
        "trust_required" -> R.string.remote_folders_error_trust_required
        "cloning" -> R.string.remote_folders_clone_in_progress
        else -> R.string.remote_request_error
    }

internal fun cloneFailureMessage(failure: CloneFailure): Int =
    when (failure) {
        CloneFailure.AUTH_FAILED -> R.string.remote_folders_clone_auth_failed
        CloneFailure.HOST_KEY -> R.string.remote_folders_clone_host_key
        CloneFailure.REPO_NOT_FOUND -> R.string.remote_folders_clone_repo_not_found
        CloneFailure.NETWORK -> R.string.remote_folders_clone_network
        CloneFailure.TIMEOUT -> R.string.remote_folders_clone_timeout
        CloneFailure.CANCELLED -> R.string.remote_folders_clone_cancelled
        CloneFailure.FAILED -> R.string.remote_folders_clone_failed
        CloneFailure.UNKNOWN -> R.string.remote_folders_clone_unknown
    }

internal fun clonePhaseLabel(phase: ClonePhase?): Int =
    when (phase) {
        null -> R.string.remote_folders_clone_starting
        ClonePhase.CONNECTING -> R.string.remote_folders_clone_phase_connecting
        ClonePhase.COUNTING -> R.string.remote_folders_clone_phase_counting
        ClonePhase.RECEIVING -> R.string.remote_folders_clone_phase_receiving
        ClonePhase.RESOLVING -> R.string.remote_folders_clone_phase_resolving
        ClonePhase.CHECKOUT -> R.string.remote_folders_clone_phase_checkout
    }

internal fun folderNameProblemMessage(problem: FolderNameProblem): Int? =
    when (problem) {
        FolderNameProblem.EMPTY -> null
        FolderNameProblem.TOO_LONG -> R.string.remote_folders_name_too_long
        FolderNameProblem.LEADING_DOT -> R.string.remote_folders_name_leading_dot
        FolderNameProblem.SEPARATOR -> R.string.remote_folders_name_separator
        FolderNameProblem.CONTROL -> R.string.remote_folders_name_control
    }
