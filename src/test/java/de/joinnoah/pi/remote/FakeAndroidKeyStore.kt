package de.joinnoah.pi.remote

import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * An in-memory stand-in for the AndroidKeyStore provider, which Robolectric lacks, so that tests
 * can run code that stores through [EncryptedFileStore]. It holds AES keys by alias and nothing
 * else. The provider stays registered for the whole JVM, across Robolectric sandboxes, so it reads
 * the alias reflectively instead of casting to a sandbox's KeyGenParameterSpec.
 */
object FakeAndroidKeyStore {
    private val keys = ConcurrentHashMap<String, SecretKey>()

    fun install() {
        synchronized(this) {
            if (Security.getProvider(NAME) == null) Security.addProvider(Provider())
        }
    }

    private const val NAME = "AndroidKeyStore"

    @Suppress("DEPRECATION")
    class Provider : java.security.Provider(NAME, 1.0, "In-memory AndroidKeyStore for tests") {
        init {
            put("KeyStore.$NAME", Store::class.java.name)
            put("KeyGenerator.AES", Generator::class.java.name)
        }
    }

    class Store : KeyStoreSpi() {
        override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]

        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null

        override fun engineGetCertificate(alias: String): Certificate? = null

        override fun engineGetCreationDate(alias: String): Date? = null

        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) {
            keys[alias] = key as SecretKey
        }

        override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) =
            throw UnsupportedOperationException()

        override fun engineSetCertificateEntry(alias: String, cert: Certificate) =
            throw UnsupportedOperationException()

        override fun engineDeleteEntry(alias: String) {
            keys.remove(alias)
        }

        override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys.toList())

        override fun engineContainsAlias(alias: String): Boolean = keys.containsKey(alias)

        override fun engineSize(): Int = keys.size

        override fun engineIsKeyEntry(alias: String): Boolean = keys.containsKey(alias)

        override fun engineIsCertificateEntry(alias: String): Boolean = false

        override fun engineGetCertificateAlias(cert: Certificate): String? = null

        override fun engineStore(stream: OutputStream?, password: CharArray?) {}

        override fun engineLoad(stream: InputStream?, password: CharArray?) {}
    }

    class Generator : KeyGeneratorSpi() {
        private var alias: String? = null

        override fun engineInit(random: SecureRandom?) {}

        override fun engineInit(params: AlgorithmParameterSpec, random: SecureRandom?) {
            alias = params.javaClass.getMethod("getKeystoreAlias").invoke(params) as String
        }

        override fun engineInit(keysize: Int, random: SecureRandom?) {}

        override fun engineGenerateKey(): SecretKey {
            val key = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
            alias?.let { keys[it] = key }
            return key
        }
    }
}
