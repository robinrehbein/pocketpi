package de.joinnoah.pi.remote

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// The app's bottom sheets in the floating-glass design. A sheet is a window of its own above the
// app, so Haze cannot blur what lies behind it, and the system's blur behind windows is missing on
// many phones (Samsung among them). So the sheet takes a small copy of the app as it opens and draws
// that copy blurred under its own content, at the glass tint, lined up with where the app sits on
// the screen: frosted glass over what was there. The copy is a still frame. Before Android 12, or
// when the copy fails, the sheet stays opaque.

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
    val scheme = MaterialTheme.colorScheme
    // The glass tint keeps text at 4.5:1 over any content, as on the floating pills.
    val tint = scheme.background.copy(alpha = GLASS_TINT_ALPHA)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = SHEET_CORNER, topEnd = SHEET_CORNER),
        containerColor = scheme.background,
        contentColor = scheme.onBackground,
        tonalElevation = 0.dp,
        // The handle and the content each draw their part of the frosted backdrop, so it moves with the sheet.
        dragHandle = { SheetHandle(backdrop, tint, rim = if (backdrop != null) glassRimColor(scheme) else null) },
    ) {
        // The same structure with or without the backdrop, so its arrival keeps the content's state.
        Box(Modifier.fillMaxWidth()) {
            if (backdrop != null) SheetBackdropLayer(backdrop, tint)
            Column(Modifier.fillMaxWidth()) { content() }
        }
    }
}

/** A still, downscaled copy of the app window and where that window sits on the screen. */
private class AppBackdrop(val image: ImageBitmap, val originOnScreen: Offset, val scale: Float)

/** How much smaller than the app the copy is: it is blurred anyway, and stays small in memory. */
private const val BACKDROP_DOWNSCALE = 4

/** How strongly the copy under the sheet is blurred, in pixels of the screen. */
private const val BACKDROP_BLUR_PX = 56f

/** Copies the app window once as the sheet opens; null until the copy is there or when it fails. */
@Composable
private fun rememberAppBackdrop(): AppBackdrop? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val view = LocalView.current
    var backdrop by remember { mutableStateOf<AppBackdrop?>(null) }
    DisposableEffect(view) {
        val window = view.context.sheetActivity()?.window
        val root = window?.decorView
        if (window == null || root == null || root.width == 0 || root.height == 0) return@DisposableEffect onDispose {}
        var active = true
        runCatching {
            val bitmap = Bitmap.createBitmap(
                (root.width / BACKDROP_DOWNSCALE).coerceAtLeast(1),
                (root.height / BACKDROP_DOWNSCALE).coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
            val location = IntArray(2).also(root::getLocationOnScreen)
            val origin = Offset(location[0].toFloat(), location[1].toFloat())
            val scale = bitmap.width.toFloat() / root.width
            PixelCopy.request(window, bitmap, { result ->
                if (active && result == PixelCopy.SUCCESS) backdrop = AppBackdrop(bitmap.asImageBitmap(), origin, scale)
            }, Handler(Looper.getMainLooper()))
        }
        onDispose { active = false }
    }
    return backdrop
}

private tailrec fun Context.sheetActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.sheetActivity()
        else -> null
    }

/**
 * The blurred app copy under one part of the sheet, lined up with the screen, and the glass tint
 * over it. Each part reads its own place on the screen, so the parts join up and follow a drag.
 */
@Composable
private fun BoxScope.SheetBackdropLayer(backdrop: AppBackdrop, tint: Color) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.matchParentSize()
            .onGloballyPositioned { origin = it.positionOnScreen() }
            .graphicsLayer {
                renderEffect = BlurEffect(BACKDROP_BLUR_PX, BACKDROP_BLUR_PX, TileMode.Clamp)
                clip = true
            }
            .drawBehind {
                val dx = backdrop.originOnScreen.x - origin.x
                val dy = backdrop.originOnScreen.y - origin.y
                val up = 1f / backdrop.scale
                translate(dx, dy) { scale(up, up, pivot = Offset.Zero) { drawImage(backdrop.image) } }
            }
    )
    Box(Modifier.matchParentSize().background(tint))
}

@Composable
private fun SheetHandle(backdrop: AppBackdrop?, tint: Color, rim: Color?) {
    Box(Modifier.fillMaxWidth()) {
        if (backdrop != null) SheetBackdropLayer(backdrop, tint)
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
