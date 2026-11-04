package de.joinnoah.pi.remote

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService

class WatchPairingListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        (application as WearApplication).receivePairCode(event.sourceNodeId, event.path, event.data)
    }
}

internal fun requestPhonePairing(context: Context, active: () -> Boolean, failure: (Int) -> Unit) {
    val app = context.applicationContext as WearApplication
    val nonce = app.reservePairRequest() ?: return
    fun failed() { if (app.cancelPairRequest(nonce)) failure(R.string.wear_phone_unavailable) }
    val client = Wearable.getNodeClient(context)
    client.connectedNodes.addOnSuccessListener { nodes ->
        if (!active()) { app.cancelPairRequest(nonce); return@addOnSuccessListener }
        val phone = nodes.sortedByDescending { it.isNearby }.firstOrNull()
        if (phone == null) { failed(); return@addOnSuccessListener }
        client.localNode.addOnSuccessListener local@{ local ->
            if (!active()) { app.cancelPairRequest(nonce); return@local }
            if (!app.startPairRequest(phone.id, nonce)) return@local
            try {
                val executor = ContextCompat.getMainExecutor(context)
                val future = RemoteActivityHelper(context, executor).startRemoteActivity(
                    Intent(Intent.ACTION_VIEW, watchPairUri(local.id, nonce)).addCategory(Intent.CATEGORY_BROWSABLE), phone.id,
                )
                future.addListener({
                    try { future.get() }
                    catch (_: Exception) { failed() }
                }, executor)
            } catch (_: Exception) { failed() }
        }.addOnFailureListener { failed() }
    }.addOnFailureListener { failed() }
}
