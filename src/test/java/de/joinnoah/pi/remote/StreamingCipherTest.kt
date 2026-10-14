package de.joinnoah.pi.remote

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class StreamingCipherTest {
    private val segment = StreamingCipher.SEGMENT_BYTES
    private val header = StreamingCipher.HEADER_BYTES

    /** Seals data keys the way the Keystore wrap does: an IV, then the GCM ciphertext and tag. */
    private val wrapKey = SecretKeySpec(ByteArray(32) { 7 }, "AES")
    private val wrap: (ByteArray) -> ByteArray = { key ->
        val iv = ByteArray(12) { 3 }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrapKey, GCMParameterSpec(128, iv))
        iv + cipher.doFinal(key)
    }
    private val unwrap: (ByteArray) -> ByteArray = { sealed ->
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrapKey, GCMParameterSpec(128, sealed, 0, 12))
        cipher.doFinal(sealed, 12, sealed.size - 12)
    }

    private fun encode(plain: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        StreamingCipher.writeTo(ByteArrayInputStream(plain), output, Long.MAX_VALUE, wrap)
        return output.toByteArray()
    }

    private fun decode(encoded: ByteArray): ByteArray =
        StreamingCipher.readFrom(ByteArrayInputStream(encoded), unwrap).use { it.readBytes() }

    /** The payload after the header, cut into the segments the writer sealed. */
    private fun segments(encoded: ByteArray): List<ByteArray> {
        val body = encoded.copyOfRange(header, encoded.size)
        val step = segment + 16
        return (body.indices step step).map { body.copyOfRange(it, minOf(it + step, body.size)) }
    }

    private fun join(parts: List<ByteArray>): ByteArray =
        parts.fold(ByteArray(0)) { acc, part -> acc + part }

    private fun plain(size: Int) = ByteArray(size) { (it * 31 % 251).toByte() }

    private fun flipped(bytes: ByteArray, index: Int) =
        bytes.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }

    @Test
    fun everySegmentShapeRoundTripsAndReportsItsSizeAndDigest() {
        for (size in listOf(0, 1, segment - 1, segment, segment + 1, 3 * segment + 5)) {
            val bytes = plain(size)
            val output = ByteArrayOutputStream()
            val written =
                StreamingCipher.writeTo(ByteArrayInputStream(bytes), output, Long.MAX_VALUE, wrap)
            assertEquals(size.toLong(), written.size)
            assertEquals(AttachmentImportRules.sha256(bytes), written.sha256)
            assertArrayEquals("size $size", bytes, decode(output.toByteArray()))
        }
    }

    @Test
    fun aFullSegmentIsNotFollowedByAnEmptyOne() {
        assertEquals(1, segments(encode(plain(segment))).size)
        assertEquals(2, segments(encode(plain(segment + 1))).size)
        assertEquals(1, segments(encode(ByteArray(0))).size)
    }

    @Test
    fun eachFileGetsItsOwnKeyAndNonce() {
        val bytes = plain(100)
        assertFalse(encode(bytes).contentEquals(encode(bytes)))
    }

    @Test
    fun aPlaintextLongerThanTheLimitIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            StreamingCipher.writeTo(ByteArrayInputStream(plain(10)), ByteArrayOutputStream(), 9, wrap)
        }
    }

    @Test
    fun aWrappedKeyOfTheWrongSizeIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            StreamingCipher.writeTo(ByteArrayInputStream(plain(1)), ByteArrayOutputStream(), 1) {
                it.copyOf(59)
            }
        }
    }

    @Test
    fun reorderedSegmentsAreRejected() {
        val encoded = encode(plain(2 * segment + 10))
        val parts = segments(encoded)
        assertEquals(3, parts.size)
        val reordered = join(listOf(parts[1], parts[0], parts[2]))
        assertCorrupt(encoded.copyOf(header) + reordered)
    }

    @Test
    fun aTruncatedFileIsRejected() {
        val encoded = encode(plain(2 * segment + 10))
        assertCorrupt(encoded.copyOf(encoded.size - 5))
        assertCorrupt(encoded.copyOf(header))
        assertCorrupt(encoded.copyOf(header - 1))
    }

    @Test
    fun aDroppedLastSegmentIsRejected() {
        val encoded = encode(plain(2 * segment + 10))
        val parts = segments(encoded)
        // The segment before it is now the last one, but it was sealed as a middle segment.
        assertCorrupt(encoded.copyOf(header) + join(parts.dropLast(1)))
    }

    @Test
    fun aFlippedBitIsRejectedInEverySegment() {
        val encoded = encode(plain(2 * segment + 10))
        val parts = segments(encoded)
        // The first byte of the first segment, a middle byte of the second, and the tag of the last.
        val cases = listOf(0 to 0, 1 to segment / 2, 2 to parts[2].size - 1)
        for ((part, index) in cases) {
            val damaged = parts.toMutableList()
            damaged[part] = flipped(parts[part], index)
            assertCorrupt(encoded.copyOf(header) + join(damaged))
        }
    }

    @Test
    fun aSegmentAppendedAfterTheLastIsRejected() {
        val encoded = encode(plain(2 * segment + 10))
        val parts = segments(encoded)
        assertCorrupt(encoded.copyOf(header) + join(parts + parts.first()))
    }

    @Test
    fun aChangedHeaderIsRejected() {
        val encoded = encode(plain(segment + 1))
        // The magic, the segment size and the nonce prefix are checked or authenticated.
        assertCorrupt(flipped(encoded, 0))
        assertCorrupt(flipped(encoded, 7))
        assertCorrupt(flipped(encoded, 9))
    }

    @Test
    fun aChangedWrappedKeyFailsToUnwrap() {
        val encoded = encode(plain(10))
        assertThrows(AEADBadTagException::class.java) {
            decode(flipped(encoded, header - 1))
        }
    }

    private fun assertCorrupt(file: ByteArray) {
        assertThrows(StreamingCipher.CorruptFileException::class.java) { decode(file) }
    }
}
