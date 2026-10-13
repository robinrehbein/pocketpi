package de.joinnoah.pi.remote

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowShortcutManager

/**
 * The launcher rate-limits setDynamicShortcuts; skipping it when nothing changed only helps if
 * that "nothing changed" memory survives a process restart, since a background cold start (a push,
 * a worker) is exactly when the in-memory signature would otherwise be lost. HomeSurfaces persists
 * the last published signature to SharedPreferences so a freshly constructed instance (standing in
 * for a fresh process) still knows it. The signature counts only for the versionCode that wrote
 * it: a new version republishes every shortcut once, pinned ones included, with its own intents.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class HomeSurfacesShortcutSignatureTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private class Pairings : PairingStorage {
        override fun load() = listOf(PairedHost("route", "relay", "device", "secret", "Mac"))

        override fun save(hosts: List<PairedHost>) {}
    }

    private fun surfaces() = HomeSurfaces(context, CoroutineScope(Dispatchers.Unconfined), Pairings())

    private fun publishedSignature(instance: HomeSurfaces): List<String>? {
        val field = HomeSurfaces::class.java.getDeclaredField("publishedShortcuts")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(instance) as List<String>?
    }

    private val prefs get() = context.getSharedPreferences(PUBLISHED_SHORTCUTS_PREFS, Context.MODE_PRIVATE)

    private fun persist(signature: List<String>, version: Int? = BuildConfig.VERSION_CODE) {
        prefs.edit()
            .putString(PUBLISHED_SHORTCUTS_KEY, signature.joinToString(SHORTCUT_SIGNATURE_DELIMITER))
            .apply { if (version != null) putInt(PUBLISHED_SHORTCUTS_VERSION_KEY, version) }
            .commit()
    }

    private val shortcuts get() = context.getSystemService(ShortcutManager::class.java)

    private fun session(id: String, title: String) =
        HomeSession("route", "project", "Project", id, title, "idle", updatedAt = 1L)

    /** A shortcut as an older version published it: straight at MainActivity, with extras. */
    private fun oldShortcut(sessionId: String, title: String) =
        ShortcutInfoCompat.Builder(context, shortcutId("route", sessionId))
            .setShortLabel(title)
            .setLongLabel("$title · Project")
            .setIntent(
                Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .putExtra(RemoteNotifications.EXTRA_ROUTE, "route")
                    .putExtra(RemoteNotifications.EXTRA_TARGET, sessionId)
            )
            .build()
            .toShortcutInfo()

    private fun pinned(id: String) = shortcuts.pinnedShortcuts.single { it.id == id }

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

    @Test
    fun aSignatureFromAnotherVersionIsIgnored() {
        val signature = listOf("session:r:a|Title|Project")
        // Published before the version was recorded, or by an older versionCode.
        persist(signature, version = null)
        assertNull(publishedSignature(surfaces()))
        persist(signature, version = BuildConfig.VERSION_CODE - 1)
        assertNull(publishedSignature(surfaces()))
    }

    @Test
    fun theFirstPublishOfAVersionMovesDynamicAndPinnedShortcutsToTheAlias() = runBlocking {
        shadowOf(shortcuts).setIsRequestPinShortcutSupported(true)
        // An older version left one pinned shortcut that is no longer dynamic, and its signature.
        shortcuts.requestPinShortcut(oldShortcut("pinned", "Pinned"), null)
        persist(listOf(shortcutSignatureEntry("route", "open", "Open", "Project")), BuildConfig.VERSION_CODE - 1)

        val home = surfaces()
        home.publishShortcuts(HomeSnapshot(paired = true, sessions = listOf(session("open", "Open"))))

        val dynamic = shortcuts.dynamicShortcuts.single()
        assertEquals(shortcutId("route", "open"), dynamic.id)
        assertEquals(LaunchIntents.ALIAS, dynamic.intent?.component?.className)
        val relaunched = checkNotNull(pinned(shortcutId("route", "pinned")).intent)
        assertEquals(LaunchIntents.ALIAS, relaunched.component?.className)
        assertEquals("pinned", relaunched.getStringExtra(RemoteNotifications.EXTRA_TARGET))
        assertEquals("Pinned", pinned(shortcutId("route", "pinned")).shortLabel)
        assertEquals(BuildConfig.VERSION_CODE, prefs.getInt(PUBLISHED_SHORTCUTS_VERSION_KEY, 0))
        assertEquals(
            listOf(shortcutSignatureEntry("route", "open", "Open", "Project")),
            publishedSignature(surfaces()),
        )
    }

    @Test
    fun pinnedShortcutsAreLeftAloneOnceTheVersionIsCurrent() = runBlocking {
        shadowOf(shortcuts).setIsRequestPinShortcutSupported(true)
        shortcuts.requestPinShortcut(oldShortcut("pinned", "Pinned"), null)
        persist(emptyList())

        surfaces().publishShortcuts(HomeSnapshot(paired = true))

        assertEquals(MainActivity::class.java.name, pinned(shortcutId("route", "pinned")).intent?.component?.className)
    }

    @After
    fun resetShadow() {
        RejectingShortcutManager.rejectUpdates = false
        RejectingShortcutManager.dynamicPublishes = 0
    }

    @Test
    @Config(shadows = [RejectingShortcutManager::class])
    fun aRejectedPinnedUpdateIsRetriedWithoutRepublishingTheDynamicShortcuts() = runBlocking {
        shadowOf(shortcuts).setIsRequestPinShortcutSupported(true)
        shortcuts.requestPinShortcut(oldShortcut("pinned", "Pinned"), null)
        persist(emptyList(), BuildConfig.VERSION_CODE - 1)
        val snapshot = HomeSnapshot(paired = true, sessions = listOf(session("open", "Open")))
        val home = surfaces()

        // The launcher rate-limits the pinned update: the version must stay unrecorded.
        RejectingShortcutManager.rejectUpdates = true
        home.publishShortcuts(snapshot)
        assertEquals(1, RejectingShortcutManager.dynamicPublishes)
        assertEquals(MainActivity::class.java.name, pinned(shortcutId("route", "pinned")).intent?.component?.className)
        assertEquals(BuildConfig.VERSION_CODE - 1, prefs.getInt(PUBLISHED_SHORTCUTS_VERSION_KEY, 0))

        // The next publish retries only the pinned update; the dynamic set is already current.
        RejectingShortcutManager.rejectUpdates = false
        home.publishShortcuts(snapshot)
        assertEquals(1, RejectingShortcutManager.dynamicPublishes)
        assertEquals(LaunchIntents.ALIAS, pinned(shortcutId("route", "pinned")).intent?.component?.className)
        assertEquals(BuildConfig.VERSION_CODE, prefs.getInt(PUBLISHED_SHORTCUTS_VERSION_KEY, 0))
    }

    @Test
    fun refreshMovesShortcutsToTheAliasWithoutAStateChange() = runBlocking {
        shadowOf(shortcuts).setIsRequestPinShortcutSupported(true)
        shortcuts.requestPinShortcut(oldShortcut("pinned", "Pinned"), null)
        persist(emptyList(), BuildConfig.VERSION_CODE - 1)

        // What PackageReplacedReceiver runs right after an update, before the app is opened.
        surfaces().refresh()

        assertEquals(LaunchIntents.ALIAS, pinned(shortcutId("route", "pinned")).intent?.component?.className)
        assertEquals(BuildConfig.VERSION_CODE, prefs.getInt(PUBLISHED_SHORTCUTS_VERSION_KEY, 0))
    }

    @Test
    fun anUpdateReachesTheNonExportedPackageReplacedReceiver() {
        val receivers =
            context.packageManager.queryBroadcastReceivers(
                Intent(Intent.ACTION_MY_PACKAGE_REPLACED).setPackage(context.packageName),
                PackageManager.GET_RESOLVED_FILTER,
            )
        val receiver = receivers.single { it.activityInfo.name == PackageReplacedReceiver::class.java.name }
        assertFalse(receiver.activityInfo.exported)
        assertTrue(receiver.filter.hasAction(Intent.ACTION_MY_PACKAGE_REPLACED))
    }

    @Test
    fun shortcutIdsParseBackToTheirSession() {
        assertEquals("route" to "019a-session", shortcutTarget(shortcutId("route", "019a-session")))
        assertEquals("route" to "a:b", shortcutTarget(shortcutId("route", "a:b")))
        assertNull(shortcutTarget("session:route"))
        assertNull(shortcutTarget("session::s"))
        assertNull(shortcutTarget("other:route:s"))
        assertNull(shortcutTarget(shortcutId("route", "a b")))
    }
}

/** A ShortcutManager whose launcher rejects updateShortcuts, as when it rate-limits the app. */
@Implements(ShortcutManager::class)
class RejectingShortcutManager : ShadowShortcutManager() {
    @Implementation
    override fun updateShortcuts(shortcutInfoList: MutableList<ShortcutInfo>): Boolean =
        !rejectUpdates && super.updateShortcuts(shortcutInfoList)

    @Implementation
    override fun setDynamicShortcuts(shortcutInfoList: MutableList<ShortcutInfo>): Boolean {
        dynamicPublishes++
        return super.setDynamicShortcuts(shortcutInfoList)
    }

    companion object {
        @JvmStatic var rejectUpdates = false
        @JvmStatic var dynamicPublishes = 0
    }
}
