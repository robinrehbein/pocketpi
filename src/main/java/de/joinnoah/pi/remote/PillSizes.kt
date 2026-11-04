package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

internal val LocalComposerPillSize = compositionLocalOf { PillSize.SMALL }
internal val LocalInsightPillSize = compositionLocalOf { PillSize.LARGE }

@Composable
internal fun InsightPillRow(
    modifier: Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    onClick: (() -> Unit)? = null,
    selected: Boolean? = null,
    onClickLabel: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val small = LocalInsightPillSize.current == PillSize.SMALL
    val interactive = if (onClick == null) modifier else if (selected != null) {
        modifier.selectable(selected, source, if (small) null else ripple(), role = Role.Button, onClick = onClick)
    } else {
        modifier.clickable(source, if (small) null else ripple(), role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
    }
    if (!small) {
        FloatingSurface(shape = CircleShape, color = color) {
            Row(interactive.minimumInteractiveComponentSize().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                content = content)
        }
    } else {
        Box(interactive.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
            FloatingSurface(shape = CircleShape, color = color) {
                Row(Modifier.heightIn(min = 32.dp).clip(CircleShape).indication(source, ripple()).testTag("insightPillSurface").padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                    content = content)
            }
        }
    }
}

@Composable
internal fun PillSizeChoice(title: String, selected: PillSize, tag: String, onSelect: (PillSize) -> Unit) {
    Column(Modifier.selectableGroup()) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillSize.entries.forEach { size ->
                Row(
                    Modifier.selectable(selected == size, role = Role.RadioButton) { onSelect(size) }
                        .testTag("$tag-${size.stored}").heightIn(min = 48.dp).padding(end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected == size, onClick = null)
                    Text(stringResource(if (size == PillSize.SMALL) R.string.remote_pill_small else R.string.remote_pill_large))
                }
            }
        }
    }
}
