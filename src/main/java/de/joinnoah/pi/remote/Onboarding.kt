package de.joinnoah.pi.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import java.util.Locale
import kotlinx.coroutines.delay

/** The longest text the pairing field takes; [Wire.pairing] refuses anything longer. */
internal const val PAIRING_CODE_MAX_LENGTH = 8192

/** Whole seconds left until [deadline] (both in [Diagnostics.now] millis), rounded up, never below zero. */
internal fun pairingSecondsLeft(deadline: Long, now: Long): Int =
    ((deadline - now + 999) / 1000).toInt().coerceAtLeast(0)

/** [seconds] as the wait card's "m:ss". */
internal fun pairingCountdownText(seconds: Int): String =
    String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)

/**
 * The text of a clip when it is a whole pairing code, so the paste dialog can offer it; anything
 * else on the clipboard stays where it is.
 */
internal fun pairingCodeFromClip(clip: String?): String? =
    clip?.trim()?.takeIf { it.length <= PAIRING_CODE_MAX_LENGTH && runCatching { Wire.pairing(it) }.isSuccess }

/**
 * The pairing code holds the pairing's secret: the password keyboard keeps it out of the keyboard's
 * learning and suggestions.
 */
internal val PAIRING_CODE_KEYBOARD =
    KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        keyboardType = KeyboardType.Password,
        imeAction = ImeAction.Done,
    )

/**
 * Shown while the Mac asks its user to approve this phone: a spinner, the time left until
 * [deadline] and a way out. At the deadline it says that no approval came, until the repository
 * ends the pairing and reports the same.
 */
@Composable
internal fun PairingWaitCard(deadline: Long, onCancel: () -> Unit, now: () -> Long = Diagnostics::now) {
    val seconds by
        produceState(pairingSecondsLeft(deadline, now()), deadline) {
            while (true) {
                value = pairingSecondsLeft(deadline, now())
                if (value == 0) break
                delay(250)
            }
        }
    Card(
        Modifier.fillMaxWidth().testTag("pairingWaitCard"),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (seconds > 0) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                Text(
                    stringResource(R.string.remote_pairing_wait_title),
                    Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (seconds > 0) {
                Text(stringResource(R.string.remote_approval))
                Text(
                    stringResource(R.string.remote_pairing_time_left, pairingCountdownText(seconds)),
                    Modifier.testTag("pairingCountdown"),
                    style = MaterialTheme.typography.labelLarge,
                )
            } else {
                // Announced as it replaces the countdown, so TalkBack users learn the wait ran out.
                Text(
                    stringResource(R.string.remote_pairing_timeout),
                    Modifier.testTag("pairingTimedOut").semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            TextButton(
                onClick = onCancel,
                modifier = Modifier.align(Alignment.End).testTag("cancelPairing"),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
            ) {
                Text(stringResource(R.string.remote_pairing_cancel))
            }
        }
    }
}

/**
 * The computers page before anything is paired: what pairing takes, step by step, the two ways to
 * hand over the Mac's code, and what the Mac needs.
 */
@Composable
internal fun FirstRunCard(onScan: () -> Unit, onPaste: () -> Unit) {
    var setup by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().testTag("firstRunCard")) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.remote_empty_hosts),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.remote_welcome),
                    Modifier.semantics { heading() }.testTag("firstRunHeadline"),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            listOf(
                R.string.remote_onboarding_step_1,
                R.string.remote_onboarding_step_2,
                R.string.remote_onboarding_step_3,
            ).forEachIndexed { index, step ->
                // One node per step, so TalkBack reads the number with its text.
                Row(
                    Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        Modifier.size(28.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    Text(stringResource(step), Modifier.weight(1f).padding(top = 3.dp))
                }
            }
            Button(onClick = onScan, modifier = Modifier.fillMaxWidth().testTag("firstRunScan")) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.remote_onboarding_scan))
            }
            OutlinedButton(onClick = onPaste, modifier = Modifier.fillMaxWidth().testTag("firstRunPaste")) {
                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.remote_onboarding_paste))
            }
            TextButton(onClick = { setup = true }, modifier = Modifier.testTag("firstRunSetup")) {
                Icon(
                    Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.remote_onboarding_setup))
            }
        }
    }
    if (setup)
        AlertDialog(
            onDismissRequest = { setup = false },
            title = { Text(stringResource(R.string.remote_onboarding_setup)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("setupMacHelp")) {
                    Text(stringResource(R.string.remote_onboarding_setup_body))
                    Text(stringResource(R.string.remote_pair_help))
                    Text(stringResource(R.string.remote_onboarding_setup_projects))
                }
            },
            confirmButton = {
                TextButton(onClick = { setup = false }) { Text(stringResource(R.string.remote_onboarding_setup_done)) }
            },
        )
}

/**
 * The dialog for a pasted pairing code. A whole code on the clipboard fills an empty field when it
 * opens. The code is a secret: the window stays out of screenshots and the recents thumbnail, and
 * the field uses [PAIRING_CODE_KEYBOARD].
 */
@Composable
internal fun PairingCodeDialog(
    text: String,
    onTextChange: (String) -> Unit,
    onPair: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(Unit) {
        if (text.isBlank()) pairingCodeFromClip(clipboard.getText()?.text)?.let(onTextChange)
    }
    SecureWindow(true)
    AlertDialog(
        onDismissRequest = onDismiss,
        // The dialog is a window of its own; the activity's flag does not cover it.
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(stringResource(R.string.remote_pair)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= PAIRING_CODE_MAX_LENGTH) onTextChange(it) },
                modifier = Modifier.testTag("pairingCodeField"),
                label = { Text(stringResource(R.string.remote_pair_code)) },
                placeholder = { Text(stringResource(R.string.remote_design_pair_input_hint)) },
                singleLine = true,
                keyboardOptions = PAIRING_CODE_KEYBOARD,
                keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onPair() }),
                trailingIcon = {
                    IconButton(
                        onClick = {
                            clipboard.getText()?.text?.trim()?.takeIf { it.length <= PAIRING_CODE_MAX_LENGTH }
                                ?.let(onTextChange)
                        },
                    ) {
                        Icon(Icons.Default.ContentPaste, stringResource(R.string.remote_paste_from_clipboard))
                    }
                },
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
            )
        },
        confirmButton = {
            TextButton(onClick = onPair, enabled = text.isNotBlank(), modifier = Modifier.testTag("confirmPairingCode")) {
                Text(stringResource(R.string.remote_pair))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.remote_cancel)) }
        },
    )
}
