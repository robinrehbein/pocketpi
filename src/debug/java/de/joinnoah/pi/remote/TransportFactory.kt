package de.joinnoah.pi.remote

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import okhttp3.WebSocket

/** Debug instrumentation may install one factory before RemoteApplication.onCreate. */
internal object DebugTransportFixture {
    internal data class Fixture(val factory: WebSocket.Factory, val decorate: ((RemoteTransport) -> RemoteTransport)?)
    private var next: Fixture? = null

    @Synchronized fun install(factory: WebSocket.Factory, decorate: ((RemoteTransport) -> RemoteTransport)? = null) {
        check(next == null) { "A fixture transport is already installed" }
        next = Fixture(factory, decorate)
    }

    @Synchronized fun take(): Fixture? = next.also { next = null }

    @Synchronized fun clear() { next = null }
}

@Suppress("UNUSED_PARAMETER")
internal fun remoteTransport(context: Context, scope: CoroutineScope): RemoteTransport {
    val fixture = DebugTransportFixture.take()
    return if (fixture == null) SecureRemoteTransport(scope) else {
        val transport = SecureRemoteTransport(scope, fixture.factory)
        fixture.decorate?.invoke(transport) ?: transport
    }
}
