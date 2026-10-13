package de.joinnoah.pi.remote

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/**
 * The transport [RemoteApplication] builds its repository with. Debug and release talk to the
 * real relay; the benchmark build type provides a fake relay instead (src/benchmark).
 */
@Suppress("UNUSED_PARAMETER")
internal fun remoteTransport(context: Context, scope: CoroutineScope): RemoteTransport =
    SecureRemoteTransport(scope)
