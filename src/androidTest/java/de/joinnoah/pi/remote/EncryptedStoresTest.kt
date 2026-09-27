package de.joinnoah.pi.remote

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.*
import org.junit.Test

class EncryptedStoresTest {
    private fun isolated(block: (Context) -> Unit) {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val directory =
            File(application.cacheDir, "encrypted-store-test-${UUID.randomUUID()}").apply {
                mkdirs()
            }
        val context =
            object : ContextWrapper(application) {
                override fun getNoBackupFilesDir() = directory
            }
        try {
            block(context)
        } finally {
            directory.deleteRecursively()
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keys
                .aliases()
                .toList()
                .filter { it.startsWith(directory.name) }
                .forEach { keys.deleteEntry(it) }
        }
    }

    @Test
    fun readsExistingPairingCipherFormatAndRoundTrips() = isolated { context ->
        val host = PairedHost("route", "https://relay.test", "device", "secret", "Host")
        val alias = context.noBackupFilesDir.name + "-pairings"
        PairingStore(context, alias = alias).save(listOf(host))
        val key =
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null)
                as SecretKey
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key)
                updateAAD(alias.toByteArray())
            }
        val legacyBytes =
            cipher.iv + cipher.doFinal(JsonArray(listOf(host.json())).toString().toByteArray())
        File(context.noBackupFilesDir, "paired-hosts.enc").writeBytes(legacyBytes)
        assertEquals(listOf(host), PairingStore(context, alias = alias).load())
        PairingStore(context, alias = alias).save(listOf(host.copy(name = "Updated")))
        assertEquals("Updated", PairingStore(context, alias = alias).load().single().name)
    }

    @Test
    fun independentDraftStoreRecreationRetainsTextAndUncertaintyWithStructuredKeys() =
        isolated { context ->
            val drafts =
                mapOf(
                    DraftKey("route/part", "session") to
                        StoredDraft(
                            "new edit",
                            "mutation",
                            "submitted",
                            MessageQuote("message", "assistant", "Selected quote", "Actual model"),
                            MessageQuote("prior", "user", "Submitted quote"),
                            attachments =
                                listOf(
                                    LocalAttachment(
                                        "AAAAAAAAAAAAAAAAAAAAAA",
                                        "notes.txt",
                                        "file",
                                        "text/plain",
                                        3,
                                        "a".repeat(64),
                                    )
                                ),
                            submittedAttachments =
                                listOf(
                                    LocalAttachment(
                                        "CCCCCCCCCCCCCCCCCCCCCC",
                                        "submitted.txt",
                                        "file",
                                        "text/plain",
                                        4,
                                        "b".repeat(64),
                                    )
                                ),
                            uploads =
                                listOf(
                                    AttachmentUpload(
                                        "AAAAAAAAAAAAAAAAAAAAAA",
                                        "BBBBBBBBBBBBBBBBBBBBBB",
                                        "project",
                                        RemoteAttachment(
                                            "BBBBBBBBBBBBBBBBBBBBBB",
                                            "notes.txt",
                                            "file",
                                            "text/plain",
                                            3,
                                            "a".repeat(64),
                                            12345,
                                        ),
                                    )
                                ),
                        ),
                    DraftKey("route", "part/session") to StoredDraft("independent"),
                )
            val alias = context.noBackupFilesDir.name + "-drafts"
            DraftStore(context, alias = alias).save(drafts)
            assertEquals(drafts, DraftStore(context, alias = alias).load())
            assertTrue(
                File(context.noBackupFilesDir, "session-drafts.enc")
                    .readBytes()
                    .toString(Charsets.ISO_8859_1)
                    .contains("new edit")
                    .not()
            )
            assertEquals(emptyList<PairedHost>(), PairingStore(context, alias = alias).load())
        }

    @Test
    fun tooLargeWritePreservesPreviouslyReadableEncryptedFile() = isolated { context ->
        val alias = "pi-remote-test-${UUID.randomUUID()}"
        try {
            val store = EncryptedFileStore(context, "bounded.enc", alias)
            store.write("saved".toByteArray())
            assertThrows(IllegalArgumentException::class.java) {
                store.write(ByteArray(32 * 1024 * 1024))
            }
            assertEquals("saved", checkNotNull(store.read()).toString(Charsets.UTF_8))
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(alias)
            }
        }
    }
}
