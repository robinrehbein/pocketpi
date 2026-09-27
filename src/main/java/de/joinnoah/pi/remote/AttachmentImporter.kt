package de.joinnoah.pi.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal object AttachmentImportRules {
    const val FILE_BYTES = 20 * 1024 * 1024
    const val IMAGE_BYTES = 1024 * 1024

    fun validId(id: String) = Regex("[A-Za-z0-9_-]{22}").matches(id)

    private fun utf8Prefix(value: String, bytes: Int): String {
        val result = StringBuilder()
        var used = 0
        var offset = 0
        while (offset < value.length) {
            val point = value.codePointAt(offset)
            val text = String(Character.toChars(point))
            val size = text.toByteArray(Charsets.UTF_8).size
            if (used + size > bytes) break
            result.append(text)
            used += size
            offset += Character.charCount(point)
        }
        return result.toString()
    }

    fun name(displayName: String?): String {
        val base = displayName.orEmpty().replace('\\', '/').substringAfterLast('/')
        val clean = buildString {
            base.codePoints().forEach { point ->
                if (
                    Character.getType(point) !in
                        listOf(
                            Character.CONTROL.toInt(),
                            Character.FORMAT.toInt(),
                            Character.SURROGATE.toInt(),
                        )
                )
                    appendCodePoint(point)
            }
        }
            .replace("..", "_")
            .trim { it.isWhitespace() || it == '.' }
        return utf8Prefix(clean, 255)
            .trim { it.isWhitespace() || it == '.' }
            .takeUnless { it.isBlank() || it.all { character -> character == '_' } } ?: "attachment"
    }

    fun jpegName(displayName: String?): String =
        name(utf8Prefix(name(displayName).substringBeforeLast('.', name(displayName)), 251)) +
            ".jpg"

    fun mime(value: String?): String =
        value?.takeIf {
            it.length <= 127 && Regex("[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+").matches(it)
        } ?: "application/octet-stream"

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun readLimited(
        input: InputStream,
        limit: Int = FILE_BYTES,
        checkActive: () -> Unit = {},
    ): ByteArray {
        require(limit in 1..FILE_BYTES)
        val output = ByteArrayOutputStream(minOf(limit, 16 * 1024))
        val buffer = ByteArray(minOf(limit + 1, 16 * 1024))
        while (true) {
            checkActive()
            val read = input.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
            if (read < 0) break
            if (read == 0) {
                val byte = input.read()
                if (byte < 0) break
                require(output.size() < limit) { "Attachment exceeds the file limit" }
                output.write(byte)
            } else {
                require(output.size() + read <= limit) { "Attachment exceeds the file limit" }
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    fun sampleSize(width: Int, height: Int): Int {
        require(width in 1..100000 && height in 1..100000 && width.toLong() * height <= 200000000) {
            "Unsupported image dimensions"
        }
        var sample = 1
        while ((maxOf(width, height).toLong() + sample - 1) / sample > 2048) sample *= 2
        return sample
    }
}

class AttachmentImporter(context: Context, private val store: AttachmentStorage) {
    private val resolver = context.contentResolver

    suspend fun import(uri: Uri, photo: Boolean): LocalAttachment {
        var storedId: String? = null
        try {
            return withContext(Dispatchers.IO) {
                require(uri.scheme == "content") {
                    "Choose an attachment through the system picker"
                }
                val coroutine = currentCoroutineContext()
                coroutine.ensureActive()
                val displayName =
                    resolver
                        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        }
                val original =
                    requireNotNull(resolver.openInputStream(uri)) { "Attachment is unavailable" }
                        .use {
                            AttachmentImportRules.readLimited(it) { coroutine.ensureActive() }
                        }
                val data =
                    if (photo) normalizePhoto(original) { coroutine.ensureActive() } else original
                coroutine.ensureActive()
                val attachment =
                    LocalAttachment(
                        Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) }),
                        if (photo) AttachmentImportRules.jpegName(displayName)
                        else AttachmentImportRules.name(displayName),
                        if (photo) "image" else "file",
                        if (photo) "image/jpeg"
                        else AttachmentImportRules.mime(resolver.getType(uri)),
                        data.size.toLong(),
                        AttachmentImportRules.sha256(data),
                    )
                storedId = attachment.id
                store.write(attachment, data)
                coroutine.ensureActive()
                attachment
            }
        } catch (error: Throwable) {
            storedId?.let { id ->
                try {
                    withContext(NonCancellable + Dispatchers.IO) { store.remove(id) }
                } catch (cleanupError: Throwable) {
                    error.addSuppressed(cleanupError)
                }
            }
            throw error
        }
    }

    internal fun normalizePhoto(original: ByteArray, checkActive: () -> Unit = {}): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
        val sample = AttachmentImportRules.sampleSize(bounds.outWidth, bounds.outHeight)
        checkActive()
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        var bitmap =
            requireNotNull(BitmapFactory.decodeByteArray(original, 0, original.size, options)) {
                "Unsupported image"
            }
        try {
            val orientation =
                try {
                    ExifInterface(ByteArrayInputStream(original))
                        .getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL,
                        )
                } catch (_: IOException) {
                    ExifInterface.ORIENTATION_NORMAL
                }
            val matrix =
                Matrix().apply {
                    when (orientation) {
                        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                        ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                        ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                        ExifInterface.ORIENTATION_TRANSPOSE -> {
                            setRotate(90f)
                            postScale(-1f, 1f)
                        }
                        ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                        ExifInterface.ORIENTATION_TRANSVERSE -> {
                            setRotate(-90f)
                            postScale(-1f, 1f)
                        }
                        ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                    }
                }
            if (!matrix.isIdentity) {
                val rotated =
                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated !== bitmap) {
                    bitmap.recycle()
                    bitmap = rotated
                }
            }
            while (true) {
                for (quality in listOf(90, 80, 65, 50, 35, 20)) {
                    checkActive()
                    val output = ByteArrayOutputStream()
                    require(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                        "Photo encoding failed"
                    }
                    if (output.size() <= AttachmentImportRules.IMAGE_BYTES)
                        return output.toByteArray()
                }
                checkActive()
                require(bitmap.width > 1 || bitmap.height > 1) { "Photo exceeds the image limit" }
                val smaller =
                    Bitmap.createScaledBitmap(
                        bitmap,
                        maxOf(1, bitmap.width * 3 / 4),
                        maxOf(1, bitmap.height * 3 / 4),
                        true,
                    )
                bitmap.recycle()
                bitmap = smaller
            }
        } finally {
            bitmap.recycle()
        }
    }
}
