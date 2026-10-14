package de.joinnoah.pi.remote

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Keep runs live in the background" ([LiveRunService]): on unless the user switches it off. Kept
 * in a preferences file of its own, apart from the other settings. Cheap to create: Android keeps
 * a preferences file in memory once it was read, so callers read it where they need it.
 */
internal class LiveRunSettings(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    private val mutable = MutableStateFlow(preferences.getBoolean(KEY_ENABLED, true))
    val enabled: StateFlow<Boolean> = mutable.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_ENABLED, enabled) }
        mutable.value = enabled
    }

    private companion object {
        const val FILE = "live_run"
        const val KEY_ENABLED = "enabled"
    }
}

/** The setting's row in Settings, with the notification settings. */
@Composable
internal fun LiveRunSettingRow() {
    val context = LocalContext.current
    val settings = remember(context) { LiveRunSettings(context) }
    val enabled by settings.enabled.collectAsState()
    // One focusable row: TalkBack reads the label, the state and the hint.
    Column(
        Modifier.fillMaxWidth()
            .toggleable(value = enabled, role = Role.Switch) { settings.setEnabled(it) }
            .testTag("liveRunSwitch")
            .padding(vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Update, contentDescription = null)
            Spacer(Modifier.width(14.dp))
            Text(
                stringResource(R.string.remote_live_run_setting),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
            Switch(checked = enabled, onCheckedChange = null)
        }
        Text(
            pluralStringResource(R.plurals.remote_live_run_setting_help, LIVE_RUN_CAP_MINUTES, LIVE_RUN_CAP_MINUTES),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
