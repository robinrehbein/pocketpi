package de.joinnoah.pi.remote

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/**
 * The watch compiles the phone's sources, RemoteApplication among them, but not the phone's
 * build-type source sets that provide this factory there. Only the real relay exists here.
 */
@Suppress("UNUSED_PARAMETER")
internal fun remoteTransport(context: Context, scope: CoroutineScope): RemoteTransport =
    SecureRemoteTransport(scope)
