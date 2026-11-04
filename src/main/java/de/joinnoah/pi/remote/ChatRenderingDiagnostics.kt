package de.joinnoah.pi.remote

import android.os.SystemClock
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

/** Temporary, device-local troubleshooting controls. Restarting the process resets all three. */
internal object ChatRenderingDiagnostics {
    data class Options(val recording: Boolean = false, val disableGlass: Boolean = false, val disableMotion: Boolean = false)
    private val mutableOptions = MutableStateFlow(Options())
    val options = mutableOptions.asStateFlow()
    fun update(value: Options) { mutableOptions.value = value }
}

/** No text, file paths, host/session names, screenshots, or raw item keys leave this recorder. */
internal class ChatRenderRecorder(private val clock: () -> Long = { SystemClock.elapsedRealtime() }) {
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
    data class Slot(val key: Any, val index: Int, val offset: Int, val size: Int, val type: String)
    data class Frame(
        val at: Long, val start: Int, val end: Int, val following: Boolean,
        val thinkingText: Boolean, val itemCount: Int, val slots: List<Slot>,
        val bounds: Map<Any, Bounds>, val scrolling: Boolean, val canScrollForward: Boolean,
    )
    private val positions = LinkedHashMap<Any, Bounds>()
    private val frames = ArrayDeque<Frame>()

    @Synchronized fun position(key: Any, bounds: Bounds) {
        positions.remove(key)
        positions[key] = bounds
        while (positions.size > 128) positions.remove(positions.keys.first())
    }

    @Synchronized fun record(start: Int, end: Int, following: Boolean, thinkingText: Boolean, itemCount: Int, slots: List<Slot>, scrolling: Boolean = false, canScrollForward: Boolean = false) {
        // Visible viewport only; caps also defend against accidentally recording an entire history.
        val visible = slots.take(32)
        frames.addLast(Frame(clock(), start, end, following, thinkingText, itemCount, visible,
            visible.mapNotNull { slot -> positions[slot.key]?.let { slot.key to it } }.toMap(), scrolling, canScrollForward))
        while (frames.size > 24) frames.removeFirst()
    }

    @Synchronized fun clear() { positions.clear(); frames.clear() }

    @Synchronized fun export(device: String, sdk: Int, version: Int, density: Float, fontScale: Float,
        options: ChatRenderingDiagnostics.Options): String {
        // Tokens are assigned anew for every export. Raw keys are never serialized or hashed.
        val tokens = mutableMapOf<Any, Int>()
        val origin = frames.firstOrNull()?.at ?: 0L
        val report = buildJsonObject {
            put("format", "pocketpi-chat-render-v1")
            put("deviceModel", device.take(80))
            put("sdk", sdk); put("versionCode", version)
            put("density", density); put("fontScale", fontScale)
            put("glassDisabled", options.disableGlass); put("motionDisabled", options.disableMotion)
            put("lastDrawAgeMs", frames.lastOrNull()?.let { (clock() - it.at).coerceAtLeast(0) })
            putJsonArray("frames") {
                frames.forEach { frame -> add(buildJsonObject {
                    put("elapsedMs", frame.at - origin)
                    put("viewportStart", frame.start); put("viewportEnd", frame.end)
                    put("following", frame.following); put("thinkingText", frame.thinkingText)
                    put("scrolling", frame.scrolling); put("canScrollForward", frame.canScrollForward)
                    put("itemCount", frame.itemCount)
                    putJsonArray("rows") {
                        frame.slots.forEach { slot -> add(buildJsonObject {
                            put("token", tokens.getOrPut(slot.key) { tokens.size + 1 })
                            put("index", slot.index); put("offset", slot.offset); put("size", slot.size)
                            put("type", slot.type.takeIf { it in setOf("bubble-user", "bubble", "thinking", "activity", "subagent") } ?: "other")
                            frame.bounds[slot.key]?.let { bounds -> putJsonArray("windowBounds") {
                                add(bounds.left); add(bounds.top); add(bounds.right); add(bounds.bottom)
                            } }
                        }) }
                    }
                }) }
            }
        }
        return report.toString()
    }
}

internal fun Modifier.chatRenderRow(recorder: ChatRenderRecorder, key: Any, enabled: Boolean): Modifier =
    if (!enabled) this else onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow(clipBounds = false)
        recorder.position(key, ChatRenderRecorder.Bounds(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt()))
    }

internal fun Modifier.chatRenderFrames(
    recorder: ChatRenderRecorder, enabled: Boolean, layout: () -> LazyListLayoutInfo,
    following: () -> Boolean, thinkingText: Boolean,
    scrolling: () -> Boolean = { false }, canScrollForward: () -> Boolean = { false },
): Modifier = if (!enabled) this else drawWithContent {
    drawContent()
    val info = layout()
    recorder.record(info.viewportStartOffset, info.viewportEndOffset, following(), thinkingText, info.totalItemsCount,
        info.visibleItemsInfo.map { ChatRenderRecorder.Slot(it.key, it.index, it.offset, it.size, it.contentType as? String ?: "other") }, scrolling(), canScrollForward())
}
