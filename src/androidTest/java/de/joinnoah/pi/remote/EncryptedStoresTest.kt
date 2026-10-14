package de.joinnoah.pi.remote

import android.content.Context
import android.content.ContextWrapper
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
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

    /** One of the app's encrypted stores, written once and read back as written or as empty. */
    private class StoreCase(
        val name: String,
        val file: String,
        val write: (Context, String) -> Unit,
        /** True when the store reads what [write] wrote, false when it reads as empty. */
        val readsWritten: (Context, String) -> Boolean,
    )

    private val host = PairedHost("route", "https://relay.test", "device", "secret", "Host")
    private val attachmentBytes = "attached".toByteArray()
    private val attachment =
        LocalAttachment(
            "AAAAAAAAAAAAAAAAAAAAAA",
            "notes.txt",
            "file",
            "text/plain",
            attachmentBytes.size.toLong(),
            AttachmentImportRules.sha256(attachmentBytes),
        )

    private fun raw(file: String) = { context: Context, alias: String ->
        EncryptedFileStore(context, file, alias).write("{}".toByteArray())
    }

    private fun rawReads(file: String) = { context: Context, alias: String ->
        EncryptedFileStore(context, file, alias).read() != null
    }

    private val stores =
        listOf(
            StoreCase(
                "pairings",
                "paired-hosts.enc",
                { context, alias -> PairingStore(context, alias = alias).save(listOf(host)) },
                { context, alias -> PairingStore(context, alias = alias).load().isNotEmpty() },
            ),
            StoreCase(
                "drafts",
                "session-drafts.enc",
                { context, alias -> DraftStore(context, alias = alias).save(mapOf(DraftKey("route", "session") to StoredDraft("text"))) },
                { context, alias -> DraftStore(context, alias = alias).load().isNotEmpty() },
            ),
            // Navigation, home and prompt history read through the same store as these.
            StoreCase("navigation", "navigation-snapshots.enc", raw("navigation-snapshots.enc"), rawReads("navigation-snapshots.enc")),
            StoreCase("home", "home-sessions.enc", raw("home-sessions.enc"), rawReads("home-sessions.enc")),
            StoreCase("prompts", "prompt-history.enc", raw("prompt-history.enc"), rawReads("prompt-history.enc")),
            StoreCase(
                "attachments",
                "attachment-${attachment.id}.v2",
                { context, alias -> AttachmentStore(context, alias).write(attachment, attachmentBytes) },
                { context, alias -> runCatching { AttachmentStore(context, alias).read(attachment) }.isSuccess },
            ),
        )

    private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /** Writes every store, breaks each file with [damage], and checks that each resets and pairing works. */
    private fun recoversFrom(damage: (Context, File, String) -> Unit) = isolated { context ->
        for (case in stores) {
            val alias = context.noBackupFilesDir.name + "-" + case.name
            case.write(context, alias)
            val file = File(context.noBackupFilesDir, case.file)
            assertTrue(case.name, file.exists())
            damage(context, file, alias)
            assertFalse(case.name, case.readsWritten(context, alias))
            assertFalse(case.name, file.exists())
            case.write(context, alias)
            assertTrue(case.name, case.readsWritten(context, alias))
        }
        val alias = context.noBackupFilesDir.name + "-pairings"
        PairingStore(context, alias = alias).save(listOf(host, host.copy(routeId = "second")))
        assertEquals(2, PairingStore(context, alias = alias).load().size)
    }

    @Test
    fun corruptedFilesResetEveryStore() = recoversFrom { _, file, _ ->
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        file.writeBytes(bytes)
    }

    @Test
    fun truncatedFilesResetEveryStore() = recoversFrom { _, file, _ ->
        file.writeBytes(file.readBytes().copyOf(20))
    }

    @Test
    fun invalidatedKeysResetEveryStore() {
        val original = EncryptedFileStore.loadKey
        try {
            recoversFrom { _, _, _ ->
                EncryptedFileStore.forgetCachedKeys()
                // The next read meets an invalidated key; the write after it makes a new one.
                EncryptedFileStore.loadKey = {
                    EncryptedFileStore.loadKey = original
                    throw KeyPermanentlyInvalidatedException()
                }
            }
        } finally {
            EncryptedFileStore.loadKey = original
        }
    }

    @Test
    fun anUnrecoverableKeyIsDeletedWithTheStore() = isolated { context ->
        val alias = context.noBackupFilesDir.name + "-pairings"
        PairingStore(context, alias = alias).save(listOf(host))
        val original = EncryptedFileStore.loadKey
        EncryptedFileStore.forgetCachedKeys()
        EncryptedFileStore.loadKey = { throw UnrecoverableKeyException("gone") }
        try {
            val store = PairingStore(context, alias = alias)
            assertEquals(emptyList<PairedHost>(), store.load())
            assertTrue(store.takeReset())
            assertFalse(keystore().containsAlias(alias))
        } finally {
            EncryptedFileStore.loadKey = original
        }
        PairingStore(context, alias = alias).save(listOf(host))
        assertEquals(listOf(host), PairingStore(context, alias = alias).load())
    }

    @Test
    fun anIOExceptionDoesNotQuarantine() = isolated { context ->
        val alias = context.noBackupFilesDir.name + "-pairings"
        PairingStore(context, alias = alias).save(listOf(host))
        val file = File(context.noBackupFilesDir, "paired-hosts.enc")
        file.delete()
        // A directory where the file belongs cannot be read.
        file.mkdirs()
        val store = PairingStore(context, alias = alias)
        assertThrows(IOException::class.java) { store.load() }
        assertFalse(store.takeReset())
        assertTrue(file.isDirectory)
        assertTrue(keystore().containsAlias(alias))
    }

    @Test
    fun oneCorruptedAttachmentIsQuarantinedAloneAndTheSharedKeySurvives() = isolated { context ->
        val alias = context.noBackupFilesDir.name + "-attachments"
        val store = AttachmentStore(context, alias)
        val otherBytes = "other".toByteArray()
        val other =
            attachment.copy(
                id = "BBBBBBBBBBBBBBBBBBBBBB",
                size = otherBytes.size.toLong(),
                sha256 = AttachmentImportRules.sha256(otherBytes),
            )
        store.write(attachment, attachmentBytes)
        store.write(other, otherBytes)
        val file = File(context.noBackupFilesDir, "attachment-${attachment.id}.v2")
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertThrows(IllegalArgumentException::class.java) { store.read(attachment) }
        assertFalse(file.exists())
        assertArrayEquals(otherBytes, store.read(other))
        assertTrue(keystore().containsAlias(alias))
        assertArrayEquals(otherBytes, AttachmentStore(context, alias).read(other))
    }
}
