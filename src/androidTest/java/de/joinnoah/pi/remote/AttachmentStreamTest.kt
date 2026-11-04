package de.joinnoah.pi.remote

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.InputStream
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Nothing here needs a newer API than the app's minSdk, so every emulator level runs it. */
class AttachmentStreamTest {
    private fun isolated(block: (Context, AttachmentStore) -> Unit) {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val directory =
            File(application.cacheDir, "attachment-stream-${UUID.randomUUID()}").apply { mkdirs() }
        val alias = directory.name
        val context =
            object : ContextWrapper(application) {
                override fun getNoBackupFilesDir() = directory
            }
        try {
            block(context, AttachmentStore(context, alias))
        } finally {
            directory.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(alias)
            }
        }
    }

    private fun attachment(id: String, bytes: ByteArray) =
        LocalAttachment(
            id,
            "notes.txt",
            "file",
            "text/plain",
            bytes.size.toLong(),
            AttachmentImportRules.sha256(bytes),
        )

    @Test
    fun anAttachmentWrittenByTheLegacyFormatStillDecrypts() = isolated { context, store ->
        val id = "0123456789abcdefghijkl"
        val bytes = "written before the streaming format".toByteArray()
        // The same file the previous version wrote: a version byte, then the whole payload.
        EncryptedFileStore(context, "attachment-$id.enc", context.noBackupFilesDir.name)
            .write(byteArrayOf(1) + bytes)
        val legacy = attachment(id, bytes)
        assertArrayEquals(bytes, store.read(legacy))
        assertArrayEquals(bytes, store.open(legacy).use { it.readBytes() })
        assertFalse(File(context.noBackupFilesDir, "attachment-$id.v2").exists())
    }

    @Test
    fun aNewAttachmentIsWrittenAsV2AndRoundTrips() = isolated { context, store ->
        val id = "abcdefghijkl0123456789"
        val bytes = ByteArray(150 * 1024) { (it % 251).toByte() }
        val stored = attachment(id, bytes)
        store.write(stored, bytes)
        assertTrue(File(context.noBackupFilesDir, "attachment-$id.v2").exists())
        assertFalse(File(context.noBackupFilesDir, "attachment-$id.enc").exists())
        assertArrayEquals(bytes, store.read(stored))
        store.cleanup(setOf(id))
        assertTrue(File(context.noBackupFilesDir, "attachment-$id.v2").exists())
        store.cleanup(emptySet())
        assertFalse(File(context.noBackupFilesDir, "attachment-$id.v2").exists())
    }

    @Test
    fun aTwentyMegabyteAttachmentStaysWithinTheHeapBudgetOnWriteAndRead() =
        isolated { _, store ->
            val id = "ZYXWVUTSRQPONMLKJIHGFE"
            val size = AttachmentImportRules.FILE_BYTES.toLong()
            val digest = AttachmentImportRules.digest(Pattern(size))
            val stored =
                LocalAttachment(
                    id,
                    "big.bin",
                    "file",
                    "application/octet-stream",
                    digest.size,
                    digest.sha256,
                )

            val writeBaseline = liveHeap()
            val writer = Pattern(size)
            store.write(stored, writer)
            assertTrue(
                "write grew the heap by ${writer.peakHeap - writeBaseline} bytes",
                writer.peakHeap - writeBaseline < HEAP_BUDGET,
            )

            val readBaseline = liveHeap()
            var peak = readBaseline
            store.open(stored).use { input ->
                val buffer = ByteArray(64 * 1024)
                var reads = 0
                while (input.read(buffer) >= 0) {
                    if (++reads % SAMPLE_READS == 0) peak = maxOf(peak, liveHeap())
                }
            }
            assertTrue(
                "read grew the heap by ${peak - readBaseline} bytes",
                peak - readBaseline < HEAP_BUDGET,
            )
        }

    /** [size] bytes of a fixed pattern, which records the live heap as it is read. */
    private class Pattern(private val size: Long) : InputStream() {
        private var position = 0L
        private var sampled = 0L
        var peakHeap = 0L
            private set

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= size) return -1
            val count = minOf(length.toLong(), size - position).toInt()
            for (index in 0 until count) {
                buffer[offset + index] = ((position + index) * 31 % 251).toByte()
            }
            position += count
            if (position / SAMPLE_BYTES > sampled) {
                sampled = position / SAMPLE_BYTES
                peakHeap = maxOf(peakHeap, liveHeap())
            }
            return count
        }
    }

    private companion object {
        /** A 20 MB attachment is streamed, so the heap grows by a buffer, not by a copy of it. */
        const val HEAP_BUDGET = 8L * 1024 * 1024

        /** The heap is sampled once per this many bytes, and once per this many reads on the read side. */
        const val SAMPLE_BYTES = 1L shl 20
        const val SAMPLE_READS = 16

        /**
         * The heap in use after a full collection. Without the collection, the ciphers and buffers
         * of finished segments still count, and a correct write would look like a large one.
         */
        fun liveHeap(): Long {
            System.gc()
            return Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        }
    }
}
