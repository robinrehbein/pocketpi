package de.joinnoah.pi.remote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun ErrorInsightSheet(count: Int, selected: Boolean, onToggle: () -> Unit, onDismiss: () -> Unit) {
    InsightSheet(title = stringResource(R.string.insight_errors_title), onDismiss = onDismiss) {
        Text(
            stringResource(R.string.remote_panel_errors_filter, count),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
        Row(
            Modifier.fillMaxWidth()
                .testTag("insightErrorFilter")
                .toggleable(value = selected, role = Role.Switch, onValueChange = { onToggle() })
                .heightIn(min = 48.dp)
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.insight_errors_filter), modifier = Modifier.weight(1f).padding(end = 12.dp))
            Switch(checked = selected, onCheckedChange = null)
        }
    }
}
