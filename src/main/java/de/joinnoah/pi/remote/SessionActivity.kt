package de.joinnoah.pi.remote

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One line of what the selected session is doing right now, shown above the composer. */
internal data class SessionActivityLine(
    @StringRes val labelRes: Int,
    val detail: String? = null,
    val glyphKind: StatusGlyphKind = StatusGlyphKind.SESSION_RUNNING,
)

internal fun sessionActivityAllowed(state: RemoteState, destination: RemoteSelection?): Boolean =
    destination != null && state.selection == destination && destination.sessionId != null &&
        state.connected && !state.loading && !state.stale && !state.preview &&
        state.status == "running" && state.questions.isEmpty()

/** A raw tool name is a safe detail only when it is short and free of anything command-like. */
private fun toolNameDetail(name: String?): String? =
    name?.takeIf { it.length <= 64 && isOpaqueId(it) }

/**
 * The compact activity line for the open chat, or null when nothing should show. The session must
 * genuinely run on the connection and be the chat's own destination: a live task list wins with
 * its first in-progress task, otherwise the newest streaming tool does, else a generic waiting
 * text. Idle, waiting, loading, stale, offline and previewed sessions show nothing, and questions
 * hold the line back so the pending answer is the only call to act.
 */
internal fun sessionActivityLine(
    state: RemoteState,
    items: List<ConversationItem>,
    destination: RemoteSelection?,
): SessionActivityLine? {
    if (!sessionActivityAllowed(state, destination)) return null
    val task = state.tasks.items.firstOrNull { it.status == TaskStatus.IN_PROGRESS }
    if (state.tasks.freshness == TaskFreshness.LIVE && task != null) {
        val text = task.activeForm?.takeIf(String::isNotBlank) ?: task.text
        return SessionActivityLine(R.string.remote_chat_activity_working, text.takeIf(String::isNotBlank), StatusGlyphKind.TASK_RUNNING)
    }
    val tool = items.lastOrNull { it is ConversationItem.Activity && it.state == "streaming" }
        as? ConversationItem.Activity ?: return genericLine()
    val summary = tool.summary
    if (summary.labelRes == null) return genericLine(toolNameDetail(tool.name), StatusGlyphKind.TOOL_RUNNING)
    // A shell command belongs to the tool card, not the compact line; path tools keep their file.
    val detail = summary.detail.takeUnless { tool.name in setOf("bash", "bash_bg", USER_BASH_TOOL) }
    return SessionActivityLine(summary.labelRes, detail, StatusGlyphKind.TOOL_RUNNING)
}

private fun genericLine(detail: String? = null, glyphKind: StatusGlyphKind = StatusGlyphKind.SESSION_RUNNING) =
    SessionActivityLine(
        if (detail == null) R.string.remote_waiting_for_output else R.string.remote_chat_activity_working,
        detail,
        glyphKind,
    )

internal fun waitingForOutputAllowed(
    state: RemoteState,
    destination: RemoteSelection?,
    activity: SessionActivityLine?,
): Boolean = sessionActivityAllowed(state, destination) && activity == null

/** The spinner and activity label immediately above the chat composer. */
@Composable
internal fun ChatCurrentActivity(line: SessionActivityLine?) {
    if (line == null) return
    val label = stringResource(line.labelRes)
    val text = line.detail?.let { "$label · $it" } ?: label
    Row(
        Modifier.fillMaxWidth().testTag("chatCurrentActivity").padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusGlyph(line.glyphKind, MaterialTheme.colorScheme.primary, Modifier.size(14.dp))
        ActivityMarquee(text, Modifier.weight(1f), motionEnabled = activityMotionEnabled(), active = activityLifecycleResumed())
    }
}

internal fun activityMarqueeDurationMs(distancePx: Int, density: Float): Int =
    ((distancePx / density / 30f) * 1000).toInt().coerceAtLeast(1)

@Composable
private fun activityMotionEnabled(): Boolean {
    val context = LocalContext.current
    val resolver = context.contentResolver
    var enabled by remember(context) { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                enabled = ValueAnimator.areAnimatorsEnabled()
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                enabled = ValueAnimator.areAnimatorsEnabled()
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        enabled = ValueAnimator.areAnimatorsEnabled()
        onDispose {
            context.unregisterReceiver(receiver)
            resolver.unregisterContentObserver(observer)
        }
    }
    return enabled
}

@Composable
private fun activityLifecycleResumed(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return resumed
}

@Composable
internal fun ActivityMarquee(text: String, modifier: Modifier = Modifier, motionEnabled: Boolean, active: Boolean) {
    val style = MaterialTheme.typography.labelSmall
    val density = LocalDensity.current
    val textWidth = rememberTextMeasurer().measure(text, style = style, maxLines = 1).size.width
    var viewportWidth by remember { mutableIntStateOf(0) }
    val distance = (textWidth - viewportWidth).coerceAtLeast(0)
    val direction = LocalLayoutDirection.current
    val offset = remember(text, viewportWidth, textWidth, motionEnabled, active, direction) { Animatable(0f) }
    LaunchedEffect(offset, distance, motionEnabled, active, density.density, direction) {
        if (motionEnabled && active && viewportWidth > 0 && distance > 0) {
            val duration = activityMarqueeDurationMs(distance, density.density)
            while (true) {
                delay(1200)
                val target = if (direction == LayoutDirection.Ltr) -distance.toFloat() else distance.toFloat()
                offset.animateTo(target, tween(duration, easing = LinearEasing))
                delay(1200)
                offset.animateTo(0f, tween(duration, easing = LinearEasing))
            }
        }
    }
    Box(modifier.onSizeChanged { viewportWidth = it.width }.clipToBounds()
        .semantics { this.text = AnnotatedString(text) }.testTag("activityMarquee")) {
        val scrolling = motionEnabled && active && viewportWidth > 0 && distance > 0
        Text(
            text,
            (if (scrolling) Modifier.wrapContentSize(Alignment.CenterStart, unbounded = true)
                .requiredWidth(with(density) { textWidth.toDp() })
                .graphicsLayer { translationX = offset.value }
            else Modifier.fillMaxWidth()).clearAndSetSemantics { }.testTag("activityMarqueeText"),
            style = style,
            maxLines = 1,
            softWrap = false,
            overflow = if (scrolling) TextOverflow.Clip else TextOverflow.Ellipsis,
        )
    }
}
