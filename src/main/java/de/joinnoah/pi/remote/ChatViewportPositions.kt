package de.joinnoah.pi.remote

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.saveable.Saver

/** Message-relative fallback avoids coupling saved positions to the 'load older' list prefix. */
internal data class ChatViewportPosition(
    val anchorId: String?,
    val messageIndex: Int,
    val offset: Int,
    val following: Boolean,
)

/** Navigation-owned, main-thread confined LRU. Stores IDs and offsets, never message text. */
internal class ChatViewportPositions(private val capacity: Int = 16) {
    init { require(capacity > 0) }
    private val positions = LinkedHashMap<RemoteNavKey.Chat, ChatViewportPosition>()

    operator fun get(key: RemoteNavKey.Chat): ChatViewportPosition? {
        val value = positions.remove(key) ?: return null
        positions[key] = value
        return value
    }

    operator fun set(key: RemoteNavKey.Chat, value: ChatViewportPosition) {
        require(value.messageIndex >= 0 && value.offset >= 0)
        positions.remove(key)
        positions[key] = value
        while (positions.size > capacity) positions.remove(positions.keys.first())
    }

    internal fun saved(): ArrayList<String> = ArrayList<String>().apply {
        positions.forEach { (key, position) ->
            addAll(listOf(key.routeId, key.projectId, key.sessionId, position.anchorId.orEmpty(),
                position.messageIndex.toString(), position.offset.toString(), position.following.toString()))
        }
    }

    companion object {
        internal fun restored(values: List<String>): ChatViewportPositions {
            val result = ChatViewportPositions()
            if (values.size % 7 != 0) return result
            values.chunked(7).takeLast(16).forEach { fields ->
                val index = fields[4].toIntOrNull()?.takeIf { it >= 0 } ?: return@forEach
                val offset = fields[5].toIntOrNull()?.takeIf { it >= 0 } ?: return@forEach
                val following = fields[6].toBooleanStrictOrNull() ?: return@forEach
                val key = RemoteNavKey.Chat(fields[0], fields[1], fields[2])
                result[key] = ChatViewportPosition(fields[3].ifEmpty { null }, index, offset, following)
            }
            return result
        }

        val Saver = Saver<ChatViewportPositions, ArrayList<String>>(
            save = { it.saved() }, restore = { restored(it) },
        )
    }
}

internal val LocalChatViewportPositions = staticCompositionLocalOf<ChatViewportPositions?> { null }

/** Resolve against current IDs: prepended pages and changed list prefixes cannot shift an anchor. */
internal fun restoredChatIndex(
    position: ChatViewportPosition,
    messageIds: List<String>,
    leadingCount: Int,
): Int? {
    if (messageIds.isEmpty()) return null
    val anchor = position.anchorId?.let(messageIds::indexOf)?.takeIf { it >= 0 }
    return leadingCount + (anchor ?: position.messageIndex.coerceAtMost(messageIds.lastIndex))
}
