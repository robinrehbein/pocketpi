package de.joinnoah.pi.remote

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun AppIconSettingsSection() {
    val context = LocalContext.current
    val manager = remember(context) { AppIconManager(context) }
    var selected by remember(manager) { mutableStateOf(manager.selectedIcon()) }
    var failed by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, manager) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) selected = manager.selectedIcon()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Section(stringResource(R.string.remote_app_icon)) {
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            Text(
                stringResource(R.string.remote_app_icon_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            AppIcon.entries.forEach { icon ->
                val label = when (icon) {
                    AppIcon.WHITE_COLOR -> R.string.remote_app_icon_white_color
                    AppIcon.WHITE_BLACK -> R.string.remote_app_icon_white_black
                    AppIcon.BLACK_WHITE -> R.string.remote_app_icon_black_white
                    AppIcon.COLOR_WHITE -> R.string.remote_app_icon_color_white
                }
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(selected == icon, role = Role.RadioButton) {
                            failed = runCatching {
                                manager.setIcon(icon)
                                (context.applicationContext as? RemoteApplication)
                                    ?.home?.refreshLauncherShortcuts()
                            }.isFailure
                            selected = manager.selectedIcon()
                        }
                        .testTag("appIcon${icon.name}")
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RadioButton(selected = selected == icon, onClick = null)
                    Image(
                        painterResource(icon.previewRes),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)),
                    )
                    Text(stringResource(label), Modifier.weight(1f))
                }
            }
            if (failed) {
                Text(
                    stringResource(R.string.remote_app_icon_error),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
