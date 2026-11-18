package de.joinnoah.pi.remote

import androidx.compose.foundation.background
import androidx.compose.ui.layout.VerticalAlignmentLine
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measured
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.WindowInsetsRulers
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.composed
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fitInside
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * What the remote view panes (terminal, browser and preview, emulator) share: the pane with its
 * floating header and notices, the opaque viewport that shows the stream, and the glass controls
 * around it. The viewport stays a flat surface; glass is for the controls (see
 * docs/glass-design-consistency.md).
 */

/** The gutter of a remote view's bars, the same as [PaneHeader]'s. */
private val REMOTE_VIEW_GUTTER = 16.dp
private val REMOTE_VIEW_BAR_PADDING = PaddingValues(horizontal = REMOTE_VIEW_GUTTER, vertical = 4.dp)
/** A compact pane's bars sit edge to edge: every dp goes to the viewport. */
private val REMOTE_VIEW_COMPACT_BAR_PADDING = PaddingValues(horizontal = REMOTE_VIEW_GUTTER)

/** The padding of a remote view's bars, tighter in a compact pane. */
@Composable
private fun remoteViewBarPadding(): PaddingValues =
    if (LocalRemoteViewCompact.current) REMOTE_VIEW_COMPACT_BAR_PADDING else REMOTE_VIEW_BAR_PADDING

/**
 * The height a compact pane keeps for its viewport (about four terminal rows, see
 * docs/testing/android-terminal-compact.md). The bars that can give way ([RemoteViewShed]) give
 * way for it first; the header and the input bars are never squeezed below their minimums for it
 * ([RemoteViewColumn]).
 */
internal val REMOTE_VIEWPORT_COMPACT_MIN_HEIGHT = 96.dp

/** Below this height a remote view pane is compact (see [LocalRemoteViewCompact]). */
internal val REMOTE_VIEW_COMPACT_HEIGHT = 400.dp

/** Whether a remote view pane [height] high is compact: a phone in landscape, a small window. */
internal fun remoteViewCompact(height: Dp): Boolean = height < REMOTE_VIEW_COMPACT_HEIGHT

/**
 * True inside a compact [RemoteViewPane], one less than [REMOTE_VIEW_COMPACT_HEIGHT] high. There
 * every pane keeps the viewport as large as it can: the header and the bars lose their padding
 * above and below (their 48dp controls stay), input bars are single lines (the default of
 * [RemoteInputBar]), and a pane moves what it can into the header (the browser its tab row, into
 * [RemoteViewPane]'s `compactActions`). No focusable input leaves composition for the height: the
 * soft keyboard opening would shrink the pane and take the focus away with it. An input on its
 * way shows as the viewport's busy bar, never as a notice row.
 */
internal val LocalRemoteViewCompact = compositionLocalOf { false }

/** A line of status under a remote view's header. [tag] is its test tag. */
internal sealed interface RemoteViewNotice {
    val text: String
    val tag: String?

    /** Something to know that is not a failure: someone else has control, reconnecting. */
    data class Info(override val text: String, override val tag: String? = null) : RemoteViewNotice

    /** Something failed. With [onRetry] it offers [retryLabel], by default "Try again". */
    data class Failure(
        override val text: String,
        override val tag: String? = null,
        val retryLabel: String? = null,
        val onRetry: (() -> Unit)? = null,
    ) : RemoteViewNotice
}

/**
 * One [RemoteViewNotice] as the Files and Changes panes show theirs. TalkBack announces either
 * kind when it appears.
 */
@Composable
internal fun RemoteViewNoticeLine(notice: RemoteViewNotice, modifier: Modifier = Modifier) {
    val tagged = if (notice.tag == null) modifier else modifier.testTag(notice.tag!!)
    when (notice) {
        is RemoteViewNotice.Info ->
            PaneNotice(notice.text, tagged.semantics { liveRegion = LiveRegionMode.Polite }, remoteViewBarPadding())
        is RemoteViewNotice.Failure ->
            PaneFailureNotice(
                notice.text,
                notice.retryLabel ?: stringResource(R.string.remote_view_retry),
                notice.onRetry,
                tagged,
                remoteViewBarPadding(),
            )
    }
}

/**
 * A remote view pane: the page background inside the safe drawing area, a [PaneHeader], the
 * [notices] under it and then [content], a column that holds the viewport and the bars.
 *
 * It is the same beside the chat as over it. As the Files and Changes panes do, it keeps its
 * header in an inspector tab too, where the tab row names and closes the tab as well.
 *
 * [tag] prefixes the test tags: `<tag>Pane`, and the header's `<tag>Close`, `<tag>Reload`,
 * `<tag>Back` and `<tag>Title`. With [onBack] the header shows a back arrow beside its close
 * button, and Back and Esc run it instead of [onClose]. A pane that stays composed while another
 * inspector tab shows does not take Back or Esc. With [escapeCloses] false a hardware Esc never
 * closes the pane, while Back still does: Esc is a key of the terminal and of the page, which a
 * habit press must not close. Over the chat, or with keyboard focus in the pane, such an Esc does
 * nothing (it does not reach the chat behind); beside the chat with focus elsewhere it runs the
 * chat's own steps (see [PaneBackHandler]). The header's close button then names no shortcut.
 *
 * [compactActions] join [actions] in a compact pane only, where the header is one 48dp row
 * (see [PaneHeader]'s `compact`). The pane provides [LocalRemoteViewCompact] to [content]; its
 * notices give way where the bars and the viewport's floor do not fit ([RemoteViewShed]).
 */
@Composable
internal fun RemoteViewPane(
    title: String,
    tag: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    /** Greys out the reload button while a reload runs. */
    busy: Boolean = false,
    /** Null leaves the reload button out. */
    onReload: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    actions: List<PaneAction> = emptyList(),
    compactActions: List<PaneAction> = emptyList(),
    notices: List<RemoteViewNotice> = emptyList(),
    /** False leaves Back and Esc to whatever is under the pane. */
    backEnabled: Boolean = true,
    escapeCloses: Boolean = true,
    closeLabel: String = stringResource(R.string.remote_view_close),
    reloadLabel: String = stringResource(R.string.remote_view_reload),
    backLabel: String = stringResource(R.string.remote_view_back),
    content: @Composable ColumnScope.() -> Unit,
) {
    // Whether keyboard focus is in the pane; read only when Esc comes, so a plain holder.
    val focusInside = remember { booleanArrayOf(false) }
    // A terminal or browser tab stays composed while another tab shows; Back is not its to take.
    PaneBackHandler(
        enabled = backEnabled && LocalTerminalKeyboardActive.current,
        escapeCloses = escapeCloses,
        focusInside = { focusInside[0] },
        onBack = onBack ?: onClose,
    )
    // The page background, as the other panes have. A Surface also keeps taps off the chat under it.
    Surface(
        modifier.fillMaxSize().testTag("${tag}Pane").onFocusChanged { focusInside[0] = it.hasFocus },
        color = MaterialTheme.colorScheme.background,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = remoteViewCompact(maxHeight)
            CompositionLocalProvider(LocalRemoteViewCompact provides compact) {
                // Pads, in the layout phase, by the part of the safe drawing insets (system bars,
                // cutout, keyboard) that overlaps the pane in its window: a pane over the whole
                // screen keeps clear of all of them, one that ends above the navigation bar or the
                // keyboard loses no height to them. No recomposition while the keyboard animates.
                RemoteViewColumn(
                    Modifier.fillMaxSize().fitInside(WindowInsetsRulers.SafeDrawing.current),
                    viewportMinHeight = if (compact) REMOTE_VIEWPORT_COMPACT_MIN_HEIGHT else 0.dp,
                ) {
                    PaneHeader(
                        title = title,
                        subtitle = subtitle,
                        nested = onBack != null,
                        busy = busy,
                        tag = tag,
                        backLabel = backLabel,
                        closeLabel = closeLabel,
                        reloadLabel = reloadLabel,
                        onBack = onBack ?: onClose,
                        onClose = onClose,
                        onReload = onReload,
                        actions = if (compact) actions + compactActions else actions,
                        compact = compact,
                        escapeLeaves = escapeCloses,
                    )
                    notices.forEach {
                        val shed = if (it is RemoteViewNotice.Info) RemoteViewShed.INFO else RemoteViewShed.FAILURE
                        RemoteViewNoticeLine(it, Modifier.remoteViewShed(shed))
                    }
                    content()
                }
            }
        }
    }
}

private object RemoteViewportSlot

/**
 * The bars of a [RemoteViewPane] that give way where the bars at their minimum heights and the
 * viewport's floor together are taller than the pane, in this order: informational notices, the
 * control bar, failure notices (they offer Retry), and the key strip last (a terminal's Esc, Tab
 * and arrows). The lowest bar of a kind goes first. A bar without a mark, such as the header, an
 * input bar or the address bar, always stays.
 */
internal enum class RemoteViewShed { INFO, CONTROL, FAILURE, KEYS }

/** A bar's mark; [RemoteViewColumn] sets [shed] while it leaves the bar out. */
private class RemoteViewShedSlot(val kind: RemoteViewShed) {
    var shed by mutableStateOf(false)
}

/**
 * Marks a bar of a [RemoteViewPane]'s content as one that gives way ([RemoteViewShed]). A bar left
 * out stays composed (a focused field in it keeps its state) but is not placed, so it takes no
 * touches; it is hidden from accessibility services and none of its controls can take keyboard
 * focus, which also takes the focus from one that had it.
 */
internal fun Modifier.remoteViewShed(shed: RemoteViewShed): Modifier = composed {
    val slot = remember(shed) { RemoteViewShedSlot(shed) }
    layoutId(slot).then(
        if (slot.shed) Modifier.focusProperties { canFocus = false }.semantics { hideFromAccessibility() } else Modifier
    )
}

private data class RemoteViewFlexSlot(val minHeight: Dp)

/**
 * Marks a bar that may be shorter than its content, down to [minHeight], such as an input bar
 * whose draft has more lines than show at once. Other bars keep their own height.
 */
private fun Modifier.remoteViewFlex(minHeight: Dp): Modifier = layoutId(RemoteViewFlexSlot(minHeight))

/** [ColumnScope] of [RemoteViewColumn]: a weight marks the viewport; alignment is the start. */
private object RemoteViewColumnScope : ColumnScope {
    override fun Modifier.weight(weight: Float, fill: Boolean): Modifier = layoutId(RemoteViewportSlot)
    override fun Modifier.align(alignment: Alignment.Horizontal): Modifier = this
    override fun Modifier.alignBy(alignmentLine: VerticalAlignmentLine): Modifier = this
    override fun Modifier.alignBy(alignmentLineBlock: (Measured) -> Int): Modifier = this
}

/**
 * A column like [Column] whose weighted children (the viewport) share what the others, the bars,
 * leave. No bar is squeezed below its minimum height: that is its own height, or for an input bar
 * ([RemoteInputBar]) the height of one line. Where the minimums and [viewportMinHeight] together
 * do not fit, the bars marked with [remoteViewShed] are left out in [RemoteViewShed]'s order until
 * they do. Only when every such bar is out does the viewport's floor shrink, to the room the
 * remaining minimums leave, `min(viewportMinHeight, total - sum of the minimums)`, so a focused
 * input bar never shrinks out of sight for it.
 *
 * A bar's minimum is not asked as an intrinsic height: [PaneHeader] and [RemoteControlBar] are
 * subcomposed (BoxWithConstraints), which answers no intrinsic measurement, and a text field's
 * intrinsic height is that of all its lines. Bars without a [remoteViewFlex] mark are measured
 * once at their own height; input bars declare their one-line height.
 */
@Composable
internal fun RemoteViewColumn(modifier: Modifier, viewportMinHeight: Dp = 0.dp, content: @Composable ColumnScope.() -> Unit) {
    Layout(content = { RemoteViewColumnScope.content() }, modifier = modifier) { measurables, constraints ->
        val bounded = constraints.hasBoundedHeight
        val total = if (bounded) constraints.maxHeight else Constraints.Infinity
        val placeables = arrayOfNulls<Placeable>(measurables.size)
        val flexMin = IntArray(measurables.size) { (measurables[it].layoutId as? RemoteViewFlexSlot)?.minHeight?.roundToPx() ?: -1 }
        // The bars that keep their own height first.
        var used = 0
        measurables.forEachIndexed { index, child ->
            if (child.layoutId === RemoteViewportSlot || flexMin[index] >= 0) return@forEachIndexed
            val placeable = child.measure(Constraints(maxWidth = constraints.maxWidth, maxHeight = total))
            placeables[index] = placeable
            used += placeable.height
        }
        var laterMins = flexMin.sumOf { it.coerceAtLeast(0) }
        val weighted = measurables.count { it.layoutId === RemoteViewportSlot }
        val floorWanted = if (bounded && weighted > 0) viewportMinHeight.roundToPx() else 0
        val shed = BooleanArray(measurables.size)
        if (bounded)
            for (kind in RemoteViewShed.entries) {
                for (index in measurables.indices.reversed()) {
                    if (used + laterMins + floorWanted <= total) break
                    if ((measurables[index].layoutId as? RemoteViewShedSlot)?.kind != kind) continue
                    used -= placeables[index]?.height ?: 0
                    placeables[index] = null
                    shed[index] = true
                }
            }
        // Tells each marked bar whether it is out (a no-op while that does not change).
        measurables.forEachIndexed { index, child -> (child.layoutId as? RemoteViewShedSlot)?.shed = shed[index] }
        val floor = if (bounded && weighted > 0) minOf(floorWanted, total - used - laterMins).coerceAtLeast(0) else 0
        // Then the input bars, top to bottom: each gets what is left above the floor and the
        // minimums of those below it, and never less than its own minimum.
        measurables.forEachIndexed { index, child ->
            val min = flexMin[index]
            if (min < 0) return@forEachIndexed
            laterMins -= min
            val room = if (bounded) maxOf(min, total - used - floor - laterMins) else Constraints.Infinity
            val placeable = child.measure(Constraints(maxWidth = constraints.maxWidth, maxHeight = room))
            placeables[index] = placeable
            used += placeable.height
        }
        val left = if (bounded) (total - used).coerceAtLeast(0) else 0
        var shared = 0
        var seen = 0
        measurables.forEachIndexed { index, child ->
            if (child.layoutId !== RemoteViewportSlot) return@forEachIndexed
            seen++
            val height = if (seen == weighted) left - shared else left / weighted
            shared += height
            placeables[index] = child.measure(Constraints(minWidth = 0, maxWidth = constraints.maxWidth, minHeight = height, maxHeight = height))
        }
        val height = if (bounded) total else used
        layout(constraints.maxWidth, height) {
            var y = 0
            placeables.forEach { placeable -> placeable?.let { it.placeRelative(0, y); y += it.height } }
        }
    }
}

/**
 * The colours of the surface a stream is drawn on. A remote screen is letterboxed on black in
 * either theme, like a video; these are the only colours of the remote views outside the theme.
 */
internal object RemoteViewportDefaults {
    val Background = Color.Black
    val Content = Color.White
}

/** What a [RemoteViewport] shows over its content: [label], and a progress indicator while [busy]. */
internal data class RemoteViewPlaceholder(val label: String, val busy: Boolean = true)

/**
 * The opaque surface of a remote view: the stream image or the terminal. [modifier] is the
 * surface's own, so it takes the size (in a [RemoteViewPane], `Modifier.weight(1f).fillMaxWidth()`),
 * the focus, key and pointer modifiers of the view. [content] stays composed under a
 * [placeholder], which covers it and lets pointer input through to [modifier].
 *
 * While [busy] (an input of this device is on its way) a thin progress bar runs along its top
 * edge; TalkBack calls it [busyLabel]. A pane shows no other "sending" state.
 *
 * Test tags: [tag], `<tag>Placeholder` and `<tag>Busy`.
 */
@Composable
internal fun RemoteViewport(
    tag: String,
    modifier: Modifier = Modifier,
    placeholder: RemoteViewPlaceholder? = null,
    color: Color = RemoteViewportDefaults.Background,
    contentColor: Color = RemoteViewportDefaults.Content,
    busy: Boolean = false,
    busyLabel: String = stringResource(R.string.remote_view_sending),
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(modifier.testTag(tag).background(color), contentAlignment = Alignment.Center) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
            if (busy)
                LinearProgressIndicator(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp).testTag("${tag}Busy")
                        .semantics { contentDescription = busyLabel },
                )
            if (placeholder != null)
                Column(
                    Modifier.matchParentSize().background(color).padding(24.dp).testTag("${tag}Placeholder"),
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (placeholder.busy) CircularProgressIndicator(Modifier.size(32.dp), color = contentColor)
                    Text(
                        placeholder.label,
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
        }
    }
}

/** Who controls a shared remote view. */
internal enum class RemoteControlState {
    /** Nobody this pane knows of has control; this device watches. */
    VIEWING,

    /** This device has control. */
    CONTROLLING,

    /** Someone else has control: another participant, the Mac, another phone. */
    HELD,
}

/**
 * A participant of a shared view (browser, preview, emulator) as the panes name it: what it is,
 * and the end of its opaque [id] to tell two apart, such as "Pi agent 4f2a91".
 */
@Composable
internal fun remoteParticipantLabel(kind: String, id: String): String =
    stringResource(if (kind == "agent") R.string.remote_view_participant_agent else R.string.remote_view_participant_device, id.takeLast(6))

/** A participant control can be handed to; [label] is what the list shows for [id]. */
internal data class RemoteControlTarget(val id: String, val label: String)

/**
 * Who has control of a shared view, and what this device can do about it, in one glass pill.
 *
 * The state line is [label], by default the state's own text ("View only", "You have control",
 * "Control held by [holder]"); TalkBack announces it when it changes. The actions show where
 * their callback is set and the [state] allows them: [onTake] unless this device has control,
 * and [onRelease] and the [handoffTargets] list (calling [onHandOff] with an id) while it has.
 * [enabled] false greys them out, while a request is on its way.
 *
 * The terminal says who holds it the same way, `state = HELD, holder = <the Mac>`; its take
 * action asks for a confirmation first.
 *
 * Test tags: `<tag>ControlBar`, `<tag>ControlState`, `<tag>Acquire`, `<tag>Release`,
 * `<tag>Handoff` and `<tag>Handoff_<id>`.
 */
@Composable
internal fun RemoteControlBar(
    state: RemoteControlState,
    tag: String,
    modifier: Modifier = Modifier,
    holder: String? = null,
    label: String? = null,
    enabled: Boolean = true,
    onTake: (() -> Unit)? = null,
    takeLabel: String = stringResource(R.string.remote_view_take_control),
    onRelease: (() -> Unit)? = null,
    releaseLabel: String = stringResource(R.string.remote_view_release_control),
    handoffTargets: List<RemoteControlTarget> = emptyList(),
    onHandOff: (id: String) -> Unit = {},
    handOffLabel: String = stringResource(R.string.remote_view_hand_over),
) {
    val text = label ?: when (state) {
        RemoteControlState.VIEWING -> stringResource(R.string.remote_view_control_viewing)
        RemoteControlState.CONTROLLING -> stringResource(R.string.remote_view_control_you)
        RemoteControlState.HELD ->
            if (holder == null) stringResource(R.string.remote_view_control_other)
            else stringResource(R.string.remote_view_control_held_by, holder)
    }
    val icon = when (state) {
        RemoteControlState.VIEWING -> Icons.Default.Visibility
        RemoteControlState.CONTROLLING -> Icons.Default.TouchApp
        RemoteControlState.HELD -> Icons.Default.Lock
    }
    val controlling = state == RemoteControlState.CONTROLLING
    val take = onTake.takeUnless { controlling }
    val release = onRelease.takeIf { controlling }
    val targets = handoffTargets.takeIf { controlling }.orEmpty()
    val hasActions = take != null || release != null || targets.isNotEmpty()
    BoxWithConstraints(modifier.fillMaxWidth().padding(remoteViewBarPadding())) {
        // Beside the state where both fit; under it on a narrow pane or with enlarged fonts.
        val stacked = hasActions && maxWidth < 360.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        val stateLine: @Composable (Modifier) -> Unit = { lineModifier ->
            Row(lineModifier.defaultMinSize(minHeight = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon, null,
                    tint = if (controlling) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text,
                    Modifier.padding(vertical = 4.dp).testTag("${tag}ControlState").semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        val buttons: @Composable () -> Unit = {
            if (take != null)
                TextButton(onClick = take, Modifier.testTag("${tag}Acquire"), enabled = enabled) { Text(takeLabel) }
            if (release != null)
                TextButton(onClick = release, Modifier.testTag("${tag}Release"), enabled = enabled) { Text(releaseLabel) }
            if (targets.isNotEmpty()) {
                var open by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { open = true }, Modifier.testTag("${tag}Handoff"), enabled = enabled) { Text(handOffLabel) }
                    GlassDropdownMenu(open, onDismissRequest = { open = false }) {
                        targets.forEach { target ->
                            val description = stringResource(R.string.remote_view_hand_over_to, target.label)
                            DropdownMenuItem(
                                text = { Text(target.label) },
                                onClick = {
                                    open = false
                                    onHandOff(target.id)
                                },
                                modifier = Modifier.testTag("${tag}Handoff_${target.id}").semantics { contentDescription = description },
                            )
                        }
                    }
                }
            }
        }
        FloatingSurface(Modifier.fillMaxWidth().testTag("${tag}ControlBar")) {
            if (stacked)
                Column(Modifier.padding(start = 16.dp, end = 4.dp)) {
                    stateLine(Modifier.padding(end = 12.dp))
                    Row(Modifier.align(Alignment.End).horizontalScroll(rememberScrollState())) { buttons() }
                }
            else
                Row(Modifier.padding(start = 16.dp, end = if (hasActions) 4.dp else 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    stateLine(Modifier.weight(1f))
                    buttons()
                }
        }
    }
}

/**
 * A key of a [RemoteKeyStrip]. [id] is what the pane sends for it and the end of its test tag;
 * [label] is what the chip shows and [description] what TalkBack says where the label is a symbol
 * or an abbreviation. A non-null [selected] makes the chip a toggle, for a modifier key.
 */
internal data class RemoteKey(
    val id: String,
    val label: String,
    val description: String = label,
    val enabled: Boolean = true,
    val selected: Boolean? = null,
    val icon: ImageVector? = null,
)

/**
 * A row of key chips that scrolls sideways. Each chip is at least 48dp wide and high and grows
 * with its label, so no label clips at a large font scale. [enabled] false disables every key.
 *
 * Test tags: `<tag>Keys`, and `<tag>Key_<id>` per chip.
 */
@Composable
internal fun RemoteKeyStrip(
    keys: List<RemoteKey>,
    onKey: (RemoteKey) -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = remoteViewBarPadding(),
) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(contentPadding).testTag("${tag}Keys"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        keys.forEach { key -> RemoteKeyChip(key, enabled && key.enabled, { onKey(key) }, Modifier.testTag("${tag}Key_${key.id}")) }
    }
}

/**
 * The glass chip of the remote views' strips: a key of a [RemoteKeyStrip] or a tab of a
 * [RemoteTabStrip]. [selected] colours it as chosen; [behaviour] makes it clickable, toggleable or
 * selectable. At least 48dp wide and high, it grows with its content.
 */
@Composable
private fun RemoteChip(
    selected: Boolean,
    enabled: Boolean,
    behaviour: Modifier,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val color = if (selected) scheme.secondaryContainer else scheme.surfaceContainer
    val contentColor = (if (selected) scheme.onSecondaryContainer else scheme.onSurface).copy(alpha = if (enabled) 1f else 0.38f)
    // The chips sit in a scrolling row, which would cut a shadow: the rim alone, as in the composer.
    FloatingSurface(modifier, shape = MaterialTheme.shapes.small, color = color, contentColor = contentColor, blur = false, shadowElevation = 0.dp) {
        Row(
            behaviour.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
private fun RemoteKeyChip(key: RemoteKey, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val selected = key.selected
    val interaction = remember { MutableInteractionSource() }
    RemoteChip(
        selected = selected == true,
        enabled = enabled,
        behaviour = modifier
            .then(
                if (selected != null)
                    Modifier.toggleable(selected, interaction, ripple(), enabled, Role.Switch) { onClick() }
                else Modifier.clickable(interaction, ripple(), enabled, role = Role.Button, onClick = onClick)
            )
            .semantics { contentDescription = key.description },
    ) {
        if (key.icon != null) Icon(key.icon, null, Modifier.size(18.dp))
        // The chip speaks its description; the label is what it shows. An icon chip has none.
        if (key.label.isNotEmpty())
            Text(key.label, Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
    }
}

/** The ids a pane sends for the navigation keys of [remoteNavigationKeys]. */
internal data class RemoteNavigationIds(val backspace: String, val left: String, val up: String, val down: String, val right: String) {
    companion object {
        /** The browser's and the emulator's: DOM key names. */
        val DOM = RemoteNavigationIds("Backspace", "ArrowLeft", "ArrowUp", "ArrowDown", "ArrowRight")

        /** The terminal's key names. */
        val TERMINAL = RemoteNavigationIds("backspace", "left", "up", "down", "right")
    }
}

/**
 * Backspace and the arrow keys, the same in every remote view: Backspace, then ← ↑ ↓ →, each an
 * icon chip with its spoken name.
 */
@Composable
internal fun remoteNavigationKeys(ids: RemoteNavigationIds = RemoteNavigationIds.DOM): List<RemoteKey> = listOf(
    RemoteKey(ids.backspace, "", stringResource(R.string.remote_view_key_backspace_description), icon = Icons.AutoMirrored.Filled.Backspace),
    RemoteKey(ids.left, "", stringResource(R.string.remote_view_key_left_description), icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft),
    RemoteKey(ids.up, "", stringResource(R.string.remote_view_key_up_description), icon = Icons.Default.KeyboardArrowUp),
    RemoteKey(ids.down, "", stringResource(R.string.remote_view_key_down_description), icon = Icons.Default.KeyboardArrowDown),
    RemoteKey(ids.right, "", stringResource(R.string.remote_view_key_right_description), icon = Icons.AutoMirrored.Filled.KeyboardArrowRight),
)

/** A tab of a [RemoteTabStrip]. [closeLabel], when set, is the spoken name of its close button. */
internal data class RemoteTab(val id: String, val label: String, val selected: Boolean, val closeLabel: String? = null)

/**
 * A row of tabs as glass chips that scrolls sideways, with the bars' padding. The selected tab
 * has the selected chip colour and TalkBack's tab semantics; a tab with a [RemoteTab.closeLabel]
 * has a 48dp close button beside it. [trailing] follows the tabs, such as a new tab button.
 * [enabled] false disables selecting and closing.
 *
 * Test tags: `<tag>Tabs`, `<tag>Tab_<id>` per tab and `<tag>TabClose_<id>` per close button.
 */
@Composable
internal fun RemoteTabStrip(
    tabs: List<RemoteTab>,
    onSelect: (String) -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    onClose: (String) -> Unit = {},
    enabled: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().selectableGroup().horizontalScroll(rememberScrollState()).padding(remoteViewBarPadding()).testTag("${tag}Tabs"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            RemoteChip(
                selected = tab.selected,
                enabled = enabled,
                behaviour = Modifier.selectable(tab.selected, enabled = enabled, role = Role.Tab) { onSelect(tab.id) }.testTag("${tag}Tab_${tab.id}"),
            ) {
                Text(tab.label, Modifier.widthIn(max = 220.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val closeLabel = tab.closeLabel
            if (closeLabel != null)
                IconButton(onClick = { onClose(tab.id) }, Modifier.size(48.dp).testTag("${tag}TabClose_${tab.id}"), enabled = enabled) {
                    Icon(Icons.Default.Close, closeLabel, Modifier.size(18.dp))
                }
        }
        trailing()
    }
}

/**
 * A glass text field with a send button, for the text a remote view types on the host.
 *
 * [maxLines] 1 makes it a single line whose keyboard action sends; more lets it grow to that many
 * lines (by default 3, and 1 in a compact pane, see [LocalRemoteViewCompact]), where the keyboard's Enter is a line break unless [keyboardOptions] says otherwise. Any
 * keyboard action calls [onSend] while [sendEnabled]. [fieldModifier] goes on the field itself,
 * for key handling and focus. [actions] are buttons before the send button, in its pill.
 *
 * Test tags: [tag] on the field and [sendTag], by default `<tag>Send`, on the button.
 */
@Composable
internal fun RemoteInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
    label: String = stringResource(R.string.remote_view_input),
    enabled: Boolean = true,
    sendEnabled: Boolean = enabled && value.isNotEmpty(),
    sendLabel: String = stringResource(R.string.remote_view_send),
    sendIcon: ImageVector = Icons.AutoMirrored.Filled.Send,
    sendTag: String = "${tag}Send",
    maxLines: Int = if (LocalRemoteViewCompact.current) 1 else 3,
    keyboardOptions: KeyboardOptions = KeyboardOptions(imeAction = if (maxLines == 1) ImeAction.Send else ImeAction.Default),
    actions: @Composable RowScope.() -> Unit = {},
) {
    val send = { if (sendEnabled) onSend() }
    val barPadding = remoteViewBarPadding()
    // One line of the field (its 56dp, more with a larger font) and the bar's padding: in a
    // remote view pane the bar never gets less, whatever the viewport would like.
    val lineHeight = with(LocalDensity.current) { MaterialTheme.typography.bodyLarge.lineHeight.takeIf { it.isSp }?.toDp() ?: 24.sp.toDp() }
    val minHeight = maxOf(TextFieldDefaults.MinHeight, lineHeight + 32.dp) + barPadding.calculateTopPadding() + barPadding.calculateBottomPadding()
    Row(
        modifier.remoteViewFlex(minHeight).fillMaxWidth().padding(barPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        FloatingSurface(Modifier.weight(1f)) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = label }.then(fieldModifier),
                enabled = enabled,
                placeholder = { Text(label, maxLines = 1) },
                singleLine = maxLines == 1,
                minLines = 1,
                maxLines = maxLines,
                keyboardOptions = keyboardOptions,
                keyboardActions = KeyboardActions(onDone = { send() }, onGo = { send() }, onSearch = { send() }, onSend = { send() }),
                // The glass pill is the container; the field draws neither its own nor an underline.
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
        }
        FloatingSurface(shape = CircleShape) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                actions()
                IconButton(onClick = onSend, modifier = Modifier.size(48.dp).testTag(sendTag), enabled = sendEnabled) {
                    Icon(sendIcon, sendLabel)
                }
            }
        }
    }
}
