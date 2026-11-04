package de.joinnoah.pi.remote

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*

/** One automatic attempt per cursor: a failed page remains explicitly retryable with the button. */
internal class ChatHistoryPageGate {
    private var attempted: String? = null

    fun request(cursor: String?, eligible: Boolean): Boolean {
        if (!eligible || cursor == null || cursor == attempted) return false
        attempted = cursor
        return true
    }
}

/** Only a real backwards scroll arms paging; opening/restoring/jumping into a chat does not. */
@Composable
internal fun AutoLoadChatHistory(
    chat: Any?,
    list: LazyListState,
    cursor: String?,
    enabled: Boolean,
    programmatic: () -> Boolean,
    messageKeys: List<Any>,
    messageStartIndex: () -> Int,
    userScrollRevision: () -> Long,
    load: () -> Unit,
    stopFollowing: () -> Unit,
    setProgrammatic: (Boolean) -> Unit,
) {
    val gate = remember(chat) { ChatHistoryPageGate() }
    val currentCursor by rememberUpdatedState(cursor)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentProgrammatic by rememberUpdatedState(programmatic)
    val keyIndices = remember(messageKeys) { messageKeys.withIndex().associate { it.value to it.index } }
    val currentKeyIndices by rememberUpdatedState(keyIndices)
    val currentLoad by rememberUpdatedState(load)
    val currentStopFollowing by rememberUpdatedState(stopFollowing)
    val currentUserScrollRevision by rememberUpdatedState(userScrollRevision)
    var anchor by remember(chat) { mutableStateOf<HistoryAnchor?>(null) }
    LaunchedEffect(chat, cursor) {
        val pending = anchor ?: return@LaunchedEffect
        if (cursor == pending.cursor) return@LaunchedEffect
        withFrameNanos { }
        // A final page removes its header and can normalize list indices/offsets. Only an
        // actual user scroll invalidates the anchor, not that layout-only normalization.
        if (currentUserScrollRevision() != pending.userScrollRevision) {
            anchor = null
            return@LaunchedEffect
        }
        val messageIndex = currentKeyIndices[pending.key]
        if (messageIndex == null) {
            anchor = null
            return@LaunchedEffect
        }
        val index = messageStartIndex() + messageIndex
        setProgrammatic(true)
        try {
            list.scrollToItem(index, -pending.itemOffset)
            anchor = null
        } finally {
            setProgrammatic(false)
        }
    }
    LaunchedEffect(chat, list) {
        if (chat == null) return@LaunchedEffect
        var previous = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
        snapshotFlow {
            HistoryScrollSample(
                list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset,
                list.isScrollInProgress, currentProgrammatic(), currentEnabled, currentCursor,
            )
        }.collect { sample ->
            val position = sample.index to sample.offset
            val backwards = position.first < previous.first ||
                (position.first == previous.first && position.second < previous.second)
            previous = position
            val nearStart = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key in currentKeyIndices }
                ?.let { (currentKeyIndices[it.key] ?: Int.MAX_VALUE) < 4 } == true
            if (gate.request(sample.cursor, sample.enabled && sample.scrolling &&
                    !sample.programmatic && backwards && nearStart)) {
                // Keyed rows preserve position unless the persistent load-older header is the
                // first visible row. In that case retain the first actual message explicitly.
                val visible = list.layoutInfo.visibleItemsInfo
                if (visible.firstOrNull()?.key !in currentKeyIndices) {
                    visible.firstOrNull { it.key in currentKeyIndices }?.let {
                        anchor = HistoryAnchor(sample.cursor, it.key, it.offset, currentUserScrollRevision())
                    }
                }
                currentStopFollowing()
                currentLoad()
            }
        }
    }
}

private data class HistoryAnchor(
    val cursor: String?,
    val key: Any,
    val itemOffset: Int,
    val userScrollRevision: Long,
)

private data class HistoryScrollSample(
    val index: Int,
    val offset: Int,
    val scrolling: Boolean,
    val programmatic: Boolean,
    val enabled: Boolean,
    val cursor: String?,
)
