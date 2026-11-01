package de.joinnoah.pi.remote

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import okhttp3.WebSocket

/** Debug instrumentation may install one factory before RemoteApplication.onCreate. */
internal object DebugTransportFixture {
    private var next: WebSocket.Factory? = null

    @Synchronized fun install(factory: WebSocket.Factory) {
        check(next == null) { "A fixture transport is already installed" }
        next = factory
    }

    @Synchronized fun take(): WebSocket.Factory? = next.also { next = null }

    @Synchronized fun clear() { next = null }
}

@Suppress("UNUSED_PARAMETER")
internal fun remoteTransport(context: Context, scope: CoroutineScope): RemoteTransport {
    val fixture = DebugTransportFixture.take()
    return if (fixture == null) SecureRemoteTransport(scope) else SecureRemoteTransport(scope, fixture)
}
