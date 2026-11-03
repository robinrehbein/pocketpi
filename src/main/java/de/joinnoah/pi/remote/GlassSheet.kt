package de.joinnoah.pi.remote

import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewParent
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import java.util.function.Consumer

// The app's bottom sheets in the floating-glass design. A sheet is a window of its own above the
// app, so Haze cannot blur what lies behind it. From Android 12 the window asks the system to blur
// everything behind it instead (cross-window blur), and the sheet turns frosted glass: the page
// background at the glass tint, a light scrim and the glass rim. Where the system has that blur
// off (battery saver, reduced animations, older Android) the sheet stays opaque.

/** The top corners of every sheet. */
private val SHEET_CORNER = 32.dp

/**
 * A modal bottom sheet in the app's design: the page background rather than a tinted surface,
 * no tonal elevation, rounder top corners and a slim handle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GlassSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val context = LocalContext.current
    // Start from the system's current answer, so a frosted sheet does not open opaque for a frame.
    var frosted by remember { mutableStateOf(crossWindowBlurOn(context)) }
    val scheme = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = SHEET_CORNER, topEnd = SHEET_CORNER),
        // The glass tint keeps text at 4.5:1 over any content, as on the floating pills.
        containerColor = if (frosted) scheme.background.copy(alpha = GLASS_TINT_ALPHA) else scheme.background,
        contentColor = scheme.onBackground,
        tonalElevation = 0.dp,
        scrimColor = if (frosted) scheme.scrim.copy(alpha = FROSTED_SCRIM_ALPHA) else BottomSheetDefaults.ScrimColor,
        // The rim is drawn inside the sheet (by the handle) so it moves with the sheet.
        dragHandle = { SheetHandle(rim = if (frosted) glassRimColor(scheme) else null) },
    ) {
        BlurBehindSheetWindow(hiding = sheetState.targetValue == SheetValue.Hidden) { frosted = it }
        content()
    }
}

/** The scrim over the blurred app: lighter than the opaque sheet's, since the blur already sets it back. */
private const val FROSTED_SCRIM_ALPHA = 0.14f

/** How far the system blurs the app behind a frosted sheet, in pixels. */
private const val SHEET_BLUR_RADIUS_PX = 64

private fun crossWindowBlurOn(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        runCatching { context.getSystemService(WindowManager::class.java)?.isCrossWindowBlurEnabled == true }
            .getOrDefault(false)

/**
 * Asks the system to blur the app behind the sheet's window and reports whether that blur is on.
 * The system can turn cross-window blur off and on while the sheet is open (battery saver), so the
 * sheet follows its listener rather than reading it once. While the sheet is [hiding] the blur is
 * taken away, so it does not stay at full strength as the sheet slides out.
 */
@Composable
private fun BlurBehindSheetWindow(hiding: Boolean, onFrosted: (Boolean) -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val view = LocalView.current
    val report by rememberUpdatedState(onFrosted)
    val window = remember(view) { view.dialogWindow() }
    var enabled by remember { mutableStateOf(crossWindowBlurOn(view.context)) }
    DisposableEffect(view, window) {
        val windowManager = view.context.getSystemService(WindowManager::class.java)
        if (window == null || windowManager == null) {
            report(false)
            return@DisposableEffect onDispose {}
        }
        val listener = Consumer<Boolean> { on ->
            enabled = on
            report(on)
        }
        // The listener is called at once with the current state. A window manager that cannot
        // blur (a test's) leaves the sheet opaque instead of failing.
        val registered = runCatching {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            windowManager.addCrossWindowBlurEnabledListener(listener)
        }.isSuccess
        if (!registered) report(false)
        onDispose { if (registered) runCatching { windowManager.removeCrossWindowBlurEnabledListener(listener) } }
    }
    val radius = if (enabled && !hiding) SHEET_BLUR_RADIUS_PX else 0
    DisposableEffect(window, radius) {
        if (window != null && window.attributes.blurBehindRadius != radius)
            runCatching { window.attributes = window.attributes.apply { blurBehindRadius = radius } }
        onDispose {}
    }
}

/** The window of the dialog that hosts this view, as the sheet's own layout provides it. */
private fun View.dialogWindow(): Window? {
    if (this is DialogWindowProvider) return window
    var current: ViewParent? = parent
    while (current != null) {
        if (current is DialogWindowProvider) return current.window
        current = current.parent
    }
    return null
}

@Composable
private fun SheetHandle(rim: Color? = null) {
    Box(
        Modifier.fillMaxWidth()
            .then(if (rim != null) Modifier.drawBehind { drawTopRim(rim) } else Modifier)
            .padding(top = 12.dp, bottom = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(width = 36.dp, height = 4.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .4f), CircleShape)
        )
    }
}

/**
 * The glass rim along the sheet's rounded top edge. The sheet clips to its shape, so half the
 * stroke is cut away and the visible line is the rim's width.
 */
private fun DrawScope.drawTopRim(color: Color) {
    val corner = SHEET_CORNER.toPx()
    val w = size.width
    val path = Path().apply {
        moveTo(0f, corner)
        arcTo(Rect(0f, 0f, 2 * corner, 2 * corner), 180f, 90f, false)
        lineTo(w - corner, 0f)
        arcTo(Rect(w - 2 * corner, 0f, w, 2 * corner), 270f, 90f, false)
    }
    drawPath(path, color, style = Stroke(width = GLASS_RIM_WIDTH.toPx() * 2))
}

/** A glass card inside a sheet: what a sheet is about, such as the current advisor. */
@Composable
internal fun SheetCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FloatingSurface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), blur = false) { content() }
}

/**
 * The container colour of a choice in a sheet: the selected one a tinted pill, the others the
 * page's low surface, so the choice reads without a border.
 */
@Composable
internal fun sheetOptionColor(selected: Boolean) =
    if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow

/** The shape of a choice row in a sheet. */
internal val SHEET_OPTION_SHAPE = RoundedCornerShape(20.dp)

/** A sheet's main action as an accent pill across the sheet. */
@Composable
internal fun SheetPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        content = content,
    )
}

/** A sheet's secondary action as a glass pill across the sheet. */
@Composable
internal fun SheetSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    // The caller's modifier goes to the button, so a test tag lands on the node that clicks and
    // carries the enabled state; the glass around it only sizes to it.
    FloatingSurface(Modifier.fillMaxWidth(), shape = CircleShape, blur = false) {
        TextButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
            content = content,
        )
    }
}

/** The row of the model and advisor pickers that opens the host's providers, as a pill in the sheet. */
@Composable
internal fun ManageProvidersRow(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .clip(SHEET_OPTION_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 18.dp)
            .testTag("managePickerProviders"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(stringResource(R.string.remote_page_manage_providers), style = MaterialTheme.typography.bodyLarge)
        Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = null)
    }
}

/**
 * A sheet's title, with an optional [subtitle] under it and, when [onClose] is set, a glass close
 * button beside it. [closeLabel] names that button for TalkBack.
 */
@Composable
internal fun SheetHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    closeLabel: String? = null,
    onClose: (() -> Unit)? = null,
    /** Space around the header; a sheet whose content is padded already passes less. */
    padding: PaddingValues = PaddingValues(start = 24.dp, end = 16.dp, bottom = 12.dp),
) {
    Row(
        modifier.fillMaxWidth().padding(padding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
            if (subtitle != null)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
        }
        if (onClose != null)
            FloatingSurface(shape = CircleShape, blur = false) {
                IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Close, closeLabel)
                }
            }
    }
}

/** A section of a sheet as a quiet card: a group of facts or settings that belong together. */
@Composable
internal fun SheetSection(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(PAGE_CARD_CORNER),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        content = content,
    )
}

/**
 * One row of a list in a sheet; the rows of a list read as one card, the [first] rounding the
 * top and the [last] the bottom.
 */
@Composable
internal fun SheetGroupRow(first: Boolean, last: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = if (first) 0.dp else 2.dp),
        shape = groupedRowShape(first, last),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        content = content,
    )
}

/** List items inside [SheetGroupRow] or [SheetSection]: the row's card is their container. */
@Composable
internal fun sheetListItemColors(): ListItemColors = ListItemDefaults.colors(containerColor = Color.Transparent)
