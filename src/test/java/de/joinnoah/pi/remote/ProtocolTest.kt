package de.joinnoah.pi.remote

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    private fun fixture() =
        Wire.json.parseToJsonElement(javaClass.getResource("/v1.json")!!.readText()).jsonObject

    @Test
    fun fixtureMatchesBothDirections() {
        val f = fixture()
        val context = f.obj("context")
        val keys = Wire.derive(f.text("secret"), context)
        assertEquals(f.obj("keys").text("c2h"), Wire.encode(keys.first))
        assertEquals(f.obj("keys").text("h2c"), Wire.encode(keys.second))
        for (direction in listOf("c2h", "h2c")) {
            val v = f.obj("vectors").obj(direction)
            assertEquals(v.text("aad"), Wire.aad(context, direction, 0).toString(Charsets.UTF_8))
            val sender = SecureChannel(f.text("secret"), context, direction == "c2h")
            val receiver = SecureChannel(f.text("secret"), context, direction != "c2h")
            val plain = Wire.json.parseToJsonElement(v.text("plaintext")).jsonObject
            val encrypted = sender.seal(plain)
            assertEquals(v.text("ciphertext"), encrypted.text("ciphertext"))
            assertEquals(plain, receiver.open(encrypted))
            assertThrows(Exception::class.java) { receiver.open(encrypted) }
            assertThrows(Exception::class.java) { receiver.seal(plain) }
        }
    }

    @Test
    fun qrAndCanonicalEncoding() {
        val qr = fixture().obj("qr")
        assertEquals(qr.obj("value"), Wire.pairing(qr.text("text")))
        val hostClock = qr.long("now") + 35000
        val skewedValue =
            JsonObject(
                qr.obj("value") +
                    ("expiresAt" to JsonPrimitive(hostClock + 120000)),
            )
        val skewedQr = "pi-remote://pair#" + Wire.encode(skewedValue.toString().toByteArray())
        assertEquals(skewedValue, Wire.pairing(skewedQr))
        assertThrows(Exception::class.java) {
            Wire.pairing("pi-remote://pair#" + Wire.encode(JsonObject(skewedValue + ("secret" to JsonPrimitive("bad"))).toString().toByteArray()))
        }
        assertThrows(Exception::class.java) {
            Wire.pairing("pi-remote://pair#" + Wire.encode(JsonObject(skewedValue + ("expiresAt" to JsonPrimitive("later"))).toString().toByteArray()))
        }
        assertThrows(Exception::class.java) { Wire.decode("AB", 1) }
        assertThrows(Exception::class.java) { Wire.decode("AA==", 1) }
        assertThrows(Exception::class.java) { Wire.nonce(0x100000000) }
    }

    @Test
    fun tamperingAndWrongContextCloseChannel() {
        val f = fixture()
        val c = f.obj("context")
        val sender = SecureChannel(f.text("secret"), c)
        val receiver = SecureChannel(f.text("secret"), c, false)
        val frame = sender.seal(Wire.objectOf("type" to "device.authenticate"))
        val bytes = Wire.decode(frame.text("ciphertext"))
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        val bad = JsonObject(frame + ("ciphertext" to JsonPrimitive(Wire.encode(bytes))))
        assertThrows(Exception::class.java) { receiver.open(bad) }
        assertThrows(Exception::class.java) { receiver.open(frame) }
        val fresh =
            SecureChannel(
                f.text("secret"),
                JsonObject(c + ("serverNonce" to JsonPrimitive(Wire.random(32)))),
                false,
            )
        assertThrows(Exception::class.java) { fresh.open(frame) }
    }
}
