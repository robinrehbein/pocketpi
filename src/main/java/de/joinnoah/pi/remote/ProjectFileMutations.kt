package de.joinnoah.pi.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The host creates an empty file (`session.files.create`), renames a file within its folder
 * (`session.files.rename`) or deletes one (`session.files.delete`), each behind its own capability
 * and all off by default. A host advertises one only while it owns its durable mutation journal,
 * file writes are on and its platform probe passed for that operation.
 */
internal const val FILES_CREATE_CAPABILITY = "session.files.create.v1"
internal const val FILES_RENAME_CAPABILITY = "session.files.rename.v1"
internal const val FILES_DELETE_CAPABILITY = "session.files.delete.v1"

/**
 * `session.files.mutation.status`, `.list` and `.acknowledge`: the outcome of an earlier mutation,
 * the host's journal epoch and its unresolved operations. Advertised whenever the host's journal
 * is open, also with file writes off.
 */
internal const val FILES_MUTATION_RECOVERY_CAPABILITY = "session.files.mutation-recovery.v1"

/** At most this many operations come in one `files.mutation.list` page. */
internal const val MAX_FILE_MUTATION_LIST_OPERATIONS = 100

/** What the host recorded for one operation. */
enum class FileMutationHostState(val wire: String) {
    /** Reserved and not finished yet; ask again with `session.files.mutation.status`. */
    PENDING("pending"),
    APPLIED("applied"),
    /** Refused before any effect, with a [FileMutationRefusal]. */
    REFUSED("refused"),
    /** May or may not have happened; never retried, only acknowledged. */
    UNKNOWN("unknown"),
}

/** Why the host refused an operation before any effect. */
enum class FileMutationRefusal(val wire: String) {
    /** The source's version is no longer the one the request named. */
    CONFLICT("conflict"),
    /** The destination exists already. */
    EXISTS("exists"),
    /** The source or its folder is missing. */
    NOT_FOUND("not_found"),
    /** Denied, ignored, not a regular file, a symlink or inside a nested repository. */
    INVALID_PATH("invalid_path"),
    /** The request's journal epoch is not the host's current one; nothing ran. */
    EPOCH("epoch"),
}

internal val FileMutationKind.wire: String
    get() = when (this) {
        FileMutationKind.CREATE -> "create"
        FileMutationKind.RENAME -> "rename"
        FileMutationKind.DELETE -> "delete"
    }

internal val FileMutationKind.command: String
    get() = "session.files.$wire"

internal val FileMutationKind.capability: String
    get() = when (this) {
        FileMutationKind.CREATE -> FILES_CREATE_CAPABILITY
        FileMutationKind.RENAME -> FILES_RENAME_CAPABILITY
        FileMutationKind.DELETE -> FILES_DELETE_CAPABILITY
    }

/** One operation as the host recorded it. [reason] is set exactly when [state] is refused. */
data class FileMutationRecord(
    val operationId: String,
    val kind: FileMutationKind,
    val path: String,
    val destinationPath: String? = null,
    val state: FileMutationHostState,
    val reason: FileMutationRefusal? = null,
    val acknowledged: Boolean = false,
)

/** One `files.mutation.list` page: the host's current journal [epoch] and unresolved operations. */
data class FileMutationList(
    val epoch: String,
    val operations: List<FileMutationRecord>,
    val nextAfter: String? = null,
)

/** Canonical unpadded base64url of exactly 16 bytes: operation IDs, epochs, versions, cursors. */
internal fun canonicalMutationId(value: String): Boolean =
    value.length == 22 && runCatching { Wire.decode(value, 16) }.isSuccess

/** A nonempty relative file path the host accepts: no leading `/`, `\`, NUL or `.`/`..`/empty segment. */
internal fun validMutationPath(value: String): Boolean =
    value.isNotEmpty() && value.encodeToByteArray().size <= 4096 && !value.startsWith('/') &&
        '\\' !in value && '\u0000' !in value && value.split('/').none { it.isEmpty() || it == "." || it == ".." }

/** A file name a create or rename may use: one segment, valid as a path. */
internal fun validMutationName(value: String): Boolean = '/' !in value && validMutationPath(value)

/**
 * The command type and fields of [request], without `type` and `requestId`. Rename and delete need
 * their [FileMutationRequest.baseVersion]; create sends none.
 */
internal fun fileMutationCommand(request: FileMutationRequest): Pair<String, List<Pair<String, Any?>>> {
    val base = listOf(
        "sessionId" to request.sessionId,
        "operationId" to request.operationId,
        "epoch" to request.epoch,
        "path" to request.path,
    )
    val fields = when (request.kind) {
        FileMutationKind.CREATE -> base
        FileMutationKind.RENAME -> base + listOf("destinationPath" to request.destinationPath, "baseVersion" to request.baseVersion)
        FileMutationKind.DELETE -> base + listOf("baseVersion" to request.baseVersion)
    }
    return request.kind.command to fields
}

internal fun fileMutationStatusFields(sessionId: String, operationId: String, epoch: String): List<Pair<String, Any?>> =
    listOf("sessionId" to sessionId, "operationId" to operationId, "epoch" to epoch)

internal fun fileMutationListFields(sessionId: String, after: String?): List<Pair<String, Any?>> =
    listOfNotNull("sessionId" to sessionId, after?.let { "after" to it })

/** Whether [request] fits the wire contract: canonical IDs, valid paths, a same-folder rename. */
internal fun validMutationRequest(request: FileMutationRequest): Boolean =
    request.sessionId.isNotEmpty() && canonicalMutationId(request.operationId) && canonicalMutationId(request.epoch) &&
        validMutationPath(request.path) &&
        when (request.kind) {
            FileMutationKind.CREATE -> request.destinationPath == null && request.baseVersion == null
            FileMutationKind.DELETE -> request.destinationPath == null && request.baseVersion?.let(::canonicalMutationId) == true
            FileMutationKind.RENAME -> {
                val destination = request.destinationPath
                destination != null && validMutationPath(destination) && destination != request.path &&
                    parentPath(destination) == parentPath(request.path) &&
                    request.baseVersion?.let(::canonicalMutationId) == true
            }
        }

private fun JsonObject.onlyMutationKeys(required: Set<String>, optional: Set<String> = emptySet()) =
    require(keys.containsAll(required) && (keys - required - optional).isEmpty())

private fun JsonObject.strictFlag(key: String): Boolean =
    requireNotNull((getValue(key) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull)

private fun JsonObject.strictText(key: String): String =
    (getValue(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("Not a string: $key")

private fun mutationKind(value: String): FileMutationKind =
    requireNotNull(FileMutationKind.entries.firstOrNull { it.wire == value })

/** The record fields shared by a `files.mutation` result and a list entry. */
private fun record(value: JsonObject): FileMutationRecord {
    val operationId = value.strictText("operationId").also { require(canonicalMutationId(it)) }
    val kind = mutationKind(value.strictText("operation"))
    val path = value.strictText("path").also { require(validMutationPath(it)) }
    val destination =
        if (value.containsKey("destinationPath")) value.strictText("destinationPath").also { require(validMutationPath(it)) } else null
    require((kind == FileMutationKind.RENAME) == (destination != null))
    if (destination != null) require(destination != path && parentPath(destination) == parentPath(path))
    val state = requireNotNull(FileMutationHostState.entries.firstOrNull { it.wire == value.strictText("state") })
    val reason =
        if (value.containsKey("reason")) requireNotNull(FileMutationRefusal.entries.firstOrNull { it.wire == value.strictText("reason") })
        else null
    require((state == FileMutationHostState.REFUSED) == (reason != null))
    val acknowledged = value.strictFlag("acknowledged")
    require(!acknowledged || state == FileMutationHostState.UNKNOWN)
    return FileMutationRecord(operationId, kind, path, destination, state, reason, acknowledged)
}

/**
 * Validates the `files.mutation` result of a create, rename, delete, status or acknowledge
 * against the operation it answers: the same session, operation ID, kind and paths.
 */
internal fun parseFileMutationRecord(
    data: JsonObject,
    sessionId: String,
    operationId: String,
    kind: FileMutationKind,
    path: String,
    destinationPath: String?,
): FileMutationRecord {
    data.onlyMutationKeys(
        setOf("kind", "sessionId", "operationId", "operation", "path", "state", "acknowledged"),
        setOf("destinationPath", "reason"),
    )
    require(data.strictText("kind") == "files.mutation" && data.strictText("sessionId") == sessionId)
    val result = record(data)
    require(result.operationId == operationId && result.kind == kind && result.path == path && result.destinationPath == destinationPath)
    return result
}

internal fun parseFileMutationRecord(data: JsonObject, request: FileMutationRequest): FileMutationRecord =
    parseFileMutationRecord(data, request.sessionId, request.operationId, request.kind, request.path, request.destinationPath)

/** Validates one `files.mutation.list` page: its epoch and at most 100 unresolved operations. */
internal fun parseFileMutationList(data: JsonObject, sessionId: String): FileMutationList {
    data.onlyMutationKeys(setOf("kind", "sessionId", "epoch", "operations"), setOf("nextAfter"))
    require(data.strictText("kind") == "files.mutation.list" && data.strictText("sessionId") == sessionId)
    val epoch = data.strictText("epoch").also { require(canonicalMutationId(it)) }
    val entries = data.array("operations")
    require(entries.size <= MAX_FILE_MUTATION_LIST_OPERATIONS)
    val operations = entries.map { value ->
        value.onlyMutationKeys(setOf("operationId", "operation", "path", "state", "acknowledged"), setOf("destinationPath"))
        record(value).also {
            require(it.state == FileMutationHostState.PENDING || it.state == FileMutationHostState.UNKNOWN)
        }
    }
    require(operations.map { it.operationId }.distinct().size == operations.size)
    val nextAfter = if (data.containsKey("nextAfter")) data.strictText("nextAfter").also { require(canonicalMutationId(it)) } else null
    return FileMutationList(epoch, operations, nextAfter)
}
