package de.joinnoah.pi.remote

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.android.gms.wearable.Wearable

/** Scans for the watch, not for the phone. Never saves or reuses a phone device's credentials. */
class WatchPairingActivity : ComponentActivity() {
    private var code by mutableStateOf("")
    private var nodeName by mutableStateOf<String?>(null)
    private var sending by mutableStateOf(false)
    private var failure by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = parseWatchPairRequest(intent) ?: run { finish(); return }
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            nodeName = nodes.find { it.id == request.nodeId }?.displayName
            if (nodeName == null) failure = true
        }.addOnFailureListener { failure = true }
        enableEdgeToEdge()
        setContent {
            var scan by remember { mutableStateOf(false) }
            // The app's palette and mode, like every other PocketPi screen.
            val preferences by (application as RemoteApplication).settings.state.collectAsState()
            val dark = preferences.themeMode.isDark(isSystemInDarkTheme())
            SystemBarsAppearance(dark)
            MaterialTheme(
                colorScheme =
                    remember(dark, preferences.theme) { appColorScheme(this@WatchPairingActivity, dark, preferences.theme) },
                typography = RemoteTypography,
                shapes = RemoteShapes,
            ) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding()) {
                        val close = stringResource(R.string.remote_cancel)
                        PaneHeader(
                            title = stringResource(R.string.wear_phone_pair_title),
                            subtitle = nodeName,
                            nested = false,
                            busy = sending,
                            tag = "watchPairing",
                            backLabel = stringResource(R.string.remote_back),
                            closeLabel = close,
                            reloadLabel = stringResource(R.string.remote_refresh),
                            onBack = { if (!sending) finish() },
                            onClose = { if (!sending) finish() },
                            onReload = null,
                            // The code is on its way: the screen closes by itself once it arrived.
                            leaveEnabled = !sending,
                        )
                        Column(
                            Modifier.padding(horizontal = 24.dp, vertical = 8.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Text(stringResource(R.string.wear_phone_pair_intro))
                            if (failure)
                                Text(stringResource(R.string.wear_phone_unavailable), color = MaterialTheme.colorScheme.error)
                            Button(onClick = { scan = true }, enabled = nodeName != null && !sending, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.remote_scan))
                            }
                            OutlinedTextField(
                                code,
                                { code = it.take(PAIRING_CODE_MAX_LENGTH) },
                                label = { Text(stringResource(R.string.wear_pair_code)) },
                                enabled = !sending,
                                singleLine = true,
                                keyboardOptions = PAIRING_CODE_KEYBOARD,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            val valid = runCatching { Wire.pairing(code) }.isSuccess
                            Button(onClick = { send(request) }, enabled = nodeName != null && !sending && valid, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.wear_send_pair_code))
                            }
                        }
                    }
                    if (scan) QrScanner({ value -> code = value.take(PAIRING_CODE_MAX_LENGTH); scan = false }, { scan = false }, { scan = false })
                }
            }
        }
    }
    private fun send(request: WatchPairRequest) {
        if (sending || nodeName == null) return
        val bytes = code.toByteArray(Charsets.UTF_8)
        if (bytes.size > 8192 || runCatching { Wire.pairing(code) }.isFailure) return
        sending = true
        Wearable.getMessageClient(this).sendMessage(request.nodeId, WATCH_PAIR_PATH + request.nonce, bytes)
            .addOnSuccessListener {
                code = ""
                sending = false
                if (!isDestroyed) {
                    Toast.makeText(this, R.string.wear_pair_code_sent, Toast.LENGTH_LONG).show()
                    finish()
                }
            }.addOnFailureListener { sending = false; failure = true }
    }
    override fun onStop() {
        code = ""
        super.onStop()
    }
}
