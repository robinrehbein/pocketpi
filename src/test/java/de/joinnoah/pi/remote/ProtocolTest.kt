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

    @Test
    fun onlyTheCanonicalEncodingDecodes() {
        val alphabet = ('A'..'Z') + ('a'..'z') + ('0'..'9') + '-' + '_'
        val lenient = java.util.Base64.getUrlDecoder()
        // Every last character after every partial group: accepted exactly when re-encoding the
        // leniently decoded bytes gives the same text back, the check the padding-bit rule replaces.
        for (prefix in listOf("", "QUJD", "_-9z")) for (tail in listOf("A", "AA", "zz")) for (last in alphabet) {
            val value = prefix + tail + last
            if (value.length % 4 == 1) continue
            val canonical = Wire.encode(lenient.decode(value)) == value
            val accepted = runCatching { Wire.decode(value) }.isSuccess
            assertEquals(value, canonical, accepted)
        }
        assertArrayEquals(byteArrayOf(1), Wire.decode("AQ", 1))
        assertThrows(Exception::class.java) { Wire.decode("AR", 1) }
        assertThrows(Exception::class.java) { Wire.decode("AAB", 2) }
        assertArrayEquals(byteArrayOf(0, 1), Wire.decode("AAE", 2))
        val random = java.util.Random(3)
        for (size in 0..40) {
            val bytes = ByteArray(size).also(random::nextBytes)
            assertArrayEquals(bytes, Wire.decode(Wire.encode(bytes), size))
        }
    }

    @Test
    fun theSizeBoundCountsUtf8BytesWithoutEncoding() {
        val random = java.util.Random(5)
        val samples = listOf("a", "\u00e4", "\u20ac", "\ud83d\ude00", "\ud83d", "\ude00")
        repeat(500) {
            val text = buildString { repeat(random.nextInt(40)) { append(samples[random.nextInt(samples.size)]) } }
            for (limit in listOf(0, 10, 30, 60, 120)) {
                assertEquals(text, text.toByteArray().size <= limit, Wire.fitsUtf8(text, limit))
            }
        }
        // Longer than the limit in UTF-16 units: rejected by its length alone.
        val oversized = Wire.objectOf("text" to "x".repeat(Wire.MAX_FRAME)).toString()
        assertThrows(IllegalArgumentException::class.java) { Wire.parse(oversized) }
        // Within the length, but over the limit in bytes.
        val wide = Wire.objectOf("text" to "\u20ac".repeat(Wire.MAX_FRAME / 3)).toString()
        assertTrue(wide.length <= Wire.MAX_FRAME)
        assertThrows(IllegalArgumentException::class.java) { Wire.parse(wide) }
        // Up to three bytes a unit fits; the bound only counts when it might not.
        val narrow = Wire.objectOf("text" to "\u00e4".repeat(Wire.MAX_FRAME / 2 - 20)).toString()
        assertTrue(narrow.length * 3 > Wire.MAX_FRAME)
        assertEquals("\u00e4".repeat(Wire.MAX_FRAME / 2 - 20), Wire.parse(narrow).text("text"))
    }
}
