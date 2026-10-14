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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
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

    private fun longLived(shortcut: ShortcutInfo) =
        ShortcutInfo::class.java.getMethod("isLongLived").invoke(shortcut) as Boolean

    @Test
    fun notifiedSessionsGetLongLivedShortcutsInOneSetWithinTheLimit() = runBlocking {
        shadowOf(shortcuts).setMaxShortcutCountPerActivity(6)
        persist(emptyList())
        val now = System.currentTimeMillis()
        val snapshot =
            HomeSnapshot(
                paired = true,
                sessions = (1..5).map { session("s$it", "Session $it") },
                notified =
                    listOf(
                        NotifiedSession("route", "n1", "Asked a question", "Project", now - 1),
                        // Not named yet: the shortcut still needs a label.
                        NotifiedSession("route", "n2", at = now - 2),
                        NotifiedSession("route", "n3", at = now - 3),
                    ),
            )
        surfaces().publishShortcuts(snapshot)
        val published = shortcuts.dynamicShortcuts.sortedBy { it.rank }
        assertEquals(6, published.size)
        assertEquals(
            listOf("s1", "s2", "s3", "s4", "n1", "n2").map { shortcutId("route", it) },
            published.map { it.id },
        )
        assertTrue(published.all(::longLived))
        assertTrue(published.all { it.locusId?.id == it.id })
        assertEquals("Asked a question", published[4].shortLabel)
        assertEquals("Untitled session", published[5].shortLabel)
        assertEquals("n2", published[5].intent?.getStringExtra(RemoteNotifications.EXTRA_TARGET))
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

    private fun streaming(status: String, reply: String, title: String = "A"): RemoteState {
        val host = PairedHost("route", "relay", "device", "secret", "Mac")
        return RemoteState(
            hosts = listOf(host),
            host = host,
            selection = RemoteSelection("route", "project", "a"),
            connected = true,
            sessionsFresh = true,
            project = Wire.objectOf("id" to "project", "name" to "Project"),
            sessions = listOf(Wire.objectOf("id" to "a", "title" to title, "status" to status)),
            messages = listOf(Wire.objectOf("id" to "m", "text" to reply)),
            status = status,
        )
    }

    @Test
    fun homeUpdatesFireWhileStreamingWhenWhatTheyShowChangesButNotOnOtherDeltas() = runTest {
        val state = MutableStateFlow(streaming("running", ""))
        val seen = mutableListOf<HomeInputs>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { homeUpdates(state).toList(seen) }

        // pi streams a delta every 100 ms for three seconds: nothing the widget shows changes.
        repeat(30) {
            advanceTimeBy(100)
            state.value = streaming("running", "x".repeat(it + 1))
        }
        assertEquals(1, seen.size)

        // The session starts waiting while deltas keep coming, which a debounce would hold back
        // until the stream ends.
        state.value = streaming("waiting", "y")
        repeat(3) {
            advanceTimeBy(100)
            state.value = streaming("waiting", "y".repeat(it + 2))
        }
        assertEquals(2, seen.size)
        assertEquals("waiting", seen.last().sessions.single().optionalText("status"))

        // Within the interval a change waits, and the latest one arrives.
        state.value = streaming("waiting", "z", title = "Renamed")
        state.value = streaming("running", "z", title = "Renamed again")
        assertEquals(2, seen.size)
        advanceTimeBy(HOME_UPDATE_INTERVAL_MS)
        assertEquals(3, seen.size)
        assertEquals("Renamed again", seen.last().sessions.single().optionalText("title"))
    }

    @Test
    fun aConnectedAppFoldsItsStateInAboutOnceAMinuteSoTheRefreshTimeFollows() = runTest {
        val state = MutableStateFlow(streaming("running", ""))
        val seen = mutableListOf<HomeInputs>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { homeUpdates(state).toList(seen) }
        assertEquals(1, seen.size)

        // Nothing changes for three minutes: the same inputs come again each minute.
        advanceTimeBy(3 * HOME_REFRESH_INTERVAL_MS + 1)
        assertEquals(4, seen.size)
        assertEquals(1, seen.toSet().size)
        // Each pass marks the rows refreshed at its time.
        var snapshot = HomeSnapshot()
        seen.forEachIndexed { index, inputs ->
            snapshot = mergeHomeSnapshot(snapshot, inputs, true, setOf("route"), index * HOME_REFRESH_INTERVAL_MS)
        }
        assertEquals(3 * HOME_REFRESH_INTERVAL_MS, snapshot.refreshedAt)

        // Offline, nothing is confirmed, so nothing comes again.
        state.value = streaming("running", "").copy(connected = false)
        advanceTimeBy(HOME_UPDATE_INTERVAL_MS)
        val offline = seen.size
        advanceTimeBy(5 * HOME_REFRESH_INTERVAL_MS)
        assertEquals(offline, seen.size)
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
