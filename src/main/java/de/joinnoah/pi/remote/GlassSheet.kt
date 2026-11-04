package de.joinnoah.pi.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

// The app's bottom sheets in the floating-glass design. A sheet is a window of its own above the
// app, so Haze cannot blur what lies behind it, and the system's blur behind windows is missing on
// many phones (Samsung among them). So the sheet takes a small copy of the app as it opens, blurs it
// once, and draws it under its own content, at the glass tint, lined up with where the app sits on
// the screen: frosted glass over what was there. The copy is a still frame, taken again when the
// configuration changes (rotation, dark mode). Before Android 12, or when the copy fails, the sheet
// stays opaque.

/** The top corners of every sheet. */
private val SHEET_CORNER = 32.dp

/**
 * A modal bottom sheet in the app's design: frosted glass over the app where it can be, otherwise
 * the page background; no tonal elevation, rounder top corners and a slim handle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GlassSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val backdrop = rememberAppBackdrop()
    var handleHeight by remember { mutableIntStateOf(0) }
    val scheme = MaterialTheme.colorScheme
    val glass = SheetGlass(
        // The scrim over the copy matches the scrim the sheet lays over the app around it.
        scrim = BottomSheetDefaults.ScrimColor,
        // The glass tint keeps text at 4.5:1 over any content, as on the floating pills.
        tint = scheme.background.copy(alpha = GLASS_TINT_ALPHA),
    )
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = SHEET_CORNER, topEnd = SHEET_CORNER),
        containerColor = scheme.background,
        contentColor = scheme.onBackground,
        tonalElevation = 0.dp,
        scrimColor = glass.scrim,
        // Material's handle slot keeps the handle's click and TalkBack actions and holds the top
        // inset; the bottom inset moves into the content. The content's backdrop reaches up over
        // the slot, so one layer frosts the whole sheet: no seam at the handle, no opaque strip
        // over the insets.
        dragHandle = { SheetHandle(showPill = backdrop == null, onHeight = { handleHeight = it }) },
        contentWindowInsets = { WindowInsets(0) },
    ) {
        // The same structure with or without the backdrop, so its arrival keeps the content's state.
        Box(Modifier.fillMaxWidth()) {
            if (backdrop != null) {
                SheetBackdropLayer(
                    backdrop, glass,
                    reachUp = { handleHeight },
                    rim = glassRimColor(scheme),
                    pill = scheme.onSurfaceVariant.copy(alpha = HANDLE_PILL_ALPHA),
                )
            }
            Column(
                Modifier.fillMaxWidth()
                    // The handle row used the top inset; the content must not pad for it again.
                    .consumeWindowInsets(BottomSheetDefaults.windowInsets.only(WindowInsetsSides.Top))
                    .windowInsetsPadding(BottomSheetDefaults.windowInsets.only(WindowInsetsSides.Bottom))
            ) {
                content()
            }
        }
    }
}

private class SheetGlass(val scrim: Color, val tint: Color)

/**
 * Blurs ARGB [pixels] of a [width] by [height] image in place: three box passes across and down,
 * which come close to a Gaussian blur. Edge pixels repeat beyond the image.
 */
internal fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int) {
    if (radius <= 0 || width <= 0 || height <= 0) return
    val scratch = IntArray(pixels.size)
    repeat(3) {
        boxBlurPass(pixels, scratch, width, height, radius, across = true)
        boxBlurPass(scratch, pixels, width, height, radius, across = false)
    }
}

private fun boxBlurPass(input: IntArray, output: IntArray, width: Int, height: Int, radius: Int, across: Boolean) {
    val lines = if (across) height else width
    val length = if (across) width else height
    val span = 2 * radius + 1
    for (line in 0 until lines) {
        fun at(i: Int): Int {
            val clamped = i.coerceIn(0, length - 1)
            return if (across) line * width + clamped else clamped * width + line
        }
        var a = 0
        var r = 0
        var g = 0
        var b = 0
        for (i in -radius..radius) {
            val p = input[at(i)]
            a += p ushr 24; r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
        }
        for (i in 0 until length) {
            output[at(i)] = ((a / span) shl 24) or ((r / span) shl 16) or ((g / span) shl 8) or (b / span)
            val leaving = input[at(i - radius)]
            val entering = input[at(i + radius + 1)]
            a += (entering ushr 24) - (leaving ushr 24)
            r += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
            g += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
            b += (entering and 0xFF) - (leaving and 0xFF)
        }
    }
}

/**
 * The blurred app copy under the whole sheet, lined up with the screen, with the scrim and the
 * glass tint over it. It covers its box and reaches [reachUp] pixels above it, over the handle
 * slot, where it also draws the glass [rim] and the handle [pill]: the slot is drawn first, so the
 * layer would hide its pill. [reachUp] is read while laying out and drawing, not while composing,
 * since the slot's height changes with the top inset as the sheet moves. The layer reads its place
 * on the screen, so it follows a drag; the copy is blurred already, so a frame only moves it.
 */
@Composable
private fun BoxScope.SheetBackdropLayer(backdrop: AppBackdrop, glass: SheetGlass, reachUp: () -> Int, rim: Color, pill: Color) {
    val fallback = MaterialTheme.colorScheme.background
    Box(
        Modifier.matchParentSize()
            .layout { measurable, constraints ->
                val up = reachUp()
                val placeable = measurable.measure(
                    Constraints.fixed(constraints.maxWidth, constraints.maxHeight + up)
                )
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, -up) }
            }
            // Measure and position the entire layer, including the handle, before aligning the
            // shared snapshot. The same bounds also clip and fill the resize fallback.
            .clipToBounds()
            .snapshotBackdrop(backdrop, tint = glass.tint, fallback = fallback, scrim = glass.scrim)
            .drawBehind {
                drawTopRim(rim)
                val up = reachUp()
                if (up > 0) {
                    val pillSize = Size(HANDLE_PILL_WIDTH.toPx(), HANDLE_PILL_HEIGHT.toPx())
                    drawRoundRect(
                        pill,
                        topLeft = Offset(
                            (size.width - pillSize.width) / 2,
                            up - HANDLE_BOTTOM_PADDING.toPx() - pillSize.height,
                        ),
                        size = pillSize,
                        cornerRadius = CornerRadius(pillSize.height / 2),
                    )
                }
            }
    )
}

private val HANDLE_TOP_PADDING = 12.dp
private val HANDLE_BOTTOM_PADDING = 16.dp
private val HANDLE_PILL_WIDTH = 36.dp
private val HANDLE_PILL_HEIGHT = 4.dp
private const val HANDLE_PILL_ALPHA = .4f

/**
 * The handle row in Material's slot, below the top inset. It draws its pill only without the
 * backdrop; with it, the backdrop layer draws the pill over the frosted copy. [onHeight] reports
 * the row's height, inset included, so the backdrop can reach over it.
 */
@Composable
private fun SheetHandle(showPill: Boolean, onHeight: (Int) -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .onSizeChanged { onHeight(it.height) }
            .windowInsetsPadding(BottomSheetDefaults.windowInsets.only(WindowInsetsSides.Top))
            .padding(top = HANDLE_TOP_PADDING, bottom = HANDLE_BOTTOM_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(width = HANDLE_PILL_WIDTH, height = HANDLE_PILL_HEIGHT)
                .then(
                    if (showPill) Modifier.background(
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = HANDLE_PILL_ALPHA), CircleShape,
                    ) else Modifier
                )
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
    FloatingSurface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), blur = false) {
        // FloatingSurface's outer Box relaxes minimum constraints. Give its painted Surface a
        // full-width child too, so a short summary does not leave most of the card unpainted.
        Box(Modifier.fillMaxWidth()) { content() }
    }
}

/**
 * The container colour of a choice in a sheet: the selected one a tinted pill, the others a
 * raised surface that stays distinct from the frosted sheet, without adding a border.
 */
@Composable
internal fun sheetOptionColor(selected: Boolean) =
    if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh

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
