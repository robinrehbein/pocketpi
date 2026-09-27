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

    fun decode(value: String, size: Int? = null): ByteArray {
        require(
            value.length <= MAX_FRAME &&
                Regex("[A-Za-z0-9_-]*").matches(value) &&
                value.length % 4 != 1
        )
        val bytes = Base64.getUrlDecoder().decode(value)
        require(encode(bytes) == value && (size == null || bytes.size == size))
        return bytes
    }

    fun random(size: Int = 16): String =
        encode(ByteArray(size).also { SecureRandom().nextBytes(it) })

    fun parse(text: String, limit: Int = MAX_FRAME): JsonObject {
        require(text.toByteArray().size <= limit)
        return json.parseToJsonElement(text).jsonObject
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

class SecureChannel(secret: String, private val context: JsonObject, client: Boolean = true) {
    private val raw = Wire.derive(secret, context)
    private val sendKey = SecretKeySpec(if (client) raw.first else raw.second, "AES")
    private val receiveKey = SecretKeySpec(if (client) raw.second else raw.first, "AES")
    private val sendDirection = if (client) "c2h" else "h2c"
    private val receiveDirection = if (client) "h2c" else "c2h"
    private var sendSequence = 0L
    private var receiveSequence = 0L
    private var closed = false

    init {
        raw.first.fill(0)
        raw.second.fill(0)
    }

    @Synchronized
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

    @Synchronized
    fun seal(payload: JsonObject): JsonObject = guarded {
        val bytes = payload.toString().toByteArray()
        require(bytes.size <= Wire.MAX_PLAINTEXT)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, sendKey, GCMParameterSpec(128, Wire.nonce(sendSequence)))
        cipher.updateAAD(Wire.aad(context, sendDirection, sendSequence))
        Wire.objectOf(
            "v" to 1,
            "type" to "encrypted",
            "connectionId" to context.text("connectionId"),
            "seq" to sendSequence++,
            "ciphertext" to Wire.encode(cipher.doFinal(bytes)),
        )
    }

    @Synchronized
    fun open(frame: JsonObject): JsonObject = guarded {
        Wire.keys(frame, setOf("v", "type", "connectionId", "seq", "ciphertext"))
        require(
            frame.long("v") == 1L &&
                frame.text("type") == "encrypted" &&
                frame.text("connectionId") == context.text("connectionId") &&
                frame.long("seq") == receiveSequence
        )
        val bytes = Wire.decode(frame.text("ciphertext"))
        require(bytes.size in 16..Wire.MAX_PLAINTEXT + 16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            receiveKey,
            GCMParameterSpec(128, Wire.nonce(receiveSequence)),
        )
        cipher.updateAAD(Wire.aad(context, receiveDirection, receiveSequence))
        val payload = Wire.parse(Wire.utf8(cipher.doFinal(bytes)), Wire.MAX_PLAINTEXT)
        receiveSequence++
        payload
    }
}
