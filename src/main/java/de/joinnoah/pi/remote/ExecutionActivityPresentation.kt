package de.joinnoah.pi.remote

import android.os.SystemClock
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay

/*
 * What the UI shows of `session_activity.v1`. Only a typed, fresh [ActivitySnapshot.Active] on the
 * current connection animates; nothing here reads transcript streaming, text or session status to
 * guess a phase. A connection that did not negotiate the capability keeps the legacy behaviour
 * (callers get null from [typedSessionActivity]).
 */

/**
 * The palette the user picked, as the app's colour scheme actually applies it (see
 * [effectiveThemePalette]). The theme root provides it; anything outside a provider gets BRAND.
 */
internal val LocalThemePalette = staticCompositionLocalOf { ThemePalette.BRAND }

/** [palette] as [appColorScheme] applies it on [sdk]: DYNAMIC without wallpaper colours is BRAND. */
internal fun effectiveThemePalette(palette: ThemePalette, sdk: Int): ThemePalette = palette.effective(sdk)

/** The typed state of one session (or actor) at one monotonic instant. */
internal sealed interface TypedActivity {
    /** Fresh and active: the only state that animates. */
    data class Live(val snapshot: ActivitySnapshot.Active) : TypedActivity

    /** Negotiated, but missing, idle, unknown, expired or of another connection: never animates. */
    data object Static : TypedActivity
}

/** The typed activity of [sessionId] at [now], or null when the connection did not negotiate it. */
internal fun typedSessionActivity(state: RemoteState, sessionId: String?, now: Long): TypedActivity? {
    val activity = state.executionActivity
    if (!activity.negotiated) return null
    // Only the open, current chat's own view: never a preview, a loading or stale screen, or a
    // session other than the selected one, whatever the ledger still holds.
    if (sessionId == null || !state.connected || state.loading || state.stale || state.preview ||
        state.selection.sessionId != sessionId) return TypedActivity.Static
    // An explicit wait (status or open question) owns the header; no phase animates beside it.
    if (state.status == "waiting" || state.questions.isNotEmpty()) return TypedActivity.Static
    val view = activity.sessions[sessionId]
    val snapshot = view?.snapshot as? ActivitySnapshot.Active ?: return TypedActivity.Static
    return if (activity.freshness(sessionId, now).live) TypedActivity.Live(snapshot) else TypedActivity.Static
}

/** A tool card on a negotiated connection: [live] only for a typed tool phase naming its exact call. */
internal data class ToolCardActivity(val live: Boolean)

/**
 * Null on a legacy connection. Otherwise the card bounces only while the fresh typed state is in
 * the tool phase and lists the card's exact `toolCallId`; a parent ID gives no credit, and a card
 * that drops out is never read as finished.
 */
internal fun typedToolCardActivity(typed: TypedActivity?, tool: ConversationItem.Activity): ToolCardActivity? {
    typed ?: return null
    val snapshot = (typed as? TypedActivity.Live)?.snapshot
    val id = tool.toolCallId
    return ToolCardActivity(
        snapshot != null && id != null && snapshot.phase == ActivityPhase.Tool && snapshot.tools.any { it.toolCallId == id },
    )
}

/** What one subagent row of a card may show from the actor ledger. */
internal data class ActorRowActivity(
    /** The actor's own fresh phase; null when it must not animate. */
    val live: ActivitySnapshot.Active?,
    /** This actor itself awaits input, per its latest accepted ledger observation on this connection. */
    val needsInput: Boolean,
)

/**
 * The ledger entry of exactly ([parentSessionId], the card's [toolCallId], the agent's
 * `instanceId`), only when the agent names that same spawn call and still reports `running`.
 * Never matched by name, index or child session; null means "show the card's static glyph".
 */
internal fun actorRowActivity(
    state: RemoteState,
    parentSessionId: String?,
    toolCallId: String?,
    agent: AgentProgress,
    now: Long,
): ActorRowActivity? {
    val activity = state.executionActivity
    if (!activity.negotiated || !state.connected || parentSessionId == null || toolCallId == null) return null
    if (state.loading || state.stale || state.preview || state.selection.sessionId != parentSessionId) return null
    val instanceId = agent.instanceId ?: return null
    if (agent.spawnToolCallId != toolCallId || agent.state != "running") return null
    val key = ActivityActorKey(parentSessionId, toolCallId, instanceId)
    val actor = activity.actors[key] ?: return null
    if (actor.view.connectionId != activity.connectionId) return null
    val snapshot = actor.view.snapshot
    val fresh = activity.actorFreshness(key, now).live
    // This agent's own `needsInput` in its latest accepted ledger observation on this connection,
    // for the open, current chat (gated above). It never animates, so it needs no activity
    // deadline, and an active, idle or unknown source does not disprove it; the ledger clears it on
    // the agent's next accepted upsert or a disconnect.
    if (actor.needsInput) return ActorRowActivity(null, true)
    // The parent's explicit wait owns the screen; no actor phase animates beside it.
    if (state.status == "waiting" || state.questions.isNotEmpty()) return null
    if (!fresh || snapshot !is ActivitySnapshot.Active) return null
    return ActorRowActivity(snapshot, false)
}

/**
 * The subagent strip on a negotiated connection: the fresh typed views of the exact child session
 * IDs it lists. A running child without one shows a static, muted glyph; the strip never takes an
 * actor's phase or input wait by name or heuristic. Null on a legacy connection.
 */
internal data class StripActivity(val live: Map<String, ActivitySnapshot.Active>)

internal fun stripActivity(state: RemoteState, sessionIds: Collection<String>, now: Long): StripActivity? {
    val activity = state.executionActivity
    if (!activity.negotiated) return null
    if (!state.connected || state.loading || state.stale || state.preview) return StripActivity(emptyMap())
    val live = sessionIds.mapNotNull { id ->
        val snapshot = activity.sessions[id]?.snapshot as? ActivitySnapshot.Active
        if (snapshot != null && activity.freshness(id, now).live) id to snapshot else null
    }.toMap()
    return StripActivity(live)
}

/** What a strip row's running glyph shows: null keeps the legacy glyph. */
internal data class StripRowActivity(val live: ActivitySnapshot.Active?)

internal fun stripRowActivity(activity: StripActivity?, entry: SubagentStripEntry): StripRowActivity? {
    activity ?: return null
    if (entry.state != "running" || entry.lastKnown) return null
    return StripRowActivity(entry.sessionId?.let { activity.live[it] })
}

/** The shared glyph of a phase: the snake for pending, thinking and writing, the bounce for tools. */
internal fun activityGlyphKind(phase: ActivityPhase): StatusGlyphKind =
    if (phase == ActivityPhase.Tool) StatusGlyphKind.TOOL_RUNNING else StatusGlyphKind.SESSION_RUNNING

/**
 * pi's `visibleThinkingLevel`, except that a missing level stays unknown (null): `off` and
 * `minimal` are near-greys that vanish, so they take `medium`.
 */
internal fun visibleActivityThinkingLevel(level: String?): String? =
    when (level) {
        null -> null
        "off", "minimal" -> "medium"
        else -> level
    }

// SynthWave '84 thinking-level border colours of pi's themes (.pi/themes/synthwave-84*.json).
private val SYNTHWAVE_THINKING_DARK = mapOf(
    "low" to Color(0xFF9D8BCA), "medium" to Color(0xFFB893CE), "high" to Color(0xFFFF7EDB),
    "xhigh" to Color(0xFFF97E72), "max" to Color(0xFFFEDE5D),
)
private val SYNTHWAVE_THINKING_LIGHT = mapOf(
    "low" to Color(0xFF9479AF), "medium" to Color(0xFF694080), "high" to Color(0xFFA01870),
    "xhigh" to Color(0xFFA13232), "max" to Color(0xFF795500),
)

/** `toolTitle` (cyan) of the synthwave themes: the writing snake on every palette. */
internal val WritingCyanDark = Color(0xFF36F9F6)
internal val WritingCyanLight = Color(0xFF00656B)

/** The synthwave `dim` token (`comment`): the pending snake. */
private val SynthwaveDimDark = Color(0xFF848BBD)
private val SynthwaveDimLight = Color(0xFF655773)

/** The colour of [phase] in [scheme] for the (effective) [palette]. */
internal fun activityPhaseColor(
    palette: ThemePalette,
    scheme: ColorScheme,
    phase: ActivityPhase,
    thinkingLevel: String?,
): Color {
    val dark = scheme.surface.luminance() < 0.5f
    val synthwave = palette == ThemePalette.SYNTHWAVE
    return when (phase) {
        ActivityPhase.Pending -> if (synthwave) (if (dark) SynthwaveDimDark else SynthwaveDimLight) else scheme.outline
        ActivityPhase.Writing -> if (dark) WritingCyanDark else WritingCyanLight
        ActivityPhase.Tool -> scheme.primary
        ActivityPhase.Thinking -> {
            val level = visibleActivityThinkingLevel(thinkingLevel) ?: return scheme.onSurfaceVariant
            if (synthwave) (if (dark) SYNTHWAVE_THINKING_DARK else SYNTHWAVE_THINKING_LIGHT)[level] ?: scheme.onSurfaceVariant
            else when (level) {
                "low" -> scheme.secondary
                "medium" -> scheme.primary
                else -> scheme.tertiary
            }
        }
    }
}

@Composable
internal fun activityPhaseColor(phase: ActivityPhase, thinkingLevel: String?): Color =
    activityPhaseColor(LocalThemePalette.current, MaterialTheme.colorScheme, phase, thinkingLevel)

/** Running tools besides the first listed one: the other listed tools plus the omitted count. */
internal fun activityExtraTools(snapshot: ActivitySnapshot.Active): Int =
    (snapshot.tools.size - 1).coerceAtLeast(0) + (snapshot.toolsOmitted ?: 0)

/** The largest extra-tool count spelled out; more reads as this number and a plus. */
internal const val MAX_SHOWN_EXTRA_TOOLS = 99

/**
 * Milliseconds until the soonest fresh session or actor of [activity] expires at [now], capped at
 * [MAX_ACTIVITY_REMAINING_MS]; null when nothing is fresh, so no timer needs to run.
 */
internal fun nextActivityDeadlineMs(activity: ExecutionActivityState, now: Long): Long? {
    if (!activity.negotiated) return null
    val remaining = activity.sessions.keys.map { activity.freshness(it, now) } +
        activity.actors.keys.map { activity.actorFreshness(it, now) }
    return remaining.filter { it.live }.minOfOrNull { it.remainingMs.coerceIn(1, MAX_ACTIVITY_REMAINING_MS) }
}

/**
 * The monotonic instant the activity UI evaluates freshness at. It is read anew whenever
 * [activity] changes, when its soonest deadline passes and when the screen resumes, so an expiry
 * stops the animation without a network update. Only a new [activity] restarts the timer;
 * recomposition alone does not rearm it.
 */
@Composable
internal fun rememberActivityNow(activity: ExecutionActivityState): Long {
    var tick by remember { mutableLongStateOf(0L) }
    // A new state pairs with a fresh reading, never an older one that would overstate freshness.
    val now = remember(activity, tick) { SystemClock.elapsedRealtime() }
    val current by rememberUpdatedState(activity)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(activity, resumed) {
        if (!resumed) return@LaunchedEffect
        // Resumed (or a new state): read the clock once, then wake at each soonest deadline.
        tick++
        while (true) {
            val wait = nextActivityDeadlineMs(current, SystemClock.elapsedRealtime()) ?: break
            delay(wait)
            tick++
        }
    }
    return now
}
