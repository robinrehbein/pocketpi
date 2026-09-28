package de.joinnoah.pi.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The launcher rate-limits setDynamicShortcuts; skipping it when nothing changed only helps if
 * that "nothing changed" memory survives a process restart, since a background cold start (a push,
 * a worker) is exactly when the in-memory signature would otherwise be lost. HomeSurfaces persists
 * the last published signature to SharedPreferences so a freshly constructed instance (standing in
 * for a fresh process) still knows it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class HomeSurfacesShortcutSignatureTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private class Pairings : PairingStorage {
        override fun load() = emptyList<PairedHost>()

        override fun save(hosts: List<PairedHost>) {}
    }

    private fun surfaces() = HomeSurfaces(context, CoroutineScope(Dispatchers.Unconfined), Pairings())

    private fun publishedSignature(instance: HomeSurfaces): List<String>? {
        val field = HomeSurfaces::class.java.getDeclaredField("publishedShortcuts")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(instance) as List<String>?
    }

    private fun persist(signature: List<String>) {
        context.getSharedPreferences(PUBLISHED_SHORTCUTS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PUBLISHED_SHORTCUTS_KEY, signature.joinToString(SHORTCUT_SIGNATURE_DELIMITER))
            .commit()
    }

    @Test
    fun withNothingPersistedYetTheSignatureStartsUnknown() {
        // The very first run of all: nothing to compare against, so the guard must not skip.
        assertNull(publishedSignature(surfaces()))
    }

    @Test
    fun aSignaturePublishedBeforeIsVisibleToAFreshInstance() {
        val signature = listOf("session:r:a|Title|Project", "session:r:b|Other|Project")
        persist(signature)
        // A new HomeSurfaces backed by the same SharedPreferences stands in for the process
        // restarting (a background cold start): it must not start believing nothing was ever
        // published, or it will call setDynamicShortcuts again for no reason.
        assertEquals(signature, publishedSignature(surfaces()))
    }

    @Test
    fun anEmptyPersistedSignatureRoundTrips() {
        persist(emptyList())
        assertEquals(emptyList<String>(), publishedSignature(surfaces()))
    }
}
