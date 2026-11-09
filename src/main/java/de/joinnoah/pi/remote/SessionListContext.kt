package de.joinnoah.pi.remote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The host adds the context fill of live sessions to `sessions.list` items when the list asks with
 * `includeContext: true`. Only the `capabilities.v3:` route advertises it, and an older host refuses
 * the unknown flag, so the phone sends it only while this capability is known.
 */
internal const val SESSION_LIST_CONTEXT_CAPABILITY = "session.list.context.v1"

/** JavaScript's `Number.MAX_SAFE_INTEGER`: the host sends counts as safe integers only. */
private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

private val contextKeys = setOf("usedTokens", "contextWindow", "percent")

/** The fields of a `sessions.list` request; each opt-in flag is sent only as literal `true`. */
internal fun sessionListFields(
    projectId: String,
    includeCompaction: Boolean,
    includeContext: Boolean,
): Array<Pair<String, Any?>> =
    buildList<Pair<String, Any?>> {
        add("projectId" to projectId)
        if (includeCompaction) add("includeCompaction" to true)
        if (includeContext) add("includeContext" to true)
    }.toTypedArray()

/**
 * Reads a list item's `context` [value] for [sessionId]: null when the item has none (unknown, never
 * zero). Throws [IllegalArgumentException] for any other shape: an explicit `null`, unknown or
 * missing keys, fractional or negative counts, a window below 1, or a percent without tokens.
 * Tokens above the window and a percent above 100 are valid after an overflow.
 */
internal fun parseSessionListContext(value: JsonElement?, sessionId: String): SessionContextUsage? {
    if (value == null) return null
    val data = value as? JsonObject ?: throw IllegalArgumentException("Invalid session context")
    require(data.keys == contextKeys) { "Invalid session context keys" }
    fun count(key: String): Long? {
        val element = data.getValue(key)
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive
        require(primitive != null && !primitive.isString) { "Invalid $key" }
        val number = primitive.longOrNull
        require(number != null && number in 0..MAX_SAFE_INTEGER) { "Invalid $key" }
        return number
    }
    val used = count("usedTokens")
    val window = requireNotNull(count("contextWindow")) { "Invalid contextWindow" }
    require(window >= 1) { "Invalid contextWindow" }
    val percent = when (val element = data.getValue("percent")) {
        is JsonNull -> null
        else -> {
            val primitive = element as? JsonPrimitive
            require(primitive != null && !primitive.isString) { "Invalid percent" }
            val number = primitive.doubleOrNull
            require(number != null && number.isFinite() && number >= 0) { "Invalid percent" }
            number
        }
    }
    require(percent == null || used != null) { "Percent without tokens" }
    return SessionContextUsage(sessionId, null, null, used, window, percent)
}

/** The context fill of a list row, or null when it is unknown or malformed (a malformed one shows as unknown). */
internal fun sessionListContext(session: JsonObject): SessionContextUsage? =
    try {
        parseSessionListContext(session["context"], session.optionalText("id").orEmpty())
    } catch (_: IllegalArgumentException) {
        null
    }

/** How full a row's context reads: the colour of its ring and bar. */
internal enum class ContextFillLevel { NORMAL, WARNING, CRITICAL }

/**
 * What a row shows of [usage]: the rounded [percent] for the label and the [level]. The level
 * follows the chat's context offer ([contextOfferLevel]): [ContextFillLevel.WARNING] from
 * [CONTEXT_OFFER_PERCENT] (or earlier on large windows), [ContextFillLevel.CRITICAL] from
 * [CONTEXT_OFFER_REOFFER_PERCENT].
 */
internal data class ContextFill(val percent: Int, val fraction: Float, val level: ContextFillLevel)

/** The fill a row shows, or null when the percent is unknown: then no ring or bar shows at all. */
internal fun contextFill(usage: SessionContextUsage?): ContextFill? {
    val percent = usage?.percent ?: return null
    val level = when (contextOfferLevel(usage)) {
        CONTEXT_OFFER_REOFFER_PERCENT -> ContextFillLevel.CRITICAL
        CONTEXT_OFFER_PERCENT -> ContextFillLevel.WARNING
        else -> ContextFillLevel.NORMAL
    }
    return ContextFill(percent.roundToInt(), (percent / 100.0).toFloat().coerceIn(0f, 1f), level)
}

/** The fill a ring or bar draws, on the node that owns it; tests read it, accessibility services ignore it. */
internal val ContextFillKey = SemanticsPropertyKey<ContextFill>("ContextFill")
internal var SemanticsPropertyReceiver.contextFillShown by ContextFillKey

@Composable
internal fun contextFillColor(level: ContextFillLevel): Color =
    when (level) {
        ContextFillLevel.NORMAL -> MaterialTheme.colorScheme.outline
        ContextFillLevel.WARNING -> waitingColor()
        ContextFillLevel.CRITICAL -> MaterialTheme.colorScheme.error
    }

/** What TalkBack appends to a row with a known fill, e.g. "Context 82% full". */
@Composable
internal fun contextFillDescription(fill: ContextFill?): String? =
    fill?.let { stringResource(R.string.remote_context_full_title, it.percent) }

/**
 * A thin ring around a list avatar showing [fill]; it draws inside the avatar's bounds, so the row
 * keeps its height. The status badge is drawn after it and stays on top.
 */
@Composable
internal fun ContextRing(fill: ContextFill, modifier: Modifier = Modifier) {
    val color = contextFillColor(fill.level)
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f)
    Canvas(modifier) {
        val stroke = 2.5.dp.toPx()
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        val topLeft = Offset(inset, inset)
        drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
        if (fill.fraction > 0f)
            drawArc(color, -90f, 360f * fill.fraction, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** "Context 64%" and a thin bar, for the clean-up cards. */
@Composable
internal fun ContextFillBar(fill: ContextFill, modifier: Modifier = Modifier) {
    val color = contextFillColor(fill.level)
    Column(modifier.testTag("contextFillBar"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.remote_session_context_label, fill.percent),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { fill.fraction },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            drawStopIndicator = {},
        )
    }
}
