package de.joinnoah.pi.remote

import androidx.annotation.StringRes
import kotlinx.serialization.json.JsonObject

/** Reloading pi's extensions, skills, prompts, themes and context files (`session.reload`). */
internal const val RELOAD_CAPABILITY = "session.reload.v1"

/** The start of the host's `internal` message when the reload's outcome is not known. */
internal const val RELOAD_UNKNOWN_PREFIX = "Reload result unknown"

/** Why a reload did not report success. */
sealed interface ReloadFailure {
    /** The session is working; asking again when it is idle may work. */
    data object Busy : ReloadFailure

    /** The host lacks [RELOAD_CAPABILITY], or the session is not host-owned. */
    data object Unsupported : ReloadFailure

    /** pi reported an error; [message] is the host's short text, if any. */
    data class Failed(val message: String?) : ReloadFailure

    /** No answer arrived in time; pi may or may not have reloaded. */
    data object Unknown : ReloadFailure

    /** The session or the connection was gone. */
    data object ConnectionFailure : ReloadFailure
}

/** Outcome of [RemoteRepository.reloadSession]. */
sealed interface ReloadResult {
    data object Done : ReloadResult

    data class Failed(val failure: ReloadFailure) : ReloadResult
}

/** Validates a `session.reload` result against the session it was requested for. */
internal fun validatedReloaded(data: JsonObject, sessionId: String) {
    Wire.keys(data, setOf("kind", "sessionId"))
    require(data.text("kind") == "reloaded")
    require(data.text("sessionId") == sessionId)
}

/** Maps a failed `session.reload` request. */
internal fun reloadFailure(e: Exception): ReloadFailure =
    when (e) {
        is RemoteRequestException ->
            when (e.code) {
                "busy" -> ReloadFailure.Busy
                "unsupported" -> ReloadFailure.Unsupported
                "offline" -> ReloadFailure.ConnectionFailure
                "timeout" -> ReloadFailure.Unknown
                "internal" ->
                    if (e.hostMessage?.startsWith(RELOAD_UNKNOWN_PREFIX) == true) ReloadFailure.Unknown
                    else ReloadFailure.Failed(e.hostMessage?.takeIf(String::isNotBlank))
                else -> ReloadFailure.Failed(null)
            }
        is IllegalStateException ->
            if (e.message == "Request timed out") ReloadFailure.Unknown else ReloadFailure.ConnectionFailure
        else -> ReloadFailure.ConnectionFailure
    }

/** Whether pi may have reloaded although the app has no answer; the chat then refreshes. */
internal fun reloadResultUnknown(failure: ReloadFailure): Boolean = failure == ReloadFailure.Unknown

/** The toast text of a failed reload; [ReloadFailure.Failed] with a message uses the detail form. */
@StringRes
internal fun reloadFailureText(failure: ReloadFailure): Int =
    when (failure) {
        ReloadFailure.Busy -> R.string.remote_reload_busy
        ReloadFailure.Unsupported -> R.string.remote_reload_unsupported
        is ReloadFailure.Failed ->
            if (failure.message == null) R.string.remote_reload_failed else R.string.remote_reload_failed_detail
        ReloadFailure.Unknown -> R.string.remote_reload_unknown
        ReloadFailure.ConnectionFailure -> R.string.remote_reload_connection
    }
