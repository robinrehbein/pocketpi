package de.joinnoah.pi.remote

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/*
 * What the remote view panes (terminal, browser and preview, emulator) share: the pane with its
 * floating header and notices, the opaque viewport that shows the stream, and the glass controls
 * around it. The viewport stays a flat surface; glass is for the controls (see
 * docs/glass-design-consistency.md).
 */

/** The gutter of a remote view's bars, the same as [PaneHeader]'s. */
private val REMOTE_VIEW_GUTTER = 16.dp
private val REMOTE_VIEW_BAR_PADDING = PaddingValues(horizontal = REMOTE_VIEW_GUTTER, vertical = 4.dp)

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
            PaneNotice(notice.text, tagged.semantics { liveRegion = LiveRegionMode.Polite }, REMOTE_VIEW_BAR_PADDING)
        is RemoteViewNotice.Failure ->
            PaneFailureNotice(
                notice.text,
                notice.retryLabel ?: stringResource(R.string.remote_view_retry),
                notice.onRetry,
                tagged,
                REMOTE_VIEW_BAR_PADDING,
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
 * inspector tab shows does not take Back or Esc.
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
    notices: List<RemoteViewNotice> = emptyList(),
    /** False leaves Back and Esc to whatever is under the pane. */
    backEnabled: Boolean = true,
    closeLabel: String = stringResource(R.string.remote_view_close),
    reloadLabel: String = stringResource(R.string.remote_view_reload),
    backLabel: String = stringResource(R.string.remote_view_back),
    content: @Composable ColumnScope.() -> Unit,
) {
    // A terminal or browser tab stays composed while another tab shows; Back is not its to take.
    PaneBackHandler(enabled = backEnabled && LocalTerminalKeyboardActive.current, onBack = onBack ?: onClose)
    // The page background, as the other panes have. A Surface also keeps taps off the chat under it.
    Surface(modifier.fillMaxSize().testTag("${tag}Pane"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
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
                actions = actions,
            )
            notices.forEach { RemoteViewNoticeLine(it) }
            content()
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
 * Test tags: [tag], and `<tag>Placeholder`.
 */
@Composable
internal fun RemoteViewport(
    tag: String,
    modifier: Modifier = Modifier,
    placeholder: RemoteViewPlaceholder? = null,
    color: Color = RemoteViewportDefaults.Background,
    contentColor: Color = RemoteViewportDefaults.Content,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(modifier.testTag(tag).background(color), contentAlignment = Alignment.Center) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
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
 * The terminal's takeover is `state = HELD, label = <held by Mac>, takeLabel = <Take over>`.
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
    BoxWithConstraints(modifier.fillMaxWidth().padding(REMOTE_VIEW_BAR_PADDING)) {
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
    contentPadding: PaddingValues = REMOTE_VIEW_BAR_PADDING,
) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(contentPadding).testTag("${tag}Keys"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        keys.forEach { key -> RemoteKeyChip(key, enabled && key.enabled, { onKey(key) }, Modifier.testTag("${tag}Key_${key.id}")) }
    }
}

@Composable
private fun RemoteKeyChip(key: RemoteKey, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val selected = key.selected
    val color = if (selected == true) scheme.secondaryContainer else scheme.surfaceContainer
    val contentColor = (if (selected == true) scheme.onSecondaryContainer else scheme.onSurface).copy(alpha = if (enabled) 1f else 0.38f)
    val shape = MaterialTheme.shapes.small
    val interaction = remember { MutableInteractionSource() }
    // The chips sit in a scrolling row, which would cut a shadow: the rim alone, as in the composer.
    FloatingSurface(shape = shape, color = color, contentColor = contentColor, blur = false, shadowElevation = 0.dp) {
        Row(
            modifier
                .then(
                    if (selected != null)
                        Modifier.toggleable(selected, interaction, ripple(), enabled, Role.Switch) { onClick() }
                    else Modifier.clickable(interaction, ripple(), enabled, role = Role.Button, onClick = onClick)
                )
                .semantics { contentDescription = key.description }
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (key.icon != null) Icon(key.icon, null, Modifier.size(18.dp))
            // The chip speaks its description; the label is what it shows.
            Text(key.label, Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
        }
    }
}

/**
 * A glass text field with a send button, for the text a remote view types on the host.
 *
 * [maxLines] 1 makes it a single line whose keyboard action sends; more lets it grow to that many
 * lines, where the keyboard's Enter is a line break unless [keyboardOptions] says otherwise. Any
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
    maxLines: Int = 3,
    keyboardOptions: KeyboardOptions = KeyboardOptions(imeAction = if (maxLines == 1) ImeAction.Send else ImeAction.Default),
    actions: @Composable RowScope.() -> Unit = {},
) {
    val send = { if (sendEnabled) onSend() }
    Row(
        modifier.fillMaxWidth().padding(REMOTE_VIEW_BAR_PADDING),
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
