package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Wrap the title's content, reserving room for the separate refresh pill even on narrow panes. */
@Composable
internal fun PaneHeader(
    title: String,
    subtitle: String?,
    nested: Boolean,
    busy: Boolean,
    tag: String,
    backLabel: String,
    closeLabel: String,
    reloadLabel: String,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onReload: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val textWidth = (maxWidth - 56.dp - 28.dp - 60.dp - if (nested) 48.dp else 0.dp).coerceAtLeast(0.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            FloatingSurface(Modifier.testTag("${tag}HeaderPill"), shape = CircleShape) {
                Row(Modifier.padding(start = 8.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Esc runs the same step as this button (see PaneBackHandler).
                    ShortcutTooltip(if (nested) backLabel else closeLabel, Shortcut.ESCAPE) {
                        IconButton(onClick = if (nested) onBack else onClose, Modifier.size(48.dp).testTag(if (nested) "${tag}Back" else "${tag}Close")) {
                            Icon(if (nested) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Close, if (nested) backLabel else closeLabel)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.widthIn(max = textWidth)) {
                        Text(title, Modifier.widthIn(min = minOf(48.dp, textWidth)).testTag("${tag}Title"), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (subtitle != null)
                            Text(subtitle, Modifier.testTag("${tag}Subtitle"), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = if (nested) FontFamily.Monospace else null,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (nested)
                        ShortcutTooltip(closeLabel) {
                            IconButton(onClick = onClose, Modifier.size(48.dp).testTag("${tag}Close")) {
                                Icon(Icons.Default.Close, closeLabel)
                            }
                        }
                }
            }
            FloatingSurface(Modifier.testTag("${tag}ReloadPill"), shape = CircleShape) {
                ShortcutTooltip(reloadLabel) {
                    IconButton(onClick = onReload, enabled = !busy, modifier = Modifier.size(48.dp).testTag("${tag}Reload")) {
                        Icon(Icons.Default.Refresh, reloadLabel)
                    }
                }
            }
        }
    }
}
