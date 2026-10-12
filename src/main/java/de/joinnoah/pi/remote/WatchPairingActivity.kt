package de.joinnoah.pi.remote

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
        setContent {
            var scan by remember { mutableStateOf(false) }
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.wear_phone_pair_title), style = MaterialTheme.typography.headlineMedium)
                        Text(stringResource(R.string.wear_phone_pair_intro))
                        nodeName?.let { Text(it) }
                        if (failure) Text(stringResource(R.string.wear_phone_unavailable))
                        Button(onClick = { scan = true }, enabled = nodeName != null && !sending) { Text(stringResource(R.string.remote_scan)) }
                        OutlinedTextField(code, { code = it.take(8192) }, label = { Text(stringResource(R.string.wear_pair_code)) }, enabled = !sending, modifier = Modifier.fillMaxWidth())
                        val valid = runCatching { Wire.pairing(code) }.isSuccess
                        Button(onClick = { send(request) }, enabled = nodeName != null && !sending && valid) { Text(stringResource(R.string.wear_send_pair_code)) }
                        TextButton(onClick = { finish() }, enabled = !sending) { Text(stringResource(R.string.remote_cancel)) }
                    }
                    if (scan) QrScanner({ value -> code = value.take(8192); scan = false }, { scan = false }, { scan = false })
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
