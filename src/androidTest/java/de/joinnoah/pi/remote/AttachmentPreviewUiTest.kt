package de.joinnoah.pi.remote

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import java.io.ByteArrayOutputStream
import org.junit.Rule
import org.junit.Test

class AttachmentPreviewUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun localPhotoShowsThumbnailAndOpensLargePreview() {
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        val bytes = try {
            bitmap.eraseColor(Color.BLUE)
            ByteArrayOutputStream().also {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it))
            }.toByteArray()
        } finally {
            bitmap.recycle()
        }
        val photo = LocalAttachment(
            "0123456789abcdefghijkl",
            "photo.jpg",
            "image",
            "image/jpeg",
            bytes.size.toLong(),
            AttachmentImportRules.sha256(bytes),
        )
        val imageFromFilePicker = photo.copy(
            id = "abcdefghijkl0123456789",
            kind = "file",
        )
        val storage = object : AttachmentStorage {
            override fun write(attachment: LocalAttachment, bytes: ByteArray) = Unit
            override fun read(attachment: LocalAttachment): ByteArray = bytes
            override fun remove(id: String) = Unit
            override fun cleanup(keepIds: Set<String>) = Unit
        }
        compose.setContent {
            MaterialTheme { AttachmentPreviewStrip(listOf(imageFromFilePicker), onRemove = {}, storage = storage) }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("attachmentThumbnailImage-${imageFromFilePicker.id}").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("attachmentThumbnailImage-${imageFromFilePicker.id}").assertIsDisplayed()
        compose.onNodeWithTag("attachmentPreview-${imageFromFilePicker.id}").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("attachmentLargeImage").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("attachmentLargeImage").assertIsDisplayed()
        compose.onNodeWithTag("closeAttachmentPreview").performClick()
        compose.onNodeWithTag("attachmentLargePreview").assertDoesNotExist()
    }
}
