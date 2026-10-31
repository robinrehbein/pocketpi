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
    fun theReusedCipherSealsEveryFrameAsAFreshOneWould() {
        val f = fixture()
        val context = f.obj("context")
        val keys = Wire.derive(f.text("secret"), context)
        for (direction in listOf("c2h", "h2c")) {
            val vector = f.obj("vectors").obj(direction)
            val key = javax.crypto.spec.SecretKeySpec(if (direction == "c2h") keys.first else keys.second, "AES")
            val sender = SecureChannel(f.text("secret"), context, direction == "c2h")
            val receiver = SecureChannel(f.text("secret"), context, direction != "c2h")
            val plain = Wire.json.parseToJsonElement(vector.text("plaintext")).jsonObject
            for (seq in 0L until 6) {
                val payload = if (seq == 0L) plain else Wire.objectOf("type" to "event", "n" to seq)
                val sealed = sender.seal(payload)
                // The reference: a new Cipher per frame with Wire.aad, as before the reuse.
                val fresh = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                fresh.init(javax.crypto.Cipher.ENCRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, Wire.nonce(seq)))
                fresh.updateAAD(Wire.aad(context, direction, seq))
                val expected = Wire.encode(fresh.doFinal(payload.toString().toByteArray()))
                assertEquals(seq, sealed.long("seq"))
                assertEquals(expected, sealed.text("ciphertext"))
                if (seq == 0L) assertEquals(vector.text("ciphertext"), sealed.text("ciphertext"))
                assertEquals(payload, receiver.open(sealed))
            }
        }
    }

    @Test
    fun theAadPrefixCompletesToWireAad() {
        val context = fixture().obj("context")
        for (direction in listOf("c2h", "h2c")) {
            val prefix = Wire.aadPrefix(context, direction)
            for (seq in listOf(0L, 1L, 9L, 10L, 255L, 65_536L, 0xffffffffL)) {
                assertArrayEquals("$direction $seq", Wire.aad(context, direction, seq), Wire.aadFrom(prefix, seq))
            }
            assertThrows(Exception::class.java) { Wire.aadFrom(prefix, 0x100000000) }
            assertThrows(Exception::class.java) { Wire.aadFrom(prefix, -1) }
        }
        assertThrows(Exception::class.java) { Wire.aadPrefix(context, "h2h") }
        assertThrows(Exception::class.java) {
            Wire.aadPrefix(JsonObject(context + ("serverNonce" to JsonPrimitive("short"))), "c2h")
        }
    }

    @Test
    fun sealAndOpenInterleaveAcrossThreads() {
        val f = fixture()
        val c = f.obj("context")
        val host = SecureChannel(f.text("secret"), c, false)
        val device = SecureChannel(f.text("secret"), c)
        val perThread = 50
        val sealers = 4
        val fromHost = (0 until sealers * perThread).map { host.seal(Wire.objectOf("type" to "event", "n" to it)) }
        val executor = java.util.concurrent.Executors.newFixedThreadPool(sealers + 1)
        try {
            val start = java.util.concurrent.CountDownLatch(1)
            // Several threads seal on the device while another opens the host's frames on it.
            val sealing =
                (0 until sealers).map { thread ->
                    executor.submit<List<JsonObject>> {
                        start.await()
                        (0 until perThread).map { device.seal(Wire.objectOf("type" to "steer", "t" to thread, "i" to it)) }
                    }
                }
            val opening =
                executor.submit<List<JsonObject>> {
                    start.await()
                    fromHost.map { device.open(it) }
                }
            start.countDown()
            val sealed = sealing.flatMap { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
            val opened = opening.get(10, java.util.concurrent.TimeUnit.SECONDS)

            assertEquals((0 until sealers * perThread).toList(), opened.map { it.long("n").toInt() })
            // Every sequence number once; opened in sequence order, every frame decrypts.
            val ordered = sealed.sortedBy { it.long("seq") }
            assertEquals((0L until sealed.size).toList(), ordered.map { it.long("seq") })
            val received = ordered.map { host.open(it) }
            for (thread in 0 until sealers) {
                assertEquals(
                    (0 until perThread).toList(),
                    received.filter { it.long("t").toInt() == thread }.map { it.long("i").toInt() },
                )
            }
            assertFalse(device.isClosed)
            assertFalse(host.isClosed)
        } finally {
            executor.shutdownNow()
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
    fun sealDoesNotWaitForAnOpenInProgress() {
        val f = fixture()
        val c = f.obj("context")
        val host = SecureChannel(f.text("secret"), c, false)
        val device = SecureChannel(f.text("secret"), c)
        val frame = host.seal(Wire.objectOf("type" to "event"))
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        // A frame whose first read inside open() parks until the test releases it.
        val held =
            JsonObject(
                object : Map<String, JsonElement> by frame {
                    override val keys: Set<String>
                        get() {
                            entered.countDown()
                            release.await()
                            return frame.keys
                        }
                }
            )
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val opening = executor.submit<JsonObject> { device.open(held) }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val sealing = executor.submit<JsonObject> { device.seal(Wire.objectOf("type" to "steer")) }
            // Returns while open() still holds the receive side.
            val sealed = sealing.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertFalse(opening.isDone)
            release.countDown()
            assertEquals("event", opening.get(5, java.util.concurrent.TimeUnit.SECONDS).text("type"))
            assertEquals("steer", host.open(sealed).text("type"))
            assertFalse(device.isClosed)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun aFailedOpenClosesTheSendSideToo() {
        val f = fixture()
        val c = f.obj("context")
        val host = SecureChannel(f.text("secret"), c, false)
        val device = SecureChannel(f.text("secret"), c)
        val frame = host.seal(Wire.objectOf("type" to "event"))
        assertThrows(Exception::class.java) { device.open(JsonObject(frame + ("seq" to JsonPrimitive(5)))) }
        assertTrue(device.isClosed)
        assertThrows(IllegalStateException::class.java) { device.seal(Wire.objectOf("type" to "steer")) }
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

    @Test
    fun theStreamAndResumeFixturePayloadsSealAndOpenWithinTheFrameLimit() {
        val f = fixture()
        val context = f.obj("context")
        for (name in listOf("stream-v1.json", "resume-v1.json")) {
            val document = Wire.json.parseToJsonElement(javaClass.getResource("/$name")!!.readText()).jsonObject
            assertEquals(name, 1L, document.long("version"))
            val sender = SecureChannel(f.text("secret"), context, true)
            val receiver = SecureChannel(f.text("secret"), context, false)
            val payloads = document.array("wireValid").map { it.obj("payload") }
            assertTrue(name, payloads.isNotEmpty())
            for (payload in payloads) {
                val frame = sender.seal(payload)
                assertTrue(frame.toString().length <= Wire.MAX_FRAME)
                assertEquals(payload, receiver.open(frame))
            }
        }
    }
}
