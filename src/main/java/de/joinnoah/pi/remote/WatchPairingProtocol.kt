package de.joinnoah.pi.remote

import android.content.Intent
import android.net.Uri

internal const val WATCH_PAIR_PATH = "/pocketpi/watch-pair/"
internal const val WATCH_PAIR_WAIT_MILLIS = 5 * 60 * 1000L
internal data class WatchPairRequest(val nodeId: String, val nonce: String)

internal fun validWatchNonce(value: String) = runCatching { Wire.decode(value, 16) }.isSuccess
internal fun watchPairUri(nodeId: String, nonce: String): Uri = Uri.Builder()
    .scheme("pocketpi").authority("pair-watch")
    .appendQueryParameter("nodeId", nodeId).appendQueryParameter("nonce", nonce).build()
internal fun parseWatchPairRequest(intent: Intent): WatchPairRequest? {
    if (intent.action != Intent.ACTION_VIEW) return null
    val uri = intent.data ?: return null
    if (uri.scheme != "pocketpi" || uri.host != "pair-watch" || uri.port != -1 || uri.userInfo != null ||
        !uri.path.isNullOrEmpty() || uri.fragment != null || uri.toString().length > 2048 ||
        uri.queryParameterNames != setOf("nodeId", "nonce")) return null
    val node = uri.getQueryParameters("nodeId").singleOrNull()?.takeIf(::isOpaqueId) ?: return null
    val nonce = uri.getQueryParameters("nonce").singleOrNull()?.takeIf(::validWatchNonce) ?: return null
    return WatchPairRequest(node, nonce)
}

/** One-use, expiring, source-bound mailbox. Credentials are never accepted through this channel. */
internal class WatchPairMailbox(private val now: () -> Long = android.os.SystemClock::elapsedRealtime) {
    private data class Pending(val nodeId: String, val nonce: String, val expires: Long)
    private var pending: Pending? = null
    @Synchronized fun begin(nodeId: String, nonce: String) {
        require(isOpaqueId(nodeId) && validWatchNonce(nonce))
        pending = Pending(nodeId, nonce, now() + WATCH_PAIR_WAIT_MILLIS)
    }
    @Synchronized fun cancel() { pending = null }
    @Synchronized fun receive(nodeId: String, path: String, bytes: ByteArray): String? {
        val request = pending ?: return null
        if (now() >= request.expires) { pending = null; return null }
        if (nodeId != request.nodeId || path != WATCH_PAIR_PATH + request.nonce || bytes.size > 8192) return null
        val code = runCatching { Wire.utf8(bytes).also { Wire.pairing(it) } }.getOrNull() ?: return null
        pending = null
        return code
    }
}
