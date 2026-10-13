package de.joinnoah.pi.remote

import android.annotation.SuppressLint
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * The optional app lock. When it is on, MainActivity covers the app and asks for the fingerprint,
 * face or the device's PIN, pattern or password when it comes back after [AppLockSettings.timeoutMinutes]
 * in the background, and on every cold start. A notification, widget, shortcut or watch handoff that
 * arrives while locked is held and opened only after the user is verified; cancelling drops it.
 *
 * Notification actions (Approve, Reply) run in NotificationActionReceiver without opening the app,
 * so they are not behind the lock; the setting says so. The lock asks nothing on a device without a
 * screen lock, since there is no credential to ask for.
 */

/**
 * Whether the app is locked, and the launch target held while it is. One per process, so the state
 * survives a recreated activity; [clock] is the time since boot, which a changed wall clock cannot move.
 */
internal class AppLock(
    val store: AppLockStore,
    private val deviceSecure: () -> Boolean,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val mutableLocked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = mutableLocked.asStateFlow()

    /** When the app was last seen in front and unlocked; null until then in this process. */
    private var lastActive: Long? = null
    private var held: NotificationTarget? = null

    /** Whether the lock can ask for anything: switched on, and the device has a screen lock. */
    fun active(): Boolean = store.state.value.enabled && deviceSecure()

    /** Locks when the timeout has run out since the app was last in front, or it never was. */
    fun due(): Boolean {
        if (!active()) return false
        val since = lastActive ?: return true
        return clock() - since >= store.state.value.timeoutMinutes * 60_000L
    }

    /** Called when the app comes to the front and before a launch target is handled. */
    fun check() {
        if (!mutableLocked.value && due()) mutableLocked.value = true
    }

    /** The app leaves the front; its time in the background starts now unless it is locked already. */
    fun onBackground() {
        if (!mutableLocked.value) lastActive = clock()
    }

    /** [target] to open now, or null when it is held until the app is unlocked. A newer one replaces the held one. */
    fun admit(target: NotificationTarget): NotificationTarget? {
        check()
        if (!mutableLocked.value) return target
        held = target
        return null
    }

    /** The user was verified: unlocks and hands back the held launch target, if any. */
    fun unlock(): NotificationTarget? {
        lastActive = clock()
        mutableLocked.value = false
        return held.also { held = null }
    }

    /** The user cancelled or failed: the app stays locked and the held target is dropped. */
    fun cancel() {
        held = null
    }
}

/** Asks the user to verify themselves. The answer reaches the activity's result callback. */
internal interface AppLockPrompt {
    fun show()

    /** Withdraws a prompt still showing, without a result. */
    fun cancel()
}

/**
 * The system prompt: BiometricPrompt with a strong biometric or the device credential from API 30,
 * the confirm-credential screen of KeyguardManager below. Neither needs a library, so MainActivity
 * stays a plain ComponentActivity. Create it before the activity is started.
 */
internal class DeviceCredentialPrompt(
    private val activity: ComponentActivity,
    private val onResult: (verified: Boolean) -> Unit,
) : AppLockPrompt {
    private var signal: CancellationSignal? = null

    // Lint wants Fragment 1.3 for this, which matters only to a FragmentActivity; MainActivity is not one.
    @SuppressLint("InvalidFragmentVersionForActivityResult")
    private val confirmCredential =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            onResult(it.resultCode == Activity.RESULT_OK)
        }

    // The phone manifest declares USE_BIOMETRIC. The watch compiles this file too but has no MainActivity.
    @SuppressLint("MissingPermission")
    override fun show() {
        val title = activity.getString(R.string.remote_app_lock_prompt_title)
        if (Build.VERSION.SDK_INT >= 30) {
            val cancel = CancellationSignal().also { signal = it }
            BiometricPrompt.Builder(activity)
                .setTitle(title)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build()
                .authenticate(
                    cancel,
                    activity.mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                            if (signal === cancel) signal = null
                            onResult(true)
                        }

                        // A wrong finger only counts as an attempt; the prompt stays and reports an error once it gives up.
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                            if (signal === cancel) signal = null
                            // Withdrawn by cancel(), which has no result.
                            if (cancel.isCanceled) return
                            onResult(false)
                        }
                    },
                )
        } else {
            @Suppress("DEPRECATION")
            val intent = activity.getSystemService(KeyguardManager::class.java)?.createConfirmDeviceCredentialIntent(title, null)
            // No intent means no screen lock, so there is nothing to ask for.
            if (intent == null) onResult(true)
            else
                try {
                    confirmCredential.launch(intent)
                } catch (_: android.content.ActivityNotFoundException) {
                    // Stays locked; the user can try again from the lock screen.
                    onResult(false)
                }
        }
    }

    override fun cancel() {
        signal?.cancel()
        signal = null
    }
}

/** Whether the device has a PIN, pattern or password; the app lock asks for nothing without one. */
internal fun deviceSecure(context: Context): Boolean =
    context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

/** The app lock's settings for the settings screen; null where no activity provides it (tests, the watch). */
internal val LocalAppLockStore = staticCompositionLocalOf<AppLockStore?> { null }

/**
 * Shows [content] and, while [locked], covers it with [AppLockScreen]. The content stays composed so
 * the navigation and drafts are where the user left them, but it is hidden from accessibility services,
 * loses focus and the keyboard, and gets no touch or back press until the app is unlocked.
 */
@Composable
internal fun AppLockGate(locked: Boolean, onUnlock: () -> Unit, colors: ColorScheme, content: @Composable () -> Unit) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(locked) {
        if (locked) {
            focus.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    Box(Modifier.fillMaxSize()) {
        Box(if (locked) Modifier.fillMaxSize().clearAndSetSemantics {} else Modifier.fillMaxSize()) { content() }
        if (locked) MaterialTheme(colorScheme = colors, typography = RemoteTypography) { AppLockScreen(onUnlock) }
    }
}

@Composable
private fun AppLockScreen(onUnlock: () -> Unit) {
    val activity = LocalActivity.current
    // Back leaves the app instead of navigating the screen under the lock.
    BackHandler { activity?.moveTaskToBack(true) }
    // A Surface takes every touch, so nothing reaches the app behind it.
    Surface(Modifier.fillMaxSize().testTag("appLockScreen"), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.remote_app_lock_locked_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.remote_app_lock_locked_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onUnlock, modifier = Modifier.testTag("appLockUnlock")) {
                Text(stringResource(R.string.remote_app_lock_unlock))
            }
        }
    }
}

/** The app lock row in the settings: a switch, what it covers, and the timeout once it is on. */
@Composable
internal fun AppLockSettingRow() {
    val store = LocalAppLockStore.current ?: return
    val settings by store.state.collectAsState()
    val context = LocalContext.current
    // Read on every composition: the screen lock can be removed while the app is in the background.
    val secure = deviceSecure(context)
    val enabled = settings.enabled && secure
    // One focusable row: TalkBack reads the label, the state and what the lock leaves out.
    Column(
        Modifier.fillMaxWidth()
            .toggleable(value = enabled, enabled = secure, role = Role.Switch) { store.setEnabled(it) }
            .testTag("appLockSwitch")
            .padding(vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Lock, contentDescription = null)
            Spacer(Modifier.width(14.dp))
            Text(stringResource(R.string.remote_app_lock_title), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Switch(checked = enabled, onCheckedChange = null, enabled = secure)
        }
        Text(
            stringResource(if (secure) R.string.remote_app_lock_help else R.string.remote_app_lock_no_screen_lock),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (enabled)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.remote_app_lock_timeout), style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                APP_LOCK_TIMEOUTS.forEachIndexed { index, minutes ->
                    SegmentedButton(
                        selected = settings.timeoutMinutes == minutes,
                        onClick = { store.setTimeoutMinutes(minutes) },
                        shape = SegmentedButtonDefaults.itemShape(index, APP_LOCK_TIMEOUTS.size),
                        modifier = Modifier.testTag("appLockTimeout-$minutes"),
                        icon = {},
                    ) {
                        Text(stringResource(R.string.remote_app_lock_minutes, minutes), maxLines = 1)
                    }
                }
            }
        }
}
