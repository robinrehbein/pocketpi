package de.joinnoah.pi.remote

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.*

fun JsonObject.text(key: String): String =
    getValue(key).jsonPrimitive.let {
        require(it.isString)
        it.content
    }

fun JsonObject.long(key: String): Long =
    getValue(key).jsonPrimitive.let {
        require(!it.isString)
        it.long
    }

fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

fun JsonObject.array(key: String): List<JsonObject> = getValue(key).jsonArray.map { it.jsonObject }

fun JsonObject.flag(key: String): Boolean =
    getValue(key).jsonPrimitive.let {
        require(!it.isString)
        it.boolean
    }

fun JsonObject.optionalText(key: String): String? =
    get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

object Wire {
    const val MAX_FRAME = 512 * 1024
    const val MAX_PLAINTEXT = 256 * 1024
    val json = Json {
        isLenient = false
        ignoreUnknownKeys = false
    }

    fun objectOf(vararg values: Pair<String, Any?>): JsonObject =
        JsonObject(
            values.associate { (key, value) ->
                key to
                    when (value) {
                        null -> JsonNull
                        is JsonElement -> value
                        is String -> JsonPrimitive(value)
                        is Boolean -> JsonPrimitive(value)
                        is Number -> JsonPrimitive(value)
                        else -> error("Unsupported JSON value")
                    }
            }
        )

    fun encode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** The unpadded base64url alphabet; compiled once, since every received frame is matched. */
    private val BASE64URL = Regex("[A-Za-z0-9_-]*")

    fun decode(value: String, size: Int? = null): ByteArray {
        require(
            value.length <= MAX_FRAME &&
                BASE64URL.matches(value) &&
                value.length % 4 != 1 &&
                paddingBitsClear(value)
        )
        val bytes = Base64.getUrlDecoder().decode(value)
        require(size == null || bytes.size == size)
        return bytes
    }

    /**
     * The canonical encoding sets none of the bits its last character carries beyond the data:
     * four of six after two characters of a group, two after three. Java's decoder ignores them,
     * so without this check several strings would decode to the same bytes.
     */
    private fun paddingBitsClear(value: String): Boolean {
        val unused =
            when (value.length % 4) {
                2 -> 0x0f
                3 -> 0x03
                else -> return true
            }
        return (base64UrlValue(value.last()) and unused) == 0
    }

    private fun base64UrlValue(char: Char): Int =
        when (char) {
            in 'A'..'Z' -> char - 'A'
            in 'a'..'z' -> char - 'a' + 26
            in '0'..'9' -> char - '0' + 52
            '-' -> 62
            '_' -> 63
            else -> throw IllegalArgumentException("Not base64url")
        }

    fun random(size: Int = 16): String =
        encode(ByteArray(size).also { SecureRandom().nextBytes(it) })

    fun parse(text: String, limit: Int = MAX_FRAME): JsonObject {
        require(fitsUtf8(text, limit))
        return json.parseToJsonElement(text).jsonObject
    }

    /**
     * Whether [text] takes at most [limit] bytes as UTF-8, without encoding it: a UTF-16 unit
     * takes one to three bytes (a surrogate pair four for two units), so the length alone decides
     * most frames, and only a frame between those bounds is counted.
     */
    internal fun fitsUtf8(text: String, limit: Int): Boolean {
        if (text.length > limit) return false
        if (text.length.toLong() * 3 <= limit) return true
        var size = 0L
        var index = 0
        while (index < text.length) {
            val char = text[index]
            size +=
                when {
                    char.code < 0x80 -> 1
                    char.code < 0x800 -> 2
                    char.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate() -> {
                        index++
                        4
                    }
                    // String.toByteArray() writes '?' for an unpaired surrogate.
                    char.isSurrogate() -> 1
                    else -> 3
                }
            if (size > limit) return false
            index++
        }
        return true
    }

    fun keys(value: JsonObject, required: Set<String>, optional: Set<String> = emptySet()) {
        require(value.keys.containsAll(required) && (value.keys - required - optional).isEmpty())
    }

    fun pairing(text: String): JsonObject {
        require(text.startsWith("pi-remote://pair#") && text.length <= 8192)
        val value = parse(utf8(decode(text.substringAfter('#'))), 6144)
        keys(value, setOf("v", "relay", "routeId", "pairId", "secret", "expiresAt"))
        require(value.long("v") == 1L)
        val relay = URI(value.text("relay"))
        require(
            relay.scheme == "https" &&
                !relay.host.isNullOrBlank() &&
                relay.userInfo == null &&
                relay.rawPath.isNullOrEmpty() &&
                relay.query == null &&
                relay.fragment == null
        )
        decode(value.text("routeId"), 16)
        decode(value.text("pairId"), 16)
        decode(value.text("secret"), 32)
        require(value.long("expiresAt") > 0)
        return value
    }

    fun context(value: JsonObject) {
        keys(
            value,
            setOf(
                "v",
                "type",
                "mode",
                "routeId",
                "keyId",
                "connectionId",
                "clientNonce",
                "serverNonce",
            ),
        )
        require(
            value.long("v") == 1L &&
                value.text("type") == "hello" &&
                value.text("mode") in listOf("pair", "device")
        )
        listOf("routeId", "keyId", "connectionId").forEach { decode(value.text(it), 16) }
        listOf("clientNonce", "serverNonce").forEach { decode(value.text(it), 32) }
    }

    fun nonce(seq: Long): ByteArray {
        require(seq in 0..0xffffffffL)
        return ByteBuffer.allocate(12).putInt(0).putLong(seq).array()
    }

    private fun jsonArray(vararg parts: Any): ByteArray =
        JsonArray(
                parts.map { if (it is Number) JsonPrimitive(it) else JsonPrimitive(it.toString()) }
            )
            .toString()
            .toByteArray()

    fun aad(context: JsonObject, direction: String, seq: Long): ByteArray {
        context(context)
        nonce(seq)
        require(direction in listOf("c2h", "h2c"))
        return jsonArray(
            "pi-remote",
            1,
            context.text("mode"),
            context.text("routeId"),
            context.text("keyId"),
            context.text("connectionId"),
            context.text("clientNonce"),
            context.text("serverNonce"),
            direction,
            seq,
        )
    }

    /**
     * The bytes [aad] produces for [context] and [direction] up to the sequence number: the JSON
     * array without its last element and closing bracket, ending in the comma before it. Validates
     * the context once; [aadFrom] then completes it per frame.
     */
    fun aadPrefix(context: JsonObject, direction: String): ByteArray {
        context(context)
        require(direction in listOf("c2h", "h2c"))
        val full = aad(context, direction, 0)
        // `aad` ends in `,0]`; the prefix keeps the comma.
        return full.copyOf(full.size - 2)
    }

    /** [aad]'s bytes for one frame, from a [prefix] that [aadPrefix] built. */
    fun aadFrom(prefix: ByteArray, seq: Long): ByteArray {
        nonce(seq)
        return prefix + (seq.toString() + "]").toByteArray()
    }

    fun derive(secret: String, context: JsonObject): Pair<ByteArray, ByteArray> {
        context(context)
        fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(data)
            }
        val info =
            jsonArray(
                "pi-remote",
                1,
                context.text("mode"),
                context.text("routeId"),
                context.text("keyId"),
                context.text("connectionId"),
            )
        val ikm = decode(secret, 32)
        val prk =
            hmac(
                decode(context.text("clientNonce"), 32) + decode(context.text("serverNonce"), 32),
                ikm,
            )
        ikm.fill(0)
        val first = hmac(prk, info + byteArrayOf(1))
        val second = hmac(prk, first + info + byteArrayOf(2))
        prk.fill(0)
        return first to second
    }

    fun utf8(bytes: ByteArray): String =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
}

/**
 * One encrypted connection. Sending and receiving keep separate state and separate locks, so a
 * [seal] on the sender never waits for an [open] that is decrypting and parsing a large frame on
 * the reader thread. Each direction keeps one [Cipher], initialised with the next sequence's IV
 * per frame, and its AAD prefix, built once from the context. Any failure in either direction
 * closes the channel for both.
 */
class SecureChannel(secret: String, private val context: JsonObject, client: Boolean = true) {
    private val raw = Wire.derive(secret, context)
    private val sendKey = SecretKeySpec(if (client) raw.first else raw.second, "AES")
    private val receiveKey = SecretKeySpec(if (client) raw.second else raw.first, "AES")
    private val connectionId = context.text("connectionId")
    private val sendAad = Wire.aadPrefix(context, if (client) "c2h" else "h2c")
    private val receiveAad = Wire.aadPrefix(context, if (client) "h2c" else "c2h")
    private val sendLock = Any()
    private val receiveLock = Any()
    private val sendCipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val receiveCipher = Cipher.getInstance("AES/GCM/NoPadding")
    private var sendSequence = 0L
    private var receiveSequence = 0L
    @Volatile private var closed = false

    /** True once [close] ran or a frame in either direction failed. */
    val isClosed: Boolean
        get() = closed

    init {
        raw.first.fill(0)
        raw.second.fill(0)
    }

    fun close() {
        closed = true
    }

    private fun <T> guarded(block: () -> T): T {
        check(!closed)
        return try {
            block()
        } catch (e: Exception) {
            closed = true
            throw e
        }
    }

    fun seal(payload: JsonObject): JsonObject = synchronized(sendLock) { sealLocked(payload) }

    private fun sealLocked(payload: JsonObject): JsonObject = guarded {
        val bytes = payload.toString().toByteArray()
        require(bytes.size <= Wire.MAX_PLAINTEXT)
        // A fresh IV per sequence, so re-initialising the same Cipher never reuses a nonce.
        sendCipher.init(Cipher.ENCRYPT_MODE, sendKey, GCMParameterSpec(128, Wire.nonce(sendSequence)))
        sendCipher.updateAAD(Wire.aadFrom(sendAad, sendSequence))
        Wire.objectOf(
            "v" to 1,
            "type" to "encrypted",
            "connectionId" to connectionId,
            "seq" to sendSequence++,
            "ciphertext" to Wire.encode(sendCipher.doFinal(bytes)),
        )
    }

    fun open(frame: JsonObject): JsonObject = synchronized(receiveLock) { openLocked(frame) }

    private fun openLocked(frame: JsonObject): JsonObject = guarded {
        val started = System.nanoTime()
        Wire.keys(frame, setOf("v", "type", "connectionId", "seq", "ciphertext"))
        require(
            frame.long("v") == 1L &&
                frame.text("type") == "encrypted" &&
                frame.text("connectionId") == connectionId &&
                frame.long("seq") == receiveSequence
        )
        val bytes = Wire.decode(frame.text("ciphertext"))
        require(bytes.size in 16..Wire.MAX_PLAINTEXT + 16)
        receiveCipher.init(
            Cipher.DECRYPT_MODE,
            receiveKey,
            GCMParameterSpec(128, Wire.nonce(receiveSequence)),
        )
        receiveCipher.updateAAD(Wire.aadFrom(receiveAad, receiveSequence))
        // The ciphertext's size already bounds the plaintext to MAX_PLAINTEXT bytes.
        val payload = Wire.json.parseToJsonElement(Wire.utf8(receiveCipher.doFinal(bytes))).jsonObject
        receiveSequence++
        // The outer frame was parsed by the caller; this times decrypting and parsing the payload.
        Diagnostics.frame(System.nanoTime() - started, bytes.size - 16)
        payload
    }
}
