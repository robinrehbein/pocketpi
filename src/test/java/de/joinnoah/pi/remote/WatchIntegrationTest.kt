package de.joinnoah.pi.remote

import android.content.Intent
import android.net.Uri
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WatchIntegrationTest {
    private val nonce = Wire.encode(ByteArray(16) { it.toByte() })
    private fun view(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
    private fun code() = Wire.json.parseToJsonElement(javaClass.getResource("/v1.json")!!.readText()).jsonObject.obj("qr").text("text").toByteArray()

    @Test fun `session handoff contains identifiers only and round trips`() {
        val selection = RemoteSelection("route:one", "project", "session:one")
        val uri = watchHandoffUri(selection)!!
        assertEquals(setOf("routeId", "sessionId"), uri.queryParameterNames)
        assertEquals("route:one" to "session:one", parseWatchHandoff(Intent(Intent.ACTION_VIEW, uri)))
        assertNull(watchHandoffUri(selection.copy(sessionId = null)))
    }
    @Test fun `handoff rejects wrong scheme duplicate or unexpected parameters and invalid ids`() {
        listOf(
            "https://session?routeId=r&sessionId=s", "pocketpi://session/other?routeId=r&sessionId=s",
            "pocketpi://session?routeId=r&routeId=other&sessionId=s", "pocketpi://session?routeId=r&sessionId=s&secret=x",
            "pocketpi://session?routeId=r&sessionId=a%20b", "pocketpi://session?routeId=r&sessionId=s#extra",
        ).forEach { assertNull(it, parseWatchHandoff(view(it))) }
        assertNull(parseWatchHandoff(Intent(Intent.ACTION_SEND, watchHandoffUri(RemoteSelection("r", "p", "s")))))
    }
    @Test fun `pair request requires a canonical nonce and exact routing parameters`() {
        val uri = watchPairUri("watch", nonce)
        assertEquals(WatchPairRequest("watch", nonce), parseWatchPairRequest(Intent(Intent.ACTION_VIEW, uri)))
        assertNull(parseWatchPairRequest(view("pocketpi://pair-watch?nodeId=watch&nonce=bad")))
        assertNull(parseWatchPairRequest(view("$uri&nonce=$nonce")))
        assertNull(parseWatchPairRequest(view("$uri&secret=unexpected")))
    }
    @Test fun `mailbox accepts only expected source nonce and valid pairing code exactly once`() {
        val mailbox = WatchPairMailbox { 100L }
        mailbox.begin("phone", nonce)
        assertNull(mailbox.receive("other", WATCH_PAIR_PATH + nonce, code()))
        assertNull(mailbox.receive("phone", WATCH_PAIR_PATH + Wire.random(), code()))
        assertNull(mailbox.receive("phone", WATCH_PAIR_PATH + nonce, "not a code".toByteArray()))
        assertNull(mailbox.receive("phone", WATCH_PAIR_PATH + nonce, ByteArray(8193)))
        assertEquals(String(code()), mailbox.receive("phone", WATCH_PAIR_PATH + nonce, code()))
        assertNull(mailbox.receive("phone", WATCH_PAIR_PATH + nonce, code()))
    }
    @Test fun `cancelled expired and superseded requests cannot supply a pairing code`() {
        var now = 0L
        val mailbox = WatchPairMailbox { now }
        mailbox.begin("phone", nonce)
        mailbox.cancel()
        assertNull(mailbox.receive("phone", WATCH_PAIR_PATH + nonce, code()))
        mailbox.begin("phone", nonce)
        now = WATCH_PAIR_WAIT_MILLIS
        assertNull(mailbox.receive("phone", WATCH_PAIR_PATH + nonce, code()))
        mailbox.begin("phone", nonce)
        val newer = Wire.random()
        mailbox.begin("phone", newer)
        assertNull(mailbox.receive("phone", WATCH_PAIR_PATH + nonce, code()))
        assertNotNull(mailbox.receive("phone", WATCH_PAIR_PATH + newer, code()))
    }
}
