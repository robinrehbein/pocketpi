package de.joinnoah.pi.remote

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Attachment files in both formats: new files are `.v2`, files from older versions are `.enc` and
 * only read, and removal and cleanup know both names. The in-memory Keystore stands in for the
 * AndroidKeyStore, as in StoreRecoveryTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AttachmentStoreFormatTest {
    private val application = ApplicationProvider.getApplicationContext<Context>()
    private val directory = File(application.cacheDir, "attachment-format-${UUID.randomUUID()}")
    private val context =
        object : ContextWrapper(application) {
            override fun getNoBackupFilesDir() = directory
        }
    private val alias = "attachment-format-${UUID.randomUUID()}"

    @Before
    fun setUp() {
        FakeAndroidKeyStore.install()
        directory.mkdirs()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
        keystore().deleteEntry(alias)
    }

    private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun file(name: String) = File(directory, name)

    private fun attachment(id: String, bytes: ByteArray) =
        LocalAttachment(
            id,
            "notes.txt",
            "file",
            "text/plain",
            bytes.size.toLong(),
            AttachmentImportRules.sha256(bytes),
        )

    /** Writes an attachment the way the previous version did, as one `.enc` store file. */
    private fun writeLegacy(attachment: LocalAttachment, bytes: ByteArray) {
        EncryptedFileStore(context, "attachment-${attachment.id}.enc", alias)
            .write(byteArrayOf(1) + bytes)
    }

    private val first = "A".repeat(22)
    private val second = "B".repeat(22)

    @Test
    fun newAttachmentsAreWrittenAsV2AndReadBack() {
        val store = AttachmentStore(context, alias)
        val bytes = ByteArray(3 * StreamingCipher.SEGMENT_BYTES + 7) { (it % 253).toByte() }
        val stored = attachment(first, bytes)
        store.write(stored, bytes)
        assertTrue(file("attachment-$first.v2").exists())
        assertFalse(file("attachment-$first.enc").exists())
        assertArrayEquals(bytes, store.read(stored))
        assertArrayEquals(bytes, store.open(stored).use { it.readBytes() })
    }

    @Test
    fun streamedWritesAreCheckedAgainstTheAttachment() {
        val store = AttachmentStore(context, alias)
        val bytes = "expected".toByteArray()
        val stored = attachment(first, bytes)
        assertThrows(IllegalArgumentException::class.java) {
            store.write(stored, ByteArrayInputStream("other!!!".toByteArray()))
        }
        assertFalse(file("attachment-$first.v2").exists())
        assertFalse(file("attachment-$first.v2.new").exists())
        store.write(stored, ByteArrayInputStream(bytes))
        assertArrayEquals(bytes, store.read(stored))
    }

    @Test
    fun aLegacyFileReadsAsBeforeAndIsNeverRewritten() {
        val store = AttachmentStore(context, alias)
        val bytes = "written by the old code".toByteArray()
        val legacy = attachment(first, bytes)
        writeLegacy(legacy, bytes)
        assertArrayEquals(bytes, store.read(legacy))
        assertArrayEquals(bytes, store.open(legacy).use { it.readBytes() })
        assertFalse(file("attachment-$first.v2").exists())
        assertTrue(file("attachment-$first.enc").exists())
    }

    @Test
    fun aLegacyAttachmentWithTheWrongDigestIsRefused() {
        val store = AttachmentStore(context, alias)
        val bytes = "legacy".toByteArray()
        writeLegacy(attachment(first, bytes), bytes)
        assertThrows(IllegalArgumentException::class.java) {
            store.read(attachment(first, "other".toByteArray()))
        }
    }

    @Test
    fun removeDeletesBothFormats() {
        val store = AttachmentStore(context, alias)
        val legacy = "legacy".toByteArray()
        writeLegacy(attachment(first, legacy), legacy)
        val streamed = "streamed".toByteArray()
        store.write(attachment(second, streamed), streamed)
        store.remove(first)
        store.remove(second)
        assertFalse(file("attachment-$first.enc").exists())
        assertFalse(file("attachment-$second.v2").exists())
    }

    @Test
    fun cleanupKeepsReferencedStreamedFilesAndRemovesOrphans() {
        val store = AttachmentStore(context, alias)
        val unrelated = file("session-drafts.enc").apply { writeText("unrelated") }
        val kept = "kept".toByteArray()
        store.write(attachment(first, kept), kept)
        val orphan = "orphan".toByteArray()
        store.write(attachment(second, orphan), orphan)
        val leftover = file("attachment-${"C".repeat(22)}.v2.new").apply { writeText("partial") }
        val backup = file("attachment-${"D".repeat(22)}.v2.bak").apply { writeText("old") }

        store.cleanup(setOf(first))

        assertTrue(file("attachment-$first.v2").exists())
        assertArrayEquals(kept, store.read(attachment(first, kept)))
        assertFalse(file("attachment-$second.v2").exists())
        assertFalse(leftover.exists())
        assertFalse(backup.exists())
        assertEquals("unrelated", unrelated.readText())
    }

    @Test
    fun aDamagedStreamedFileIsQuarantinedAloneAndTheKeySurvives() {
        val store = AttachmentStore(context, alias)
        val bad = "bad".toByteArray()
        val good = "good".toByteArray()
        store.write(attachment(first, bad), bad)
        store.write(attachment(second, good), good)
        val damaged = file("attachment-$first.v2")
        damaged.writeBytes(
            damaged.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        )

        assertThrows(IllegalArgumentException::class.java) { store.read(attachment(first, bad)) }
        assertFalse(damaged.exists())
        assertArrayEquals(good, store.read(attachment(second, good)))
        assertNotNull(keystore().getKey(alias, null))
    }

    @Test
    fun aStreamedFileWithAChangedHeaderIsQuarantined() {
        val store = AttachmentStore(context, alias)
        val bytes = "header".toByteArray()
        store.write(attachment(first, bytes), bytes)
        val target = file("attachment-$first.v2")
        target.writeBytes(target.readBytes().also { it[0] = (it[0].toInt() xor 1).toByte() })

        assertThrows(IllegalArgumentException::class.java) { store.read(attachment(first, bytes)) }
        assertFalse(target.exists())
    }
}
