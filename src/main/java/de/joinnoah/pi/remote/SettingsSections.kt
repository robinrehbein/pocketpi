package de.joinnoah.pi.remote

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The lazy-list key of a Settings group's heading is this prefix and the group's tag. */
internal const val SETTINGS_GROUP_KEY_PREFIX = "settings-group-"

/** A Settings group's name above its cards: General, Chat, Session list, Devices, Privacy, About. */
@Composable
internal fun SettingsGroupHeading(text: String, tag: String) {
    Text(
        text,
        Modifier.fillMaxWidth()
            .padding(start = 4.dp, top = 12.dp)
            .semantics { heading() }
            .testTag(SETTINGS_GROUP_KEY_PREFIX + tag),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** One row of a settings card: an icon, a title and a line under it, the whole row one target. */
@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

/**
 * The session filter's "Hide offline" switch, mirrored here: both change the same setting, so the
 * filter of every project's list shows what is chosen here.
 */
@Composable
internal fun HideOfflineSessionsSection(hide: Boolean, onChange: (Boolean) -> Unit) {
    Section(stringResource(R.string.remote_settings_offline_title)) {
        // One focusable row: TalkBack reads the label, the state and the hint.
        SettingsRow(
            Icons.Default.CloudOff,
            stringResource(R.string.remote_settings_hide_offline),
            stringResource(R.string.remote_settings_hide_offline_help),
            Modifier.toggleable(value = hide, role = Role.Switch, onValueChange = onChange).testTag("hideOfflineSwitch"),
        ) {
            Switch(checked = hide, onCheckedChange = null)
        }
    }
}

/**
 * The Macs this phone is paired with, which opens the hosts list, and how a watch is paired: that
 * starts on the watch, which then asks this phone to scan the Mac's code.
 */
@Composable
internal fun DevicesSection(pairedMacs: Int, onPairedMacs: () -> Unit) {
    Section(null, Modifier.testTag("settingsDevices")) {
        SettingsRow(
            Icons.Default.LaptopMac,
            stringResource(R.string.remote_settings_paired_macs),
            if (pairedMacs == 0) stringResource(R.string.remote_settings_paired_none)
            else pluralStringResource(R.plurals.remote_settings_paired_count, pairedMacs, pairedMacs),
            Modifier.clickable(role = Role.Button, onClick = onPairedMacs).testTag("settingsPairedMacs"),
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
        HorizontalDivider()
        SettingsRow(
            Icons.Default.Watch,
            stringResource(R.string.remote_settings_watch),
            stringResource(R.string.remote_settings_watch_help),
            Modifier.semantics(mergeDescendants = true) {}.testTag("settingsWatch"),
        )
    }
}

/** The app's version, for a tester's report, and the privacy policy. */
@Composable
internal fun AboutSection(onPrivacyPolicy: () -> Unit) {
    Section(null, Modifier.testTag("settingsAbout")) {
        SettingsRow(
            Icons.Default.Info,
            stringResource(R.string.remote_app_name),
            stringResource(R.string.remote_settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            Modifier.semantics(mergeDescendants = true) {}.testTag("settingsVersion"),
        )
        HorizontalDivider()
        Text(stringResource(R.string.remote_settings_privacy_title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.remote_settings_privacy_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onPrivacyPolicy, modifier = Modifier.testTag("openPrivacyPolicy")) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.remote_settings_privacy_open))
        }
    }
}
