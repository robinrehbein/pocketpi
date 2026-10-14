package de.joinnoah.pi.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.annotation.VisibleForTesting
import java.io.File
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * One AES-GCM encrypted file in `noBackupFilesDir`, keyed by the AndroidKeyStore key [alias]. The
 * file holds a 12-byte IV, then the ciphertext and its 16-byte tag; [alias] is the associated data.
 *
 * A file that can never be read again is quarantined: [read] deletes it and returns null, as if it
 * had never been written, so the app starts over instead of failing on every use. That happens only
 * for a file shorter than an IV and a tag, a tag that does not verify ([AEADBadTagException]), or a
 * key the Keystore cannot use any more ([KeyPermanentlyInvalidatedException],
 * [UnrecoverableKeyException]). Only the last two also delete the key: one bad file says nothing
 * about the key, and several files may share an alias (every attachment does). Any other failure,
 * an IOException above all, is thrown and leaves the file and the key alone.
 *
 * [onReset] runs before a quarantined file is deleted, while this store's lock is held, so caches
 * derived from the file go first.
 */
class EncryptedFileStore(
    context: Context,
    name: String,
    private val alias: String,
    private val onReset: () -> Unit = {},
) {
    private val file = AtomicFile(File(context.noBackupFilesDir, name))

    // Shared by every instance on the same file, so no two of them interleave a read and a write.
    private val lock: Any = fileLocks.computeIfAbsent(file.baseFile.absolutePath) { Any() }

    private var reset = false

    /** True once after [read] quarantined the file; later calls return false until it happens again. */
    fun takeReset(): Boolean = synchronized(lock) { reset.also { reset = false } }

    fun read(): ByteArray? =
        synchronized(lock) {
            if (!file.baseFile.exists()) return null
            val bytes = file.readFully()
            if (bytes.size < IV_BYTES + TAG_BYTES) {
                Diagnostics.record(Diagnostics.Kind.CAUGHT, "store.quarantine", outcome = "truncated")
                quarantine(dropKey = false)
                return null
            }
            require(bytes.size <= MAX_FILE_BYTES)
            try {
                withKey { unseal(alias, bytes) }
            } catch (e: Exception) {
                val unreadable = unreadable(e) ?: throw e.also { forgetKeyOn(alias, it) }
                Diagnostics.caught("store.quarantine", e)
                quarantine(dropKey = unreadable == Unreadable.KEY)
                null
            }
        }

    fun write(value: ByteArray) {
        require(value.size <= MAX_FILE_BYTES - IV_BYTES - TAG_BYTES)
        synchronized(lock) {
            val bytes =
                try {
                    withKey { seal(alias, value) }
                } catch (e: Exception) {
                    if (unreadable(e) != Unreadable.KEY) throw e.also { forgetKeyOn(alias, it) }
                    // The file is replaced anyway, so a new key lets the write go through.
                    Diagnostics.caught("store.write.key", e)
                    dropKey(alias)
                    seal(alias, value)
                }
            val stream = file.startWrite()
            try {
                stream.write(bytes)
                file.finishWrite(stream)
            } catch (e: Exception) {
                Diagnostics.caught("store.write", e)
                file.failWrite(stream)
                throw e
            }
        }
    }

    /**
     * Runs [block], which takes the key of [alias]. A key the Keystore refuses once is asked for
     * again before anything is deleted: some Keystores refuse while their daemon is not ready yet
     * (after a boot, say), and one such failure must not destroy the pairings and every attachment
     * under the alias. A second failure is left to the caller, which quarantines.
     */
    private fun <T> withKey(block: () -> T): T =
        try {
            block()
        } catch (e: Exception) {
            if (unreadable(e) != Unreadable.KEY) throw e
            forgetCachedKey(alias)
            block()
        }

    private fun quarantine(dropKey: Boolean) {
        if (dropKey) dropKey(alias)
        reset = true
        try {
            onReset()
        } catch (swallowed: Exception) {
            Diagnostics.caught("store.onReset", swallowed)
        }
        file.delete()
    }

    internal enum class Unreadable {
        /** The key cannot be used any more, so no file under its alias can be read. */
        KEY,

        /** This one file does not decrypt; the key is fine. */
        FILE,
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
        private const val MAX_FILE_BYTES = 32 * 1024 * 1024

        private val keyLock = Any()
        private val fileLocks = ConcurrentHashMap<String, Any>()

        /** Keys by alias, so a read or a write does not load the AndroidKeyStore each time. */
        private val keys = HashMap<String, SecretKey>()

        /**
         * Loads the key of an alias from the AndroidKeyStore, creating it when missing. Tests
         * replace it to count loads or to throw what a broken Keystore throws.
         */
        @VisibleForTesting internal var loadKey: (String) -> SecretKey = ::keystoreKey

        /** Forgets every cached key, as a new process would; the Keystore keeps them. */
        @VisibleForTesting
        internal fun forgetCachedKeys() {
            synchronized(keyLock) { keys.clear() }
        }

        private fun key(alias: String): SecretKey =
            synchronized(keyLock) { keys.getOrPut(alias) { loadKey(alias) } }

        private fun seal(alias: String, value: ByteArray): ByteArray {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key(alias))
            cipher.updateAAD(alias.toByteArray())
            return cipher.iv + cipher.doFinal(value)
        }

        private fun unseal(alias: String, bytes: ByteArray): ByteArray {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(alias),
                GCMParameterSpec(TAG_BYTES * 8, bytes, 0, IV_BYTES),
            )
            cipher.updateAAD(alias.toByteArray())
            return cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)
        }

        /**
         * Seals the data key of a streamed file under [alias]; the result goes in that file's
         * header (see [StreamingCipher]). Like [write], a key that cannot be used is replaced.
         */
        internal fun wrapKey(alias: String, value: ByteArray): ByteArray =
            try {
                withAliasKey(alias) { seal(alias, value) }
            } catch (e: Exception) {
                if (unreadable(e) != Unreadable.KEY) throw e.also { forgetKeyOn(alias, it) }
                Diagnostics.caught("store.write.key", e)
                dropKey(alias)
                seal(alias, value)
            }

        /** Opens a data key sealed by [wrapKey]. The caller decides what [unreadable] means for it. */
        internal fun unwrapKey(alias: String, sealed: ByteArray): ByteArray =
            withAliasKey(alias) { unseal(alias, sealed) }

        /** Runs [block] with the key of [alias], asking for that key again once when it is refused. */
        private fun <T> withAliasKey(alias: String, block: () -> T): T =
            try {
                block()
            } catch (e: Exception) {
                if (unreadable(e) != Unreadable.KEY) throw e.also { forgetKeyOn(alias, it) }
                forgetCachedKey(alias)
                block()
            }

        /** A key the Keystore rejected for another reason is loaded again next time, never deleted. */
        private fun forgetKeyOn(alias: String, error: Exception) {
            if (error is InvalidKeyException) synchronized(keyLock) { keys.remove(alias) }
        }

        /** Forgets the cached key of [alias] only; the next use loads it from the Keystore again. */
        private fun forgetCachedKey(alias: String) {
            synchronized(keyLock) { keys.remove(alias) }
        }

        /** Forgets the key of [alias] here and in the Keystore; the next use creates a new one. */
        internal fun dropKey(alias: String) {
            synchronized(keyLock) {
                keys.remove(alias)
                try {
                    KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(alias)
                } catch (swallowed: Exception) {
                    Diagnostics.caught("store.dropKey", swallowed)
                }
            }
        }

        /** Which failure [error] is, when it means the file or its key can never be read again. */
        internal fun unreadable(error: Throwable): Unreadable? {
            var cause: Throwable? = error
            while (cause != null) {
                when (cause) {
                    is KeyPermanentlyInvalidatedException,
                    is UnrecoverableKeyException -> return Unreadable.KEY
                    is AEADBadTagException -> return Unreadable.FILE
                }
                cause = cause.cause?.takeIf { it !== cause }
            }
            return null
        }

        private fun keystoreKey(alias: String): SecretKey {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            return (store.getKey(alias, null) as? SecretKey)
                ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
                    init(
                        KeyGenParameterSpec.Builder(
                                alias,
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .build()
                    )
                    generateKey()
                }
        }
    }
}
