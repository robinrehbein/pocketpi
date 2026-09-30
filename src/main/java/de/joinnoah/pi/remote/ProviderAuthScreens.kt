package de.joinnoah.pi.remote

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun ProvidersScreen(
    key: RemoteNavKey.Providers,
    model: ProvidersViewModel,
    navigator: RemoteNavigator,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val auth = state.providerAuth.takeIf { it.routeId == key.routeId } ?: ProviderAuthState(routeId = key.routeId)
    val available = canManageProviders(state)
    val context = LocalContext.current
    // Loads the list once the capability is known, also after a reconnect lost a request.
    LaunchedEffect(available, state.loading, auth.loaded, auth.loading, auth.error) {
        if (available && !state.loading && !auth.loaded && !auth.loading && auth.error == null) model.reload()
    }
    // Coming back from the browser: a login that went on meanwhile is re-read.
    LifecycleResumeEffect(available, auth.busy) {
        if (available && auth.busy) model.reload()
        onPauseOrDispose {}
    }
    ProvidersContent(
        auth = auth,
        hostName = state.host?.name,
        connected = state.connected,
        available = available,
        connection = state.connection,
        onBack = navigator::back,
        onRetry = model::reload,
        onStart = model::startLogin,
        onLogout = model::logout,
        onAnswer = model::answerLogin,
        onCancelLogin = model::cancelLogin,
        onDismissLogin = model::dismissLogin,
        onDismissNotice = model::dismissNotice,
        onOpenLink = { openHttpsLink(context, it) },
    )
}

/** Opens [url] in the browser, only when it is an `https:` address. Returns false when it did not open. */
internal fun openHttpsLink(context: Context, url: String): Boolean {
    if (!validHttpsUrl(url)) return false
    val uri = Uri.parse(url)
    // A Custom Tab keeps the user in the app's task; a browser without support gets a plain view.
    try {
        CustomTabsIntent.Builder().build().launchUrl(context, uri)
        return true
    } catch (_: ActivityNotFoundException) {
    }
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

/**
 * While [enabled], keeps the window out of screenshots and the recents thumbnail and tells
 * autofill to skip it, so a typed key, a pasted redirect or a device code is not captured.
 */
@Composable
internal fun SecureWindow(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        val window = view.context.findActivity()?.window
        val autofill = view.importantForAutofill
        if (enabled) {
            window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        onDispose {
            if (enabled) {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                view.importantForAutofill = autofill
            }
        }
    }
}

@Composable
private fun noticeText(notice: ProviderNotice): String =
    if (notice.provider != null) stringResource(notice.message, notice.provider)
    else stringResource(notice.message)

@Composable
internal fun ProvidersContent(
    auth: ProviderAuthState,
    hostName: String?,
    connected: Boolean,
    available: Boolean,
    connection: Int,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onStart: (providerId: String, method: ProviderAuthMethod, replace: Boolean) -> Unit,
    onLogout: (providerId: String) -> Unit,
    onAnswer: (promptId: String, value: String) -> Boolean,
    onCancelLogin: () -> Unit,
    onDismissLogin: () -> Unit,
    onDismissNotice: () -> Unit,
    onOpenLink: (String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val flow = auth.flow
    // Back from a running login leaves it running on the Mac; the list shows a banner to return.
    var hidden by remember(flow?.loginId) { mutableStateOf(false) }
    val showFlow = flow != null && !hidden
    BackHandler(enabled = showFlow) { if (flow?.finished == true) onDismissLogin() else hidden = true }
    if (flow != null && showFlow)
        LoginFlowScreen(
            flow = flow,
            providerName = auth.providers.firstOrNull { it.id == flow.providerId }?.name ?: flow.providerId,
            connected = connected,
            connection = connection,
            onBack = { if (flow.finished) onDismissLogin() else hidden = true },
            onAnswer = onAnswer,
            onCancel = onCancelLogin,
            onDone = onDismissLogin,
            onOpenLink = onOpenLink,
            modifier = modifier,
        )
    else
        ProviderListScreen(
            auth = auth,
            hostName = hostName,
            connected = connected,
            available = available,
            connection = connection,
            onBack = onBack,
            onRetry = onRetry,
            onStart = onStart,
            onLogout = onLogout,
            onShowFlow = { hidden = false },
            onDismissNotice = onDismissNotice,
            modifier = modifier,
        )
}

@Composable
private fun ProviderScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FloatingSurface(modifier = Modifier.testTag("navigationPill"), shape = CircleShape) {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.remote_back))
                    }
                }
                Spacer(Modifier.width(16.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
            }
        },
        content = content,
    )
}

@Composable
private fun NoticeCard(text: String, onDismiss: (() -> Unit)?, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().testTag("providerNotice"),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
    ) {
        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text,
                Modifier.weight(1f).padding(vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (onDismiss != null)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, stringResource(R.string.providers_dismiss))
                }
        }
    }
}

@Composable
private fun ProviderListScreen(
    auth: ProviderAuthState,
    hostName: String?,
    connected: Boolean,
    available: Boolean,
    connection: Int,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onStart: (String, ProviderAuthMethod, Boolean) -> Unit,
    onLogout: (String) -> Unit,
    onShowFlow: () -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var picking by remember { mutableStateOf<AuthProvider?>(null) }
    var replacing by remember { mutableStateOf<Pair<AuthProvider, ProviderAuthMethod>?>(null) }
    var signingOut by remember { mutableStateOf<AuthProvider?>(null) }
    val host = hostName ?: stringResource(R.string.providers_host_fallback)
    val actionable = connected && available && !auth.working && !auth.busy

    fun begin(provider: AuthProvider, method: ProviderAuthMethod) {
        if (provider.storedLogin) replacing = provider to method else onStart(provider.id, method, false)
    }

    ProviderScaffold(stringResource(R.string.providers_title), onBack, modifier) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            auth.notice?.let { NoticeCard(noticeText(it), onDismissNotice) }
            when {
                !connected -> Message(stringResource(connection), "providersOffline")
                !available -> Message(stringResource(R.string.providers_unavailable), "providersUnavailable")
                auth.error != null && !auth.loaded ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Message(stringResource(auth.error), "providersError")
                        OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.providers_retry)) }
                    }
                !auth.loaded ->
                    Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                        val loading = stringResource(R.string.providers_loading)
                        CircularProgressIndicator(Modifier.semantics { liveRegion = LiveRegionMode.Polite; contentDescription = loading })
                    }
                else ->
                    LazyColumn(
                        Modifier.weight(1f).testTag("providerList"),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item("banner") {
                            val running = auth.flow?.takeIf { !it.finished }
                            val other = auth.other
                            if (running != null)
                                LoginBanner(
                                    stringResource(
                                        R.string.providers_banner_own,
                                        auth.providers.firstOrNull { it.id == running.providerId }?.name ?: running.providerId,
                                    ),
                                    onShowFlow,
                                )
                            else if (other != null)
                                LoginBanner(
                                    stringResource(
                                        R.string.providers_banner_other,
                                        auth.providers.firstOrNull { it.id == other.providerId }?.name ?: other.providerId,
                                    ),
                                    null,
                                )
                        }
                        item("intro") {
                            Text(
                                stringResource(R.string.providers_intro, host),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (auth.providers.isEmpty())
                            item("empty") { Message(stringResource(R.string.providers_empty), "providersEmpty") }
                        items(auth.providers, key = { it.id }) { provider ->
                            ProviderRow(
                                provider = provider,
                                enabled = actionable,
                                onSignIn = {
                                    if (provider.methods.size == 1) begin(provider, provider.methods.single())
                                    else picking = provider
                                },
                                onSignOut = { signingOut = provider },
                            )
                        }
                        if (auth.truncated)
                            item("truncated") {
                                Text(
                                    stringResource(R.string.providers_truncated),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                    }
            }
        }
    }
    picking?.let { provider ->
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text(stringResource(R.string.providers_method_title, provider.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    provider.methods.forEach { method ->
                        OutlinedButton(
                            onClick = {
                                picking = null
                                begin(provider, method)
                            },
                            modifier = Modifier.fillMaxWidth().testTag("providerMethod:${method.wire}"),
                        ) {
                            Text(
                                stringResource(
                                    if (method == ProviderAuthMethod.OAUTH) R.string.providers_method_oauth
                                    else R.string.providers_method_api_key
                                )
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { picking = null }) { Text(stringResource(R.string.remote_cancel)) } },
        )
    }
    replacing?.let { (provider, method) ->
        AlertDialog(
            onDismissRequest = { replacing = null },
            title = { Text(stringResource(R.string.providers_replace_title)) },
            text = { Text(stringResource(R.string.providers_replace_message, provider.name, host)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        replacing = null
                        onStart(provider.id, method, true)
                    },
                    modifier = Modifier.testTag("providerReplaceConfirm"),
                ) {
                    Text(stringResource(R.string.providers_replace_confirm))
                }
            },
            dismissButton = { TextButton(onClick = { replacing = null }) { Text(stringResource(R.string.remote_cancel)) } },
        )
    }
    signingOut?.let { provider ->
        AlertDialog(
            onDismissRequest = { signingOut = null },
            title = { Text(stringResource(R.string.providers_logout_title, provider.name)) },
            text = { Text(stringResource(R.string.providers_logout_message, host)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        signingOut = null
                        onLogout(provider.id)
                    },
                    modifier = Modifier.testTag("providerLogoutConfirm"),
                ) {
                    Text(stringResource(R.string.providers_sign_out))
                }
            },
            dismissButton = { TextButton(onClick = { signingOut = null }) { Text(stringResource(R.string.remote_cancel)) } },
        )
    }
}

@Composable
private fun Message(text: String, tag: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 16.dp).testTag(tag),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LoginBanner(text: String, onShow: (() -> Unit)?) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("providerBanner"),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
    ) {
        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f).padding(vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })
            if (onShow != null)
                TextButton(onClick = onShow, modifier = Modifier.testTag("providerBannerContinue")) {
                    Text(stringResource(R.string.providers_banner_continue))
                }
        }
    }
}

@Composable
private fun statusLabel(status: ProviderStatus): String =
    stringResource(
        when {
            !status.configured -> R.string.providers_status_signed_out
            status.source == ProviderKeySource.CONFIG -> R.string.providers_status_config
            status.source == ProviderKeySource.RUNTIME -> R.string.providers_status_runtime
            status.source == ProviderKeySource.ENVIRONMENT -> R.string.providers_status_environment
            status.type == ProviderAuthMethod.OAUTH -> R.string.providers_status_signed_in_oauth
            status.type == ProviderAuthMethod.API_KEY -> R.string.providers_status_signed_in_key
            else -> R.string.providers_status_signed_in
        }
    )

@Composable
private fun ProviderRow(
    provider: AuthProvider,
    enabled: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().testTag("providerRow:${provider.id}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(provider.name, style = MaterialTheme.typography.titleMedium)
            Surface(
                shape = RoundedCornerShape(50),
                color =
                    if (provider.status.configured) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    statusLabel(provider.status),
                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp).testTag("providerStatus:${provider.id}"),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (provider.methods.isEmpty() && !provider.storedLogin)
                Text(
                    stringResource(R.string.providers_no_phone_login),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (provider.methods.isNotEmpty())
                    Button(
                        onClick = onSignIn,
                        enabled = enabled,
                        modifier = Modifier.testTag("providerSignIn:${provider.id}"),
                    ) {
                        Text(stringResource(R.string.providers_sign_in))
                    }
                if (provider.storedLogin)
                    TextButton(
                        onClick = onSignOut,
                        enabled = enabled,
                        modifier = Modifier.testTag("providerSignOut:${provider.id}"),
                    ) {
                        Text(stringResource(R.string.providers_sign_out))
                    }
            }
        }
    }
}

@Composable
internal fun LoginFlowScreen(
    flow: LoginFlow,
    providerName: String,
    connected: Boolean,
    connection: Int,
    onBack: () -> Unit,
    onAnswer: (promptId: String, value: String) -> Boolean,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    onOpenLink: (String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    SecureWindow(flow.needsSecureWindow())
    var linkFailed by remember { mutableStateOf(false) }
    val open: (String) -> Unit = { linkFailed = !onOpenLink(it) }
    ProviderScaffold(stringResource(R.string.providers_flow_title, providerName), onBack, modifier) { insets ->
        Column(
            Modifier.fillMaxSize()
                .padding(insets)
                .consumeWindowInsets(insets)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .testTag("loginFlow"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!connected) Message(stringResource(connection), "loginOffline")
            flow.notice?.let { NoticeCard(noticeText(it), null) }
            if (linkFailed) NoticeCard(stringResource(R.string.providers_no_browser), { linkFailed = false })
            if (flow.finished) LoginResult(flow, providerName, onDone)
            else {
                flow.deviceCode?.let { DeviceCodeCard(it, flow.deviceCodeAt, open) }
                flow.authUrl?.let { AuthUrlCard(it, open) }
                flow.info?.let { InfoCard(it, open) }
                val prompt = flow.prompt
                if (prompt != null)
                    PromptCard(prompt, sending = flow.answering, connected = connected, onAnswer = onAnswer)
                else ProgressLine(flow)
                if (flow.needsSecureWindow())
                    Text(
                        stringResource(R.string.providers_secure_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                OutlinedButton(
                    onClick = onCancel,
                    enabled = !flow.cancelling,
                    modifier = Modifier.fillMaxWidth().testTag("loginCancel"),
                ) {
                    Text(
                        stringResource(
                            if (flow.cancelling) R.string.providers_cancelling else R.string.providers_cancel_login
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressLine(flow: LoginFlow) {
    val text =
        flow.progress
            ?: stringResource(
                if (flow.deviceCode == null && flow.authUrl == null) R.string.providers_flow_starting
                else R.string.providers_flow_waiting
            )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.testTag("loginProgress"),
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(text, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@Composable
private fun LoginResult(flow: LoginFlow, providerName: String, onDone: () -> Unit) {
    val success = flow.state == LoginState.SUCCEEDED
    Column(Modifier.fillMaxWidth().testTag("loginResult"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            if (success) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(40.dp),
        )
        Text(
            when {
                success -> stringResource(R.string.providers_result_success_title, providerName)
                flow.state == LoginState.CANCELLED -> stringResource(R.string.providers_result_cancelled_title)
                flow.state == LoginState.TIMEOUT -> stringResource(R.string.providers_result_timeout_title)
                else -> stringResource(R.string.providers_result_failed_title)
            },
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
        )
        if (success) {
            Text(stringResource(R.string.providers_result_success_body))
            if (flow.modelStale) Text(stringResource(R.string.providers_result_stale))
        } else Text(stringResource(loginFailureMessage(flow.state, flow.error)))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().testTag("loginDone")) {
            Text(stringResource(R.string.providers_done))
        }
    }
}

private suspend fun copySensitive(clipboard: Clipboard, text: String) {
    val clip = ClipData.newPlainText("code", text)
    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    clipboard.setClipEntry(ClipEntry(clip))
}

@Composable
private fun rememberRemainingSeconds(startedAt: Long?, expiresIn: Long?): Long? {
    if (startedAt == null || expiresIn == null) return null
    val deadline = startedAt + expiresIn * 1000
    val remaining by
        produceState(((deadline - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0), deadline) {
            while (true) {
                value = ((deadline - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)
                if (value == 0L) break
                delay(1000)
            }
        }
    return remaining
}

internal fun formatCountdown(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

@Composable
private fun DeviceCodeCard(code: AuthEvent.DeviceCode, receivedAt: Long?, onOpen: (String) -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val remaining = rememberRemainingSeconds(receivedAt, code.expiresInSeconds)
    Card(Modifier.fillMaxWidth().testTag("loginDeviceCode")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.providers_device_code_intro))
            Text(
                code.userCode,
                style = MaterialTheme.typography.displaySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.testTag("loginCode"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { scope.launch { copySensitive(clipboard, code.userCode) } }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.providers_copy_code))
                }
                Button(onClick = { onOpen(code.verificationUri) }, modifier = Modifier.testTag("loginOpenLink")) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.providers_open_link))
                }
            }
            Text(
                when {
                    remaining == null -> stringResource(R.string.providers_code_expiry_unknown)
                    remaining == 0L -> stringResource(R.string.providers_code_expired)
                    else -> stringResource(R.string.providers_expires_in, formatCountdown(remaining))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("loginCountdown"),
            )
        }
    }
}

@Composable
private fun AuthUrlCard(event: AuthEvent.AuthUrl, onOpen: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("loginAuthUrl")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(event.instructions ?: stringResource(R.string.providers_auth_url_intro))
            Button(onClick = { onOpen(event.url) }, modifier = Modifier.testTag("loginOpenLink")) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.providers_open_sign_in))
            }
        }
    }
}

@Composable
private fun InfoCard(info: AuthEvent.Info, onOpen: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("loginInfo")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(info.message)
            info.links.forEach { link ->
                TextButton(onClick = { onOpen(link.url) }) {
                    Text(link.label ?: stringResource(R.string.providers_open_link))
                }
            }
        }
    }
}

/**
 * The prompt the host waits on. Typed values stay in this composable's memory only: not saveable,
 * not in a draft or navigation snapshot, cleared after sending and when the prompt goes away.
 */
@Composable
private fun PromptCard(pending: PendingPrompt, sending: Boolean, connected: Boolean, onAnswer: (String, String) -> Boolean) {
    val prompt = pending.prompt
    key(pending.promptId) {
        Card(Modifier.fillMaxWidth().testTag("loginPrompt")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (prompt.type == PromptType.MANUAL_CODE)
                    Text(stringResource(R.string.providers_manual_code_hint), style = MaterialTheme.typography.bodyMedium)
                Text(prompt.message, style = MaterialTheme.typography.bodyLarge)
                if (prompt.type == PromptType.SELECT) SelectPrompt(pending, sending, connected, onAnswer)
                else TextPrompt(pending, sending, connected, onAnswer)
            }
        }
    }
}

@Composable
private fun TextPrompt(pending: PendingPrompt, sending: Boolean, connected: Boolean, onAnswer: (String, String) -> Boolean) {
    val prompt = pending.prompt
    val secret = prompt.type == PromptType.SECRET
    val manual = prompt.type == PromptType.MANUAL_CODE
    var value by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(!secret) }
    var notSent by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { value = "" } }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val tooLong = value.toByteArray().size > MAX_PROVIDER_AUTH_ANSWER_BYTES
    OutlinedTextField(
        value = value,
        onValueChange = { value = it },
        modifier = Modifier.fillMaxWidth().testTag("loginSecretField"),
        label = {
            Text(
                prompt.placeholder
                    ?: stringResource(
                        when {
                            secret -> R.string.providers_secret_label
                            manual -> R.string.providers_manual_code_label
                            else -> R.string.providers_text_label
                        }
                    )
            )
        },
        singleLine = !manual,
        minLines = if (manual) 2 else 1,
        isError = tooLong,
        supportingText = if (tooLong) ({ Text(stringResource(R.string.providers_answer_too_long)) }) else null,
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions =
            KeyboardOptions(
                keyboardType =
                    when {
                        // Password keeps the keyboard from learning or suggesting the value.
                        secret || manual -> KeyboardType.Password
                        else -> KeyboardType.Text
                    },
                autoCorrectEnabled = false,
            ),
        trailingIcon =
            if (secret) {
                {
                    IconButton(onClick = { shown = !shown }) {
                        Icon(
                            if (shown) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            stringResource(if (shown) R.string.providers_hide else R.string.providers_show),
                        )
                    }
                }
            } else null,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (manual)
            OutlinedButton(
                onClick = {
                    scope.launch {
                        clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text
                            ?.let { value = it.toString() }
                    }
                },
                modifier = Modifier.testTag("loginPaste"),
            ) {
                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.providers_paste))
            }
        Button(
            onClick = {
                val answer = if (secret || manual) value.trim() else value
                // Cleared only once the answer was sent; otherwise the user keeps what they typed.
                if (onAnswer(pending.promptId, answer)) {
                    value = ""
                    notSent = false
                    if (secret || manual)
                        scope.launch {
                            // The pasted value must not linger on the clipboard.
                            val onClipboard =
                                clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text
                            if (onClipboard?.toString()?.trim() == answer) clipboard.setClipEntry(null)
                        }
                } else notSent = true
            },
            enabled = !sending && connected && !tooLong && (prompt.type == PromptType.TEXT || value.isNotBlank()),
            modifier = Modifier.testTag("loginSend"),
        ) {
            if (sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text(stringResource(if (secret) R.string.providers_secret_save else R.string.providers_send))
        }
    }
    if (notSent) Text(stringResource(R.string.providers_answer_not_sent), color = MaterialTheme.colorScheme.error)
}

@Composable
private fun SelectPrompt(pending: PendingPrompt, sending: Boolean, connected: Boolean, onAnswer: (String, String) -> Boolean) {
    var selected by remember { mutableStateOf<String?>(null) }
    var notSent by remember { mutableStateOf(false) }
    Column(Modifier.selectableGroup()) {
        pending.prompt.options.forEach { option ->
            Row(
                Modifier.fillMaxWidth()
                    .selectable(selected = selected == option.id, enabled = !sending, role = Role.RadioButton) {
                        selected = option.id
                    }
                    .padding(vertical = 8.dp)
                    .testTag("loginOption:${option.id}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == option.id, onClick = null, enabled = !sending)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(option.label)
                    option.description?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    Button(
        onClick = { selected?.let { notSent = !onAnswer(pending.promptId, it) } },
        enabled = !sending && connected && selected != null,
        modifier = Modifier.testTag("loginSend"),
    ) {
        if (sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else Text(stringResource(R.string.providers_send))
    }
    if (notSent) Text(stringResource(R.string.providers_answer_not_sent), color = MaterialTheme.colorScheme.error)
}
