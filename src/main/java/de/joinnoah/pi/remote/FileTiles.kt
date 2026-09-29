package de.joinnoah.pi.remote

import android.text.format.Formatter
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.platform.testTag
import kotlin.math.roundToInt

@Composable
internal fun FileEntryTile(
    entry: FileEntry,
    preview: FileTilePreview?,
    onOpen: () -> Unit,
    onRequestPreview: () -> Unit,
    onPeek: (IntRect) -> Unit,
    modifier: Modifier = Modifier,
    tag: String = "fileTile:${entry.name}",
) {
    val openable = entry.type == FileEntryType.DIR || entry.type == FileEntryType.FILE
    val kind = stringResource(
        when (entry.type) {
            FileEntryType.SYMLINK -> R.string.remote_files_symlink
            FileEntryType.SUBMODULE -> R.string.remote_files_submodule
            else -> entry.type.kindString()
        }
    )
    val context = LocalContext.current
    val fileSize = if (entry.type == FileEntryType.FILE) entry.size?.let { Formatter.formatShortFileSize(context, it) } else null
    val peekAction = stringResource(R.string.remote_files_peek_action)
    var bounds = IntRect.Zero
    if (entry.type == FileEntryType.FILE) {
        LaunchedEffect(entry.name, preview == null) {
            if (preview == null) onRequestPreview()
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 112.dp)
            .onGloballyPositioned { coordinates ->
                val rect = coordinates.boundsInWindow()
                bounds = IntRect(rect.left.roundToInt(), rect.top.roundToInt(), rect.right.roundToInt(), rect.bottom.roundToInt())
            }
            .testTag(tag)
            .then(
                if (openable) Modifier.combinedClickable(
                    onClick = onOpen,
                    onLongClickLabel = peekAction,
                    onLongClick = { onPeek(bounds) },
                ) else Modifier.semantics { disabled() }
            ),
        shape = RoundedCornerShape(18.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            Modifier.padding(14.dp).heightIn(min = 84.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = entry.type.icon(),
                    contentDescription = kind,
                    modifier = Modifier.size(24.dp),
                    tint = if (entry.type == FileEntryType.DIR) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entry.type == FileEntryType.FILE) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = previewText(preview),
                        modifier = Modifier.weight(1f)
                            .testTag("fileTilePreview:${entry.name}")
                            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                            .drawWithContent {
                                drawContent()
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        0f to androidx.compose.ui.graphics.Color.Black,
                                        1f to androidx.compose.ui.graphics.Color.Transparent,
                                    ),
                                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                                )
                            },
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (openable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(kind, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (fileSize != null) {
                        Text(fileSize, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun FileEntryType.kindString(): Int = when (this) {
    FileEntryType.DIR -> R.string.remote_files_kind_dir
    FileEntryType.FILE -> R.string.remote_files_kind_file
    FileEntryType.SYMLINK -> R.string.remote_files_kind_symlink
    FileEntryType.SUBMODULE -> R.string.remote_files_kind_submodule
}

private fun FileEntryType.icon() = when (this) {
    FileEntryType.DIR -> Icons.Default.Folder
    FileEntryType.FILE -> Icons.Default.Description
    FileEntryType.SYMLINK -> Icons.Default.Link
    FileEntryType.SUBMODULE -> Icons.Default.AccountTree
}

@Composable
private fun previewText(preview: FileTilePreview?): String = when {
    preview == null || preview.loading -> stringResource(R.string.remote_files_preview_loading)
    preview.binary -> stringResource(R.string.remote_files_preview_binary)
    preview.tooLarge -> stringResource(R.string.remote_files_preview_too_large)
    preview.failure != null -> stringResource(R.string.remote_files_preview_failed)
    preview.content.isNullOrEmpty() -> stringResource(R.string.remote_files_preview_empty)
    else -> preview.content.lines().take(3).joinToString("\n")
}

/** Coordinates from the lazy grid item must be window coordinates. */
internal class FilePeekPositionProvider(density: Density, private val fixedAnchor: IntRect? = null) : PopupPositionProvider {
    private val margin = with(density) { 16.dp.roundToPx() }
    private val preferredWidth = with(density) { 260.dp.roundToPx() }

    internal fun widthForWindow(windowWidth: Int): Int =
        preferredWidth.coerceAtMost((windowWidth - 2 * margin).coerceAtLeast(0))

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val anchor = fixedAnchor ?: anchorBounds
        val centered = anchor.left + (anchor.width - popupContentSize.width) / 2
        val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)
        val x = centered.coerceIn(margin, maxX)
        val y = if (anchor.top >= popupContentSize.height) {
            anchor.top - popupContentSize.height
        } else {
            anchor.bottom.coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0))
        }
        return IntOffset(x, y)
    }
}

@Composable
internal fun FilePeekPopup(
    peek: FilesPeek,
    preview: FileTilePreview?,
    anchorBounds: IntRect,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val windowWidth = with(density) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    val popupWidth = with(density) { FilePeekPositionProvider(density).widthForWindow(windowWidth).toDp() }
    Popup(
        popupPositionProvider = FilePeekPositionProvider(density, anchorBounds),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Card(
            modifier = Modifier.width(popupWidth).testTag("filePeek"),
            shape = RoundedCornerShape(20.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        peek.path.substringAfterLast('/'),
                        modifier = Modifier.weight(1f).semantics { heading() },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp).testTag("filePeekDismiss")) {
                        Icon(Icons.Default.Close, stringResource(R.string.remote_files_peek_dismiss))
                    }
                }
                if (peek.type == FileEntryType.DIR) {
                    when {
                        peek.loading -> Text(stringResource(R.string.remote_files_preview_loading))
                        peek.failure != null -> Text(stringResource(R.string.remote_files_preview_failed))
                        peek.listing == null || peek.listing.entries.isEmpty() -> Text(stringResource(R.string.remote_files_empty))
                        else -> {
                            peek.listing.entries.take(MAX_FILE_PEEK_NAMES).forEach { child ->
                                Text(child.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (peek.listing.entries.size > MAX_FILE_PEEK_NAMES || peek.listing.nextAfter != null || peek.listing.truncated) {
                                Text(stringResource(R.string.remote_files_peek_more), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                } else {
                    Text(
                        previewText(preview),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
