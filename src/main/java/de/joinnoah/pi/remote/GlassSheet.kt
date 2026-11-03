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
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.collapse
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.SheetValue
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

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
        // The handle and the window insets live inside the content, so one backdrop layer covers the
        // whole sheet: no seam between handle and content, and no opaque strip over the insets.
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        // The same structure with or without the backdrop, so its arrival keeps the content's state.
        Box(Modifier.fillMaxWidth()) {
            if (backdrop != null) SheetBackdropLayer(backdrop, glass)
            Column(Modifier.fillMaxWidth().windowInsetsPadding(BottomSheetDefaults.windowInsets)) {
                SheetHandle(sheetState, onDismissRequest, rim = if (backdrop != null) glassRimColor(scheme) else null)
                content()
            }
        }
    }
}

private class SheetGlass(val scrim: Color, val tint: Color)

/** A blurred, downscaled copy of the app window and the window's place and size on the screen. */
private class AppBackdrop(val image: ImageBitmap, val originOnScreen: IntOffset, val windowSize: IntSize)

/** How much smaller than the app the copy is: it is blurred anyway, and stays small in memory. */
private const val BACKDROP_DOWNSCALE = 4

/** The blur radius in pixels of the small copy; three box passes of it read as about 56 screen pixels. */
private const val BACKDROP_BLUR_RADIUS = 14

/** Copies and blurs the app window as the sheet opens; null until the copy is there or when it fails. */
@Composable
private fun rememberAppBackdrop(): AppBackdrop? {
    // Robolectric's PixelCopy draws the window itself and fails on Compose's vector caches.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || Build.FINGERPRINT == "robolectric") return null
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    var backdrop by remember { mutableStateOf<AppBackdrop?>(null) }
    LaunchedEffect(view, configuration) {
        backdrop = null
        val window = view.context.sheetActivity()?.window ?: return@LaunchedEffect
        val root = window.decorView
        if (root.width == 0 || root.height == 0) return@LaunchedEffect
        val bitmap = Bitmap.createBitmap(
            (root.width / BACKDROP_DOWNSCALE).coerceAtLeast(1),
            (root.height / BACKDROP_DOWNSCALE).coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val copied = suspendCancellableCoroutine { continuation ->
            runCatching {
                PixelCopy.request(window, bitmap, { result ->
                    if (continuation.isActive) continuation.resume(result == PixelCopy.SUCCESS)
                }, Handler(Looper.getMainLooper()))
            }.onFailure { if (continuation.isActive) continuation.resume(false) }
        }
        if (!copied) return@LaunchedEffect
        val location = IntArray(2).also(root::getLocationOnScreen)
        withContext(Dispatchers.Default) {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            boxBlur(pixels, bitmap.width, bitmap.height, BACKDROP_BLUR_RADIUS)
            bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
        backdrop = AppBackdrop(bitmap.asImageBitmap(), IntOffset(location[0], location[1]), IntSize(root.width, root.height))
    }
    return backdrop
}

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

private tailrec fun Context.sheetActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.sheetActivity()
        else -> null
    }

/**
 * The blurred app copy under the whole sheet, lined up with the screen, with the scrim and the
 * glass tint over it. It reads the sheet's place on the screen, so it follows a drag; the copy is
 * blurred already, so a frame only moves it.
 */
@Composable
private fun BoxScope.SheetBackdropLayer(backdrop: AppBackdrop, glass: SheetGlass) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        // The copy is drawn at the whole window's size; clipping keeps it inside the sheet's layer.
        Modifier.matchParentSize()
            .clipToBounds()
            .onGloballyPositioned { origin = it.positionOnScreen() }
            .drawBehind {
                drawImage(
                    backdrop.image,
                    dstOffset = IntOffset(
                        (backdrop.originOnScreen.x - origin.x).roundToInt(),
                        (backdrop.originOnScreen.y - origin.y).roundToInt(),
                    ),
                    dstSize = backdrop.windowSize,
                )
                drawRect(glass.scrim)
                drawRect(glass.tint)
            }
    )
}

/**
 * The sheet's handle, with the click and TalkBack actions Material gives its own handle: dismiss,
 * and expand or collapse when the sheet has a half-open state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetHandle(sheetState: SheetState, onDismissRequest: () -> Unit, rim: Color?) {
    val scope = rememberCoroutineScope()
    val dismissLabel = stringResource(R.string.sheet_handle_dismiss)
    val expandLabel = stringResource(R.string.sheet_handle_expand)
    val collapseLabel = stringResource(R.string.sheet_handle_collapse)
    val hideAndDismiss: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) onDismissRequest() }
    }
    Box(
        Modifier.fillMaxWidth()
            .then(if (rim != null) Modifier.drawBehind { drawTopRim(rim) } else Modifier)
            .padding(top = 12.dp, bottom = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.clickable {
                when (sheetState.currentValue) {
                    SheetValue.Expanded -> hideAndDismiss()
                    SheetValue.PartiallyExpanded -> scope.launch { sheetState.expand() }
                    SheetValue.Hidden -> scope.launch { sheetState.show() }
                }
            }
                .semantics(mergeDescendants = true) {
                    dismiss(dismissLabel) { hideAndDismiss(); true }
                    if (sheetState.currentValue == SheetValue.PartiallyExpanded) {
                        expand(expandLabel) { scope.launch { sheetState.expand() }; true }
                    } else if (sheetState.hasPartiallyExpandedState) {
                        collapse(collapseLabel) { scope.launch { sheetState.partialExpand() }; true }
                    }
                }
                .padding(horizontal = 24.dp, vertical = 8.dp),
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
