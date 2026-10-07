package de.joinnoah.pi.remote

import androidx.annotation.StringRes
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Reading the session tree and moving around it (`session.tree`, `.navigate`, `.fork`). */
internal const val SESSION_TREE_CAPABILITY = "session.tree.v1"

internal const val MAX_TREE_NODES = 800
internal const val MAX_TREE_PREVIEW_BYTES = 120

/** Longest text a `tree.navigated` or edit fork result carries, in UTF-8 bytes. */
private const val MAX_TREE_TEXT_BYTES = 128 * 1024
private const val MAX_TREE_TIMESTAMP_BYTES = 64

/** Rows deeper than this share the last indent, so a long chain still fits the screen. */
internal const val MAX_TREE_INDENT = 6

/** The IDs `session.tree.navigate` accepts; the host's extension parses the same pattern. */
private val TREE_NODE_ID = Regex("[A-Za-z0-9_-]{1,64}")

enum class TreeNodeKind(val wire: String) {
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool"),
    COMPACTION("compaction"),
    BRANCH_SUMMARY("branch_summary"),
    CUSTOM_MESSAGE("custom_message");

    companion object {
        fun of(wire: String): TreeNodeKind? = entries.firstOrNull { it.wire == wire }
    }
}

data class TreeNode(
    val id: String,
    val parentId: String?,
    val kind: TreeNodeKind,
    val preview: String,
    val timestamp: String,
    val children: Int,
    val forkable: Boolean,
)

data class SessionTree(
    val sessionId: String,
    /** The kept node the session continues from; null for an empty session. */
    val leafId: String?,
    val truncated: Boolean,
    /** The host can run `session.tree.navigate` now. */
    val canNavigate: Boolean,
    val nodes: List<TreeNode>,
)

/** The outcome of a `session.tree.navigate`. */
data class TreeNavigation(val leafId: String?, val text: String?)

/** Why a tree request did not produce its result. */
enum class TreeFailure {
    OFFLINE,
    /** The host or its pi lacks `/tree` support (an older host, or the Remote extension is not loaded). */
    UNSUPPORTED,
    /** Another change is running in the session. */
    BUSY,
    /** The user stopped the summary, or another extension vetoed the move. */
    CANCELLED,
    NOT_FOUND,
    INVALID,
    /** The host's report did not arrive or failed on its side; the move may have happened. */
    UNKNOWN_RESULT,
    /** The reply broke the protocol. */
    PROTOCOL,
    FAILED,
}

internal class TreeException(val failure: TreeFailure) : Exception(failure.name)

sealed interface TreeLoadResult {
    data class Loaded(val tree: SessionTree) : TreeLoadResult

    data class Failed(val failure: TreeFailure) : TreeLoadResult
}

sealed interface TreeNavigateResult {
    data class Done(val navigation: TreeNavigation) : TreeNavigateResult

    data class Failed(val failure: TreeFailure) : TreeNavigateResult
}

private fun utf8Size(value: String) = value.encodeToByteArray().size

private fun JsonObject.strictBoolean(key: String): Boolean {
    val primitive = getValue(key) as? JsonPrimitive
    require(primitive != null && !primitive.isString)
    return requireNotNull(primitive.booleanOrNull)
}

private fun JsonObject.strictCount(key: String): Int {
    val value = long(key)
    require(value in 0..Int.MAX_VALUE)
    return value.toInt()
}

private fun JsonObject.nullableId(key: String): String? {
    val value = getValue(key)
    if (value is JsonNull) return null
    return text(key).also { require(opaqueId(it)) }
}

/** The text field of a result: at most 128 KiB, possibly empty. */
private fun JsonObject.treeText(key: String): String =
    text(key).also { require(utf8Size(it) <= MAX_TREE_TEXT_BYTES) }

/** Validates a `session.tree` result against the session it was requested for. */
internal fun validatedTree(data: JsonObject, sessionId: String): SessionTree {
    Wire.keys(data, setOf("kind", "sessionId", "leafId", "truncated", "canNavigate", "nodes"))
    require(data.text("kind") == "tree")
    require(data.text("sessionId") == sessionId)
    val leafId = data.nullableId("leafId")
    val truncated = data.strictBoolean("truncated")
    val canNavigate = data.strictBoolean("canNavigate")
    val raw = data.getValue("nodes") as? JsonArray
    require(raw != null && raw.size <= MAX_TREE_NODES)
    val seen = HashSet<String>()
    val nodes =
        raw.map { element ->
            val item = element as? JsonObject
            require(item != null)
            Wire.keys(
                item,
                setOf("id", "parentId", "kind", "preview", "timestamp", "children", "forkable"),
            )
            val id = item.text("id")
            require(opaqueId(id))
            val parentId = item.nullableId("parentId")
            val kind = requireNotNull(TreeNodeKind.of(item.text("kind")))
            val preview = item.text("preview")
            require(utf8Size(preview) <= MAX_TREE_PREVIEW_BYTES && '\r' !in preview && '\n' !in preview)
            val timestamp = item.text("timestamp")
            require(timestamp.isNotEmpty() && utf8Size(timestamp) <= MAX_TREE_TIMESTAMP_BYTES)
            val node = TreeNode(id, parentId, kind, preview, timestamp, item.strictCount("children"), item.strictBoolean("forkable"))
            // Every ID appears once, and a parent is listed before its children.
            require(parentId == null || parentId in seen)
            require(seen.add(id))
            node
        }
    require(leafId == null || leafId in seen)
    return SessionTree(sessionId, leafId, truncated, canNavigate, nodes)
}

/** Validates a `session.tree.navigate` result against the session it was requested for. */
internal fun validatedNavigation(data: JsonObject, sessionId: String): TreeNavigation {
    Wire.keys(data, setOf("kind", "sessionId", "leafId"), setOf("text"))
    require(data.text("kind") == "tree.navigated")
    require(data.text("sessionId") == sessionId)
    val leafId = data.nullableId("leafId")
    val text = if ("text" in data) data.treeText("text") else null
    return TreeNavigation(leafId, text)
}

/** Whether [id] is a node ID the host can navigate to. */
internal fun validTreeNodeId(id: String): Boolean = TREE_NODE_ID.matches(id)

/** The fields of a `session.tree`; throws [IllegalArgumentException] for an invalid request. */
internal fun treeFields(sessionId: String): Array<Pair<String, Any?>> {
    require(opaqueId(sessionId))
    return arrayOf("sessionId" to sessionId)
}

/** The fields of a `session.tree.navigate`; throws [IllegalArgumentException] for an invalid request. */
internal fun treeNavigateFields(
    sessionId: String,
    nodeId: String,
    summarize: Boolean,
): Array<Pair<String, Any?>> {
    require(opaqueId(sessionId) && validTreeNodeId(nodeId))
    return arrayOf("sessionId" to sessionId, "nodeId" to nodeId, "summarize" to summarize)
}

/** The fields of a `session.tree.fork`; throws [IllegalArgumentException] for an invalid request. */
internal fun treeForkFields(sessionId: String, nodeId: String): Array<Pair<String, Any?>> {
    require(opaqueId(sessionId) && opaqueId(nodeId))
    return arrayOf("sessionId" to sessionId, "nodeId" to nodeId)
}

/** Nesting depth of each node; a root is 0. Parents come before their children in [nodes]. */
internal fun treeDepths(nodes: List<TreeNode>): Map<String, Int> {
    val depths = HashMap<String, Int>(nodes.size)
    for (node in nodes) depths[node.id] = node.parentId?.let { (depths[it] ?: 0) + 1 } ?: 0
    return depths
}

/** How many indent steps a row at [depth] gets. */
internal fun treeIndent(depth: Int): Int = depth.coerceIn(0, MAX_TREE_INDENT)

/** The composer content a navigation back to a user message returns; none for no text. */
internal fun treeDraft(text: String?): ForkDraft? = text?.takeIf { it.isNotEmpty() }?.let(::forkDraft)

/** The instant of an ISO-8601 [timestamp], or null when it does not parse. */
internal fun treeTimestampMillis(timestamp: String): Long? =
    runCatching { Instant.parse(timestamp).toEpochMilli() }.getOrNull()

// ---- Gating -------------------------------------------------------------------------------

/**
 * `/tree` is offered when the host advertises [SESSION_TREE_CAPABILITY], a session is selected and
 * the app is connected and not loading. It is a read, so it stays available while the session runs.
 */
internal fun canShowTree(state: RemoteState): Boolean =
    state.connected && !state.loading && state.selection.sessionId != null &&
        SESSION_TREE_CAPABILITY in state.capabilities &&
        SESSION_TREE_CAPABILITY !in state.unavailableCapabilities

/** Why "Continue from here" is or is not offered for one node. */
internal enum class TreeContinue {
    AVAILABLE,
    /** The node already is the session's leaf. */
    ALREADY_HERE,
    /** The host says the session cannot be navigated (terminal, history, no extension). */
    NOT_NAVIGABLE,
    /** The session is not idle. */
    NOT_IDLE,
}

internal fun continueAvailability(state: RemoteState, tree: SessionTree, node: TreeNode): TreeContinue =
    when {
        !canShowTree(state) || !tree.canNavigate || !validTreeNodeId(node.id) -> TreeContinue.NOT_NAVIGABLE
        node.id == tree.leafId -> TreeContinue.ALREADY_HERE
        state.status != "idle" || state.sending || state.configurationChanging -> TreeContinue.NOT_IDLE
        else -> TreeContinue.AVAILABLE
    }

/** Navigating in place needs the host's go-ahead, an idle session and a node that is not the leaf. */
internal fun canContinueInPlace(state: RemoteState, tree: SessionTree, node: TreeNode): Boolean =
    continueAvailability(state, tree, node) == TreeContinue.AVAILABLE

/** Forking from a node follows the rewind rules (`session.fork.v1`, stopped source) and needs a forkable node. */
internal fun canForkFromNode(state: RemoteState, node: TreeNode): Boolean =
    canShowTree(state) && canFork(state) && forkStopped(state) && node.forkable

// ---- Errors -------------------------------------------------------------------------------

/** How the host's `internal` error starts when it does not know whether a navigation happened. */
private const val NAVIGATION_UNKNOWN_PREFIX = "Navigation result unknown"

/** The failure behind a thrown request error; [navigating] for a `session.tree.navigate`. */
internal fun treeFailure(e: Exception, navigating: Boolean = false): TreeFailure =
    when (e) {
        is TreeException -> e.failure
        is RemoteRequestException ->
            when (e.code) {
                "busy" -> TreeFailure.BUSY
                "cancelled" -> TreeFailure.CANCELLED
                "unsupported" -> TreeFailure.UNSUPPORTED
                "not_found" -> TreeFailure.NOT_FOUND
                "invalid_request" -> TreeFailure.INVALID
                "offline" -> TreeFailure.OFFLINE
                "timeout" -> TreeFailure.UNKNOWN_RESULT
                // Other internal errors (no model for a summary, ...) are clean failures.
                "internal" ->
                    if (e.hostMessage?.startsWith(NAVIGATION_UNKNOWN_PREFIX) == true) TreeFailure.UNKNOWN_RESULT
                    else TreeFailure.FAILED
                else -> TreeFailure.FAILED
            }
        is IllegalStateException ->
            when {
                e.message == "Request timed out" -> TreeFailure.UNKNOWN_RESULT
                // The reply of a move may have been lost with the connection.
                navigating && e.message == "Connection lost" -> TreeFailure.UNKNOWN_RESULT
                else -> TreeFailure.FAILED
            }
        else -> TreeFailure.FAILED
    }

/** Whether the host may have moved the leaf although the app has no answer; the chat then refreshes. */
internal fun treeResultUnknown(failure: TreeFailure): Boolean = failure == TreeFailure.UNKNOWN_RESULT

/** The text for a failed tree read: a session pi has not saved yet has no tree. */
@StringRes
internal fun treeLoadFailureText(failure: TreeFailure): Int =
    if (failure == TreeFailure.NOT_FOUND) R.string.remote_tree_load_not_found else treeFailureText(failure)

@StringRes
internal fun treeFailureText(failure: TreeFailure): Int =
    when (failure) {
        TreeFailure.OFFLINE -> R.string.remote_tree_offline
        TreeFailure.UNSUPPORTED -> R.string.remote_tree_unsupported
        TreeFailure.BUSY -> R.string.remote_tree_busy
        TreeFailure.CANCELLED -> R.string.remote_tree_cancelled
        TreeFailure.NOT_FOUND -> R.string.remote_tree_not_found
        TreeFailure.INVALID -> R.string.remote_tree_invalid
        TreeFailure.UNKNOWN_RESULT -> R.string.remote_tree_unknown_result
        TreeFailure.PROTOCOL, TreeFailure.FAILED -> R.string.remote_tree_failed
    }

/** The error shown when the host refuses a fork from a tree node with [code]. */
@StringRes
internal fun treeForkError(code: String?): Int =
    when (code) {
        "busy" -> R.string.remote_fork_busy
        "unsupported" -> R.string.remote_tree_unsupported
        "not_found" -> R.string.remote_tree_fork_not_found
        "invalid_request" -> R.string.remote_tree_fork_invalid
        else -> R.string.remote_request_error
    }
