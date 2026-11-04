package de.joinnoah.pi.remote

/** The host's `busy` message when the Mac editor of a terminal session holds unsent text (a fixture string). */
internal const val CLOSE_DRAFT_REFUSAL = "Unsent text in the Mac editor"

/** The host's `internal` message when pi did not confirm that it exited (a fixture string). */
internal const val CLOSE_RESULT_UNKNOWN = "Close result unknown; refresh"

/** The string that explains why the host refused `session.close` with [e]. */
internal fun closeRefusal(e: Throwable): Int {
    val request = e as? RemoteRequestException ?: return R.string.remote_request_error
    return when {
        request.isExtensionControlRefusal -> R.string.remote_close_unsupported
        request.code == "busy" && request.hostMessage == CLOSE_DRAFT_REFUSAL -> R.string.remote_close_draft
        request.code == "busy" -> R.string.remote_close_busy
        request.code == "internal" && request.hostMessage == CLOSE_RESULT_UNKNOWN -> R.string.remote_close_timeout
        request.code == "offline" -> R.string.remote_close_offline
        else -> R.string.remote_request_error
    }
}

/** The close may have ended pi although no reply confirmed it, or the session cannot be reached: read the list again. */
internal fun closeNeedsRefresh(e: Throwable): Boolean =
    (e as? RemoteRequestException)?.let {
        it.code == "offline" || (it.code == "internal" && it.hostMessage == CLOSE_RESULT_UNKNOWN)
    } == true
