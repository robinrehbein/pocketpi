package de.joinnoah.pi.remote

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SdkSuppress
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

@SdkSuppress(minSdkVersion = 29)
class AttachmentImportUiTest {
    private fun isolated(block: (Context, AttachmentStore) -> Unit) {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val directory =
            File(application.cacheDir, "attachment-test-${UUID.randomUUID()}").apply { mkdirs() }
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

    private fun selected(context: Context, bytes: ByteArray, block: (Uri) -> Unit) {
        val values =
            ContentValues().apply {
                put(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    "attachment-test-${UUID.randomUUID()}.png",
                )
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PocketPiTests")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        val uri =
            requireNotNull(
                context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            )
        try {
            requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(bytes) }
            block(uri)
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    private fun image(
        width: Int,
        height: Int,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.BLUE)
            ByteArrayOutputStream()
                .also { assertTrue(bitmap.compress(format, 95, it)) }
                .toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun encryptedFilesRoundTripEmptyAndNonemptyBytesAndCleanupOnlyOwnsAttachmentNames() =
        isolated { context, store ->
            val unrelated =
                File(context.noBackupFilesDir, "session-drafts.enc").apply {
                    writeText("unrelated")
                }
            val first =
                LocalAttachment(
                    "0123456789abcdefghijkl",
                    "notes.txt",
                    "file",
                    "text/plain",
                    6,
                    AttachmentImportRules.sha256("secret".toByteArray()),
                )
            store.write(first, "secret".toByteArray())
            assertArrayEquals("secret".toByteArray(), store.read(first))
            assertFalse(
                File(context.noBackupFilesDir, "attachment-${first.id}.enc")
                    .readBytes()
                    .toString(Charsets.ISO_8859_1)
                    .contains("secret")
            )
            assertThrows(IllegalArgumentException::class.java) {
                store.read(first.copy(sha256 = "0".repeat(64)))
            }
            val empty =
                first.copy(
                    id = "abcdefghijkl0123456789",
                    size = 0,
                    sha256 = AttachmentImportRules.sha256(byteArrayOf()),
                )
            store.write(empty, byteArrayOf())
            assertArrayEquals(byteArrayOf(), store.read(empty))
            store.cleanup(setOf(first.id))
            assertThrows(IllegalArgumentException::class.java) { store.read(empty) }
            assertEquals("unrelated", unrelated.readText())
            val encrypted = File(context.noBackupFilesDir, "attachment-${first.id}.enc")
            val damaged =
                encrypted.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            encrypted.writeBytes(damaged)
            assertThrows(Exception::class.java) { store.read(first) }
        }

    @Test
    fun photoUriIsCopiedNormalizedAndReadableAfterOriginalIsDeleted() = isolated { context, store ->
        lateinit var attachment: LocalAttachment
        selected(context, image(3200, 1600)) { uri ->
            attachment = runBlocking { AttachmentImporter(context, store).import(uri, true) }
        }
        assertEquals("image", attachment.kind)
        assertEquals("image/jpeg", attachment.mimeType)
        assertTrue(attachment.name.endsWith(".jpg"))
        assertTrue(attachment.size <= AttachmentImportRules.IMAGE_BYTES)
        val bitmap =
            BitmapFactory.decodeByteArray(store.read(attachment), 0, attachment.size.toInt())
        try {
            assertEquals(1600, bitmap.width)
            assertEquals(800, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun jpegExifOrientationIsAppliedAndRemovedFromNormalizedImage() = isolated { context, store ->
        val jpeg = File(context.noBackupFilesDir, "orientation-fixture.jpg")
        try {
            jpeg.writeBytes(image(80, 40, Bitmap.CompressFormat.JPEG))
            ExifInterface(jpeg.path).apply {
                setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_ROTATE_90.toString(),
                )
                saveAttributes()
            }
            selected(context, jpeg.readBytes()) { uri ->
                val imported = runBlocking { AttachmentImporter(context, store).import(uri, true) }
                val normalized = store.read(imported)
                val bitmap = BitmapFactory.decodeByteArray(normalized, 0, normalized.size)
                try {
                    assertEquals(40, bitmap.width)
                    assertEquals(80, bitmap.height)
                } finally {
                    bitmap.recycle()
                }
                val orientation =
                    ExifInterface(normalized.inputStream())
                        .getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL,
                        )
                assertTrue(
                    orientation == ExifInterface.ORIENTATION_NORMAL ||
                        orientation == ExifInterface.ORIENTATION_UNDEFINED
                )
            }
        } finally {
            jpeg.delete()
        }
    }

    @Test
    fun fileImportPreservesBytesAndPhotoImportRejectsMisleadingProviderMime() =
        isolated { context, store ->
            selected(context, "plain file".toByteArray()) { uri ->
                val imported = runBlocking { AttachmentImporter(context, store).import(uri, false) }
                assertEquals("file", imported.kind)
                assertArrayEquals("plain file".toByteArray(), store.read(imported))
                assertThrows(IllegalArgumentException::class.java) {
                    runBlocking { AttachmentImporter(context, store).import(uri, true) }
                }
                assertEquals(
                    1,
                    context.noBackupFilesDir.listFiles()!!.count {
                        it.name.startsWith("attachment-")
                    },
                )
            }
        }

    @Test
    fun noisyPhotoIsCompressedBelowTheImageLimit() = isolated { context, store ->
        val bitmap = Bitmap.createBitmap(1600, 1600, Bitmap.Config.ARGB_8888)
        val random = java.util.Random(832)
        val pixels = IntArray(1600 * 1600) { random.nextInt() or 0xff000000.toInt() }
        bitmap.setPixels(pixels, 0, 1600, 0, 0, 1600, 1600)
        val original =
            try {
                val initialJpeg = ByteArrayOutputStream()
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, initialJpeg))
                assertTrue(initialJpeg.size() > AttachmentImportRules.IMAGE_BYTES)
                ByteArrayOutputStream()
                    .also { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    .toByteArray()
            } finally {
                bitmap.recycle()
            }
        selected(context, original) { uri ->
            val imported = runBlocking { AttachmentImporter(context, store).import(uri, true) }
            assertTrue(imported.size <= AttachmentImportRules.IMAGE_BYTES)
            val data = store.read(imported)
            val result = BitmapFactory.decodeByteArray(data, 0, data.size)
            try {
                assertTrue(result.width <= 2048)
                assertTrue(result.height <= 2048)
            } finally {
                result.recycle()
            }
        }
    }

    @Test
    fun cancellationAfterEncryptedWriteRemovesTheNewBlob() = isolated { context, store ->
        selected(context, "cancelled draft".toByteArray()) { uri ->
            lateinit var job: Job
            val cancellingStore =
                object : AttachmentStorage by store {
                    override fun write(attachment: LocalAttachment, bytes: ByteArray) {
                        store.write(attachment, bytes)
                        job.cancel()
                    }
                }
            runBlocking {
                val imported =
                    async(start = CoroutineStart.LAZY) {
                        AttachmentImporter(context, cancellingStore).import(uri, false)
                    }
                job = imported
                imported.start()
                try {
                    imported.await()
                    fail("Import should be cancelled")
                } catch (_: CancellationException) {}
            }
            assertTrue(context.noBackupFilesDir.listFiles().orEmpty().isEmpty())
        }
    }
}
