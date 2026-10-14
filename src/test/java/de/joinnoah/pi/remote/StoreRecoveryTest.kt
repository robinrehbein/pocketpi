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
import javax.crypto.SecretKey
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An encrypted store that can never be read again is reset to empty instead of failing on every
 * use; a transient failure is not. The in-memory Keystore stands in for the AndroidKeyStore; the
 * instrumented EncryptedStoresTest covers the real one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StoreRecoveryTest {
    private val application = ApplicationProvider.getApplicationContext<Context>()
    private val directory = File(application.cacheDir, "store-recovery-${UUID.randomUUID()}")
    private val context =
        object : ContextWrapper(application) {
            override fun getNoBackupFilesDir() = directory
        }
    private val alias = "store-recovery-${UUID.randomUUID()}"
    private val originalLoadKey = EncryptedFileStore.loadKey
    private var loads = 0

    @Before
    fun setUp() {
        FakeAndroidKeyStore.install()
        directory.mkdirs()
        EncryptedFileStore.loadKey = { loads++; originalLoadKey(it) }
    }

    @After
    fun tearDown() {
        EncryptedFileStore.loadKey = originalLoadKey
        directory.deleteRecursively()
        keystore().deleteEntry(alias)
    }

    private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun storedKey() = keystore().getKey(alias, null) as SecretKey?

    private fun file(name: String) = File(directory, name)

    /** Flips the last byte, which belongs to the GCM tag. */
    private fun corrupt(name: String) {
        val bytes = file(name).readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        file(name).writeBytes(bytes)
    }

    private fun truncate(name: String) {
        file(name).writeBytes(file(name).readBytes().copyOf(20))
    }

    private val host = PairedHost("route", "https://relay.test", "device", "secret", "Mac")

    @Test
    fun theKeyIsLoadedOncePerAliasAcrossStores() {
        EncryptedFileStore(context, "a.enc", alias).write("a".toByteArray())
        EncryptedFileStore(context, "b.enc", alias).write("b".toByteArray())
        assertEquals("a", EncryptedFileStore(context, "a.enc", alias).read()!!.decodeToString())
        assertEquals("b", EncryptedFileStore(context, "b.enc", alias).read()!!.decodeToString())
        assertEquals(1, loads)
    }

    @Test
    fun anInvalidatedKeyResetsTheStoreAndIsDropped() {
        val store = EncryptedFileStore(context, "a.enc", alias)
        store.write("a".toByteArray())
        val old = storedKey()
        EncryptedFileStore.forgetCachedKeys()
        EncryptedFileStore.loadKey = { throw KeyPermanentlyInvalidatedException() }
        assertNull(store.read())
        assertTrue(store.takeReset())
        assertFalse(store.takeReset())
        assertFalse(file("a.enc").exists())
        assertNull(storedKey())

        loads = 0
        EncryptedFileStore.loadKey = { loads++; originalLoadKey(it) }
        store.write("again".toByteArray())
        assertEquals("again", store.read()!!.decodeToString())
        assertEquals(1, loads)
        assertNotEquals(old, storedKey())
    }

    @Test
    fun anUnrecoverableKeyResetsTheStoreAndIsDropped() {
        val store = EncryptedFileStore(context, "a.enc", alias)
        store.write("a".toByteArray())
        EncryptedFileStore.forgetCachedKeys()
        EncryptedFileStore.loadKey = { throw UnrecoverableKeyException("gone") }
        assertNull(store.read())
        assertTrue(store.takeReset())
        assertNull(storedKey())
    }

    @Test
    fun aWriteWithAnInvalidatedKeyMakesANewKey() {
        val store = EncryptedFileStore(context, "a.enc", alias)
        store.write("a".toByteArray())
        EncryptedFileStore.forgetCachedKeys()
        var failed = false
        EncryptedFileStore.loadKey = {
            if (!failed) {
                failed = true
                throw KeyPermanentlyInvalidatedException()
            }
            originalLoadKey(it)
        }
        store.write("b".toByteArray())
        assertEquals("b", store.read()!!.decodeToString())
        assertFalse(store.takeReset())
    }

    @Test
    fun oneBadFileIsQuarantinedAloneAndTheSharedKeySurvives() {
        val bad = EncryptedFileStore(context, "attachment-bad.enc", alias)
        val good = EncryptedFileStore(context, "attachment-good.enc", alias)
        bad.write(byteArrayOf(1, 2, 3))
        good.write(byteArrayOf(4, 5, 6))
        val key = storedKey()
        corrupt("attachment-bad.enc")
        assertNull(bad.read())
        assertTrue(bad.takeReset())
        assertFalse(file("attachment-bad.enc").exists())
        assertEquals(key, storedKey())
        assertArrayEquals(byteArrayOf(4, 5, 6), good.read())
        assertFalse(good.takeReset())
    }

    @Test
    fun aTruncatedFileIsQuarantinedWithoutTheKey() {
        val store = EncryptedFileStore(context, "a.enc", alias)
        store.write("a".toByteArray())
        truncate("a.enc")
        assertNull(store.read())
        assertTrue(store.takeReset())
        assertFalse(file("a.enc").exists())
        assertNotNull(storedKey())
    }

    @Test
    fun anIOExceptionNeverQuarantines() {
        // A directory where the file belongs cannot be opened for reading.
        file("a.enc").mkdirs()
        val store = EncryptedFileStore(context, "a.enc", alias)
        assertThrows(IOException::class.java) { store.read() }
        assertFalse(store.takeReset())
        assertTrue(file("a.enc").isDirectory)
    }

    @Test
    fun anotherKeystoreFailureNeverQuarantines() {
        val store = EncryptedFileStore(context, "a.enc", alias)
        store.write("a".toByteArray())
        EncryptedFileStore.forgetCachedKeys()
        EncryptedFileStore.loadKey = { throw java.security.KeyStoreException("busy") }
        assertThrows(java.security.KeyStoreException::class.java) { store.read() }
        assertFalse(store.takeReset())
        assertTrue(file("a.enc").exists())
        assertNotNull(storedKey())
    }

    @Test
    fun everyStoreLoadsEmptyAfterAResetAndWritesAgain() {
        val pairings = PairingStore(context, alias = alias)
        pairings.save(listOf(host))
        corrupt("paired-hosts.enc")
        assertEquals(emptyList<PairedHost>(), pairings.load())
        assertTrue(pairings.takeReset())
        pairings.save(listOf(host))
        assertEquals(listOf(host), pairings.load())

        val drafts = DraftStore(context, alias = alias)
        drafts.save(mapOf(DraftKey("route", "session") to StoredDraft("text")))
        truncate("session-drafts.enc")
        assertEquals(emptyMap<DraftKey, StoredDraft>(), drafts.load())
        assertTrue(drafts.takeReset())
        drafts.save(mapOf(DraftKey("route", "session") to StoredDraft("again")))
        assertEquals("again", drafts.load().values.single().text)

        val navigation = NavigationSnapshotStore(context)
        navigation.save(mapOf("route" to NavigationSnapshot("route", emptyList())))
        corrupt("navigation-snapshots.enc")
        assertEquals(emptyMap<String, NavigationSnapshot>(), navigation.load())
        navigation.save(mapOf("route" to NavigationSnapshot("route", emptyList())))
        assertEquals(setOf("route"), navigation.load().keys)
    }

    @Test
    fun theResetHookRunsBeforeTheFileIsDeleted() {
        var existed: Boolean? = null
        val store = EncryptedFileStore(context, "a.enc", alias, onReset = { existed = file("a.enc").exists() })
        store.write("a".toByteArray())
        corrupt("a.enc")
        assertNull(store.read())
        assertEquals(true, existed)
    }

    @Test
    fun aPairingResetClearsTheRouteCache() {
        val stores = AppStores(context)
        stores.pairings.save(listOf(host))
        assertEquals(setOf("route"), stores.routes.routes())
        corrupt("paired-hosts.enc")
        // A push whose route is not cached reads the file itself and finds it unreadable.
        stores.routes.clear()
        assertEquals(emptySet<String>(), stores.routes.routes { stores.pairingFile.load() })
        // Reading the file directly leaves no route cached either.
        stores.pairings.save(listOf(host))
        corrupt("paired-hosts.enc")
        assertEquals(emptyList<PairedHost>(), stores.pairingFile.load())
        assertNull(stores.routes.routes())
        // The app's own load then says so once and caches that no route is paired.
        assertEquals(emptyList<PairedHost>(), stores.pairings.load())
        assertTrue(stores.pairings.takeReset())
        assertFalse(stores.pairings.takeReset())
        assertEquals(emptySet<String>(), stores.routes.routes())
    }

    @Test
    fun oneUnreadableAttachmentReadsAsMissingAndTheOthersStayReadable() {
        val attachments = AttachmentStore(context, alias)
        fun attachment(id: String, bytes: ByteArray) =
            LocalAttachment(id, "notes.txt", "file", "text/plain", bytes.size.toLong(), AttachmentImportRules.sha256(bytes))
        val bad = attachment("A".repeat(22), "bad".toByteArray())
        val good = attachment("B".repeat(22), "good".toByteArray())
        attachments.write(bad, "bad".toByteArray())
        attachments.write(good, "good".toByteArray())
        corrupt("attachment-${bad.id}.enc")
        assertThrows(IllegalArgumentException::class.java) { attachments.read(bad) }
        assertFalse(file("attachment-${bad.id}.enc").exists())
        assertEquals("good", attachments.read(good).decodeToString())
        assertNotNull(storedKey())
    }

    @Test
    fun aKeyThatFailsOnceIsLoadedAgainBeforeAnythingIsDeleted() {
        val store = EncryptedFileStore(context, "a.enc", alias)
        store.write("a".toByteArray())
        EncryptedFileStore.forgetCachedKeys()
        var failed = false
        EncryptedFileStore.loadKey = {
            if (!failed) {
                failed = true
                throw UnrecoverableKeyException("keystore not ready")
            }
            originalLoadKey(it)
        }
        assertEquals("a", store.read()!!.decodeToString())
        assertFalse(store.takeReset())
        assertTrue(file("a.enc").exists())
        assertNotNull(storedKey())
    }

    @Test
    fun theAppStoresHandOutOneInstancePerFile() {
        val stores = AppStores(context)
        assertSame(stores.pairings, stores.pairings)
        assertSame(stores.pairingFile, stores.pairingFile)
        assertSame(stores.routes, stores.routes)
        assertSame(stores.drafts, stores.drafts)
        assertSame(stores.home, stores.home)
        assertSame(stores.prompts, stores.prompts)
        stores.pairings.save(listOf(host))
        corrupt("paired-hosts.enc")
        // The reset is reported once, whichever caller loads the shared store first.
        assertEquals(emptyList<PairedHost>(), stores.pairings.load())
        assertTrue(stores.pairingFile.takeReset())
    }

    @Test
    fun aPairedHostNeverPrintsItsSecret() {
        val printed = PairedHost("route", "https://relay.test", "device", "s3cr3t-value", "Mac").toString()
        assertFalse(printed.contains("s3cr3t-value"))
        assertTrue(printed.contains("route"))
    }
}
