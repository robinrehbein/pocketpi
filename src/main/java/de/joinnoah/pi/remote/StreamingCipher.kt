package de.joinnoah.pi.remote

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The `.v2` attachment format: a header, then the payload in segments of at most [SEGMENT_BYTES]
 * plaintext bytes, each sealed with AES-256-GCM under a random data key. Writing and reading hold
 * one segment at a time, so a large attachment costs a bounded amount of heap.
 *
 * The header is the magic, the segment size, a random 7-byte nonce prefix and the data key wrapped
 * by the Keystore key ([EncryptedFileStore.wrapKey]). Every segment's nonce is the prefix, its
 * index and a last-segment flag; the header is its associated data. Only the final segment carries
 * the flag, so a reordered, dropped, appended or altered segment fails authentication, and so does
 * a file whose header was changed.
 */
internal object StreamingCipher {
    /** Plaintext bytes per segment. The last segment of a file may be shorter, or empty. */
    internal const val SEGMENT_BYTES = 64 * 1024

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_BYTES = 32
    private const val TAG_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val PREFIX_BYTES = 7

    /** An IV, then the wrapped data key and its tag, as the Keystore wrap writes them. */
    private const val WRAPPED_BYTES = 12 + KEY_BYTES + TAG_BYTES
    private val MAGIC = "PPA2".toByteArray(Charsets.US_ASCII)

    /** Magic, segment size, nonce prefix, wrapped data key. */
    internal const val HEADER_BYTES = 4 + 4 + PREFIX_BYTES + WRAPPED_BYTES

    private val random = SecureRandom()

    /** A file that fails the framing or authentication checks. It cannot be read again. */
    class CorruptFileException(message: String, cause: Throwable? = null) :
        IOException(message, cause)

    /** What [writeTo] stored: the plaintext size and its SHA-256 as lowercase hex. */
    class Written(val size: Long, val sha256: String)

    /**
     * Reads all of [input] into [output] in the `.v2` format. [wrap] seals the new data key, and
     * writing fails with an IllegalArgumentException once [input] holds more than [limit] bytes.
     */
    fun writeTo(
        input: InputStream,
        output: OutputStream,
        limit: Long,
        wrap: (ByteArray) -> ByteArray,
    ): Written {
        val dataKey = KeyGenerator.getInstance("AES").apply { init(KEY_BYTES * 8) }.generateKey()
        val prefix = ByteArray(PREFIX_BYTES).also { random.nextBytes(it) }
        val wrapped = wrap(dataKey.encoded)
        require(wrapped.size == WRAPPED_BYTES) { "Unexpected wrapped key size" }
        val header =
            ByteBuffer.allocate(HEADER_BYTES)
                .put(MAGIC)
                .putInt(SEGMENT_BYTES)
                .put(prefix)
                .put(wrapped)
                .array()
        output.write(header)
        // One segment is read ahead, so the last one is known before it is sealed.
        val source = PushbackInputStream(input, 1)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(SEGMENT_BYTES)
        var size = 0L
        var index = 0
        while (true) {
            val count = AttachmentImportRules.readInto(source, buffer)
            val last = isEnd(source)
            size += count
            require(size <= limit) { "Attachment exceeds the file limit" }
            digest.update(buffer, 0, count)
            val sealed =
                cipher(Cipher.ENCRYPT_MODE, dataKey, nonce(prefix, index, last), header)
                    .doFinal(buffer, 0, count)
            output.write(sealed)
            if (last) break
            index++
        }
        return Written(size, AttachmentImportRules.hex(digest.digest()))
    }

    /**
     * Opens a `.v2` payload read from [source] and returns its plaintext. [unwrap] opens the data
     * key; a failure there is passed on as it is, so the caller can tell a lost key from a file.
     * Reading the plaintext throws [CorruptFileException] at the first segment that fails.
     */
    fun readFrom(source: InputStream, unwrap: (ByteArray) -> ByteArray): InputStream {
        val header = ByteArray(HEADER_BYTES)
        if (AttachmentImportRules.readInto(source, header) != HEADER_BYTES) {
            throw CorruptFileException("Attachment header is truncated")
        }
        val fields = ByteBuffer.wrap(header)
        val magic = ByteArray(MAGIC.size).also { fields.get(it) }
        val segmentBytes = fields.getInt()
        val prefix = ByteArray(PREFIX_BYTES).also { fields.get(it) }
        val wrapped = ByteArray(WRAPPED_BYTES).also { fields.get(it) }
        if (!magic.contentEquals(MAGIC) || segmentBytes != SEGMENT_BYTES) {
            throw CorruptFileException("Attachment header is not recognized")
        }
        val key = unwrap(wrapped)
        if (key.size != KEY_BYTES) throw CorruptFileException("Attachment key is malformed")
        return SegmentReader(
            PushbackInputStream(source, 1),
            SecretKeySpec(key, "AES"),
            prefix,
            header,
        )
    }

    /** True at the end of [source]; otherwise the next byte stays in it. */
    private fun isEnd(source: PushbackInputStream): Boolean {
        val next = source.read()
        if (next < 0) return true
        source.unread(next)
        return false
    }

    private fun cipher(mode: Int, key: SecretKey, nonce: ByteArray, header: ByteArray): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            init(mode, key, GCMParameterSpec(TAG_BYTES * 8, nonce))
            updateAAD(header)
        }

    private fun nonce(prefix: ByteArray, index: Int, last: Boolean): ByteArray =
        ByteBuffer.allocate(NONCE_BYTES).put(prefix).putInt(index).put(if (last) 1 else 0).array()

    private class SegmentReader(
        private val source: PushbackInputStream,
        private val key: SecretKey,
        private val prefix: ByteArray,
        private val header: ByteArray,
    ) : InputStream() {
        private val sealed = ByteArray(SEGMENT_BYTES + TAG_BYTES)
        private var plain = ByteArray(0)
        private var position = 0
        private var index = 0
        private var finished = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            while (position == plain.size) {
                if (finished) return -1
                nextSegment()
            }
            val count = minOf(length, plain.size - position)
            plain.copyInto(target, offset, position, position + count)
            position += count
            return count
        }

        private fun nextSegment() {
            val count = AttachmentImportRules.readInto(source, sealed)
            val last = isEnd(source)
            // A segment that is not last is always full, and the last one holds at least its tag.
            if (count < TAG_BYTES || (!last && count != sealed.size)) {
                throw CorruptFileException("Attachment segment $index is truncated")
            }
            plain =
                try {
                    cipher(Cipher.DECRYPT_MODE, key, nonce(prefix, index, last), header)
                        .doFinal(sealed, 0, count)
                } catch (error: AEADBadTagException) {
                    throw CorruptFileException("Attachment segment $index failed to verify", error)
                }
            index++
            position = 0
            finished = last
        }

        override fun close() = source.close()
    }
}
